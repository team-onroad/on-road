package com.project.on_road.data.remote

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import retrofit2.http.Headers
import retrofit2.http.Body
import retrofit2.http.DELETE
import okhttp3.RequestBody
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody

/** 백엔드 API (docs/api.md). 경로는 BASE_URL(…/api/) 뒤에 붙는다. */
interface OnRoadService {

    // 3.2 온보딩
    @POST("users")
    suspend fun createUser(@Body body: UserCreateDto): UserDto

    // 3.3 사용자 조회 (앱 실행 시 user_id 확인, 404면 온보딩)
    @GET("users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): UserDto

    // 3.12 회원정보 수정. null 을 그대로 보내야 하는 필드(d_date 지우기)가 있어서 본문을 직접 만든다
    @PATCH("users/{userId}")
    suspend fun updateUser(@Path("userId") userId: String, @Body body: RequestBody): UserDto

    // 3.4 홈 (체크리스트 목록, 최근 시뮬레이션)
    @GET("users/{userId}/dashboard")
    suspend fun getDashboard(@Path("userId") userId: String): DashboardDto

    // 3.5 정책 검색 (RAG)
    @POST("search")
    suspend fun search(@Body body: SearchRequestDto): SearchResponseDto

    // 지우기 (성공 204). 없거나 다른 사용자 것이면 404
    @DELETE("users/{userId}")
    suspend fun deleteUser(@Path("userId") userId: String): retrofit2.Response<Unit>

    @DELETE("documents/{documentId}")
    suspend fun deleteDocument(@Path("documentId") documentId: Long, @Query("user_id") userId: String): retrofit2.Response<Unit>

    @DELETE("checklists/{checklistId}")
    suspend fun deleteChecklist(@Path("checklistId") checklistId: Long, @Query("user_id") userId: String): retrofit2.Response<Unit>

    // 정책 저장 (관심 정책). 저장 · 취소는 여러 번 보내도 같은 결과
    @GET("users/{userId}/saved-policies")
    suspend fun savedPolicies(@Path("userId") userId: String): SavedPolicyListDto

    @POST("users/{userId}/saved-policies")
    suspend fun savePolicy(@Path("userId") userId: String, @Body body: SavePolicyDto): JsonObject

    @DELETE("users/{userId}/saved-policies/{policyId}")
    suspend fun unsavePolicy(@Path("userId") userId: String, @Path("policyId") policyId: String): retrofit2.Response<Unit>

    // 이수한 교육 · 취득한 자격증 (3.37)
    @GET("users/education-options")
    suspend fun educationOptions(): List<EducationOptionDto>

    @GET("users/{userId}/credentials")
    suspend fun credentials(@Path("userId") userId: String): CredentialListDto

    @POST("users/{userId}/credentials")
    suspend fun addCredential(@Path("userId") userId: String, @Body body: CredentialCreateDto): CredentialDto

    @DELETE("users/{userId}/credentials/{credentialId}")
    suspend fun deleteCredential(@Path("userId") userId: String, @Path("credentialId") credentialId: Long): retrofit2.Response<Unit>

    // 분야별로 보기: 내 연령 · 지역에 맞는 정책을 분야 코드로 (검색 · AI 없이, category 없으면 전체)
    @GET("policies")
    suspend fun listPolicies(@Query("user_id") userId: String, @Query("category") category: String?): SearchResponseDto

    // 3.6 정책 상세 (user_id 없으면 eligibility null)
    @GET("policies/{policyId}")
    suspend fun getPolicy(
        @Path("policyId") policyId: Long,
        @Query("user_id") userId: String?,
    ): PolicyDetailDto

    // 3.23 쉬운 말 단계별 보기 (level 없으면 서버가 나이로 추천)
    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @GET("policies/{policyId}/easy-text")
    suspend fun getEasyText(
        @Path("policyId") policyId: Long,
        @Query("user_id") userId: String?,
        @Query("level") level: String?,
    ): EasyTextDto

    // 3.24 신청서 작성 도우미
    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @GET("policies/{policyId}/form-guide")
    suspend fun getFormGuide(
        @Path("policyId") policyId: Long,
        @Query("user_id") userId: String?,
    ): FormGuideDto

    // 3.7 배분 기준표
    @GET("simulations/criteria")
    suspend fun getCriteria(): CriteriaDto

    // 3.8 생활비 시뮬레이션 제출
    @POST("simulations")
    suspend fun createSimulation(@Body body: SimulationCreateDto): SimulationDto

    // 3.9 체크리스트 조회 또는 생성 (있으면 200, 새로 만들면 201)
    @POST("checklists")
    suspend fun openChecklist(@Body body: ChecklistOpenDto): ChecklistDto

    // 3.10 체크리스트 상세
    @GET("checklists/{checklistId}")
    suspend fun getChecklist(
        @Path("checklistId") checklistId: Long,
        @Query("user_id") userId: String,
    ): ChecklistDto

    // 신청 예정일 정하기 · 지우기. 본문 {"due_date": "YYYY-MM-DD" | null} (null 을 그대로 보내야 해서 본문을 직접 만든다). 오늘 이전은 422
    @PATCH("checklists/{checklistId}")
    suspend fun setDueDate(
        @Path("checklistId") checklistId: Long,
        @Query("user_id") userId: String,
        @Body body: RequestBody,
    ): ChecklistDto

    // 3.11 항목 체크 토글. 본문은 {"is_checked": bool} 또는 {"is_not_applicable": bool} 중 하나
    @PATCH("checklists/{checklistId}/items/{itemId}")
    suspend fun updateChecklistItem(
        @Path("checklistId") checklistId: Long,
        @Path("itemId") itemId: Long,
        @Query("user_id") userId: String,
        @Body body: JsonObject,
    ): ChecklistItemResponseDto

    // 3.13 성장 기록
    @GET("users/{userId}/growth")
    suspend fun getGrowth(@Path("userId") userId: String): GrowthDto

    // 활동 기록 (실전 연습 · 한 달 살아보기) → 자립 준비도
    @POST("users/{userId}/activities")
    suspend fun postActivity(@Path("userId") userId: String, @Body body: ActivityDto): ActivityCreatedDto

    // 자립 준비도 (6개 영역 점수와 다음 할 일)
    @GET("users/{userId}/readiness")
    suspend fun getReadiness(@Path("userId") userId: String): ReadinessDto

    // 3.32 맞춤 신청 로드맵 · 3.33 진로 로드맵
    @GET("users/{userId}/policy-roadmap")
    suspend fun getPolicyRoadmap(@Path("userId") userId: String): PolicyRoadmapDto

    @GET("users/{userId}/same-kind/{policyId}")
    suspend fun getSameKind(@Path("userId") userId: String, @Path("policyId") policyId: String): SameKindGroupDto

    @GET("users/{userId}/recommended-policies")
    suspend fun getRecommendedPolicies(@Path("userId") userId: String): List<RecommendedPolicyDto>

    @GET("users/{userId}/career-roadmap")
    suspend fun getCareerRoadmap(@Path("userId") userId: String): CareerRoadmapDto

    // 3.14 시뮬레이션 결과 조회
    @GET("simulations/{simulationId}")
    suspend fun getSimulation(
        @Path("simulationId") simulationId: Long,
        @Query("user_id") userId: String,
    ): SimulationDto

    // 3.15 AI 상담 채팅
    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @POST("chat")
    suspend fun chat(@Body body: ChatRequestDto): ChatResponseDto

    /** 상담 스트리밍: 고민 상담 답은 문장마다 온다 (text/event-stream). 받는 쪽은 Sse.read */
    @Headers("${Network.TIMEOUT_HEADER}: 60")
    @retrofit2.http.Streaming
    @POST("chat/stream")
    suspend fun chatStream(@Body body: ChatRequestDto): okhttp3.ResponseBody

    // 3.16 롤플레잉 상황 목록
    @GET("coach/roleplay/scenarios")
    suspend fun roleplayScenarios(@Query("stage") stage: String?): RoleplayScenariosDto

    // 3.17 첫 대사
    @GET("coach/roleplay/{scenarioId}/opening")
    suspend fun roleplayOpening(
        @Path("scenarioId") scenarioId: String,
        @Query("stage") stage: String?,
        @Query("variant_id") variantId: String? = null,
    ): RoleplayOpeningDto

    // 3.18 대화 한 턴
    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @POST("coach/roleplay/{scenarioId}/reply")
    suspend fun roleplayReply(
        @Path("scenarioId") scenarioId: String,
        @Body body: RoleplayReplyRequestDto,
    ): RoleplayReplyDto

    /** 실전 연습 대답 스트리밍: 상대 말이 문장마다 온다 (음성은 첫 문장부터 읽기 시작) */
    @Headers("${Network.TIMEOUT_HEADER}: 60")
    @retrofit2.http.Streaming
    @POST("coach/roleplay/{scenarioId}/reply/stream")
    suspend fun roleplayReplyStream(
        @Path("scenarioId") scenarioId: String,
        @Body body: RoleplayReplyRequestDto,
    ): okhttp3.ResponseBody

    // 대화 중 힌트 ("뭐라고 하지?", LLM 안 씀)
    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @POST("coach/roleplay/{scenarioId}/hint")
    suspend fun roleplayHint(
        @Path("scenarioId") scenarioId: String,
        @Body body: RoleplayHintRequestDto,
    ): RoleplayHintDto

    // 3.19 피드백 (save = true 면 성장 기록에 남김)
    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @POST("coach/roleplay/{scenarioId}/feedback")
    suspend fun roleplayFeedback(
        @Path("scenarioId") scenarioId: String,
        @Body body: RoleplayFeedbackRequestDto,
    ): RoleplayFeedbackDto

    // 3.20 서류 양식 목록
    @GET("forms/templates")
    suspend fun formTemplates(@Query("stage") stage: String?): FormTemplatesDto

    // 공통 양식 (퇴소 · 재보호 · 보호연장 신청서 등): 정책 없이 form_id 로 시작
    @GET("forms/common")
    suspend fun commonForms(): CommonFormListDto

    // 3.21 양식 항목
    @GET("forms/templates/{templateId}")
    suspend fun formTemplate(@Path("templateId") templateId: String): FormTemplateDetailDto

    // 3.22 이력서·자기소개서 초안
    @POST("users/{userId}/documents")
    suspend fun createDocument(
        @Path("userId") userId: String,
        @Body body: DocumentRequestDto,
    ): DocumentDto

    // ---------------- 3.25~3.32 문서 AI (DocumentDtos.kt) ----------------

    // 이미 있으면 그 문서(200), 없으면 새로 만듦(201)
    @POST("documents")
    suspend fun createWriterDocument(@Body body: DocumentCreateDto): WriterDocumentDto

    /** 양식 사진·PDF → AI 가 칸을 읽어 문서 생성 (사진 읽기는 오래 걸려 타임아웃을 늘린다) */
    @Headers("${Network.TIMEOUT_HEADER}: 120")
    @POST("documents/upload")
    suspend fun uploadDocument(@Body body: DocumentUploadDto): WriterDocumentDto

    /** 서류 속 숨겨진 위험 찾기 (등기부등본 · 임대차 계약서 · 근로계약서). 사진 읽기라 타임아웃을 늘린다 */
    @Headers("${Network.TIMEOUT_HEADER}: 120")
    @POST("documents/risk-check")
    suspend fun riskCheck(@Body body: RiskCheckRequestDto): RiskCheckDto

    @GET("users/{userId}/documents")
    suspend fun listDocuments(@Path("userId") userId: String): DocumentListDto

    @GET("documents/{documentId}")
    suspend fun getDocument(@Path("documentId") documentId: Long, @Query("user_id") userId: String): WriterDocumentDto

    @PATCH("documents/{documentId}/fields/{fieldId}")
    suspend fun updateField(
        @Path("documentId") documentId: Long,
        @Path("fieldId") fieldId: String,
        @Query("user_id") userId: String,
        @Body body: FieldUpdateDto,
    ): FieldResponseDto

    @POST("documents/{documentId}/fields/{fieldId}/undo")
    suspend fun undoField(
        @Path("documentId") documentId: Long,
        @Path("fieldId") fieldId: String,
        @Query("user_id") userId: String,
    ): FieldResponseDto

    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @POST("documents/{documentId}/assist")
    suspend fun assist(@Path("documentId") documentId: Long, @Body body: AssistRequestDto): AssistResponseDto

    @Headers("${Network.TIMEOUT_HEADER}: 60") // AI 가 여러 번 이어서 답하는 요청: 느린 날에도 끊기지 않게
    @POST("documents/{documentId}/interview")
    suspend fun interview(@Path("documentId") documentId: Long, @Body body: InterviewRequestDto): InterviewResponseDto

    @GET("documents/{documentId}/check")
    suspend fun checkDocument(@Path("documentId") documentId: Long, @Query("user_id") userId: String): CheckDto

    @POST("documents/{documentId}/export")
    suspend fun exportDocument(@Path("documentId") documentId: Long, @Body body: UserOnlyDto): ExportDto

    @Streaming
    @GET("documents/{documentId}/file")
    suspend fun documentFile(
        @Path("documentId") documentId: Long,
        @Query("user_id") userId: String,
        @Query("format") format: String = "pdf",
    ): ResponseBody

    // 3.35 진로 외부 정보 (큐넷 · 고용24 · 커리어넷)
    @GET("career/job-info")
    suspend fun careerJobInfo(@Query("name") name: String): JobInfoDto

    @GET("career/duties")
    suspend fun careerDuties(@Query("keyword") keyword: String): DutiesDto

    @GET("career/exams")
    suspend fun careerExams(@Query("cert") cert: String): ExamsDto

    @GET("career/certificates")
    suspend fun careerCertificates(@Query("q") q: String): CertificatesDto

    @GET("career/trainings")
    suspend fun careerTrainings(
        @Query("keyword") keyword: String,
        @Query("region") region: String?,
        @Query("district") district: String? = null,
    ): TrainingsDto

    @GET("career/trainings/detail")
    suspend fun careerTrainingDetail(
        @Query("id") id: String,
        @Query("round") round: String,
        @Query("institution") institution: String,
    ): TrainingDetailDto
}

// ---------------- 사용자 ----------------

data class UserCreateDto(
    val name: String,
    @SerializedName("birth_date") val birthDate: String,
    val phone: String? = null,
    val region: String,
    val status: String,
    @SerializedName("d_date") val dDate: String? = null,
    val district: String? = null,
    @SerializedName("care_type") val careType: String? = null,
)

data class UserDto(
    @SerializedName("user_id") val userId: String,
    val name: String,
    @SerializedName("birth_date") val birthDate: String,
    val age: Int,
    val phone: String?,
    val region: String,
    val status: String,
    val stage: String,
    @SerializedName("d_date") val dDate: String?,
    @SerializedName("d_day") val dDay: Int?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    /** 지금 하는 일 (student · working · job_seeking · resting, 서버가 예전 버전이면 없음) */
    val work: String? = null,
    /** 거주 시 · 군 · 구 (서버가 예전 버전이면 없음) */
    val district: String? = null,
    /** 자녀 · 동생 (서버가 예전 버전이면 없음) */
    val family: List<FamilyMemberDto>? = null,
    @SerializedName("interest_code") val interestCode: String? = null,
    @SerializedName("interest_tags") val interestTags: List<String>? = null,
    @SerializedName("care_type") val careType: String? = null,
)

data class FamilyMemberDto(
    val relation: String,
    val gender: String?,
    @SerializedName("birth_date") val birthDate: String,
    val age: Int,
)

// ---------------- 정책 ----------------

data class EligibilityDto(
    val status: String,
    val age: String,
    val region: String,
    /** 보호 상태: now(퇴소했고 퇴소한 사람이 받는 정책) · after_leave(아직 보호 중인데 퇴소해야 받는 정책) · null */
    val leave: String? = null,
)

/** status: null 이면 새로 저장할 때 saved, 이미 저장했으면 그대로 · "receiving" 이면 지금 받고 있음 (3.36). null 은 보내지 않는다(Gson 기본) */
data class SavePolicyDto(
    @SerializedName("policy_id") val policyId: Long,
    val status: String? = null,
    @SerializedName("started_on") val startedOn: String? = null,
)

data class SavedPolicyListDto(val count: Int, val policies: List<SavedPolicyDto>)

/** status: saved · receiving (예전 서버는 없음 → saved) */
data class SavedPolicyDto(
    val policy: SavedPolicyRefDto,
    val status: String? = null,
    @SerializedName("started_on") val startedOn: String? = null,
)

data class SavedPolicyRefDto(@SerializedName("policy_id") val policyId: Long)

/** 3.37 교육 · 자격증 등록. code 를 주면 name 은 없어도 된다. null 은 보내지 않는다 */
data class CredentialCreateDto(
    val kind: String,
    val code: String? = null,
    val name: String? = null,
    @SerializedName("done_on") val doneOn: String? = null,
)

data class CredentialDto(
    @SerializedName("credential_id") val credentialId: Long,
    val kind: String,
    val code: String? = null,
    val name: String,
    @SerializedName("done_on") val doneOn: String? = null,
)

data class CredentialListDto(val count: Int, val credentials: List<CredentialDto>)

data class EducationOptionDto(val code: String, val name: String, val description: String)

data class RoadmapEducationDto(
    val code: String,
    val name: String,
    val done: Boolean,
    @SerializedName("done_on") val doneOn: String? = null,
    val note: String,
)

data class SearchRequestDto(
    @SerializedName("user_id") val userId: String,
    val question: String,
    /** false: 답변 문장 없이 정책 카드만 (분야별로 보기). 서버가 답변 AI 를 부르지 않아 빠르다 */
    val answer: Boolean = true,
)

data class EvidenceDto(val content: String)

data class SearchResultDto(
    @SerializedName("policy_id") val policyId: Long,
    @SerializedName("policy_key") val policyKey: String,
    val name: String,
    val agency: String,
    val category: String,
    @SerializedName("checked_at") val checkedAt: String,
    @SerializedName("is_outdated") val isOutdated: Boolean,
    val eligibility: EligibilityDto,
    @SerializedName("target_description") val targetDescription: String,
    @SerializedName("support_content") val supportContent: String,
    @SerializedName("support_amount") val supportAmount: String?,
    @SerializedName("support_period") val supportPeriod: String?,
    @SerializedName("original_text") val originalText: String,
    @SerializedName("easy_text") val easyText: String,
    @SerializedName("source_url") val sourceUrl: String,
    val evidences: List<EvidenceDto>,
    /** 카드용 짧은 요약 (3.5). null 이면 target_description 등으로 표시 */
    val summary: PolicySummaryDto? = null,
    /** 답변 근거가 아니라 질문과 뜻이 가까워 덧붙인 정책 (3.5). 앱은 '비슷한 정책'으로 따로 보여 준다 */
    val related: Boolean = false,
    /** 본인 나이로는 아니지만 가족(자녀 · 동생) 나이가 맞는 아동 대상 정책 (3.5) */
    @SerializedName("for_family") val forFamily: Boolean = false,
)

data class SearchResponseDto(
    @SerializedName("answer_status") val answerStatus: String,
    val answer: String?,
    val results: List<SearchResultDto>,
)

data class PolicyDetailDto(
    @SerializedName("policy_id") val policyId: Long,
    @SerializedName("policy_key") val policyKey: String,
    val name: String,
    val agency: String,
    val contact: String?,
    val category: String,
    val region: String,
    @SerializedName("age_min") val ageMin: Int?,
    @SerializedName("age_max") val ageMax: Int?,
    @SerializedName("target_description") val targetDescription: String,
    @SerializedName("support_content") val supportContent: String,
    @SerializedName("support_amount") val supportAmount: String?,
    @SerializedName("support_period") val supportPeriod: String?,
    @SerializedName("apply_method") val applyMethod: String,
    /** 명세는 [{name, condition}] 이지만 예전 데이터는 ["신분증", …] 이라서 둘 다 받는다 (RemoteOnRoadApi 에서 변환) */
    @SerializedName("required_docs") val requiredDocs: List<JsonElement>,
    @SerializedName("apply_url") val applyUrl: String?,
    @SerializedName("source_url") val sourceUrl: String,
    @SerializedName("original_text") val originalText: String,
    @SerializedName("easy_text") val easyText: String,
    @SerializedName("checked_at") val checkedAt: String,
    @SerializedName("is_outdated") val isOutdated: Boolean,
    val eligibility: EligibilityDto?,
    /** 서버가 support_amount 를 읽어 맞춘 금액 (읽지 못하면 null) */
    @SerializedName("amount_won") val amountWon: Long? = null,
    @SerializedName("amount_cycle") val amountCycle: String? = null,
    @SerializedName("amount_text") val amountText: String? = null,
    @SerializedName("period_years") val periodYears: Int? = null,
    /** 받는 것 한 줄 (카드 요약의 지원 내용) */
    val benefit: String? = null,
    /** 나이 · 지역 말고 따로 확인할 대상 조건 · 받을 수 없는 경우 (카드 요약) */
    val conditions: List<String>? = null,
    val exclusions: List<String>? = null,
    @SerializedName("apply_ways") val applyWays: List<ApplyWayDto>? = null,
    @SerializedName("apply_deadline") val applyDeadline: String? = null,
    @SerializedName("apply_steps") val applySteps: List<String>? = null,
    @SerializedName("doc_facts") val docFacts: List<String>? = null,
    /** 이 정책 신청에 쓰는 서버 양식 (정책 상세 '신청서 쓰기'). 없으면 버튼을 숨긴다 */
    val forms: List<FormLinkDto>? = null,
    /** 같이 보면 좋은 정책 (같은 분야 · 받을 수 있는 정책, 최대 3개) */
    val related: List<DetailRelatedDto>? = null,
    @SerializedName("same_kind") val sameKind: SameKindDto? = null,
    /** 들어야 받을 수 있는 교육 (settlement · finance · null) */
    @SerializedName("required_education") val requiredEducation: String? = null,
)

data class SameKindGroupDto(
    @SerializedName("kind_title") val kindTitle: String,
    val rule: String,
    val total: Int,
    val picked: SameKindPickDto?,
    val others: List<SameKindRowDto>? = null,
)

data class SameKindPickDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val region: String,
    @SerializedName("amount_text") val amountText: String?,
    val reason: String,
)

