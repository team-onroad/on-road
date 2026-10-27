package com.project.on_road.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.project.on_road.data.BudgetCategory
import com.project.on_road.data.ApplyMethod
import com.project.on_road.data.ChatApi
import com.project.on_road.data.ChatKind
import com.project.on_road.data.ChatMessage
import com.project.on_road.data.ChatRole
import com.project.on_road.data.DocCandidate
import com.project.on_road.data.Checklist
import com.project.on_road.data.ConditionCheck
import com.project.on_road.data.ChecklistDoc
import com.project.on_road.data.ChecklistItemState
import com.project.on_road.data.ChecklistPolicy
import com.project.on_road.data.ChecklistStep
import com.project.on_road.data.ChecklistSummary
import com.project.on_road.data.Dashboard
import com.project.on_road.data.DashboardUser
import com.project.on_road.data.EasyLevel
import com.project.on_road.data.EasyTerm
import com.project.on_road.data.EasyTextView
import com.project.on_road.data.Evidence
import com.project.on_road.data.FieldSource
import com.project.on_road.data.FormGuideItem
import com.project.on_road.data.FormGuideView
import com.project.on_road.data.GrowthRecord
import com.project.on_road.data.HelpLine
import com.project.on_road.data.LatestSimulation
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.NextAction
import com.project.on_road.data.NextActionRules
import com.project.on_road.data.OnRoadApi
import com.project.on_road.data.Policy
import com.project.on_road.data.PolicySummary
import com.project.on_road.data.SummarySection
import com.project.on_road.data.RequiredDoc
import com.project.on_road.data.RoleplayRecord
import com.project.on_road.data.Scenario
import com.project.on_road.data.SearchResult
import com.project.on_road.data.SessionStore
import com.project.on_road.data.Shortage
import com.project.on_road.data.SimulationResult
import com.project.on_road.data.SimulationSummary
import com.project.on_road.data.StepType
import com.project.on_road.data.UserProfile
import com.project.on_road.data.UserStage
import com.project.on_road.data.UserUpdate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 백엔드 연결 구현체 (docs/api.md 3.2 ~ 3.14).
 * 저장한 정책(찜)과 다음 할 일 추천은 서버 API가 없어서 앱에서 처리한다 (찜 목록은 SessionStore 에 저장).
 */
