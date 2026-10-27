package com.project.on_road.data.remote

import com.project.on_road.data.*

/**
 * AI 성장 코치 백엔드 연결 (docs/api.md 3.16 ~ 3.22).
 * - 서버: 롤플레잉 상황·첫 대사·대화·피드백, 서류 양식 목록·항목, 이력서·자기소개서 초안
 * - 앱(local): 흥미 탐색, 직업 추천, 로드맵, 내 양식 올리기 (서버 API 없음)
 */
class RemoteCoachApi(
    private val service: OnRoadService,
    private val local: CoachApi = FakeCoachApi(),
) : CoachApi {

    // ───────────── 앱에서 처리 ─────────────

    override suspend fun interestQuestions(stage: UserStage): List<InterestQuestion> = local.interestQuestions(stage)

    override suspend fun analyzeInterest(userId: String, answers: List<InterestOption>, freeText: String): InterestProfile =
        local.analyzeInterest(userId, answers, freeText)

    override suspend fun recommendJobs(userId: String, profile: InterestProfile, stage: UserStage): List<JobRecommendation> =
        local.recommendJobs(userId, profile, stage)

    override suspend fun buildRoadmap(userId: String, job: JobRecommendation, stage: UserStage): Roadmap =
        local.buildRoadmap(userId, job, stage)

    override suspend fun analyzeUpload(userId: String, bytes: ByteArray, mimeType: String): FormDraft =
        local.analyzeUpload(userId, bytes, mimeType)

    // ───────────── 롤플레잉 (3.16 ~ 3.19) ─────────────

    // GET /coach/roleplay/scenarios?stage=
    override suspend fun roleplayScenarios(stage: UserStage): List<RoleplayScenario> =
        Network.call { service.roleplayScenarios(stage.toCode()) }.scenarios.map { it.toModel() }

    // GET /coach/roleplay/{id}/opening?stage=
    override suspend fun roleplayOpening(scenarioId: String, stage: UserStage): RoleplayTurn {
        val res = Network.call { service.roleplayOpening(scenarioId, stage.toCode()) }
        return RoleplayTurn(fromUser = false, text = res.message)
    }

    // POST /coach/roleplay/{id}/reply : 마지막 내 말은 message, 그 앞은 history (1개 이상)
    override suspend fun roleplayReply(
        userId: String,
        scenarioId: String,
        stage: UserStage,
        history: List<RoleplayTurn>,
    ): RoleplayReply {
        val last = history.lastOrNull()
        require(last != null && last.fromUser) { "마지막은 내 말이어야 해요" }
        val prior = historyOf(history.dropLast(1).map { it.fromUser to it.text })
        require(prior.isNotEmpty()) { "첫 대사가 있어야 해요" }
        val res = Network.call {
            service.roleplayReply(
                scenarioId,
                RoleplayReplyRequestDto(
                    userId = userId,
                    stage = stage.toCode(),
                    message = last.text.trim().take(MESSAGE_MAX),
                    history = prior,
                ),
            )
        }
        return RoleplayReply(res.reply, res.isFinished)
    }

    // POST /coach/roleplay/{id}/feedback
    override suspend fun roleplayFeedback(
        userId: String,
        scenarioId: String,
        history: List<RoleplayTurn>,
        save: Boolean,
    ): RoleplayFeedback {
        val res = Network.call {
            service.roleplayFeedback(
                scenarioId,
                RoleplayFeedbackRequestDto(
                    userId = userId,
                    history = historyOf(history.map { it.fromUser to it.text }),
                    save = save,
                ),
            )
        }
        return RoleplayFeedback(
            good = res.good,
            missed = res.missed,
            documents = res.documents,
            better = res.better.map { it.before to it.after },
            roleplayId = res.roleplayId,
        )
    }

    // ───────────── 서류 양식 (3.20, 3.21) ─────────────

    // GET /forms/templates?stage=
    override suspend fun formTemplates(stage: UserStage): List<FormTemplate> =
        Network.call { service.formTemplates(stage.toCode()) }.templates.map {
            FormTemplate(it.templateId, it.name, it.description, it.stages.map(::stageOf).toSet())
        }

    // GET /forms/templates/{id}
    override suspend fun analyzeTemplate(templateId: String): FormDraft {
        val res = Network.call { service.formTemplate(templateId) }
        return FormDraft(
            templateId = res.templateId,
            title = res.name,
            fields = res.fields.map {
                FormField(
                    id = it.fieldId,
                    label = it.label,
                    easyLabel = it.easyLabel,
                    guide = it.guide,
                    caution = it.caution,
                    example = it.example,
                    multiline = it.multiline,
                    essay = it.essay,
                    outline = it.outline?.takeIf { lines -> lines.isNotEmpty() },
                )
            },
            originalExport = true,
        )
    }

    // ───────────── 초안 (3.22) ─────────────

    // POST /users/{userId}/documents
    override suspend fun generateDocument(userId: String, type: DocType, records: AppRecords): GeneratedDoc {
        val res = Network.call {
            service.createDocument(
                userId,
                DocumentRequestDto(
                    type = type.toCode(),
                    appRecords = AppRecordsDto(
                        savedPolicyCount = records.savedPolicyCount.coerceAtLeast(0),
                        interestSummary = records.interestSummary?.trim()?.take(500)?.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
        }
        return GeneratedDoc(
            type = docTypeOf(res.type) ?: type,
            sections = res.sections.map { s ->
                DocSection(s.title, s.sentences.map { DocSentence(it.text, it.source) })
            },
            notice = res.notice,
        )
    }
}

private fun RoleplayScenarioDto.toModel() = RoleplayScenario(
    id = scenarioId,
    title = title,
    category = category,
    partner = partner,
    setting = setting,
    goal = goal,
    level = levelOf(level),
    stages = stages.map(::stageOf).toSet(),
)

/**
 * 서버 난이도 코드 → 앱 난이도.
 * 서버는 소문자(easy/normal/hard)로 보내는데 예전에는 대문자 이름으로만 찾아서 전부 '보통'으로 보였다.
 */
private fun levelOf(code: String): RoleplayLevel = when (code.trim().lowercase()) {
    "easy", "쉬움", "low", "1" -> RoleplayLevel.EASY
    "hard", "실전", "high", "real", "3" -> RoleplayLevel.HARD
    else -> RoleplayLevel.NORMAL
}

private fun DocType.toCode(): String = when (this) {
    DocType.ACTIVITY_RECORD -> "activity_record"
    DocType.RESUME -> "resume"
    DocType.COVER_LETTER -> "cover_letter"
}

private fun docTypeOf(code: String): DocType? = when (code) {
    "activity_record" -> DocType.ACTIVITY_RECORD
    "resume" -> DocType.RESUME
    "cover_letter" -> DocType.COVER_LETTER
    else -> null
}