data class SameKindRowDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val region: String,
    @SerializedName("amount_text") val amountText: String?,
    val mine: Boolean = false,
)

data class SameKindDto(
    @SerializedName("kind_title") val kindTitle: String,
    @SerializedName("is_best") val isBest: Boolean,
    val reason: String,
    val best: RoadmapAlternativeDto,
    val others: List<RoadmapAlternativeDto>? = null,
)

/** 정책 상세의 같이 보면 좋은 정책 (시뮬레이션의 RelatedPolicyDto 와 이름이 겹치지 않게 따로 둔다) */
data class DetailRelatedDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val agency: String,
    val category: String,
    @SerializedName("amount_text") val amountText: String?,
    val benefit: String? = null,
)

// ---------------- 3.32 맞춤 신청 로드맵 · 3.33 진로 로드맵 ----------------

data class PolicyRoadmapDto(
    @SerializedName("base_date") val baseDate: String?,
    @SerializedName("d_day") val dDay: Int?,
    val summary: RoadmapSummaryDto,
    val stages: List<RoadmapStageDto>,
)

data class RoadmapSummaryDto(
    @SerializedName("monthly_won") val monthlyWon: Long,
    @SerializedName("once_won") val onceWon: Long,
    @SerializedName("policy_count") val policyCount: Int,
    @SerializedName("match_count") val matchCount: Int,
    // 받고 있는 정책 (3.36). 예전 서버는 없음 → 0
    @SerializedName("receiving_count") val receivingCount: Int = 0,
    @SerializedName("receiving_monthly_won") val receivingMonthlyWon: Long = 0,
    @SerializedName("new_monthly_won") val newMonthlyWon: Long = 0,
    @SerializedName("new_once_won") val newOnceWon: Long = 0,
)

