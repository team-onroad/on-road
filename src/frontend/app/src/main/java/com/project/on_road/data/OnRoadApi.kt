package com.project.on_road.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 백엔드 API 계약 (docs/api.md).
 * RemoteOnRoadApi 가 서버에 연결하고, FakeOnRoadApi 는 화면 확인용으로 같은 동작을 메모리에서 흉내 낸다.
 */
interface OnRoadApi {
    // ───── 사용자 (3.2, 3.3, 3.12) ─────

    suspend fun createUser(
        name: String,
        birthDate: LocalDate,
        region: String,
        status: LivingStatus,
        /** 휴대폰 번호 (하이픈 없이, 선택) */
        phone: String? = null,
        /** D-day 기준일: 퇴소 전이면 퇴소 예정일, 퇴소 후면 목표일 (선택) */
        dDate: LocalDate? = null,
    ): UserProfile

    /** 3.12 회원정보 수정 (퇴소 처리, 퇴소일·목표일 바꾸기). 바꿀 값만 채워서 보낸다 */
    suspend fun updateUser(userId: String, update: UserUpdate): UserProfile

    /** 없는 사용자면 null (앱은 온보딩으로) */
    suspend fun getUser(userId: String): UserProfile?

    // ───── 홈 (3.4) ─────

    suspend fun getDashboard(userId: String): Dashboard

    /**
     * 다음 할 일 추천 (앱 규칙: 진행 중 체크리스트, 저장한 정책, 시뮬레이션 기록).
     * dashboard 를 이미 받아 왔으면 넘겨서 다시 부르지 않게 한다.
     */
    suspend fun getNextActions(userId: String, dashboard: Dashboard? = null): List<NextAction>

    // ───── 정책 (3.5, 3.6) ─────

    suspend fun searchPolicies(userId: String, question: String): SearchResult
    suspend fun getPolicy(policyId: String): Policy?
    suspend fun getPolicies(ids: List<String>): List<Policy>

    /** 3.23 단계별 쉬운 말. 준비 안 된 정책·가짜 모드면 null (기존 easySummary 사용) */
    suspend fun getEasyText(policyId: String, level: String?): EasyTextView? = null

    /** 3.24 신청서 작성 도우미. 준비 안 된 정책·가짜 모드면 null */
    suspend fun getFormGuide(policyId: String): FormGuideView? = null

    /** 저장한 정책(찜)은 서버 API가 없어서 앱에 저장한다 */
    suspend fun savePolicy(userId: String, policyId: String, saved: Boolean)
    suspend fun getSavedPolicyIds(userId: String): Set<String>

    // ───── 체크리스트 (3.4, 3.9 ~ 3.11) ─────

    /** 체크리스트 목록 (홈 3.4 의 checklists 전체) */
    suspend fun getChecklists(userId: String): List<ChecklistSummary>

    /** 3.9 이 정책의 체크리스트를 열기 (있으면 그대로, 없으면 새로 만듦) */
    suspend fun openChecklist(userId: String, policyId: String): Checklist

    /** 3.10 체크리스트 다시 불러오기 */
    suspend fun getChecklist(userId: String, checklistId: Long): Checklist

    /**
     * 3.11 항목 체크. checked 와 notApplicable 중 하나만 넣는다.
     * - notApplicable 은 조건이 있는 서류에만 보낼 수 있다
     */
    suspend fun updateChecklistItem(
        userId: String,
        checklistId: Long,
        itemId: Long,
        checked: Boolean? = null,
        notApplicable: Boolean? = null,
    ): ChecklistItemState

    // ───── 생활비 시뮬레이션 (3.7, 3.8, 3.14). 앱 금액은 만원 ─────

    /** 3.7 항목별 권장 최소 금액 (만원) */
    suspend fun getCriteria(): Map<BudgetCategory, Int>

    suspend fun simulate(userId: String, income: Int, allocation: Map<BudgetCategory, Int>): SimulationResult

    /** 3.14 지난 결과 다시 보기 */
    suspend fun getSimulation(userId: String, simulationId: String): SimulationResult

    // ───── 성장 기록 (3.13) ─────

    suspend fun getGrowth(userId: String): GrowthRecord

    /** 활동 기록 (practice · month_life) → 서버 자립 준비도. 서버가 없으면 아무것도 안 함 */
    suspend fun logActivity(userId: String, kind: String, ref: String?, data: Map<String, Any?>) {}

    /** 자립 준비도. 서버가 없으면 null */
    suspend fun getReadiness(userId: String): Readiness? = null
}