class RemoteOnRoadApi(
    private val service: OnRoadService,
    private val session: () -> SessionStore?,
) : OnRoadApi {

    private val currentUserId: String? get() = session()?.userId

    // ---------------- 사용자 (3.2, 3.3, 3.12) ----------------

    override suspend fun createUser(
        name: String,
        birthDate: LocalDate,
        region: String,
        status: LivingStatus,
        phone: String?,
        dDate: LocalDate?,
    ): UserProfile = Network.call {
        service.createUser(
            UserCreateDto(
                name = name,
                birthDate = birthDate.toString(), // YYYY-MM-DD
                region = region,
                status = status.toCode(),
                phone = phone?.takeIf { it.isNotBlank() },
                dDate = dDate?.toString(), // YYYY-MM-DD
            )
        ).toProfile()
    }

    override suspend fun updateUser(userId: String, update: UserUpdate): UserProfile = Network.call {
        val body = JsonObject().apply {
            update.status?.let { addProperty("status", it.toCode()) }
            when {
                update.clearDDate -> add("d_date", JsonNull.INSTANCE) // null 을 보내야 지워진다
                update.dDate != null -> addProperty("d_date", update.dDate.toString())
            }
        }
        val json = GsonBuilder().serializeNulls().create().toJson(body)
        service.updateUser(userId, json.toRequestBody("application/json; charset=utf-8".toMediaType())).toProfile()
    }

    override suspend fun getUser(userId: String): UserProfile? =
        try {
            Network.call { service.getUser(userId).toProfile() }
        } catch (e: ApiException) {
            if (e.code == "USER_NOT_FOUND") null else throw e
        }

    // ---------------- 홈 (3.4) ----------------

    override suspend fun getDashboard(userId: String): Dashboard {
        val res = Network.call { service.getDashboard(userId) }
        return Dashboard(
            user = DashboardUser(
                name = res.user.name,
                stage = stageOf(res.user.stage),
                status = statusOf(res.user.status),
                dDate = res.user.dDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                dDay = res.user.dDay,
            ),
            checklists = res.checklists.map { it.toSummary() },
            latestSimulation = res.latestSimulation?.let {
                LatestSimulation(it.simulationId.toString(), it.shortageCount, formatCreated(it.createdAt))
            },
        )
    }

    override suspend fun getNextActions(userId: String, dashboard: Dashboard?): List<NextAction> {
        val dash = dashboard ?: getDashboard(userId)
        val started = dash.checklists.map { it.policyId }.toSet()
        val savedPolicies = getPolicies(getSavedPolicyIds(userId).filter { it !in started })
        return NextActionRules.build(dash, savedPolicies)
    }

    // ---------------- 정책 (3.5, 3.6) ----------------

    override suspend fun searchPolicies(userId: String, question: String): SearchResult {
        val res = Network.call { service.search(SearchRequestDto(userId, question)) }
        val policies = res.results.map { it.toPolicy() }
        val grounded = res.answerStatus == "answered" && policies.isNotEmpty()
        return SearchResult(
            question = question,
            grounded = grounded,
            message = if (grounded) "공식 자료에서 관련 정책 ${policies.size}개를 찾았어요." else "확인할 수 있는 공식 정보가 없어요.",
            policies = policies,
            // 정책 검색 화면은 카드가 본문이라, 맨 위 답변은 RAG 답변 대신 확인 안내 한 줄만 보여준다 (상담 화면은 RAG 답변 그대로)
            answer = if (grounded) AnswerFormat.SEARCH_NOTICE else null,
        )
    }

    /** 서버 정책 id는 숫자. 예시 데이터 id(settlement 등)가 들어오면 null. */
    override suspend fun getPolicy(policyId: String): Policy? {
        val id = policyId.toLongOrNull() ?: return null
        return try {
            Network.call { service.getPolicy(id, currentUserId).toPolicy() }
        } catch (e: ApiException) {
            if (e.code == "POLICY_NOT_FOUND") null else throw e
        }
    }

    override suspend fun getPolicies(ids: List<String>): List<Policy> =
        ids.mapNotNull { runCatching { getPolicy(it) }.getOrNull() }

    // 3.23 단계별 쉬운 말: 변환 결과가 없으면 null → 화면은 기존 쉬운 말(easy_text)을 보여준다
    override suspend fun getEasyText(policyId: String, level: String?): EasyTextView? {
        val id = policyId.toLongOrNull() ?: return null
        return try {
            Network.call { service.getEasyText(id, currentUserId, level) }.toView()
        } catch (e: ApiException) {
            if (e.code == "EASY_TEXT_NOT_FOUND" || e.code == "POLICY_NOT_FOUND") null else throw e
        }
    }

    // 3.24 신청서 작성 도우미: 양식이 없으면 null → "준비 중" 표시
    override suspend fun getFormGuide(policyId: String): FormGuideView? {
        val id = policyId.toLongOrNull() ?: return null
        return try {
            Network.call { service.getFormGuide(id, currentUserId) }.toView()
        } catch (e: ApiException) {
            if (e.code == "FORM_GUIDE_NOT_FOUND" || e.code == "POLICY_NOT_FOUND") null else throw e
        }
    }

    // 저장한 정책(찜): 서버 API 없음 → 앱에 저장
    override suspend fun savePolicy(userId: String, policyId: String, saved: Boolean) {
        val store = session() ?: return
        store.savedPolicyIds = if (saved) store.savedPolicyIds + policyId else store.savedPolicyIds - policyId
    }

    override suspend fun getSavedPolicyIds(userId: String): Set<String> = session()?.savedPolicyIds.orEmpty()

    // ---------------- 체크리스트 (3.9 ~ 3.11) ----------------

    /** 체크리스트 목록은 홈(3.4)의 checklists 전체를 쓴다 */
    override suspend fun getChecklists(userId: String): List<ChecklistSummary> = getDashboard(userId).checklists

    override suspend fun openChecklist(userId: String, policyId: String): Checklist {
        val id = policyId.toLongOrNull() ?: throw ApiException(404, "POLICY_NOT_FOUND", "정책을 찾을 수 없습니다.")
        return Network.call { service.openChecklist(ChecklistOpenDto(userId, id)) }.toChecklist()
    }

    override suspend fun getChecklist(userId: String, checklistId: Long): Checklist =
        Network.call { service.getChecklist(checklistId, userId) }.toChecklist()

    override suspend fun updateChecklistItem(
        userId: String,
        checklistId: Long,
        itemId: Long,
        checked: Boolean?,
        notApplicable: Boolean?,
    ): ChecklistItemState {
        require((checked == null) != (notApplicable == null)) { "checked 와 notApplicable 중 하나만 보낸다" }
        val body = JsonObject().apply {
            if (checked != null) addProperty("is_checked", checked)
            if (notApplicable != null) addProperty("is_not_applicable", notApplicable)
        }
        val res = Network.call { service.updateChecklistItem(checklistId, itemId, userId, body) }
        return ChecklistItemState(res.item.itemId, res.item.isChecked, res.item.isNotApplicable)
    }

    // ---------------- 시뮬레이션 (3.7, 3.8, 3.14. 앱은 만원, 서버는 원) ----------------

    override suspend fun getCriteria(): Map<BudgetCategory, Int> {
        val res = Network.call { service.getCriteria() }
        // 앱은 만원 단위라서 올림 (앱 기준을 넘기면 서버 기준도 넘도록)
        return res.items.associate { categoryOf(it.key) to (it.minimum + WON - 1) / WON }
    }

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
        return res.toResult()
    }

    override suspend fun getSimulation(userId: String, simulationId: String): SimulationResult {
        val id = simulationId.toLongOrNull()
            ?: throw ApiException(404, "SIMULATION_NOT_FOUND", "시뮬레이션 기록을 찾을 수 없습니다.")
        return Network.call { service.getSimulation(id, userId) }.toResult()
    }

    // ---------------- 성장 기록 (3.13) ----------------

    override suspend fun getGrowth(userId: String): GrowthRecord {
        val res = Network.call { service.getGrowth(userId) }
        return GrowthRecord(
            savedPolicies = getPolicies(getSavedPolicyIds(userId).toList()),
            checklists = res.checklists.map { it.toSummary() },
            simulations = res.simulations.map {
                SimulationSummary(
                    id = it.simulationId.toString(),
                    income = it.totalIncome / WON,
                    remaining = it.remaining / WON,
                    shortageCount = it.shortageCount,
                    createdAt = formatCreated(it.createdAt),
                )
            },
            roleplays = res.roleplays.map {
                RoleplayRecord(it.roleplayId, it.scenarioId, it.title, it.goodCount, it.missedCount, formatCreated(it.createdAt))
            },
        )
    }
}

