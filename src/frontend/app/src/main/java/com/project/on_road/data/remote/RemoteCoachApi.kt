package com.project.on_road.data.remote

import com.project.on_road.data.*

/**
 * AI 성장 코치 백엔드 연결용 뼈대. 엔드포인트는 예시이며 백엔드 명세에 맞춰 수정한다.
 * 연결 순서: Retrofit 의존성 추가 → DTO 작성 → TODO를 호출로 교체 → AppContainer.USE_REMOTE = true
 */
class RemoteCoachApi(private val baseUrl: String) : CoachApi {

    // GET /coach/interest/questions?stage=
    override suspend fun interestQuestions(stage: UserStage): List<InterestQuestion> =
        TODO("GET $baseUrl/coach/interest/questions")

    // POST /coach/interest/analyze
    override suspend fun analyzeInterest(userId: String, answers: List<InterestOption>, freeText: String): InterestProfile =
        TODO("POST $baseUrl/coach/interest/analyze")

    // POST /coach/jobs/recommend  (임베딩 유사도 매칭 + 추천 이유 생성)
    override suspend fun recommendJobs(userId: String, profile: InterestProfile, stage: UserStage): List<JobRecommendation> =
        TODO("POST $baseUrl/coach/jobs/recommend")

    // POST /coach/roadmap
    override suspend fun buildRoadmap(userId: String, job: JobRecommendation, stage: UserStage): Roadmap =
        TODO("POST $baseUrl/coach/roadmap")

    // GET /coach/roleplay/scenarios?stage=
    override suspend fun roleplayScenarios(stage: UserStage): List<RoleplayScenario> =
        TODO("GET $baseUrl/coach/roleplay/scenarios")

    // GET /coach/roleplay/{id}/opening?stage=
    override suspend fun roleplayOpening(scenarioId: String, stage: UserStage): RoleplayTurn =
        TODO("GET $baseUrl/coach/roleplay/$scenarioId/opening")

    // POST /coach/roleplay/{id}/reply  (상대역 발화. 정책 정보는 DB 근거로 제한)
    override suspend fun roleplayReply(userId: String, scenarioId: String, stage: UserStage, history: List<RoleplayTurn>): RoleplayTurn =
        TODO("POST $baseUrl/coach/roleplay/$scenarioId/reply")

    // POST /coach/roleplay/{id}/feedback
    override suspend fun roleplayFeedback(userId: String, scenarioId: String, history: List<RoleplayTurn>): RoleplayFeedback =
        TODO("POST $baseUrl/coach/roleplay/$scenarioId/feedback")

    // GET /forms/templates?stage=
    override suspend fun formTemplates(stage: UserStage): List<FormTemplate> =
        TODO("GET $baseUrl/forms/templates")

    // GET /forms/templates/{id}
    override suspend fun analyzeTemplate(templateId: String): FormDraft =
        TODO("GET $baseUrl/forms/templates/$templateId")

    // POST /forms/analyze  (multipart: 사진·PDF 업로드 → 항목 추출)
    override suspend fun analyzeUpload(userId: String, bytes: ByteArray, mimeType: String): FormDraft =
        TODO("POST $baseUrl/forms/analyze")

    // POST /users/{userId}/documents  (앱 기록 기반 초안 생성)
    override suspend fun generateDocument(userId: String, type: DocType): GeneratedDoc =
        TODO("POST $baseUrl/users/$userId/documents")
}