data class RoadmapStageDto(val stage: String, val title: String, val items: List<RoadmapItemDto>)

data class RoadmapItemDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val agency: String,
    val category: String,
    val eligibility: String,
    @SerializedName("amount_text") val amountText: String?,
    val benefit: String? = null,
    @SerializedName("apply_from") val applyFrom: String?,
    @SerializedName("apply_note") val applyNote: String,
    val until: String? = null,
    @SerializedName("age_until") val ageUntil: String? = null,
    @SerializedName("age_max") val ageMax: Int? = null,
    val region: String? = null,
    @SerializedName("kind_title") val kindTitle: String? = null,
    @SerializedName("pick_reason") val pickReason: String? = null,
    val alternatives: List<RoadmapAlternativeDto>? = null,
    /** 사용자가 받는 중으로 표시한 정책 (3.36) */
    val receiving: Boolean = false,
    @SerializedName("started_on") val startedOn: String? = null,
    /** 들어야 받을 수 있는 교육과 이수 여부 (3.37) */
    val education: RoadmapEducationDto? = null,
)

data class RoadmapAlternativeDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val region: String,
    @SerializedName("amount_text") val amountText: String?,
)

data class ChatPlanDto(
    @SerializedName("base_date") val baseDate: String?,
    @SerializedName("d_day") val dDay: Int?,
    @SerializedName("monthly_won") val monthlyWon: Long,
    @SerializedName("once_won") val onceWon: Long,
    @SerializedName("match_count") val matchCount: Int,
    val items: List<ChatPlanItemDto>,
)

