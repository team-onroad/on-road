package com.project.on_road.data.remote

import com.google.gson.annotations.SerializedName

// ---------------- 진로 외부 정보 (3.35): 큐넷 · 고용24 · 커리어넷 ----------------
// available=false 면 키가 없거나 바깥 서버가 실패한 것: 앱은 그 칸을 숨긴다

data class ExamRoundDto(
    val name: String?,
    val year: Int?,
    val round: Int?,
    @SerializedName("written_apply_start") val writtenApplyStart: String?,
    @SerializedName("written_apply_end") val writtenApplyEnd: String?,
    @SerializedName("written_exam_start") val writtenExamStart: String?,
    @SerializedName("written_exam_end") val writtenExamEnd: String?,
    @SerializedName("written_pass") val writtenPass: String?,
    @SerializedName("practical_apply_start") val practicalApplyStart: String?,
    @SerializedName("practical_apply_end") val practicalApplyEnd: String?,
    @SerializedName("practical_exam_start") val practicalExamStart: String?,
    @SerializedName("practical_exam_end") val practicalExamEnd: String?,
    @SerializedName("practical_pass") val practicalPass: String?,
)

data class ExamsDto(
    val available: Boolean,
    val items: List<ExamRoundDto>?,
    val message: String?,
    val cert: String?,
    val code: String?,
    val note: String?,
    val url: String?,
)

data class TrainingItemDto(
    val id: String?,
    val round: String?,
    @SerializedName("institution_id") val institutionId: String?,
    val title: String?,
    val institution: String?,
    val address: String?,
    val phone: String?,
    val start: String?,
    val end: String?,
    val cost: Int?,
    val nearby: Boolean?,
    @SerializedName("same_district") val sameDistrict: Boolean? = null,
    val url: String?,
)

data class CertificateItemDto(val code: String?, val name: String?, val kind: String?)

data class CertificatesDto(val available: Boolean, val items: List<CertificateItemDto>?, val message: String?)

data class TrainingsDto(val available: Boolean, val items: List<TrainingItemDto>?, val message: String?)

data class TrainingCourseDto(
    val title: String?,
    val institution: String?,
    val address: String?,
    val homepage: String?,
    val target: String?,
    val days: Int?,
    val hours: Int?,
    val cost: Int?,
    val support: Int?,
    @SerializedName("my_cost") val myCost: Int?,
    @SerializedName("contact_phone") val contactPhone: String?,
    val url: String?,
)

data class TrainingDetailDto(val available: Boolean, val course: TrainingCourseDto?, val message: String?)

data class DutyDto(
    val code: String?,
    val unit: String?,
    val definition: String?,
    val group: String?,
    val skills: List<String>?,
)

data class DutiesDto(val available: Boolean, val items: List<DutyDto>?, val message: String?)

data class ExternalPrepareDto(val curriculum: List<String>?, val training: List<String>?)

data class ExternalJobDto(
    val name: String?,
    val summary: String?,
    val works: List<String>?,
    val certificates: List<String>?,
    val majors: List<String>?,
    @SerializedName("related_jobs") val relatedJobs: List<String>?,
    val prepare: ExternalPrepareDto?,
    val forecast: List<String>?,
    val wage: String?,
    val satisfaction: String?,
)

data class JobInfoDto(val available: Boolean, val source: String?, val job: ExternalJobDto?, val message: String?)
