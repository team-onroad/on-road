package com.project.on_road.data.remote

import com.google.gson.annotations.SerializedName

/** 문서 AI (docs/api.md 3.25~3.32, docs/document-ai.md). ContactDto 는 OnRoadService.kt 의 것을 쓴다 */

// ---------------- 요청 ----------------

data class DocumentCreateDto(
    @SerializedName("user_id") val userId: String,
    @SerializedName("source_type") val sourceType: String, // "template" | "policy_form"
    @SerializedName("source_id") val sourceId: String? = null,
    @SerializedName("policy_id") val policyId: Long? = null,
    @SerializedName("checklist_item_id") val checklistItemId: Long? = null,
    val context: String? = null,
)

/** value 또는 segments 중 하나, 그리고/또는 isCompleted. null 은 보내지 않는다(Gson 기본) */
data class FieldUpdateDto(
    val value: String? = null,
    val segments: List<SegmentDto>? = null,
    @SerializedName("is_completed") val isCompleted: Boolean? = null,
)

data class AssistRequestDto(
    @SerializedName("user_id") val userId: String,
    @SerializedName("field_id") val fieldId: String,
    val prompt: String,
    val mode: String = "append", // "append" | "replace"
)

data class InterviewRequestDto(
    @SerializedName("user_id") val userId: String,
    val message: String? = null,
)

data class UserOnlyDto(@SerializedName("user_id") val userId: String)

// ---------------- 응답 ----------------

data class SegmentDto(val text: String, val source: String)

data class DocFieldDto(
    @SerializedName("field_id") val fieldId: String,
    val label: String,
    @SerializedName("easy_label") val easyLabel: String?,
    val section: String?,
    val guide: String,
    val caution: String?,
    val example: String?,
    val outline: List<String>?,
    val multiline: Boolean,
    val essay: Boolean,
    val required: Boolean?,
    @SerializedName("input_type") val inputType: String,
    val options: List<String>?,
    @SerializedName("value_source") val valueSource: String,
    val writable: Boolean,
    val value: String,
    val segments: List<SegmentDto>,
    val tip: String?,
    @SerializedName("reused_from") val reusedFrom: String?,
    @SerializedName("is_completed") val isCompleted: Boolean,
    @SerializedName("can_undo") val canUndo: Boolean,
    val warnings: List<String>,
) {
    val name: String get() = easyLabel ?: label
}

data class DocProgressDto(val completed: Int, val total: Int) {
    val ratio: Float get() = if (total == 0) 0f else completed / total.toFloat()
}

data class WriterDocumentDto(
    @SerializedName("document_id") val documentId: Long,
    val title: String,
    @SerializedName("source_type") val sourceType: String,
    @SerializedName("source_id") val sourceId: String,
    @SerializedName("policy_id") val policyId: Long?,
    @SerializedName("checklist_item_id") val checklistItemId: Long?,
    val status: String, // "writing" | "exported"
    val context: String?,
    val progress: DocProgressDto,
    @SerializedName("next_field_id") val nextFieldId: String?,
    val fields: List<DocFieldDto>,
    val notices: List<String>,
)

data class DocumentSummaryDto(
    @SerializedName("document_id") val documentId: Long,
    val title: String,
    @SerializedName("source_type") val sourceType: String,
    @SerializedName("source_id") val sourceId: String,
    @SerializedName("policy_id") val policyId: Long?,
    val status: String,
    val progress: DocProgressDto,
    @SerializedName("updated_at") val updatedAt: String,
)

data class DocumentListDto(val documents: List<DocumentSummaryDto>)

data class FieldResponseDto(
    val field: DocFieldDto,
    val progress: DocProgressDto,
    @SerializedName("next_field_id") val nextFieldId: String?,
    val status: String,
)

data class AssistResponseDto(
    val route: String, // field | policy | counsel | crisis
    val action: String?, // tip | outline | draft | revise
    val field: DocFieldDto?,
    val progress: DocProgressDto?,
    val message: String?,
    val search: SearchResponseDto?,
    val contacts: List<ContactDto>,
)

data class QuestionDto(
    @SerializedName("field_id") val fieldId: String,
    val label: String,
    val text: String,
    @SerializedName("input_type") val inputType: String,
    val options: List<String>?,
    @SerializedName("current_value") val currentValue: String?,
    @SerializedName("awaiting_confirm") val awaitingConfirm: Boolean,
    @SerializedName("can_skip") val canSkip: Boolean,
)

data class ProblemDto(
    @SerializedName("field_id") val fieldId: String,
    val label: String,
    val reason: String,
    val message: String,
)

data class CheckDto(val ready: Boolean, val problems: List<ProblemDto>, val warnings: List<String>)

data class ChecklistUpdateDto(
    @SerializedName("checklist_id") val checklistId: Long,
    @SerializedName("item_id") val itemId: Long,
    val label: String,
    @SerializedName("is_checked") val isChecked: Boolean,
)

data class ExportDto(
    val document: WriterDocumentDto,
    val checklist: ChecklistUpdateDto?,
    val warnings: List<String>,
    @SerializedName("pdf_layout") val pdfLayout: String, // original | table
)

data class InterviewResponseDto(
    val route: String,
    val message: String?,
    val field: DocFieldDto?,
    val question: QuestionDto?,
    val progress: DocProgressDto,
    val done: Boolean,
    val export: ExportDto?,
    val check: CheckDto?,
    val search: SearchResponseDto?,
    val contacts: List<ContactDto>,
)