/**
 * 다음 할 일 추천 규칙 (서버·가짜 공통). savedPolicies: 체크리스트가 아직 없는 저장한 정책.
 * 서버 기록(체크리스트·시뮬레이션)에 앱 안 기록(관심 분야·퇴소일·실전 연습·흥미 탐색)을 더해서
 * 사용자마다 다른 순서와 '추천 이유'를 만든다.
 */
internal object NextActionRules {
    fun build(dashboard: Dashboard, savedPolicies: List<Policy>): List<NextAction> {
        val session = runCatching { AppContainer.session }.getOrNull()
        // 청소년은 진로·학업 중심으로 따로 (퇴소일·생활비 같은 청년용 추천은 넣지 않는다)
        if (AppState.stage == UserStage.TEEN) return buildTeen(dashboard, savedPolicies, session)
        val actions = mutableListOf<NextAction>()

        // 1) 진행 중인 신청
        dashboard.checklists.filter { !it.completed }.forEach { c ->
            actions += NextAction(
                ActionKind.OPEN_CHECKLIST,
                "‘${c.policyName}’ ${c.nextStep?.title ?: ""} 이어서 하기",
                "${c.totalSteps}단계 중 ${c.doneSteps}단계를 마쳤어요.",
                c.policyId,
                priority = 1,
                reason = "진행 중인 신청이에요",
            )
        }

        // 2) 저장만 해 둔 정책
        val started = dashboard.checklists.map { it.policyId }.toSet()
        savedPolicies.filter { it.id !in started }.forEach { p ->
            actions += NextAction(
                ActionKind.START_CHECKLIST,
                "저장한 ‘${p.name}’ 신청 준비 시작하기",
                "대상 확인부터 차근차근 시작해요.",
                p.id,
                priority = 2,
                reason = "저장만 하고 아직 시작 안 했어요",
            )
        }

        // 3) 퇴소 D-day: 90일 안이면 남은 준비를 먼저
        val leave = dashboard.user?.dDate ?: session?.leaveDate
        val status = dashboard.user?.status ?: session?.status
        if (leave != null && status != LivingStatus.LEFT) {
            val today = LocalDate.now()
            val days = ChronoUnit.DAYS.between(today, leave)
            if (days in 0..90) {
                val done = session?.timelineDone.orEmpty()
                // 퇴소일까지 해야 하는 일 중 아직 안 한 것
                val left = TimelineRules.tasks.count { t -> t.id !in done && t.offsetDays <= 0 }
                if (left > 0) {
                    actions += NextAction(
                        ActionKind.OPEN_TIMELINE,
                        "퇴소 전 준비 ${left}개 확인하기",
                        "날짜별 할 일을 체크해요.",
                        priority = if (days <= 30) 1 else 2,
                        reason = "퇴소까지 ${days}일 남았어요",
                    )
                }
            }
        }

        // 4) 관심 분야: 상담·검색·저장·생활비에서 자주 나온 분야인데 진행 중인 신청이 없으면
        val stats = ActivityStore.topicStats()
        val startedTopics = dashboard.checklists.mapNotNull { Topic.ofPolicyCategory(it.policy.category) }.toSet() +
            savedPolicies.mapNotNull { Topic.ofPolicyCategory(it.category) }
        stats.filter { it.count >= 2 && it.topic !in startedTopics }.take(2).forEachIndexed { i, st ->
            actions += NextAction(
                ActionKind.SEARCH_TOPIC,
                if (st.topic == Topic.MIND) "마음 돌봄 지원 알아보기" else "‘${st.topic.label}’ 지원 찾아보기",
                if (st.topic == Topic.MIND) "무료 상담 같은 지원이 있어요."
                else "맞는 정책을 찾아 신청까지 이어가요.",
                priority = 2 + i,
                reason = "${st.topic.label} 관련 기록이 ${st.count}번 있었어요",
                query = st.topic.searchQuery,
            )
        }

        // 5) 생활비
        val latest = dashboard.latestSimulation
        if (latest == null) {
            actions += NextAction(
                ActionKind.RUN_SIMULATION,
                "첫 달 생활비 계획 세워 보기",
                "수입을 나눠 보고 부족한 곳을 찾아요.",
                priority = 3,
                reason = "아직 생활비 계획이 없어요",
            )
        } else if (latest.shortageCount > 0) {
            actions += NextAction(
                ActionKind.RUN_SIMULATION,
                "부족했던 생활비 계획 다시 맞춰 보기",
                "부족한 곳을 다시 채워 봐요.",
                priority = 3,
                reason = "지난 계획에서 ${latest.shortageCount}개 항목이 부족했어요",
            )
        }

        // 6) 실전 연습: 안 해 봤으면 관심 분야에 맞는 상황, 해 봤으면 빠뜨린 게 있던 상황 다시
        val practices = ActivityStore.practices()
        val last = practices.firstOrNull()
        if (last == null) {
            // 서버 시나리오 id 는 서버가 정하므로 목록으로 보내고, 관심 분야에 맞는 상황만 권한다
            val hint = when (stats.firstOrNull()?.topic) {
                Topic.HOUSING -> "집주인 통화나 전입신고 상황부터"
                Topic.JOB -> "면접 상황부터"
                Topic.MONEY -> "은행 계좌 만들기 상황부터"
                else -> "자주 겪는 상황부터"
            }
            actions += NextAction(
                ActionKind.PRACTICE,
                "AI와 실전 연습 해 보기",
                "$hint 연습해 봐요.",
                priority = 4,
                reason = "아직 연습 기록이 없어요",
            )
        } else if (last.missed > 0) {
            actions += NextAction(
                ActionKind.PRACTICE,
                "‘${last.title}’ 다시 연습하기",
                "빠뜨린 것을 챙겨서 다시 해 봐요.",
                priority = 4,
                reason = "지난번에 ${last.missed}개를 빠뜨렸어요",
                scenarioId = last.scenarioId,
            )
        }

        // 6-1) 한 달 살아보기: 안 해 봤으면 권하고, 해 봤으면 아쉬웠던 것과 이어지는 일
        val month = ActivityStore.monthLives().firstOrNull()
        if (month == null) {
            actions += NextAction(
                ActionKind.MONTH_LIFE,
                "혼자 사는 한 달 미리 살아보기",
                "매주 생기는 일에 대처하며 생활비를 버텨 봐요.",
                priority = 4,
                reason = "아직 한 달 살아보기를 하지 않았어요",
            )
        } else {
            if ("smishing" in month.badEvents && practices.none { it.scenarioId == "scam-call" && it.at > month.at }) {
                actions += NextAction(
                    ActionKind.PRACTICE,
                    "수상한 전화 대처 연습하기",
                    "사기 전화·문자에 속지 않는 법을 연습해요.",
                    priority = 3,
                    reason = "한 달 살아보기에서 사기 문자 링크를 눌렀어요",
                    scenarioId = "scam-call",
                )
            }
            if (month.shortage > 0 || month.usedEmergency > 0) {
                actions += NextAction(
                    ActionKind.SEARCH_TOPIC,
                    "긴급 생활비 지원 알아보기",
                    "돈이 모자랄 때 받을 수 있는 지원을 찾아요.",
                    priority = 3,
                    reason = if (month.shortage > 0) "한 달 살아보기에서 ${month.shortage}만원이 모자랐어요"
                    else "한 달 살아보기에서 비상금 ${month.usedEmergency}만원을 썼어요",
                    query = "긴급복지지원 생활비 자립준비청년",
                )
            }
        }

        // 6-2) 내보낸 서류: 내러 가기 전에 그 기관 상황을 연습 (2주 안, 아직 연습 안 했으면)
        val twoWeeks = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        ActivityStore.exportedDocs().firstOrNull { it.at > twoWeeks }?.let { doc ->
            Links.practiceForDoc(doc.title)?.let { (id, name) ->
                if (practices.none { it.scenarioId == id && it.at > doc.at }) {
                    actions += NextAction(
                        ActionKind.PRACTICE,
                        "내러 가기 전에 ‘$name’ 연습하기",
                        "서류를 낼 때 할 말을 미리 연습해요.",
                        priority = 2,
                        reason = "‘${doc.title}’를 만들었어요",
                        scenarioId = id,
                    )
                }
            }
        }

        // 7) 진로
        if (session?.interestTypes.isNullOrEmpty() && ActivityStore.interests().isEmpty()) {
            actions += NextAction(
                ActionKind.EXPLORE_CAREER,
                "흥미 유형 알아보기",
                "나에게 맞는 직업을 찾아요.",
                priority = 5,
                reason = "아직 흥미 탐색을 하지 않았어요",
            )
        }

        if (dashboard.checklists.isEmpty() && savedPolicies.isEmpty() && stats.isEmpty()) {
            actions += NextAction(
                ActionKind.SEARCH_POLICY,
                "나에게 맞는 정책 찾아보기",
                "궁금한 것을 물어보면 공식 자료에서 찾아 드려요.",
                priority = 0,
            )
        }

        return actions.distinctBy { it.title }.sortedBy { it.priority }
    }

