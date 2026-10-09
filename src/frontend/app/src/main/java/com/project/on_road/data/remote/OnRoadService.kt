package com.project.on_road.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 백엔드 API (docs/api.md). 경로는 BASE_URL(…/api/) 뒤에 붙는다. */
interface OnRoadService {

    // 3.2 온보딩
    @POST("users")
    suspend fun createUser(@Body body: UserCreateDto): UserDto

    // 3.3 사용자 조회 (앱 실행 시 user_id 확인, 404면 온보딩)
    @GET("users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): UserDto

    // 3.5 정책 검색 (RAG)
    @POST("search")
    suspend fun search(@Body body: SearchRequestDto): SearchResponseDto

    // 3.6 정책 상세 (user_id 없으면 eligibility null)
    @GET("policies/{policyId}")
    suspend fun getPolicy(
        @Path("policyId") policyId: Long,
        @Query("user_id") userId: String?,
    ): PolicyDetailDto

    // 3.7 배분 기준표
    @GET("simulations/criteria")
    suspend fun getCriteria(): CriteriaDto

    // 3.8 생활비 시뮬레이션 제출
    @POST("simulations")
    suspend fun createSimulation(@Body body: SimulationCreateDto): SimulationDto

    // 3.14 시뮬레이션 결과 조회
    @GET("simulations/{simulationId}")
    suspend fun getSimulation(
        @Path("simulationId") simulationId: Long,
        @Query("user_id") userId: String,
    ): SimulationDto
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
    @SerializedName("required_docs") val requiredDocs: List<String>,
    @SerializedName("apply_url") val applyUrl: String?,
    @SerializedName("source_url") val sourceUrl: String,
    @SerializedName("original_text") val originalText: String,
    @SerializedName("easy_text") val easyText: String,
    @SerializedName("checked_at") val checkedAt: String,
    @SerializedName("is_outdated") val isOutdated: Boolean,
    val eligibility: EligibilityDto?,
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

// ---------------- 에러 ({"error":{"code","message"}}) ----------------

data class ErrorBodyDto(val error: ErrorDto?)

data class ErrorDto(val code: String?, val message: String?)