data class ChatPlanItemDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    @SerializedName("amount_text") val amountText: String?,
    val benefit: String? = null,
    @SerializedName("apply_from") val applyFrom: String?,
    @SerializedName("apply_note") val applyNote: String,
    @SerializedName("has_form") val hasForm: Boolean,
)

data class RecommendedPolicyDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val category: String,
    val eligibility: String,
    @SerializedName("when_text") val whenText: String,
    @SerializedName("amount_unit") val amountUnit: String?,
    @SerializedName("amount_value") val amountValue: String?,
    val benefit: String? = null,
    @SerializedName("pick_reason") val pickReason: String? = null,
)

data class CareerRoadmapDto(val recommended: String, val paths: List<CareerPathDto>)

data class CareerPathDto(val path: String, val title: String, val steps: List<CareerStepDto>, val policies: List<CareerPolicyDto>)

data class CareerStepDto(
    @SerializedName("step_id") val stepId: String?,
    val kind: String,
    val title: String,
    val detail: String,
    @SerializedName("scenario_id") val scenarioId: String?,
    @SerializedName("template_id") val templateId: String?,
    val url: String?,
    val phone: String?,
    val target: String? = null,
    val query: String? = null,
    val guide: List<String>? = null,
    val reason: String? = null,
)

data class CareerPolicyDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val agency: String,
    val eligibility: String,
    @SerializedName("amount_text") val amountText: String?,
    val benefit: String? = null,
)

