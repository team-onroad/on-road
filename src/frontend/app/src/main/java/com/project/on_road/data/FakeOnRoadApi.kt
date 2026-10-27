package com.project.on_road.data

import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Period
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 네트워크 지연을 흉내 내는 메모리 기반 가짜 API (화면 확인용, AppContainer.USE_REMOTE = false).
 * 앱을 재시작하면 데이터가 초기화된다. 체크·해당 없음·진행률 규칙은 서버 명세와 같게 맞췄다.
 */
class FakeOnRoadApi(
    private val policyLookup: suspend (String) -> Policy? = { SamplePolicies.find(it) }
) : OnRoadApi {

    private val users = mutableMapOf<String, UserProfile>()
    /** userId → (policyId → 체크리스트) */
    private val checklists = mutableMapOf<String, MutableMap<String, Checklist>>()
    /** 체크리스트 id → 마지막으로 바꾼 순서 (최근 수정순 정렬용) */
    private val touched = mutableMapOf<Long, Long>()
    private var nextChecklistId = 1L
    private var nextItemId = 100L
    private var clock = 0L
    private val saved = mutableMapOf<String, MutableSet<String>>()
    private val simulations = mutableMapOf<String, MutableList<SimulationResult>>()

    private val policyByCategory = mapOf(
        BudgetCategory.HOUSING to listOf("lh-housing", "jeonse"),
        BudgetCategory.FOOD to listOf("jarip-allowance"),
        BudgetCategory.TRANSPORT to listOf("jarip-allowance"),
        BudgetCategory.PHONE to listOf("jarip-allowance"),
        BudgetCategory.ETC to listOf("didim", "settlement")
    )

    private suspend fun latency(ms: Long = 450) = delay(ms)

    override suspend fun createUser(
        name: String,
        birthDate: LocalDate,
        region: String,
        status: LivingStatus,
        phone: String?,
        dDate: LocalDate?,
    ): UserProfile {
        latency(700)
        val user = UserProfile(
            userId = "u_" + UUID.randomUUID().toString().take(8),
            name = name.trim(),
            birthDate = birthDate,
            region = region,
            status = status,
            stage = stageOf(birthDate),
            phone = phone,
            dDate = dDate,
            dDay = dDate?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() },
        )
        users[user.userId] = user
        return user
    }

    override suspend fun updateUser(userId: String, update: UserUpdate): UserProfile {
        latency()
        val old = users[userId] ?: error("가짜 모드: 사용자 없음")
        // 서버 규칙과 같음: 퇴소 후로 바뀌면 퇴소일을 비우고, 같이 보낸 날짜가 있으면 목표일로 저장
        var dDate = old.dDate
        if (update.status == LivingStatus.LEFT && old.status != LivingStatus.LEFT) dDate = null
        if (update.clearDDate) dDate = null else if (update.dDate != null) dDate = update.dDate
        val user = old.copy(
            status = update.status ?: old.status,
            dDate = dDate,
            dDay = dDate?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() },
        )
        users[userId] = user
        return user
    }

    /** 메모리 기반이라 재시작하면 null. 가짜 모드에서는 AppContainer가 이 확인을 건너뛴다. */
    override suspend fun getUser(userId: String): UserProfile? = users[userId]?.let { it.copy(stage = stageOf(it.birthDate)) }

    /** 서버 규칙과 같음: 만 10세 이하 아동, 11~17세 청소년, 18세 이상 자립준비청년 */
    private fun stageOf(birthDate: LocalDate): UserStage {
        val age = Period.between(birthDate, LocalDate.now()).years
        return when {
            age <= 10 -> UserStage.CHILD
            age <= 17 -> UserStage.TEEN
            else -> UserStage.YOUNG_ADULT
        }
    }

    // ---------------- 홈 ----------------

    override suspend fun getDashboard(userId: String): Dashboard {
        latency(300)
        val user = users[userId]?.let {
            DashboardUser(it.name, stageOf(it.birthDate), it.status, it.dDate, it.dDate?.let { d -> daysUntil(d) })
        }
        val latest = simulations[userId]?.lastOrNull()?.let { LatestSimulation(it.id, it.shortages.size, it.createdAt) }
        return Dashboard(user, summaries(userId), latest)
    }

    override suspend fun getNextActions(userId: String, dashboard: Dashboard?): List<NextAction> {
        val dash = dashboard ?: getDashboard(userId)
        val savedPolicies = saved[userId].orEmpty().mapNotNull { policyLookup(it) }
        val actions = NextActionRules.build(dash, savedPolicies).toMutableList()
        // 예시 데이터 전용: 퇴소 예정이면 자립정착금 준비를 먼저 권한다
        if (users[userId]?.status == LivingStatus.LEAVING_SOON && dash.checklists.none { it.policyId == "settlement" }) {
            actions += NextAction(
                ActionKind.START_CHECKLIST,
                "퇴소 전에 ‘자립정착금’ 준비하기",
                "보호가 끝나기 전에 신청해야 하는 제도예요.",
                "settlement",
                priority = 1,
            )
        }
        return actions.distinctBy { it.title }.sortedBy { it.priority }
    }

    private fun daysUntil(date: LocalDate): Int =
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()

    /** 진행 중 먼저, 완료는 뒤. 각각 최근 수정순 */
    private fun summaries(userId: String): List<ChecklistSummary> =
        checklists[userId]?.values.orEmpty()
            .sortedWith(compareBy<Checklist> { it.completed }.thenByDescending { touched[it.id] ?: 0L })
            .map { it.toSummary() }

    private fun Checklist.toSummary() = ChecklistSummary(
        id = id,
        policy = policy,
        doneSteps = doneCount,
        totalSteps = steps.size,
        nextStep = steps.firstOrNull { !it.done }?.type,
    )

    override suspend fun searchPolicies(userId: String, question: String): SearchResult {
        latency(900)
        val q = question.replace(" ", "")
        val matched = SamplePolicies.all
            .map { p ->
                val score = p.keywords.count { q.contains(it.replace(" ", "")) } * 2 +
                    (if (q.contains(p.name.replace(" ", ""))) 3 else 0)
                p to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

        return if (matched.isEmpty()) {
            SearchResult(question, false, "질문과 관련된 공식 근거를 찾지 못했어요.", emptyList())
        } else {
            SearchResult(question, true, "공식 자료에서 관련 정책 ${matched.size}개를 찾았어요.", matched)
        }
    }

    override suspend fun getPolicy(policyId: String): Policy? {
        latency(300)
        return SamplePolicies.find(policyId)
    }

    override suspend fun getPolicies(ids: List<String>): List<Policy> {
        latency(400)
        return ids.mapNotNull { SamplePolicies.find(it) }
    }

    override suspend fun savePolicy(userId: String, policyId: String, saved: Boolean) {
        latency(200)
        val set = this.saved.getOrPut(userId) { mutableSetOf() }
        if (saved) set.add(policyId) else set.remove(policyId)
    }

    override suspend fun getSavedPolicyIds(userId: String): Set<String> {
        latency(200)
        return saved[userId]?.toSet().orEmpty()
    }

    // ---------------- 체크리스트 ----------------

    override suspend fun getChecklists(userId: String): List<ChecklistSummary> {
        latency(400)
        return summaries(userId)
    }

    override suspend fun openChecklist(userId: String, policyId: String): Checklist {
        latency(400)
        checklists[userId]?.get(policyId)?.let { return it }
        val policy = policyLookup(policyId) ?: error("정책을 찾을 수 없습니다: $policyId")
        val checklist = buildChecklist(policy)
        checklists.getOrPut(userId) { mutableMapOf() }[policyId] = checklist
        touched[checklist.id] = ++clock
        return checklist
    }

    override suspend fun getChecklist(userId: String, checklistId: Long): Checklist {
        latency(300)
        return checklists[userId]?.values?.find { it.id == checklistId } ?: error("체크리스트 없음: $checklistId")
    }

    override suspend fun updateChecklistItem(
        userId: String,
        checklistId: Long,
        itemId: Long,
        checked: Boolean?,
        notApplicable: Boolean?,
    ): ChecklistItemState {
        latency(250)
        require((checked == null) != (notApplicable == null)) { "checked 와 notApplicable 중 하나만" }
        val map = checklists[userId] ?: error("체크리스트 없음")
        val c = map.values.find { it.id == checklistId } ?: error("체크리스트 없음: $checklistId")

        c.steps.find { it.itemId == itemId }?.let { step ->
            require(notApplicable == null) { "단계 항목은 해당 없음을 고를 수 없어요" }
            val next = c.copy(steps = c.steps.map { if (it.itemId == itemId) it.copy(done = checked!!) else it })
            map[c.policyId] = next
            touched[c.id] = ++clock
            return ChecklistItemState(step.itemId, checked!!, false)
        }

        val doc = c.docs.find { it.itemId == itemId } ?: error("항목 없음: $itemId")
        val updated = when {
            // 해당 없음을 고르면 체크가 풀린다
            notApplicable == true -> {
                require(doc.condition != null) { "조건 없는 서류는 해당 없음을 고를 수 없어요" }
                doc.copy(notApplicable = true, checked = false)
            }
            notApplicable == false -> doc.copy(notApplicable = false)
            // 해당 없음인 서류를 체크하면 해당 없음이 풀린다
            checked == true -> doc.copy(checked = true, notApplicable = false)
            else -> doc.copy(checked = false)
        }
        map[c.policyId] = c.copy(docs = c.docs.map { if (it.itemId == itemId) updated else it })
        touched[c.id] = ++clock
        return ChecklistItemState(updated.itemId, updated.checked, updated.notApplicable)
    }

    override suspend fun simulate(
        userId: String,
        income: Int,
        allocation: Map<BudgetCategory, Int>
    ): SimulationResult {
        latency(800)
        val shortages = BudgetCategory.entries.mapNotNull { c ->
            val a = allocation[c] ?: 0
            if (a < c.minimum) Shortage(c, a, c.minimum) else null
        }
        val scenarios = listOf(
            Scenario(
                "추천 비율형",
                "주거비 35%, 식비 30%를 기본으로 나머지를 고르게 나눠요.",
                BudgetRules.allocate(income, BudgetRules.recommended)
            ),
            Scenario(
                "주거 지원 활용형",
                "공공임대나 전세임대로 주거비를 줄이고 저축을 늘려요.",
                BudgetRules.allocate(
                    income,
                    mapOf(
                        BudgetCategory.HOUSING to 20, BudgetCategory.FOOD to 32,
                        BudgetCategory.TRANSPORT to 8, BudgetCategory.PHONE to 5, BudgetCategory.ETC to 35
                    )
                )
            ),
            Scenario(
                "생활 안정형",
                "식비와 교통비에 여유를 두어 매달 흔들리지 않게 꾸려요.",
                BudgetRules.allocate(
                    income,
                    mapOf(
                        BudgetCategory.HOUSING to 30, BudgetCategory.FOOD to 35,
                        BudgetCategory.TRANSPORT to 10, BudgetCategory.PHONE to 5, BudgetCategory.ETC to 20
                    )
                )
            )
        )
        val relatedIds = if (shortages.isEmpty()) {
            listOf("jarip-allowance", "didim")
        } else {
            shortages.flatMap { policyByCategory[it.category].orEmpty() }.distinct()
        }
        val result = SimulationResult(
            id = "s_" + System.currentTimeMillis(),
            income = income,
            allocation = allocation,
            shortages = shortages,
            scenarios = scenarios,
            relatedPolicies = relatedIds.mapNotNull { SamplePolicies.find(it) },
            createdAt = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date())
        )
        simulations.getOrPut(userId) { mutableListOf() }.add(result)
        return result
    }

    override suspend fun getCriteria(): Map<BudgetCategory, Int> {
        latency(200)
        return BudgetCategory.entries.associateWith { it.minimum }
    }

    override suspend fun getSimulation(userId: String, simulationId: String): SimulationResult {
        latency(300)
        return simulations[userId]?.find { it.id == simulationId } ?: error("시뮬레이션 없음: $simulationId")
    }

    override suspend fun getGrowth(userId: String): GrowthRecord {
        latency(400)
        return GrowthRecord(
            savedPolicies = saved[userId].orEmpty().mapNotNull { policyLookup(it) },
            checklists = summaries(userId),
            simulations = simulations[userId].orEmpty().reversed().map { s ->
                SimulationSummary(s.id, s.income, s.income - s.allocation.values.sum(), s.shortages.size, s.createdAt)
            },
            roleplays = FakeRecords.roleplays.reversed(),
        )
    }

    private fun buildChecklist(p: Policy): Checklist {
        fun id() = nextItemId++
        val policy = ChecklistPolicy(
            id = p.id,
            name = p.name,
            agency = p.agency,
            category = p.category,
            contact = "보건복지상담센터 129",
            applyUrl = p.applyUrl.ifBlank { null },
            checkedAt = p.checkedAt,
            isActive = true,
        )
        return Checklist(
            id = nextChecklistId++,
            policy = policy,
            steps = listOf(
                ChecklistStep(id(), StepType.TARGET, p.target, p.easySummary),
                ChecklistStep(
                    id(), StepType.CONDITION,
                    "${p.target}\n기본 조건(나이·지역) 해당 · 세부 조건은 공식 원문에서 확인",
                    null,
                ),
                ChecklistStep(
                    id(), StepType.DOCS,
                    "아래 서류를 하나씩 챙겨요. 서류를 다 챙기면 이 단계도 완료로 표시해 주세요.",
                    null,
                ),
                ChecklistStep(id(), StepType.APPLY, p.howToApply, null),
            ),
            docs = p.requiredDocs.map { ChecklistDoc(id(), it.name, it.condition) },
        )
    }
}
