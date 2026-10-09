package com.project.on_road.data.remote

import com.project.on_road.data.BudgetCategory
import com.project.on_road.data.ChatApi
import com.project.on_road.data.ChatMessage
import com.project.on_road.data.ChatRole
import com.project.on_road.data.Checklist
import com.project.on_road.data.Evidence
import com.project.on_road.data.FakeOnRoadApi
import com.project.on_road.data.GrowthRecord
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.NextAction
import com.project.on_road.data.OnRoadApi
import com.project.on_road.data.Policy
import com.project.on_road.data.Scenario
import com.project.on_road.data.SearchResult
import com.project.on_road.data.Shortage
import com.project.on_road.data.SimulationResult
import com.project.on_road.data.UserProfile
import com.project.on_road.data.UserProgress
import com.project.on_road.data.UserStage
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 백엔드 연결 구현체.
 * - 서버 연결: 사용자(3.2·3.3), 검색(3.5), 정책 상세(3.6), 시뮬레이션(3.8)
 * - 서버 API가 아직 없어 앱 안(메모리)에서 처리: 체크리스트, 저장(찜), 진행률, 다음 할 일, 성장 기록
 *   → 백엔드에 체크리스트(3.9~3.11)·대시보드(3.4)·성장 기록(3.13)이 생기면 local 위임 부분을 교체한다.
 */
class RemoteOnRoadApi(
    private val service: OnRoadService,
    private val currentUserId: () -> String?,
) : OnRoadApi {

    /** 서버 API가 없는 기능용. 정책은 서버에서 받아온 것으로 체크리스트를 만든다. */
    private val local = FakeOnRoadApi(policyLookup = { getPolicy(it) })

    // ---------------- 사용자 ----------------

    override suspend fun createUser(
        name: String,
        birthDate: LocalDate,
        region: String,
        status: LivingStatus,
    ): UserProfile = Network.call {
        service.createUser(
            UserCreateDto(
                name = name,
                birthDate = birthDate.toString(), // YYYY-MM-DD
                region = region,
                status = status.toCode(),
            )
        ).toProfile()
    }

    override suspend fun getUser(userId: String): UserProfile? =
        try {
            Network.call { service.getUser(userId).toProfile() }
        } catch (e: ApiException) {
            if (e.code == "USER_NOT_FOUND") null else throw e
        }

    // ---------------- 정책 ----------------

    override suspend fun searchPolicies(userId: String, question: String): SearchResult {
        val res = Network.call { service.search(SearchRequestDto(userId, question)) }
        val policies = res.results.map { it.toPolicy() }
        val grounded = res.answerStatus == "answered" && policies.isNotEmpty()
        return SearchResult(
            question = question,
            grounded = grounded,
            message = if (grounded) "공식 자료에서 관련 정책 ${policies.size}개를 찾았어요." else "확인할 수 있는 공식 정보가 없어요.",
            policies = policies,
        )
    }

    /** 서버 정책 id는 숫자. 예시 데이터 id(settlement 등)가 들어오면 null. */
    override suspend fun getPolicy(policyId: String): Policy? {
        val id = policyId.toLongOrNull() ?: return null
        return try {
            Network.call { service.getPolicy(id, currentUserId()).toPolicy() }
        } catch (e: ApiException) {
            if (e.code == "POLICY_NOT_FOUND") null else throw e
        }
    }

    override suspend fun getPolicies(ids: List<String>): List<Policy> =
        ids.mapNotNull { runCatching { getPolicy(it) }.getOrNull() }

    // ---------------- 시뮬레이션 (앱은 만원, 서버는 원) ----------------

    override suspend fun simulate(userId: String, income: Int, allocation: Map<BudgetCategory, Int>): SimulationResult {
        val res = Network.call {
            service.createSimulation(
                SimulationCreateDto(
                    userId = userId,
                    totalIncome = income * WON,
                    allocations = allocation.toDto(),
                )
            )
        }
        val result = res.toResult()
        local.recordSimulation(userId, result) // 진행률·성장 기록용 (서버 3.13 연결 전까지)
        return result
    }

    // ---------------- 서버 API 없음 → 앱 안에서 처리 ----------------

    override suspend fun getProgress(userId: String): UserProgress = local.getProgress(userId)
    override suspend fun savePolicy(userId: String, policyId: String, saved: Boolean) = local.savePolicy(userId, policyId, saved)
    override suspend fun getSavedPolicyIds(userId: String): Set<String> = local.getSavedPolicyIds(userId)
    override suspend fun getChecklists(userId: String): List<Checklist> = local.getChecklists(userId)
    override suspend fun getChecklist(userId: String, policyId: String): Checklist? = local.getChecklist(userId, policyId)
    override suspend fun createChecklist(userId: String, policyId: String): Checklist = local.createChecklist(userId, policyId)
    override suspend fun saveChecklist(userId: String, checklist: Checklist): Checklist = local.saveChecklist(userId, checklist)
    override suspend fun getNextActions(userId: String): List<NextAction> = local.getNextActions(userId)
    override suspend fun getGrowth(userId: String): GrowthRecord = local.getGrowth(userId)
}