/** 3.6 필요 서류. condition 이 있으면 경우에 따라 필요한 서류 */
data class RequiredDocDto(
    val name: String,
    val condition: String?,
)

// ---------------- 홈·체크리스트 공통 (1.5) ----------------

data class ChecklistPolicyDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val agency: String,
    val category: String,
    val contact: String?,
    @SerializedName("apply_url") val applyUrl: String?,
    @SerializedName("checked_at") val checkedAt: String,
    @SerializedName("is_active") val isActive: Boolean,
)

data class ProgressDto(
    @SerializedName("checked_steps") val checkedSteps: Int,
    @SerializedName("total_steps") val totalSteps: Int,
    val percent: Int,
)

data class DocumentProgressDto(
    val checked: Int,
    val total: Int,
)

data class NextStepDto(
    @SerializedName("step_key") val stepKey: String,
    val label: String,
)

// ---------------- 3.4 홈 ----------------

data class DashboardUserDto(
    @SerializedName("user_id") val userId: String,
    val name: String,
    val stage: String,
    val status: String,
    @SerializedName("d_date") val dDate: String?,
    @SerializedName("d_day") val dDay: Int?,
)

data class DashboardChecklistDto(
    @SerializedName("checklist_id") val checklistId: Long,
    val policy: ChecklistPolicyDto,
    val progress: ProgressDto,
    @SerializedName("next_step") val nextStep: NextStepDto?,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("due_date") val dueDate: String? = null,
)

data class LatestSimulationDto(
    @SerializedName("simulation_id") val simulationId: Long,
    @SerializedName("shortage_count") val shortageCount: Int,
    @SerializedName("created_at") val createdAt: String,
)

data class DashboardDto(
    val user: DashboardUserDto,
    val checklists: List<DashboardChecklistDto>,
    @SerializedName("latest_simulation") val latestSimulation: LatestSimulationDto?,
)