/**
 * AI 상담 (3.15). 서버는 대화를 저장하지 않으므로 앱이 들고 있는 이전 대화를 요청마다 보낸다.
 * type: policy(정책 답변) / counsel(고민·진로 상담) / crisis(위기 안내 + 연락처)
 */
class RemoteChatApi(private val service: OnRoadService) : ChatApi {
    override suspend fun reply(userId: String, history: List<ChatMessage>, message: String): ChatMessage {
        val text = message.trim().take(MESSAGE_MAX)
        // 방금 보낸 내 말은 message 로 따로 보내므로 이전 대화에서 뺀다
        val last = history.lastOrNull()
        val prior = if (last != null && last.role == ChatRole.USER && last.text.trim() == message.trim()) history.dropLast(1) else history
        val res = try {
            Network.call {
                service.chat(
                    ChatRequestDto(
                        userId = userId,
                        message = text,
                        history = historyOf(prior.filter { it.kind != ChatKind.LOCAL }.map { (it.role == ChatRole.USER) to it.text }),
                    )
                )
            }
        } catch (e: ApiException) {
            if (e.code != "AI_UNAVAILABLE" && e.code != "RAG_UNAVAILABLE") throw e
            return ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = e.userMessage(),
                needsCheck = true,
                kind = ChatKind.LOCAL,
            )
        }

        return when (res.type) {
            "crisis" -> ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = res.message.orEmpty(),
                kind = ChatKind.CRISIS,
                contacts = res.contacts.map { HelpLine(it.name, it.phone) },
            )
            "counsel" -> ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = res.message.orEmpty(),
                kind = ChatKind.COUNSEL,
            )
            // 서류를 쓰고 싶다는 말 → 문서 AI 로 넘긴다 (라우터)
            "document" -> ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = res.message ?: "서류 작성을 도와드릴게요. 어떤 서류를 쓸까요?",
                kind = ChatKind.DOCUMENT,
                candidates = res.handoff?.candidates.orEmpty().map {
                    DocCandidate(it.title, it.policyId, if (it.sourceType == "template") it.sourceId else null)
                },
                handoffContext = res.handoff?.context,
            )
            else -> {
                val search = res.search
                val policies = search?.results.orEmpty().map { it.toPolicy() }
                val answer = search?.let { s -> s.answer?.takeIf { s.answerStatus == "answered" && it.isNotBlank() } }
                ChatMessage(
                    id = 0,
                    role = ChatRole.ASSISTANT,
                    text = answer?.let(AnswerFormat::answer)
                        ?: ("확인할 수 있는 공식 정보가 없어요. 추측해서 답하지 않을게요.\n\n" +
                            "질문을 조금 더 구체적으로 해 주시거나, 보건복지상담센터(129)에 확인해 보세요."),
                    // 근거는 청크 전체 대신 답변과 겹치는 부분만 짧게
                    evidence = search?.results.orEmpty()
                        .flatMap { r -> r.evidences.map { AnswerFormat.evidence(it.content, r.name, answer.orEmpty()) } }
                        .take(3),
                    policies = policies,
                    needsCheck = answer == null,
                    kind = ChatKind.POLICY,
                )
            }
        }
    }
}