/** AI 상담. 서버 대화 이력 저장이 없으므로 매 질문을 POST /search 한 번으로 처리한다. */
class RemoteChatApi(private val service: OnRoadService) : ChatApi {
    override suspend fun reply(userId: String, history: List<ChatMessage>, message: String): ChatMessage {
        val res = try {
            Network.call { service.search(SearchRequestDto(userId, message)) }
        } catch (e: ApiException) {
            if (e.code != "RAG_UNAVAILABLE") throw e
            return ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = "지금은 공식 자료 검색을 잠시 쓸 수 없어요. 잠시 후 다시 물어봐 주세요.",
                needsCheck = true,
            )
        }
        val policies = res.results.map { it.toPolicy() }
        val answered = res.answerStatus == "answered" && !res.answer.isNullOrBlank()
        return ChatMessage(
            id = 0,
            role = ChatRole.ASSISTANT,
            text = if (answered) {
                res.answer!!
            } else {
                "확인할 수 있는 공식 정보가 없어요. 추측해서 답하지 않을게요.\n\n" +
                    "질문을 조금 더 구체적으로 해 주시거나, 보건복지상담센터(129)에 확인해 보세요."
            },
            evidence = policies.flatMap { it.evidence }.take(3),
            policies = policies,
            needsCheck = !answered,
        )
    }
}

// ---------------- 변환 ----------------

private const val WON = 10_000

private val CHECKED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val CREATED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

private fun LivingStatus.toCode(): String = when (this) {
    LivingStatus.IN_FACILITY -> "in_care"
    LivingStatus.LEAVING_SOON -> "leaving_soon"
    LivingStatus.LEFT -> "left_care"
}

private fun statusOf(code: String): LivingStatus = when (code) {
    "in_care" -> LivingStatus.IN_FACILITY
    "leaving_soon" -> LivingStatus.LEAVING_SOON
    else -> LivingStatus.LEFT
}

private fun stageOf(code: String): UserStage = when (code) {
    "child" -> UserStage.CHILD
    "teen" -> UserStage.TEEN
    else -> UserStage.YOUNG_ADULT
}

/** 서버 카테고리 코드 → 앱 표시 이름 (categoryIcon 기준) */
private fun categoryLabel(code: String): String = when (code) {
    "housing" -> "주거"
    "living_cost", "finance" -> "생활·금융"
    "medical" -> "건강"
    "employment" -> "취업"
    "education" -> "교육"
    "independence" -> "자립"
    else -> "기타"
}

private fun formatChecked(date: String): String =
    runCatching { LocalDate.parse(date).format(CHECKED_FORMAT) }.getOrDefault(date)

private fun formatCreated(dateTime: String): String =
    runCatching { OffsetDateTime.parse(dateTime).format(CREATED_FORMAT) }.getOrDefault(dateTime)

private fun UserDto.toProfile() = UserProfile(
    userId = userId,
    name = name,
    birthDate = LocalDate.parse(birthDate),
    region = region,
    status = statusOf(status),
    stage = stageOf(stage),
)