// ---------------- 3.9 ~ 3.11 체크리스트 ----------------

data class ChecklistOpenDto(
    @SerializedName("user_id") val userId: String,
    @SerializedName("policy_id") val policyId: Long,
)

/** 단계마다 들어 있는 필드가 달라서 모두 nullable */
data class StepDetailDto(
    @SerializedName("target_description") val targetDescription: String?,
    @SerializedName("easy_text") val easyText: String?,
    val eligibility: EligibilityDto?,
    @SerializedName("apply_method") val applyMethod: String?,
    @SerializedName("apply_url") val applyUrl: String?,
    /** 단계별 짧은 요약 (대상·조건: 지원 대상·받을 수 없는 경우 / 신청: 신청 방법). 없으면 null */
    val summary: PolicySummaryDto? = null,
)

data class ChecklistDocDto(
    @SerializedName("item_id") val itemId: Long,
    val label: String,
    /** 조건까지 담은 짧은 이름 (예: "위임장 (대리 신청 시)"). 없으면 null */
    @SerializedName("short_label") val shortLabel: String? = null,
    val condition: String?,
    @SerializedName("is_checked") val isChecked: Boolean,
    @SerializedName("is_not_applicable") val isNotApplicable: Boolean,
    @SerializedName("checked_at") val checkedAt: String?,
    /** 이 서류로 연결된 문서 AI 문서 (없으면 null). exported 면 체크를 앱에서 바꿀 수 없다 */
    @SerializedName("document_id") val documentId: Long? = null,
    @SerializedName("document_status") val documentStatus: String? = null,
    /** 이 서류를 바로 쓸 수 있는 서버 양식 (없으면 빈 목록) */
    val forms: List<FormLinkDto>? = null,
)

/** GET /forms/common */
data class CommonFormListDto(val forms: List<FormLinkDto>?)

/** 서버 양식 (POST /documents 에 form_id 로 시작). notice: 공식 양식 대신 쓰는 양식일 때 안내 */
data class FormLinkDto(
    @SerializedName("form_id") val formId: String,
    val title: String,
    val checked: Boolean = false,
    val notice: String? = null,
    /** 이 양식으로 쓰는 필요 서류 이름 (정책 상세에서만, 없으면 null) */
    @SerializedName("doc_name") val docName: String? = null,
)

data class ChecklistStepDto(
    @SerializedName("item_id") val itemId: Long,
    @SerializedName("step_key") val stepKey: String,
    val label: String,
    @SerializedName("is_checked") val isChecked: Boolean,
    @SerializedName("checked_at") val checkedAt: String?,
    val detail: StepDetailDto?,
    val documents: List<ChecklistDocDto>,
)

data class ChecklistDto(
    @SerializedName("checklist_id") val checklistId: Long,
    val policy: ChecklistPolicyDto,
    val progress: ProgressDto,
    @SerializedName("document_progress") val documentProgress: DocumentProgressDto,
    val steps: List<ChecklistStepDto>,
    @SerializedName("due_date") val dueDate: String? = null,
    /** 서류 목록에는 없지만 이 정책에 쓰는 양식 */
    @SerializedName("extra_forms") val extraForms: List<FormLinkDto>? = null,
)

data class ChecklistItemDto(
    @SerializedName("item_id") val itemId: Long,
    @SerializedName("item_type") val itemType: String,
    @SerializedName("step_key") val stepKey: String,
    val label: String,
    val condition: String?,
    @SerializedName("is_checked") val isChecked: Boolean,
    @SerializedName("is_not_applicable") val isNotApplicable: Boolean,
    @SerializedName("checked_at") val checkedAt: String?,
)

data class ChecklistItemResponseDto(
    val item: ChecklistItemDto,
    val progress: ProgressDto,
    @SerializedName("document_progress") val documentProgress: DocumentProgressDto,
)

// ---------------- 3.13 성장 기록 ----------------

data class GrowthUserDto(
    @SerializedName("user_id") val userId: String,
    val name: String,
    val stage: String,
    val status: String,
    @SerializedName("d_date") val dDate: String?,
    @SerializedName("d_day") val dDay: Int?,
    @SerializedName("created_at") val createdAt: String,
)

data class GrowthSummaryDto(
    @SerializedName("checklist_count") val checklistCount: Int,
    @SerializedName("completed_checklist_count") val completedChecklistCount: Int,
    @SerializedName("simulation_count") val simulationCount: Int,
    @SerializedName("roleplay_count") val roleplayCount: Int,
)

data class GrowthChecklistDto(
    @SerializedName("checklist_id") val checklistId: Long,
    val policy: ChecklistPolicyDto,
    val progress: ProgressDto,
    @SerializedName("document_progress") val documentProgress: DocumentProgressDto,
    @SerializedName("next_step") val nextStep: NextStepDto?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("due_date") val dueDate: String? = null,
)

data class SimulationSummaryDto(
    @SerializedName("simulation_id") val simulationId: Long,
    @SerializedName("total_income") val totalIncome: Int,
    val remaining: Int,
    @SerializedName("shortage_count") val shortageCount: Int,
    @SerializedName("criteria_version") val criteriaVersion: String,
    @SerializedName("created_at") val createdAt: String,
)

data class RoleplayRecordDto(
    @SerializedName("roleplay_id") val roleplayId: Long,
    @SerializedName("scenario_id") val scenarioId: String,
    val title: String,
    @SerializedName("good_count") val goodCount: Int,
    @SerializedName("missed_count") val missedCount: Int,
    @SerializedName("created_at") val createdAt: String,
)

data class GrowthDto(
    val user: GrowthUserDto,
    val summary: GrowthSummaryDto,
    val checklists: List<GrowthChecklistDto>,
    val simulations: List<SimulationSummaryDto>,
    val roleplays: List<RoleplayRecordDto>,
)