    /** 청소년: 진로 탐색 → 로드맵 → 자립 기초 미션 → 장학금 → 면접 연습 */
    private fun buildTeen(dashboard: Dashboard, savedPolicies: List<Policy>, session: SessionStore?): List<NextAction> {
        val actions = mutableListOf<NextAction>()

        // 장학금 등 신청을 준비 중이면 먼저
        dashboard.checklists.filter { !it.completed }.forEach { c ->
            actions += NextAction(
                ActionKind.OPEN_CHECKLIST,
                "‘${c.policyName}’ ${c.nextStep?.title ?: ""} 이어서 하기",
                "${c.totalSteps}단계 중 ${c.doneSteps}단계를 마쳤어요.",
                c.policyId,
                priority = 1,
                reason = "진행 중인 신청이에요",
            )
        }
        val started = dashboard.checklists.map { it.policyId }.toSet()
        savedPolicies.filter { it.id !in started }.forEach { p ->
            actions += NextAction(
                ActionKind.START_CHECKLIST,
                "저장한 ‘${p.name}’ 신청 준비 시작하기",
                "대상 확인부터 차근차근 시작해요.",
                p.id,
                priority = 2,
                reason = "저장만 하고 아직 시작 안 했어요",
            )
        }

        // 진로: 탐색 안 했으면 탐색, 로드맵이 있으면 다음 단계
        val roadmap = ActivityStore.roadmap()
        val next = roadmap?.nextIndex?.let { roadmap.steps[it] }
        when {
            session?.interestTypes.isNullOrEmpty() && ActivityStore.interests().isEmpty() -> actions += NextAction(
                ActionKind.EXPLORE_CAREER,
                "흥미 유형 알아보기",
                "나에게 맞는 직업을 찾아요.",
                priority = 1,
                reason = "아직 흥미 탐색을 하지 않았어요",
            )
            roadmap == null -> actions += NextAction(
                ActionKind.EXPLORE_CAREER,
                "추천 직업으로 로드맵 만들기",
                "직업 하나를 골라 준비 순서를 받아요.",
                priority = 2,
                reason = "흥미 탐색은 했는데 로드맵이 없어요",
            )
            roadmap != null && next != null -> actions += NextAction(
                ActionKind.ROADMAP,
                "${next.period}: ${next.title}",
                next.detail,
                priority = 2,
                reason = "${roadmap.jobName} 로드맵 ${roadmap.steps.size}단계 중 ${roadmap.done.size}단계 완료",
            )
        }

        // 자립 기초 미션
        val done = ActivityStore.basicsDone()
        BasicMissions.all.firstOrNull { it.id !in done }?.let { m ->
            actions += NextAction(
                ActionKind.BASIC_MISSION,
                "자립 기초: ${m.title}",
                "읽고 퀴즈 한 문제를 풀어요.",
                priority = 3,
                reason = "${BasicMissions.all.size}개 중 ${done.size}개 완료",
                query = m.id,
            )
        }

        // 장학금 (관심 분야 기록이 있으면 그 분야, 없으면 장학금)
        val topic = ActivityStore.topicStats().firstOrNull { it.count >= 2 && it.topic != Topic.HOUSING }
        actions += NextAction(
            ActionKind.SEARCH_TOPIC,
            if (topic != null && topic.topic != Topic.STUDY) "‘${topic.topic.label}’ 지원 찾아보기" else "받을 수 있는 장학금 찾아보기",
            "공식 자료에서 찾아 드려요.",
            priority = 4,
            reason = topic?.let { "${it.topic.label} 관련 기록이 ${it.count}번 있었어요" } ?: "학업을 도와주는 제도가 있어요",
            query = if (topic != null && topic.topic != Topic.STUDY) topic.topic.searchQuery else "청소년 장학금 학업 지원",
        )

        // 면접 연습
        val last = ActivityStore.practices().firstOrNull()
        if (last == null) {
            actions += NextAction(
                ActionKind.PRACTICE,
                "AI와 면접 연습 해 보기",
                "진학·아르바이트 면접을 미리 말해 봐요.",
                priority = 5,
                reason = "아직 연습 기록이 없어요",
            )
        } else if (last.missed > 0) {
            actions += NextAction(
                ActionKind.PRACTICE,
                "‘${last.title}’ 다시 연습하기",
                "빠뜨린 것을 챙겨서 다시 해 봐요.",
                priority = 5,
                reason = "지난번에 ${last.missed}개를 빠뜨렸어요",
                scenarioId = last.scenarioId,
            )
        }

        return actions.distinctBy { it.title }.sortedBy { it.priority }
    }
}
