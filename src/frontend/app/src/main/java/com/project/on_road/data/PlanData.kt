package com.project.on_road.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * 앱 전체의 '할 일' 한 곳.
 * 홈 '이번 주 할 일' · 타임라인 · 알림 · 결과 화면의 '다음 할 일'이 모두 여기서 같은 목록을 본다.
 * (예전에는 홈은 서버 추천, 타임라인은 앱 계산, 알림은 옛 규칙을 따로 써서 서로 말이 달랐다)
 *
 * - 할 일 규칙은 [TimelinePlanner] 그대로
 * - 끝낸 일 · 해당 없음은 휴대폰에 저장 (session.timelineDone / timelineNa) — 서버 · DB 변경 없음
 * - 사는 지역 · 진행 중인 신청 · 저장한 정책 · 준비도 약한 영역은 [refresh] 로 받아 둔다
 * - 연습 · 서류 위험 찾기 · 생활비 배분 · 한 달 살아보기를 끝내면 [completeBy] 로 맞는 할 일을 자동으로 끝낸다
 */
object PlanData {
    var region by mutableStateOf<String?>(null)
        private set
    var policies by mutableStateOf<List<PlanPolicy>>(emptyList())
        private set
    var weak by mutableStateOf<PlanWeakArea?>(null)
        private set
    /** 자립 준비도 (홈 카드 · 준비도 화면이 같이 쓴다) */
    var readiness by mutableStateOf<Readiness?>(null)
        private set

    /** 끝낸 일 · 해당 없음 (화면이 바로 따라 바뀌도록 상태 목록으로 둔다) */
    val done = mutableStateListOf<String>()
    val na = mutableStateListOf<String>()

    private var syncedUser: String? = "\u0000"
    private var lastRefresh = 0L

    /** 다른 계정으로 바뀌었으면 그 계정의 기록으로 다시 채운다 */
    private fun sync() {
        val s = AppContainer.session
        if (syncedUser == s.userId) return
        syncedUser = s.userId
        done.clear(); done.addAll(s.timelineDone)
        na.clear(); na.addAll(s.timelineNa)
        region = null
        policies = emptyList()
        weak = null
        readiness = ScreenCache.get("readiness")
        lastRefresh = 0L
    }

    /**
     * 지역 · 신청 · 저장한 정책 · 준비도를 서버에서 받는다. 못 받아도 기본 할 일은 그대로 보인다.
     * 홈처럼 자주 여는 화면은 30초 안에 다시 부르지 않는다 ([force] 면 바로).
     * [checklists] 를 주면 (대시보드에서 이미 받은 것) 다시 요청하지 않는다.
     */
    suspend fun refresh(checklists: List<ChecklistSummary>? = null, force: Boolean = false) {
        sync()
        val userId = AppContainer.session.userId ?: return
        val now = System.currentTimeMillis()
        if (!force && checklists == null && now - lastRefresh < REFRESH_MS) return
        val api = AppContainer.api
        if (region == null) region = runCatching { api.getUser(userId)?.region }.getOrNull()
        val list = checklists ?: runCatching { api.getChecklists(userId) }.getOrNull()
        if (list != null) {
            // 진행 중 + 끝낸 신청 (끝낸 신청은 관련 할 일을 자동으로 끝낸다)
            val mine = list.map { PlanPolicy(it.policyId, it.policyName, it.nextStep?.title, completed = it.completed) }
            val known = list.map { it.policyId }.toSet()
            val before = policies.associateBy { it.policyId }
            val saved = AppContainer.session.savedPolicyIds.filter { it !in known }.take(3).mapNotNull { id ->
                before[id] ?: runCatching { api.getPolicy(id) }.getOrNull()?.let { PlanPolicy(it.id, it.name, null) }
            }
            policies = mine + saved
        }
        if (force || now - lastRefresh >= REFRESH_MS) {
            val r = runCatching { api.getReadiness(userId) }.getOrNull()
            if (r != null) {
                readiness = r
                ScreenCache.put("readiness", r)
            }
            weak = r?.let {
                it.areas.firstOrNull { a -> a.key == it.weakest }?.let { a -> a.nextStep?.let { s -> PlanWeakArea(a.label, s) } }
            } ?: weak
        }
        lastRefresh = now
    }

    /** 내 할 일 전체 (끝낸 일 포함). 퇴소일이 없으면 비어 있다 */
    fun all(today: LocalDate, leaveDate: LocalDate? = AppContainer.session.leaveDate): List<PlanTask> {
        sync()
        leaveDate ?: return emptyList()
        return TimelinePlanner.plan(PlanInput(leaveDate, today, region, AppContainer.session.workStatus, policies, weak))
    }

    /** 신청을 끝낸 정책 때문에 자동으로 끝난 할 일 (id → 까닭) */
    fun autoDone(): Map<String, String> = TimelinePlanner.autoDone(policies).associate { it.taskId to it.reason }

    /** 끝냈거나 해당 없거나, 오래 지나 '지난 일'로 넘어간 할 일 */
    fun isClosed(t: PlanTask, today: LocalDate, auto: Map<String, String> = autoDone()): Boolean =
        t.id in done || t.id in na || t.id in auto ||
            t.date.isBefore(today.minusDays(TimelinePlanner.MISSED_DAYS.toLong()))

    /** 아직 남은 할 일. 날짜순 (놓친 일이 맨 앞) */
    fun open(today: LocalDate): List<PlanTask> {
        val auto = autoDone()
        return all(today).filter { !isClosed(it, today, auto) }
    }

    /** 이번 주 할 일: 놓친 일 + 오늘부터 7일 안 */
    fun thisWeek(today: LocalDate): List<PlanTask> =
        open(today).filter { !it.date.isAfter(today.plusDays(7)) }

    /** 지금 바로 할 다음 일 하나 */
    fun next(today: LocalDate): PlanTask? = open(today).firstOrNull()

    fun toggleDone(id: String) {
        sync()
        if (id in done) done.remove(id) else done.add(id)
        AppContainer.session.timelineDone = done.toSet()
    }

    fun toggleNa(id: String) {
        sync()
        if (id in na) na.remove(id) else na.add(id)
        AppContainer.session.timelineNa = na.toSet()
    }

    /**
     * 기능을 끝냈을 때 그 기능으로 하는 할 일 하나를 자동으로 끝낸다.
     * 같은 기능을 쓰는 할 일이 여럿이면 (서류 위험 찾기: 집 계약 · 근로계약 등) 오늘과 날짜가 가장 가까운 것 하나만.
     * 끝낸 할 일을 돌려준다 (없으면 null). 퇴소 후 목표 화면을 쓰는 사람은 타임라인이 달라서 건드리지 않는다.
     */
    fun completeBy(go: PlanGo, today: LocalDate = LocalDate.now()): PlanTask? {
        if (DDate.isGoal(AppContainer.session.status)) return null
        val t = open(today)
            .filter { it.kind != PlanKind.POLICY && it.actions.any { a -> a.go == go } }
            .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, it.date)) }
            ?: return null
        toggleDone(t.id)
        return t
    }

    /** 체크리스트 단계를 모두 끝냈을 때: 서버에서 다시 받기 전에도 바로 '신청 끝'으로 본다 */
    fun finishPolicy(policyId: String, name: String) {
        sync()
        val rest = policies.filter { it.policyId != policyId }
        policies = rest + PlanPolicy(policyId, name, null, completed = true)
    }

    private const val REFRESH_MS = 30_000L
}