// ---------------- 시뮬레이션 (금액은 모두 원 단위) ----------------

data class CriteriaItemDto(
    val key: String,
    val label: String,
    val minimum: Int,
)

data class CriteriaDto(
    @SerializedName("criteria_version") val criteriaVersion: String,
    val items: List<CriteriaItemDto>,
)

data class AllocationsDto(
    val housing: Int,
    val food: Int,
    val transport: Int,
    val telecom: Int,
    val other: Int,
)

data class SimulationCreateDto(
    @SerializedName("user_id") val userId: String,
    @SerializedName("total_income") val totalIncome: Int,
    val allocations: AllocationsDto,
)

data class ShortageDto(
    val item: String,
    val label: String,
    val input: Int,
    val minimum: Int,
    val gap: Int,
)

data class AlternativeDto(
    val label: String,
    val allocations: AllocationsDto,
    val remaining: Int,
    @SerializedName("remaining_shortages") val remainingShortages: List<ShortageDto>,
)

data class RelatedPolicyDto(
    @SerializedName("policy_id") val policyId: Long,
    val name: String,
    val agency: String,
    val category: String,
)

data class SimulationDto(
    @SerializedName("simulation_id") val simulationId: Long,
    @SerializedName("criteria_version") val criteriaVersion: String,
    @SerializedName("total_income") val totalIncome: Int,
    val allocations: AllocationsDto,
    val remaining: Int,
    val shortages: List<ShortageDto>,
    val alternatives: List<AlternativeDto>,
    @SerializedName("related_policies") val relatedPolicies: List<RelatedPolicyDto>,
    @SerializedName("created_at") val createdAt: String,
)

// ---------------- AI 공통 (1.6) ----------------

/** 이전 대화 한 개. role: user / assistant, content: 1~1000자 */
data class HistoryDto(
    val role: String,
    val content: String,
)

// ---------------- 3.15 AI 상담 채팅 ----------------

data class ChatRequestDto(
    @SerializedName("user_id") val userId: String,
    val message: String,
    val history: List<HistoryDto>,
)

data class ContactDto(
    val name: String,
    val phone: String,
)

data class ChatResponseDto(
    /** policy / counsel / crisis / document */
    val type: String,
    /** type = policy 면 null (답변은 search.answer) */
    val message: String?,
    /** type = counsel, crisis 면 null. 3.5 응답과 같은 형식 */
    val search: SearchResponseDto?,
    val contacts: List<ContactDto>,
    /** type = document 일 때만: 문서 AI 로 넘길 후보 */
    val handoff: HandoffDto? = null,
    /** 대화에서 찾은 프로필 변경 후보 (확인 카드). 승인할 때만 회원정보 수정 */
    @SerializedName("profile_changes") val profileChanges: List<ProfileChangeDto>? = null,
    /** 넓은 정책 질문일 때만: 내 조건 요약 */
    val plan: ChatPlanDto? = null,
)

data class ProfileChangeDto(
    /** region / phone / status / d_date */
    val field: String,
    val label: String,
    val current: String?,
    /** PATCH /users 에 그대로 보낼 값 */
    val proposed: String,
    @SerializedName("proposed_label") val proposedLabel: String,
)

data class HandoffCandidateDto(
    @SerializedName("source_type") val sourceType: String, // template | policy_form
    @SerializedName("source_id") val sourceId: String?,
    @SerializedName("policy_id") val policyId: Long?,
    val title: String,
)

data class HandoffDto(
    /** POST /documents 의 context 로 그대로 보낸다 */
    val context: String,
    val candidates: List<HandoffCandidateDto>,
)

// ---------------- 3.16 ~ 3.19 롤플레잉 ----------------

data class RoleplayScenarioDto(
    @SerializedName("scenario_id") val scenarioId: String,
    val title: String,
    val category: String,
    val partner: String,
    val setting: String,
    val goal: String,
    val level: String,
    val stages: List<String>,
)

data class RoleplayScenariosDto(val scenarios: List<RoleplayScenarioDto>)

data class RoleplayOpeningDto(
    @SerializedName("scenario_id") val scenarioId: String,
    val level: String,
    val message: String,
    @SerializedName("variant_id") val variantId: String? = null,
    val situation: String? = null,
    @SerializedName("surprise_ids") val surpriseIds: List<String>? = null,
    @SerializedName("surprise_count") val surpriseCount: Int? = null,
)

data class RoleplayReplyRequestDto(
    @SerializedName("user_id") val userId: String,
    val stage: String?,
    val message: String,
    /** 1개 이상. 첫 턴은 첫 대사 하나 */
    val history: List<HistoryDto>,
    @SerializedName("variant_id") val variantId: String? = null,
    @SerializedName("surprise_ids") val surpriseIds: List<String> = emptyList(),
)

data class RoleplayReplyDto(
    val reply: String,
    @SerializedName("is_finished") val isFinished: Boolean,
)

data class RoleplayFeedbackRequestDto(
    @SerializedName("user_id") val userId: String,
    val history: List<HistoryDto>,
    val save: Boolean,
    @SerializedName("variant_id") val variantId: String? = null,
    @SerializedName("surprise_ids") val surpriseIds: List<String> = emptyList(),
)

data class RoleplayHintRequestDto(
    val history: List<HistoryDto>,
    @SerializedName("variant_id") val variantId: String? = null,
    @SerializedName("surprise_ids") val surpriseIds: List<String> = emptyList(),
)

data class RoleplayHintCheckDto(val label: String, val ok: Boolean, val kind: String)

data class RoleplayHintDto(
    val done: Boolean,
    val label: String,
    val example: String,
    val kind: String,
    val remaining: Int,
    val checks: List<RoleplayHintCheckDto>? = null,
)

// ---------------- 활동 기록 · 자립 준비도 ----------------

data class ActivityDto(val kind: String, val ref: String? = null, val data: Map<String, Any?>)

