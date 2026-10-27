package com.project.on_road.data

import android.content.Context
import com.project.on_road.data.remote.*
import java.io.File

/**
 * 문서 AI 서버 호출 (docs/document-ai.md). 화면은 DTO 를 그대로 쓴다.
 * 모든 호출은 Network.call 로 감싸서 서버 에러를 ApiException(code, message)으로 받는다.
 */
class DocumentRepository(private val service: OnRoadService, private val userId: () -> String) {

    suspend fun templates(stage: UserStage): List<TemplateSummaryDto> =
        Network.call { service.formTemplates(stage.code) }.templates

    /** 정책 신청서 항목이 준비된 정책인지 (체크리스트에서 '신청서 쓰기' 버튼을 보일지) */
    suspend fun hasPolicyForm(policyId: Long): Boolean =
        runCatching { Network.call { service.formGuide(policyId, userId()) }.close() }.isSuccess

    suspend fun startTemplate(templateId: String, context: String? = null): DocumentDto =
        Network.call { service.createDocument(DocumentCreateDto(userId(), "template", sourceId = templateId, context = context)) }

    suspend fun startPolicyForm(policyId: Long, checklistItemId: Long? = null, context: String? = null): DocumentDto =
        Network.call {
            service.createDocument(
                DocumentCreateDto(userId(), "policy_form", policyId = policyId, checklistItemId = checklistItemId, context = context),
            )
        }

    suspend fun list(): List<DocumentSummaryDto> = Network.call { service.listDocuments(userId()) }.documents

    suspend fun get(documentId: Long): DocumentDto = Network.call { service.getDocument(documentId, userId()) }

    suspend fun saveValue(documentId: Long, fieldId: String, value: String, complete: Boolean? = null): FieldResponseDto =
        Network.call { service.updateField(documentId, fieldId, userId(), FieldUpdateDto(value = value, isCompleted = complete)) }

    /** AI 가 쓴 구간을 지키면서 저장 (사용자가 손대지 않은 칸) */
    suspend fun complete(documentId: Long, fieldId: String, completed: Boolean): FieldResponseDto =
        Network.call { service.updateField(documentId, fieldId, userId(), FieldUpdateDto(isCompleted = completed)) }

    suspend fun undo(documentId: Long, fieldId: String): FieldResponseDto =
        Network.call { service.undoField(documentId, fieldId, userId()) }

    suspend fun assist(documentId: Long, fieldId: String, prompt: String, replace: Boolean = false): AssistResponseDto =
        Network.call {
            service.assist(documentId, AssistRequestDto(userId(), fieldId, prompt, if (replace) "replace" else "append"))
        }

    /** message 가 null 이면 지금 물어볼 칸 (시작·이어 하기) */
    suspend fun interview(documentId: Long, message: String?): InterviewResponseDto =
        Network.call { service.interview(documentId, InterviewRequestDto(userId(), message)) }

    suspend fun check(documentId: Long): CheckDto = Network.call { service.checkDocument(documentId, userId()) }

    suspend fun export(documentId: Long): ExportDto = Network.call { service.exportDocument(documentId, UserOnlyDto(userId())) }

    /** 내보낸 PDF 를 앱 캐시(documents/)에 저장하고 파일을 돌려준다 (FileProvider 로 열기·공유) */
    suspend fun downloadPdf(context: Context, documentId: Long, title: String): File {
        val body = Network.call { service.documentFile(documentId, userId(), "pdf") }
        val dir = File(context.cacheDir, "documents").apply { mkdirs() }
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        val file = File(dir, "$safe-$documentId.pdf")
        body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
}

val UserStage.code: String
    get() = when (this) {
        UserStage.CHILD -> "child"
        UserStage.TEEN -> "teen"
        UserStage.YOUNG_ADULT -> "youth"
    }