// ---------------- 변환 ----------------

private const val WON = 10_000

/** 질문·대사 길이 (3.15, 3.18) */
internal const val MESSAGE_MAX = 500
/** 이전 대화 규칙 (1.6): 최근 20개, 한 개당 1000자 */
private const val HISTORY_MAX = 20
private const val HISTORY_CONTENT_MAX = 1000

/** (내가 한 말인지, 내용) 목록 → 서버 history. 빈 말은 빼고 최근 20개만, 1000자까지 */
internal fun historyOf(turns: List<Pair<Boolean, String>>): List<HistoryDto> =
    turns.map { (fromUser, text) -> fromUser to text.trim() }
        .filter { it.second.isNotEmpty() }
        .takeLast(HISTORY_MAX)
        .map { (fromUser, text) -> HistoryDto(if (fromUser) "user" else "assistant", text.take(HISTORY_CONTENT_MAX)) }

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

internal fun stageOf(code: String): UserStage = when (code) {
    "child" -> UserStage.CHILD
    "teen" -> UserStage.TEEN
    else -> UserStage.YOUNG_ADULT
}

internal fun UserStage.toCode(): String = when (this) {
    UserStage.CHILD -> "child"
    UserStage.TEEN -> "teen"
    UserStage.YOUNG_ADULT -> "youth"
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
    phone = phone,
    dDate = dDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    dDay = dDay,
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
    evidence = evidences.map { AnswerFormat.evidence(it.content, "$agency · $name") },
    keywords = emptyList(),
    isOutdated = isOutdated,
    sourceUrl = sourceUrl,
    eligibility = eligibility.status,
    cardSummary = this.summary?.toModel(),
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
    requiredDocs = requiredDocs.mapNotNull { it.toRequiredDoc() },
    evidence = listOf(Evidence("$agency · 공식 원문", originalText)),
    keywords = emptyList(),
    isOutdated = isOutdated,
    sourceUrl = sourceUrl,
    eligibility = eligibility?.status,
)

/** 필요 서류 한 개: {"name","condition"} 또는 예전 형식 "신분증" */
private fun JsonElement.toRequiredDoc(): RequiredDoc? = when {
    isJsonPrimitive -> asString.takeIf { it.isNotBlank() }?.let { RequiredDoc(it) }
    isJsonObject -> {
        val o = asJsonObject
        val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        val condition = o.get("condition")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        name?.takeIf { it.isNotBlank() }?.let { RequiredDoc(it, condition) }
    }
    else -> null
}

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

// ----- 체크리스트 -----

private fun ChecklistPolicyDto.toModel() = ChecklistPolicy(
    id = policyId.toString(),
    name = name,
    agency = agency,
    category = categoryLabel(category),
    contact = contact?.takeIf { it.isNotBlank() },
    applyUrl = applyUrl?.takeIf { it.isNotBlank() },
    checkedAt = formatChecked(checkedAt),
    isActive = isActive,
)

/** next_step 이 있는데 모르는 코드면 완료로 잘못 보이지 않게 신청 단계로 둔다 */
private fun NextStepDto?.toStepType(): StepType? = this?.let { StepType.of(it.stepKey) ?: StepType.APPLY }

private fun DashboardChecklistDto.toSummary() = ChecklistSummary(
    id = checklistId,
    policy = policy.toModel(),
    doneSteps = progress.checkedSteps,
    totalSteps = progress.totalSteps,
    nextStep = nextStep.toStepType(),
)

private fun GrowthChecklistDto.toSummary() = ChecklistSummary(
    id = checklistId,
    policy = policy.toModel(),
    doneSteps = progress.checkedSteps,
    totalSteps = progress.totalSteps,
    nextStep = nextStep.toStepType(),
)

/** 조건 판정 표시 (1.3). "신청 가능"으로 확정하지 않는다 */
/** 서버가 판정한 나이·지역만 결과를 쓰고, DB 에 없는 나머지 조건은 확인 필요로 둔다 */
private fun conditionChecks(e: EligibilityDto?): List<ConditionCheck> {
    fun judge(v: String?): Boolean? = when (v) {
        "match" -> true
        "excluded" -> false
        else -> null
    }
    return listOf(
        ConditionCheck("나이", judge(e?.age)),
        ConditionCheck("지역", judge(e?.region)),
        ConditionCheck("보호 이력·종료 시기 등 세부 조건", null),
    )
}