data class ActivityCreatedDto(@SerializedName("activity_id") val activityId: Long)

data class ReadinessItemDto(
    val key: String,
    val label: String,
    val done: Boolean,
    val detail: String? = null,
    val progress: Float? = null,
)

data class ReadinessPointDto(@SerializedName("weeks_ago") val weeksAgo: Int, val score: Int)

data class ReadinessLevelDto(val label: String, val points: Int)

data class ReadinessAreaDto(
    val key: String,
    val label: String,
    val score: Int,
    @SerializedName("done_count") val doneCount: Int,
    val total: Int,
    val items: List<ReadinessItemDto>,
    @SerializedName("next_step") val nextStep: FollowUpDto?,
)

data class ReadinessDto(
    val score: Int,
    val level: String,
    val areas: List<ReadinessAreaDto>,
    val weakest: String?,
    @SerializedName("next_steps") val nextSteps: List<FollowUpDto>,
    // 고도화 (서버가 예전 버전이면 없음 → null)
    @SerializedName("week_delta") val weekDelta: Int? = null,
    val history: List<ReadinessPointDto>? = null,
    val recent: List<String>? = null,
    @SerializedName("next_level") val nextLevel: ReadinessLevelDto? = null,
    /** 행동 기록으로 본 판단 근거 (서버가 예전 버전이면 없음) */
    val insights: List<ReadinessInsightDto>? = null,
)

/** tone: good(잘하는 것) · watch(다시 볼 것) */
data class ReadinessInsightDto(val area: String, val tone: String, val text: String)

data class RoleplayCheckDto(val label: String, val ok: Boolean, val kind: String, val example: String?)

data class BetterDto(
    val before: String,
    val after: String,
)

data class RoleplayFeedbackDto(
    /** save = false 면 null */
    @SerializedName("roleplay_id") val roleplayId: Long?,
    val good: List<String>,
    val missed: List<String>,
    val documents: List<String>,
    val better: List<BetterDto>,
    val score: Int? = null,
    val grade: String? = null,
    val items: List<RoleplayCheckDto>? = null,
    val situation: String? = null,
    @SerializedName("retry_lines") val retryLines: List<String>? = null,
    @SerializedName("next_steps") val nextSteps: List<FollowUpDto>? = null,
)

data class FollowUpDto(
    val kind: String,
    val label: String,
    val query: String? = null,
    @SerializedName("scenario_id") val scenarioId: String? = null,
)

// ---------------- 3.20 · 3.21 서류 양식 ----------------

data class FormTemplateDto(
    @SerializedName("template_id") val templateId: String,
    val name: String,
    val description: String,
    val stages: List<String>,
)

data class FormTemplatesDto(val templates: List<FormTemplateDto>)

data class FormFieldDto(
    @SerializedName("field_id") val fieldId: String,
    val label: String,
    @SerializedName("easy_label") val easyLabel: String,
    val guide: String,
    val caution: String?,
    val example: String?,
    val multiline: Boolean,
    val essay: Boolean,
    val outline: List<String>?,
)

data class FormTemplateDetailDto(
    @SerializedName("template_id") val templateId: String,
    val name: String,
    val description: String,
    val stages: List<String>,
    val fields: List<FormFieldDto>,
)

// ---------------- 3.22 이력서·자기소개서 초안 ----------------

data class AppRecordsDto(
    @SerializedName("saved_policy_count") val savedPolicyCount: Int,
    @SerializedName("interest_summary") val interestSummary: String?,
)

data class DocumentRequestDto(
    /** activity_record / resume / cover_letter */
    val type: String,
    @SerializedName("app_records") val appRecords: AppRecordsDto?,
)

data class DocSentenceDto(
    val text: String,
    val source: String?,
)

data class DocSectionDto(
    val title: String,
    val sentences: List<DocSentenceDto>,
)

data class DocumentDto(
    val type: String,
    val sections: List<DocSectionDto>,
    val notice: String,
)

// ---------------- 에러 ({"error":{"code","message"}}) ----------------

data class ErrorBodyDto(val error: ErrorDto?)

data class ErrorDto(val code: String?, val message: String?)

// ---------------- 3.23 쉬운 말 단계 ----------------

data class EasyLevelDto(
    val level: String,
    @SerializedName("level_name") val levelName: String,
)

data class EasyTermDto(val term: String, val gloss: String)

data class EasyTextDto(
    @SerializedName("policy_id") val policyId: Long,
    val level: String,
    @SerializedName("level_name") val levelName: String,
    @SerializedName("recommended_level") val recommendedLevel: String,
    val levels: List<EasyLevelDto>,
    @SerializedName("core_text") val coreText: String,
    @SerializedName("more_text") val moreText: String?,
    val terms: List<EasyTermDto>,
    val verified: Boolean,
)

// ---------------- 3.24 신청서 작성 도우미 ----------------

data class FormGuideItemDto(
    @SerializedName("field_id") val fieldId: String,
    val label: String,
    val section: String?,
    val how: String,
    @SerializedName("value_source") val valueSource: String,
    val required: Boolean?,
    val guide: String,
    val example: String?,
    val prefill: String?,
    val evidence: String?,
)

data class FormGuideDto(
    @SerializedName("policy_id") val policyId: Long,
    @SerializedName("form_id") val formId: String,
    val title: String,
    val checked: Boolean,
    val notes: List<String>,
    val items: List<FormGuideItemDto>,
    val notices: List<String>,
)

// ---------------- 정책 짧은 요약 (3.5 · 3.10 summary) ----------------

data class SummarySectionDto(val label: String, val items: List<String>)

data class PolicySummaryDto(
    val sections: List<SummarySectionDto>,
    val notice: String?,
    val verified: Boolean,
)

/** 신청 방법 정리본 한 갈래 (방문 · 온라인 …) */
data class ApplyWayDto(val way: String? = null, val lines: List<String>? = null)