private fun SearchResultDto.toPolicy() = Policy(
    id = policyId.toString(),
    name = name,
    category = categoryLabel(category),
    agency = agency,
    checkedAt = formatChecked(checkedAt),
    summary = originalText,
    easySummary = easyText,
    target = targetDescription,
    amount = supportAmount ?: supportContent,
    period = supportPeriod ?: "공식 원문에서 확인",
    howToApply = "",
    applyUrl = sourceUrl,
    requiredDocs = emptyList(),
    evidence = evidences.map { Evidence("$agency · $name", it.content) },
    keywords = emptyList(),
    isOutdated = isOutdated,
    sourceUrl = sourceUrl,
    eligibility = eligibility.status,
)

private fun PolicyDetailDto.toPolicy() = Policy(
    id = policyId.toString(),
    name = name,
    category = categoryLabel(category),
    agency = agency,
    checkedAt = formatChecked(checkedAt),
    summary = originalText,
    easySummary = easyText,
    target = targetDescription,
    amount = supportAmount ?: supportContent,
    period = supportPeriod ?: "공식 원문에서 확인",
    howToApply = applyMethod,
    applyUrl = applyUrl ?: sourceUrl,
    requiredDocs = requiredDocs,
    evidence = listOf(Evidence("$agency · 공식 원문", originalText)),
    keywords = emptyList(),
    isOutdated = isOutdated,
    sourceUrl = sourceUrl,
    eligibility = eligibility?.status,
)

private fun RelatedPolicyDto.toPolicy() = Policy(
    id = policyId.toString(),
    name = name,
    category = categoryLabel(category),
    agency = agency,
    checkedAt = "",
    summary = "",
    easySummary = "",
    target = "",
    amount = "",
    period = "",
    howToApply = "",
    applyUrl = "",
    requiredDocs = emptyList(),
    evidence = emptyList(),
    keywords = emptyList(),
)

private fun categoryOf(item: String): BudgetCategory = when (item) {
    "housing" -> BudgetCategory.HOUSING
    "food" -> BudgetCategory.FOOD
    "transport" -> BudgetCategory.TRANSPORT
    "telecom" -> BudgetCategory.PHONE
    else -> BudgetCategory.ETC
}

private fun Map<BudgetCategory, Int>.toDto() = AllocationsDto(
    housing = (this[BudgetCategory.HOUSING] ?: 0) * WON,
    food = (this[BudgetCategory.FOOD] ?: 0) * WON,
    transport = (this[BudgetCategory.TRANSPORT] ?: 0) * WON,
    telecom = (this[BudgetCategory.PHONE] ?: 0) * WON,
    other = (this[BudgetCategory.ETC] ?: 0) * WON,
)

private fun AllocationsDto.toManwon(): Map<BudgetCategory, Int> = mapOf(
    BudgetCategory.HOUSING to housing / WON,
    BudgetCategory.FOOD to food / WON,
    BudgetCategory.TRANSPORT to transport / WON,
    BudgetCategory.PHONE to telecom / WON,
    BudgetCategory.ETC to other / WON,
)

private fun ShortageDto.toShortage() = Shortage(
    category = categoryOf(item),
    allocated = input / WON,
    recommended = minimum / WON,
)

private fun AlternativeDto.toScenario(): Scenario {
    val description = buildString {
        if (remainingShortages.isEmpty()) {
            append("모든 항목이 권장 최소 금액을 넘어요.")
        } else {
            append("아직 부족해요: ")
            append(remainingShortages.joinToString(", ") { "${it.label} ${it.gap / WON}만원" })
        }
        if (remaining > 0) append(" 남는 돈 ${remaining / WON}만원")
    }
    return Scenario(title = label, description = description, allocation = allocations.toManwon())
}

private fun SimulationDto.toResult() = SimulationResult(
    id = simulationId.toString(),
    income = totalIncome / WON,
    allocation = allocations.toManwon(),
    shortages = shortages.map { it.toShortage() },
    scenarios = alternatives.map { it.toScenario() },
    relatedPolicies = relatedPolicies.map { it.toPolicy() },
    createdAt = formatCreated(createdAt),
)
