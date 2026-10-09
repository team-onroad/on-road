package com.project.on_road.data

import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Period
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 네트워크 지연을 흉내 내는 메모리 기반 가짜 API. 앱을 재시작하면 데이터가 초기화된다.
 * policyLookup: 정책 조회 방법. 백엔드 연결 시 RemoteOnRoadApi가 서버 조회로 바꿔 넣는다
 * (체크리스트·찜·성장 기록은 서버 API가 생기기 전까지 이 클래스가 메모리에서 처리).
 */
class FakeOnRoadApi(
    private val policyLookup: suspend (String) -> Policy? = { SamplePolicies.find(it) }
) : OnRoadApi {

    private val users = mutableMapOf<String, UserProfile>()
    private val checklists = mutableMapOf<String, MutableMap<String, Checklist>>()
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
        status: LivingStatus
    ): UserProfile {
        latency(700)
        val user = UserProfile(
            userId = "u_" + UUID.randomUUID().toString().take(8),
            name = name.trim(),
            birthDate = birthDate,
            region = region,
            status = status,
            stage = stageOf(birthDate)
        )
        users[user.userId] = user
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

    /** 서버에서 받은 시뮬레이션 결과를 진행률·성장 기록용으로 보관 (RemoteOnRoadApi에서 호출) */
    fun recordSimulation(userId: String, result: SimulationResult) {
        simulations.getOrPut(userId) { mutableListOf() }.add(result)
    }

    override suspend fun getProgress(userId: String): UserProgress {
        latency(300)
        val list = checklists[userId]?.values.orEmpty()
        return UserProgress(
            checklistCount = list.size,
            completedChecklists = list.count { it.completed },
            overallProgress = if (list.isEmpty()) 0f else list.map { it.progress }.average().toFloat(),
            simulationCount = simulations[userId]?.size ?: 0,
            savedPolicyCount = saved[userId]?.size ?: 0
        )
    }

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

    override suspend fun getChecklists(userId: String): List<Checklist> {
        latency(400)
        return checklists[userId]?.values?.toList().orEmpty()
    }

    override suspend fun getChecklist(userId: String, policyId: String): Checklist? {
        latency(350)
        return checklists[userId]?.get(policyId)
    }

    override suspend fun createChecklist(userId: String, policyId: String): Checklist {
        latency(400)
        val policy = policyLookup(policyId) ?: error("정책을 찾을 수 없습니다: $policyId")
        val checklist = buildChecklist(policy)
        checklists.getOrPut(userId) { mutableMapOf() }[policyId] = checklist
        return checklist
    }

    override suspend fun saveChecklist(userId: String, checklist: Checklist): Checklist {
        latency(300)
        checklists.getOrPut(userId) { mutableMapOf() }[checklist.policyId] = checklist
        return checklist
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

    override suspend fun getNextActions(userId: String): List<NextAction> {
        latency(300)
        val actions = mutableListOf<NextAction>()
        val userChecklists = checklists[userId].orEmpty()

        userChecklists.values.filter { !it.completed }.forEach { c ->
            val next = c.steps.first { !it.done }
            actions += NextAction(
                ActionKind.OPEN_CHECKLIST,
                "‘${c.policyName}’ ${next.type.title} 이어서 하기",
                "${c.steps.size}단계 중 ${c.doneCount}단계를 마쳤어요.",
                c.policyId,
                priority = 1
            )
        }

        saved[userId].orEmpty().filter { it !in userChecklists }.forEach { pid ->
            policyLookup(pid)?.let { p ->
                actions += NextAction(
                    ActionKind.START_CHECKLIST,
                    "저장한 ‘${p.name}’ 신청 준비 시작하기",
                    "대상 확인부터 차근차근 시작해요.",
                    p.id,
                    priority = 2
                )
            }
        }

        if (users[userId]?.status == LivingStatus.LEAVING_SOON && "settlement" !in userChecklists && policyLookup("settlement") != null) {
            actions += NextAction(
                ActionKind.START_CHECKLIST,
                "퇴소 전에 ‘자립정착금’ 준비하기",
                "보호가 끝나기 전에 신청해야 하는 제도예요.",
                "settlement",
                priority = 1
            )
        }

        if (simulations[userId].isNullOrEmpty()) {
            actions += NextAction(
                ActionKind.RUN_SIMULATION,
                "첫 달 생활비 계획 세워 보기",
                "수입을 항목별로 나눠 보고 부족한 곳을 찾아요.",
                priority = 3
            )
        }

        if (userChecklists.isEmpty() && saved[userId].isNullOrEmpty()) {
            actions += NextAction(
                ActionKind.SEARCH_POLICY,
                "나에게 맞는 정책 찾아보기",
                "궁금한 것을 물어보면 공식 자료에서 찾아 드려요.",
                priority = 0
            )
        }

        return actions.distinctBy { it.title }.sortedBy { it.priority }
    }

    override suspend fun getGrowth(userId: String): GrowthRecord {
        latency(400)
        return GrowthRecord(
            savedPolicies = saved[userId].orEmpty().mapNotNull { policyLookup(it) },
            completedChecklists = checklists[userId]?.values?.filter { it.completed }.orEmpty(),
            simulations = simulations[userId]?.reversed().orEmpty()
        )
    }

    private fun buildChecklist(p: Policy) = Checklist(
        id = "c_${p.id}",
        policyId = p.id,
        policyName = p.name,
        requiredDocs = p.requiredDocs,
        checkedDocs = emptySet(),
        applyUrl = p.applyUrl,
        steps = listOf(
            ChecklistStep(
                StepType.TARGET,
                "신청 대상: ${p.target}\n본인이 신청 대상에 해당하는지 확인합니다.",
                "먼저 내가 받을 수 있는 사람인지 확인해요.\n대상: ${p.target}"
            ),
            ChecklistStep(
                StepType.CONDITION,
                "지원 내용: ${p.amount}\n지원 기간: ${p.period}\n소득·보호기간 등 세부 요건은 담당 기관(${p.agency})의 최종 확인이 필요합니다.",
                "받을 수 있는 것: ${p.amount}\n기간: ${p.period}\n자세한 조건은 ${p.agency}에 꼭 물어봐요."
            ),
            ChecklistStep(
                StepType.DOCS,
                "구비서류 ${p.requiredDocs.size}종을 준비합니다. 기관에 따라 추가 서류를 요구할 수 있습니다.",
                "아래 서류를 하나씩 챙겨요. 모두 체크하면 이 단계가 저절로 완료돼요."
            ),
            ChecklistStep(
                StepType.APPLY,
                "신청 방법: ${p.howToApply}",
                "준비가 끝났다면 이렇게 신청해요.\n${p.howToApply}"
            )
        )
    )
}
