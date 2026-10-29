package com.project.on_road.data.remote

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import retrofit2.http.Headers
import retrofit2.http.Body
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

    // 3.6 정책 상세 (user_id 없으면 eligibility null)
    @GET("policies/{policyId}")
    suspend fun getPolicy(
        @Path("policyId") policyId: Long,
        @Query("user_id") userId: String?,
    ): PolicyDetailDto

    // 3.23 쉬운 말 단계별 보기 (level 없으면 서버가 나이로 추천)
    @GET("policies/{policyId}/easy-text")
    suspend fun getEasyText(
        @Path("policyId") policyId: Long,
        @Query("user_id") userId: String?,
        @Query("level") level: String?,
    ): EasyTextDto

    // 3.24 신청서 작성 도우미
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

    // 3.14 시뮬레이션 결과 조회
    @GET("simulations/{simulationId}")
    suspend fun getSimulation(
        @Path("simulationId") simulationId: Long,
        @Query("user_id") userId: String,
    ): SimulationDto

    // 3.15 AI 상담 채팅
    @POST("chat")
    suspend fun chat(@Body body: ChatRequestDto): ChatResponseDto

    // 3.16 롤플레잉 상황 목록
    @GET("coach/roleplay/scenarios")
    suspend fun roleplayScenarios(@Query("stage") stage: String?): RoleplayScenariosDto

    // 3.17 첫 대사
    @GET("coach/roleplay/{scenarioId}/opening")
    suspend fun roleplayOpening(
        @Path("scenarioId") scenarioId: String,
        @Query("stage") stage: String?,
    ): RoleplayOpeningDto

    // 3.18 대화 한 턴
    @POST("coach/roleplay/{scenarioId}/reply")
    suspend fun roleplayReply(
        @Path("scenarioId") scenarioId: String,
        @Body body: RoleplayReplyRequestDto,
    ): RoleplayReplyDto

    // 3.19 피드백 (save = true 면 성장 기록에 남김)
    @POST("coach/roleplay/{scenarioId}/feedback")
    suspend fun roleplayFeedback(
        @Path("scenarioId") scenarioId: String,
        @Body body: RoleplayFeedbackRequestDto,
    ): RoleplayFeedbackDto

    // 3.20 서류 양식 목록
    @GET("forms/templates")
    suspend fun formTemplates(@Query("stage") stage: String?): FormTemplatesDto

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

    @POST("documents/{documentId}/assist")
    suspend fun assist(@Path("documentId") documentId: Long, @Body body: AssistRequestDto): AssistResponseDto

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
}

// ---------------- 사용자 ----------------

data class UserCreateDto(
    val name: String,
    @SerializedName("birth_date") val birthDate: String,
    val phone: String? = null,
    val region: String,
    val status: String,
    @SerializedName("d_date") val dDate: String? = null,
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
)

// ---------------- 정책 ----------------

data class EligibilityDto(
    val status: String,
    val age: String,
    val region: String,
)

data class SearchRequestDto(
    @SerializedName("user_id") val userId: String,
    val question: String,
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
)

data class RoleplayReplyRequestDto(
    @SerializedName("user_id") val userId: String,
    val stage: String?,
    val message: String,
    /** 1개 이상. 첫 턴은 첫 대사 하나 */
    val history: List<HistoryDto>,
)

data class RoleplayReplyDto(
    val reply: String,
    @SerializedName("is_finished") val isFinished: Boolean,
)

data class RoleplayFeedbackRequestDto(
    @SerializedName("user_id") val userId: String,
    val history: List<HistoryDto>,
    val save: Boolean,
)

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