private val METHOD_LABEL = Regex("""(방문|온라인|우편·팩스|우편|팩스|전화)\s*:\s*""")

/** "방문: … 온라인: … 우편·팩스: …" → 방법별로 나눈다. 구분이 없으면 통째로 하나 */
private fun applyMethods(text: String?): List<ApplyMethod> {
    val src = text?.trim().orEmpty()
    if (src.isEmpty()) return emptyList()
    val marks = METHOD_LABEL.findAll(src).toList()
    if (marks.isEmpty()) return listOf(ApplyMethod("신청 방법", src))
    val head = src.substring(0, marks.first().range.first).trim()
    return marks.mapIndexed { i, m ->
        val end = if (i + 1 < marks.size) marks[i + 1].range.first else src.length
        var body = src.substring(m.range.last + 1, end).trim()
        if (i == 0 && head.isNotEmpty()) body = "$head $body"
        ApplyMethod(m.groupValues[1], body)
    }
}

private fun ChecklistDto.toChecklist(): Checklist {
    val docs = steps.flatMap { it.documents }.map {
        ChecklistDoc(
            itemId = it.itemId,
            name = it.label,
            condition = it.condition?.takeIf { c -> c.isNotBlank() },
            checked = it.isChecked,
            notApplicable = it.isNotApplicable,
            shortLabel = it.shortLabel?.takeIf { l -> l.isNotBlank() },
        )
    }
    return Checklist(
        id = checklistId,
        policy = policy.toModel(),
        steps = steps.mapNotNull { st ->
            val type = StepType.of(st.stepKey) ?: return@mapNotNull null
            val d = st.detail
            val description = when (type) {
                StepType.TARGET -> d?.targetDescription ?: st.label
                StepType.CONDITION -> d?.targetDescription ?: st.label
                StepType.DOCS -> if (docs.isEmpty()) "필요한 서류는 공식 원문에서 확인해 주세요."
                else "아래 서류를 하나씩 챙겨요. 서류를 다 챙기면 이 단계도 완료로 표시해 주세요."
                StepType.APPLY -> d?.applyMethod ?: "신청 방법은 공식 원문에서 확인해 주세요."
            }
            ChecklistStep(
                itemId = st.itemId,
                type = type,
                description = description,
                // 쉬운 말은 대상 확인 단계에만 있고, 정책이 노출 대상이 아니면 null
                easyDescription = if (type == StepType.TARGET) d?.easyText?.takeIf { it.isNotBlank() } else null,
                done = st.isChecked,
                checks = if (type == StepType.CONDITION) conditionChecks(d?.eligibility) else emptyList(),
                methods = if (type == StepType.APPLY) applyMethods(d?.applyMethod) else emptyList(),
                summary = d?.summary?.toModel(),
            )
        },
        docs = docs,
    )
}

// ----- 시뮬레이션 -----

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

// ---------------- 3.23 · 3.24 변환 ----------------

private fun EasyTextDto.toView() = EasyTextView(
    level = level,
    levelName = levelName,
    recommendedLevel = recommendedLevel,
    levels = levels.map { EasyLevel(it.level, it.levelName) },
    coreText = coreText,
    moreText = moreText,
    terms = terms.map { EasyTerm(it.term, it.gloss) },
    verified = verified,
)

private fun FormGuideDto.toView() = FormGuideView(
    title = title,
    checked = checked,
    notes = notes,
    items = items.map {
        FormGuideItem(
            fieldId = it.fieldId,
            label = it.label,
            section = it.section,
            how = it.how,
            source = when (it.valueSource) {
                "profile" -> FieldSource.PROFILE
                "handwrite" -> FieldSource.HANDWRITE
                "office" -> FieldSource.OFFICE
                else -> FieldSource.USER
            },
            required = it.required,
            guide = it.guide,
            example = it.example,
            prefill = it.prefill,
            evidence = it.evidence,
        )
    },
    notices = notices,
)

private fun PolicySummaryDto.toModel(): PolicySummary? {
    val list = sections.map { SummarySection(it.label, it.items.filter { i -> i.isNotBlank() }) }.filter { it.items.isNotEmpty() }
    return if (list.isEmpty()) null else PolicySummary(list, notice?.takeIf { it.isNotBlank() }, verified)
}
