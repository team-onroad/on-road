package com.project.on_road.data.remote

import com.google.gson.GsonBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.project.on_road.data.BudgetCategory
import com.project.on_road.data.ApplyMethod
import com.project.on_road.data.ChatApi
import com.project.on_road.data.FamilyMember
import com.project.on_road.data.ChatKind
import com.project.on_road.data.ChatMessage
import com.project.on_road.data.ChatRole
import com.project.on_road.data.DocCandidate
import com.project.on_road.data.toChange
import com.project.on_road.data.Checklist
import com.project.on_road.data.ConditionCheck
import com.project.on_road.data.ChecklistDoc
import com.project.on_road.data.ChecklistForm
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
import com.project.on_road.data.serverCode
import com.project.on_road.data.NextStep
import com.project.on_road.data.Readiness
import com.project.on_road.data.ReadinessArea
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
        district: String?,
        careType: com.project.on_road.data.CareType?,
    ): UserProfile = Network.call {
        service.createUser(
            UserCreateDto(
                name = name,
                birthDate = birthDate.toString(), // YYYY-MM-DD
                region = region,
                status = status.toCode(),
                phone = phone?.takeIf { it.isNotBlank() },
                dDate = dDate?.toString(), // YYYY-MM-DD
                district = district,
                careType = careType?.code,
            )
        ).toProfile()
    }

    override suspend fun updateUser(userId: String, update: UserUpdate): UserProfile = Network.call {
        val body = JsonObject().apply {
            update.status?.let { addProperty("status", it.toCode()) }
            update.name?.let { addProperty("name", it) }
            update.region?.let { addProperty("region", it) }
            update.phone?.let { addProperty("phone", it) }
            update.birthDate?.let { addProperty("birth_date", it.toString()) }
            when {
                update.clearDDate -> add("d_date", JsonNull.INSTANCE) // null 을 보내야 지워진다
                update.dDate != null -> addProperty("d_date", update.dDate.toString())
            }
            when {
                update.clearWork -> add("work", JsonNull.INSTANCE)
                update.work != null -> addProperty("work", update.work.serverCode())
            }
            when {
                update.clearDistrict -> add("district", JsonNull.INSTANCE)
                update.district != null -> addProperty("district", update.district)
            }
            when {
                update.clearCareType -> add("care_type", JsonNull.INSTANCE)
                update.careType != null -> addProperty("care_type", update.careType.code)
            }
            update.interestCode?.let { addProperty("interest_code", it) }
            update.interestTags?.let { tags -> add("interest_tags", com.google.gson.JsonArray().apply { tags.forEach { add(it) } }) }
            update.family?.let { list ->
                add("family", com.google.gson.JsonArray().apply {
                    list.forEach { m ->
                        add(JsonObject().apply {
                            addProperty("relation", m.relation)
                            if (m.gender != null) addProperty("gender", m.gender) else add("gender", JsonNull.INSTANCE)
                            addProperty("birth_date", m.birthDate.toString())
                        })
                    }
                })
            }
        }
        val json = GsonBuilder().serializeNulls().create().toJson(body)
        service.updateUser(userId, json.toRequestBody("application/json; charset=utf-8".toMediaType())).toProfile()
    }

    override suspend fun getUser(userId: String): UserProfile? =
        try {
            Network.call { service.getUser(userId).also { it.syncWork(session()) }.toProfile() }
        } catch (e: ApiException) {
            if (e.code == "USER_NOT_FOUND") null else throw e
        }

    // ---------------- 홈 (3.4) ----------------

    override suspend fun getDashboard(userId: String): Dashboard {
        val res = Network.call { service.getDashboard(userId) }
        // 신청 예정일 알림을 서버 값으로 맞춘다
        runCatching { com.project.on_road.notification.DueReminder.sync(res.checklists.map { it.toSummary() }) }
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

    override suspend fun searchPolicies(userId: String, question: String, withAnswer: Boolean): SearchResult {
        val res = Network.call { service.search(SearchRequestDto(userId, question, answer = withAnswer)) }
        // 순서: 답변 근거(내 정책 → 가족 대상 정책) → 비슷한 정책. 화면은 묶음이 바뀌는 곳에 제목을 단다
        val policies = res.results.map { it.toPolicy() }.sortedBy { if (it.related) 2 else if (it.forFamily) 1 else 0 }
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

    override suspend fun browsePolicies(userId: String, category: String?): SearchResult {
        val res = Network.call { service.listPolicies(userId, category) }
        val policies = res.results.map { it.toPolicy() }
        return SearchResult(
            question = category.orEmpty(),
            grounded = policies.isNotEmpty(),
            message = if (policies.isNotEmpty()) "정책 ${policies.size}개" else "이 분야 정책이 없어요.",
            policies = policies,
            answer = null,
        )
    }

    /** 서버 정책 id는 숫자. 예시 데이터 id(settlement 등)가 들어오면 null. */
    /** 정책 상세는 5분 동안 다시 쓴다 (홈 · 다음 할 일 · 비교 화면이 같은 정책을 여러 번 부름). 대상 판정이 사람마다 달라 키에 user_id 를 넣는다 */
    private val policyCache = MemoCache<Pair<Long, String?>, Policy?>(5 * 60 * 1000L)

    override suspend fun getPolicy(policyId: String): Policy? {
        val id = policyId.toLongOrNull() ?: return null
        return policyCache.getOrLoad(id to currentUserId) {
            try {
                Network.call { service.getPolicy(id, currentUserId).toPolicy() }
            } catch (e: ApiException) {
                if (e.code == "POLICY_NOT_FOUND") null else throw e
            }
        }
    }

    /** 여러 정책은 한꺼번에 (하나씩 기다리지 않게) */
    override suspend fun getPolicies(ids: List<String>): List<Policy> = kotlinx.coroutines.coroutineScope {
        ids.map { id -> async { runCatching { getPolicy(id) }.getOrNull() } }.awaitAll().filterNotNull()
    }

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
    /** 관심 정책은 서버에 저장한다 (다른 휴대폰 · 다시 설치해도 남음). 휴대폰에도 같이 적어 두고 서버가 안 될 때 쓴다 */
    override suspend fun savePolicy(userId: String, policyId: String, saved: Boolean) {
        val id = policyId.toLongOrNull() ?: throw ApiException(404, "POLICY_NOT_FOUND", "정책을 찾을 수 없습니다.")
        Network.call {
            if (saved) service.savePolicy(userId, SavePolicyDto(id))
            else service.unsavePolicy(userId, policyId).also { if (!it.isSuccessful) throw retrofit2.HttpException(it) }
        }
        session()?.let { it.savedPolicyIds = if (saved) it.savedPolicyIds + policyId else it.savedPolicyIds - policyId }
        savedCache.clear()
    }

    override suspend fun setReceiving(userId: String, policyId: String, receiving: Boolean) {
        val id = policyId.toLongOrNull() ?: throw ApiException(404, "POLICY_NOT_FOUND", "정책을 찾을 수 없습니다.")
        Network.call { service.savePolicy(userId, SavePolicyDto(id, status = if (receiving) "receiving" else "saved")) }
        // 받는 중으로 바꾸면 관심 정책에도 담긴다 (서버도 같은 저장 줄)
        session()?.let { it.savedPolicyIds = it.savedPolicyIds + policyId }
        savedCache.clear()
    }

    override suspend fun getReceivingPolicyIds(userId: String): Set<String> =
        Network.call { service.savedPolicies(userId) }.policies
            .filter { it.status == "receiving" }
            .map { it.policy.policyId.toString() }
            .toSet()

    // ---------------- 교육 이수 · 자격증 (3.37) ----------------

    override suspend fun getCredentials(userId: String): List<com.project.on_road.data.Credential> =
        Network.call { service.credentials(userId) }.credentials.map { it.toCredential() }

    override suspend fun addCredential(
        userId: String, kind: String, name: String?, code: String?, doneOn: LocalDate?,
    ): com.project.on_road.data.Credential =
        Network.call { service.addCredential(userId, CredentialCreateDto(kind, code, name, doneOn?.toString())) }.toCredential()

    override suspend fun deleteCredential(userId: String, credentialId: Long) {
        Network.call {
            service.deleteCredential(userId, credentialId).also { if (!it.isSuccessful) throw retrofit2.HttpException(it) }
        }
    }

    override suspend fun getEducationOptions(): List<com.project.on_road.data.EducationOption> =
        Network.call { service.educationOptions() }.map { com.project.on_road.data.EducationOption(it.code, it.name, it.description) }

    /** 홈 · 비교 · 서류가 여러 번 부르니 1분 동안 다시 쓴다 (담기 · 빼기 하면 바로 비움) */
    private val savedCache = MemoCache<String, Set<String>>(60 * 1000L)

    override suspend fun getSavedPolicyIds(userId: String): Set<String> {
        val local = session()?.savedPolicyIds.orEmpty()
        return runCatching {
            savedCache.getOrLoad(userId) {
                var ids = Network.call { service.savedPolicies(userId) }.policies.map { it.policy.policyId.toString() }.toSet()
                // 예전에 휴대폰에만 담아 둔 정책은 처음 한 번만 서버로 옮긴다
                val store = session()
                if (store != null && !store.savedPoliciesSynced) {
                    val moved = (local - ids).filter { pid ->
                        val n = pid.toLongOrNull() ?: return@filter false
                        runCatching { Network.call { service.savePolicy(userId, SavePolicyDto(n)) } }.isSuccess
                    }
                    ids = ids + moved
                    store.savedPoliciesSynced = true
                }
                session()?.savedPolicyIds = ids
                ids
            }
        }.getOrDefault(local) // 서버가 안 되면 휴대폰에 적어 둔 것
    }

    // ---------------- 체크리스트 (3.9 ~ 3.11) ----------------

    /** 체크리스트 목록은 홈(3.4)의 checklists 전체를 쓴다 */
    override suspend fun getChecklists(userId: String): List<ChecklistSummary> = getDashboard(userId).checklists

    override suspend fun deleteChecklist(userId: String, checklistId: Long) {
        Network.call { service.deleteChecklist(checklistId, userId).also { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
    }

    override suspend fun deleteAccount(userId: String) {
        Network.call { service.deleteUser(userId).also { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
        savedCache.clear()
    }

    override suspend fun openChecklist(userId: String, policyId: String): Checklist {
        val id = policyId.toLongOrNull() ?: throw ApiException(404, "POLICY_NOT_FOUND", "정책을 찾을 수 없습니다.")
        return Network.call { service.openChecklist(ChecklistOpenDto(userId, id)) }.toChecklist()
    }

    override suspend fun getChecklist(userId: String, checklistId: Long): Checklist =
        Network.call { service.getChecklist(checklistId, userId) }.toChecklist()

    override suspend fun setDueDate(userId: String, checklistId: Long, date: LocalDate?): Checklist {
        val json = GsonBuilder().serializeNulls().create().toJson(mapOf("due_date" to date?.toString()))
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        return Network.call { service.setDueDate(checklistId, userId, body) }.toChecklist()
    }

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

    private val criteriaCache = MemoCache<Unit, CriteriaDto>(60 * 60 * 1000L)

    override suspend fun getCriteria(): Map<BudgetCategory, Int> {
        val res = criteriaCache.getOrLoad(Unit) { Network.call { service.getCriteria() } }
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

    // ---------------- 활동 기록 · 자립 준비도 ----------------

    override suspend fun logActivity(userId: String, kind: String, ref: String?, data: Map<String, Any?>) {
        Network.call { service.postActivity(userId, ActivityDto(kind, ref, data)) }
    }

    override suspend fun getPolicyRoadmap(userId: String): com.project.on_road.data.PolicyRoadmap =
        Network.call { service.getPolicyRoadmap(userId) }.toRoadmap()

    override suspend fun getRecommendedPolicies(userId: String): List<com.project.on_road.data.RecommendedPolicy> =
        Network.call { service.getRecommendedPolicies(userId) }.map {
            com.project.on_road.data.RecommendedPolicy(
                it.policyId.toString(), it.name, categoryLabel(it.category), it.eligibility, it.whenText,
                it.amountUnit, it.amountValue, it.benefit, it.pickReason,
            )
        }

    override suspend fun getSameKind(userId: String, policyId: String): com.project.on_road.data.SameKindGroup? =
        runCatching { Network.call { service.getSameKind(userId, policyId) } }.getOrNull()?.let { g ->
            com.project.on_road.data.SameKindGroup(
                kindTitle = g.kindTitle,
                rule = g.rule,
                total = g.total,
                picked = g.picked?.let { com.project.on_road.data.SameKindPick(it.policyId.toString(), it.name, it.region, it.amountText, it.reason) },
                others = g.others.orEmpty().map { com.project.on_road.data.SameKindRow(it.policyId.toString(), it.name, it.region, it.amountText, it.mine) },
            )
        }

    override suspend fun getCareerRoadmap(userId: String): com.project.on_road.data.CareerRoadmap =
        Network.call { service.getCareerRoadmap(userId) }.toCareer()

    override suspend fun getReadiness(userId: String): Readiness {
        val res = Network.call { service.getReadiness(userId) }
        fun FollowUpDto.toStep() = NextStep(kind, label, query, scenarioId)
        return Readiness(
            score = res.score,
            level = res.level,
            areas = res.areas.map { a ->
                ReadinessArea(
                    a.key, a.label, a.score, a.doneCount, a.total,
                    a.items.map { it.label to it.done }, a.nextStep?.toStep(),
                    details = a.items.map { it.detail },
                    progresses = a.items.map { it.progress ?: if (it.done) 1f else 0f },
                )
            },
            weakest = res.weakest,
            nextSteps = res.nextSteps.map { it.toStep() },
            weekDelta = res.weekDelta ?: 0,
            history = res.history.orEmpty().sortedByDescending { it.weeksAgo }.map { it.score },
            recent = res.recent.orEmpty(),
            nextLevel = res.nextLevel?.let { it.label to it.points },
            watch = res.insights.orEmpty().filter { it.tone == "watch" }.map { it.text },
            good = res.insights.orEmpty().filter { it.tone == "good" }.map { it.text },
        )
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
    override suspend fun reply(
        userId: String,
        history: List<ChatMessage>,
        message: String,
        onPartial: ((String) -> Unit)?,
        onStatus: ((String) -> Unit)?,
    ): ChatMessage {
        val text = message.trim().take(MESSAGE_MAX)
        // 방금 보낸 내 말은 message 로 따로 보내므로 이전 대화에서 뺀다
        val last = history.lastOrNull()
        val prior = if (last != null && last.role == ChatRole.USER && last.text.trim() == message.trim()) history.dropLast(1) else history
        val req = ChatRequestDto(
            userId = userId,
            message = text,
            history = historyOf(prior.filter { it.kind != ChatKind.LOCAL }.map { (it.role == ChatRole.USER) to it.text }),
        )
        val res = try {
            // 스트리밍: 고민 상담 답은 문장이 나오는 대로 onPartial 로. 서버에 아직 스트리밍 주소가 없으면 보통 요청
            try {
                Sse.read(Network.call { service.chatStream(req) }, ChatResponseDto::class.java, onPartial, onStatus).first
            } catch (e: ApiException) {
                if (!e.noStreamRoute()) throw e
                Network.call { service.chat(req) }
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

        val reply = when (res.type) {
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
                        .filterNot { it.related }
                        .flatMap { r -> r.evidences.map { AnswerFormat.evidence(it.content, r.name, answer.orEmpty()) } }
                        .take(3),
                    policies = policies,
                    needsCheck = answer == null,
                    kind = ChatKind.POLICY,
                    plan = res.plan?.toPlan(),
                )
            }
        }
        // 대화에서 찾은 프로필 변경 후보 → 확인 카드 (승인할 때만 반영)
        return reply.copy(profileChanges = res.profileChanges.orEmpty().map { it.toChange() })
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

/** 서버에 지금 하는 일이 있고 휴대폰엔 없으면 받아 둔다 (다른 휴대폰에서 정한 값) */
private fun UserDto.syncWork(store: com.project.on_road.data.SessionStore?) {
    if (store == null || store.workStatus != null) return
    store.workStatus = com.project.on_road.data.WorkStatus.entries.firstOrNull { it.serverCode() == work }
}

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
    district = district,
    family = family.orEmpty().mapNotNull { m ->
        runCatching { FamilyMember(m.relation, m.gender, LocalDate.parse(m.birthDate), m.age) }.getOrNull()
    },
    interestCode = interestCode,
    interestTags = interestTags.orEmpty(),
    careType = com.project.on_road.data.CareType.of(careType),
)

private fun SearchResultDto.toPolicy() = Policy(
    id = policyId.toString(),
    name = name,
    category = categoryLabel(category),
    agency = agency,
    checkedAt = formatChecked(checkedAt),
    summary = originalText,
    easySummary = easyText,
    target = GovText.clean(targetDescription).orEmpty(),
    amount = GovText.amount(supportAmount) ?: GovText.clean(supportContent).orEmpty(),
    period = GovText.clean(supportPeriod) ?: "공식 원문에서 확인",
    howToApply = "",
    applyUrl = sourceUrl,
    requiredDocs = emptyList(),
    // 비슷한 정책은 답변 근거가 아니므로 근거 문장으로 보여 주지 않는다
    evidence = if (related) emptyList() else evidences.map { AnswerFormat.evidence(it.content, "$agency · $name") },
    keywords = emptyList(),
    isOutdated = isOutdated,
    sourceUrl = sourceUrl,
    eligibility = eligibility.status,
    leave = eligibility.leave,
    cardSummary = this.summary?.toModel(),
    amountShort = GovText.amount(supportAmount).orEmpty(),
    related = related,
    forFamily = forFamily,
)

private fun PolicyDetailDto.toPolicy() = Policy(
    id = policyId.toString(),
    name = name,
    category = categoryLabel(category),
    agency = agency,
    checkedAt = formatChecked(checkedAt),
    summary = originalText,
    easySummary = easyText,
    target = GovText.clean(targetDescription).orEmpty(),
    amount = GovText.amount(supportAmount) ?: GovText.clean(supportContent).orEmpty(),
    period = GovText.clean(supportPeriod) ?: "공식 원문에서 확인",
    howToApply = GovText.clean(applyMethod).orEmpty(),
    applyUrl = applyUrl ?: sourceUrl,
    requiredDocs = requiredDocs.mapNotNull { it.toRequiredDoc() },
    evidence = listOf(Evidence("$agency · 공식 원문", originalText)),
    keywords = emptyList(),
    isOutdated = isOutdated,
    sourceUrl = sourceUrl,
    eligibility = eligibility?.status,
    leave = eligibility?.leave,
    amountShort = amountText ?: GovText.amount(supportAmount).orEmpty(),
    contact = contact.orEmpty(),
    region = region,
    ageMin = ageMin,
    ageMax = ageMax,
    amountWon = amountWon,
    amountCycle = amountCycle,
    periodYears = periodYears,
    relatedPolicies = related.orEmpty().map {
        com.project.on_road.data.RelatedPolicyItem(it.policyId.toString(), it.name, it.agency, categoryLabel(it.category), it.amountText, it.benefit)
    },
    sameKind = sameKind?.let { k ->
        fun alt(a: RoadmapAlternativeDto) = com.project.on_road.data.RoadmapAlternative(a.policyId.toString(), a.name, a.region, a.amountText)
        com.project.on_road.data.SameKindInfo(k.kindTitle, k.isBest, k.reason, alt(k.best), k.others.orEmpty().map(::alt))
    },
    benefit = benefit,
    conditions = conditions.orEmpty(),
    exclusions = exclusions.orEmpty(),
    applyWays = applyWays.orEmpty().mapNotNull { w -> w.way?.takeIf { it.isNotBlank() }?.let { it to w.lines.orEmpty() } },
    applyDeadline = applyDeadline,
    applySteps = applySteps.orEmpty(),
    docFacts = docFacts.orEmpty(),
    requiredEducation = requiredEducation,
)

private fun String?.toDate(): LocalDate? = this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun PolicyRoadmapDto.toRoadmap() = com.project.on_road.data.PolicyRoadmap(
    baseDate = baseDate.toDate(),
    dDay = dDay,
    monthlyWon = summary.monthlyWon,
    onceWon = summary.onceWon,
    matchCount = summary.matchCount,
    receivingCount = summary.receivingCount,
    receivingMonthlyWon = summary.receivingMonthlyWon,
    newMonthlyWon = summary.newMonthlyWon,
    newOnceWon = summary.newOnceWon,
    stages = stages.map { s ->
        com.project.on_road.data.RoadmapStage(s.stage, s.title, s.items.map { i ->
            com.project.on_road.data.RoadmapItem(
                i.policyId.toString(), i.name, i.agency, i.category, i.eligibility, i.amountText, i.benefit, i.applyFrom.toDate(), i.applyNote,
                until = i.until.toDate(),
                ageUntil = i.ageUntil.toDate(),
                ageMax = i.ageMax,
                region = i.region ?: "전국",
                kindTitle = i.kindTitle,
                pickReason = i.pickReason,
                alternatives = i.alternatives.orEmpty().map { a ->
                    com.project.on_road.data.RoadmapAlternative(a.policyId.toString(), a.name, a.region, a.amountText)
                },
                receiving = i.receiving,
                startedOn = i.startedOn.toDate(),
                education = i.education?.let { e ->
                    com.project.on_road.data.RoadmapEducation(e.code, e.name, e.done, e.doneOn.toDate(), e.note)
                },
            )
        })
    },
)

internal fun ChatPlanDto.toPlan() = com.project.on_road.data.ChatPlan(
    baseDate = baseDate.toDate(),
    dDay = dDay,
    monthlyWon = monthlyWon,
    onceWon = onceWon,
    matchCount = matchCount,
    items = items.map {
        com.project.on_road.data.ChatPlanItem(
            it.policyId.toString(), it.name, it.amountText, it.benefit, it.applyFrom.toDate(), it.applyNote, it.hasForm,
        )
    },
)

internal fun CareerRoadmapDto.toCareer() = com.project.on_road.data.CareerRoadmap(
    recommended = recommended,
    paths = paths.map { p ->
        com.project.on_road.data.CareerPathPlan(
            p.path, p.title,
            // 예전 서버(step_id 없음)는 방향 + 순서로 이름을 만든다
            p.steps.mapIndexed { i, it ->
                com.project.on_road.data.CareerStep(
                    it.stepId ?: "${p.path}-$i", it.kind, it.title, it.detail, it.scenarioId, it.templateId, it.url, it.phone, it.target, it.query,
                    it.guide.orEmpty(), it.reason,
                )
            },
            p.policies.map { com.project.on_road.data.CareerPolicy(it.policyId.toString(), it.name, it.agency, it.eligibility, it.amountText, it.benefit) },
        )
    },
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
    dueDate = parseDue(dueDate),
)

private fun GrowthChecklistDto.toSummary() = ChecklistSummary(
    id = checklistId,
    policy = policy.toModel(),
    doneSteps = progress.checkedSteps,
    totalSteps = progress.totalSteps,
    nextStep = nextStep.toStepType(),
    dueDate = parseDue(dueDate),
)

private fun parseDue(date: String?): LocalDate? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

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
    val src = GovText.clean(text).orEmpty()
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

private fun FormLinkDto.toModel() = ChecklistForm(formId, title, notice?.takeIf { it.isNotBlank() })

private fun ChecklistDto.toChecklist(): Checklist {
    val docs = steps.flatMap { it.documents }.map {
        ChecklistDoc(
            itemId = it.itemId,
            name = it.label,
            condition = it.condition?.takeIf { c -> c.isNotBlank() },
            checked = it.isChecked,
            notApplicable = it.isNotApplicable,
            shortLabel = it.shortLabel?.takeIf { l -> l.isNotBlank() },
            documentId = it.documentId,
            documentStatus = it.documentStatus,
            forms = it.forms.orEmpty().map { f -> f.toModel() },
        )
    }
    return Checklist(
        id = checklistId,
        extraForms = extraForms.orEmpty().map { it.toModel() },
        policy = policy.toModel(),
        steps = steps.mapNotNull { st ->
            val type = StepType.of(st.stepKey) ?: return@mapNotNull null
            val d = st.detail
            val description = when (type) {
                StepType.TARGET -> GovText.clean(d?.targetDescription) ?: st.label
                StepType.CONDITION -> GovText.clean(d?.targetDescription) ?: st.label
                StepType.DOCS -> if (docs.isEmpty()) "필요한 서류는 공식 원문에서 확인해 주세요."
                else "아래 서류를 하나씩 챙겨요. 서류를 다 챙기면 이 단계도 완료로 표시해 주세요."
                StepType.APPLY -> GovText.clean(d?.applyMethod) ?: "신청 방법은 공식 원문에서 확인해 주세요."
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
        dueDate = parseDue(dueDate),
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

private fun CredentialDto.toCredential() = com.project.on_road.data.Credential(
    credentialId, kind, code, name, doneOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
)
