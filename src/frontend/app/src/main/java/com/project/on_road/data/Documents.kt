package com.project.on_road.data

import android.content.Context
import com.project.on_road.data.remote.*
import java.io.File

/**
 * 문서 AI 서버 호출 (docs/document-ai.md). 화면은 DTO 를 그대로 쓴다.
 * 모든 호출은 Network.call 로 감싸서 서버 에러를 ApiException(code, message)으로 받는다.
 */
class DocumentRepository(private val service: OnRoadService, private val userId: () -> String) {

    /**
     * 정책 신청서 항목이 준비된 정책인지 (체크리스트에서 '신청서 쓰기' 버튼을 보일지, 서류 고르기에서 정부 신청서로 열지).
     * 서버가 "없다"고 답할 때만 false. 서버가 깨어나는 중이거나 잠깐 끊긴 경우는 한 번 더 묻고, 그래도 안 되면 예외를 던진다
     * (예전에는 실패를 false 로 봐서 자립수당 신청서가 몰래 간단 양식으로 열렸다)
     */
    suspend fun hasPolicyForm(policyId: Long): Boolean {
        repeat(2) { attempt ->
            try {
                Network.call { service.getFormGuide(policyId, userId()) }
                return true
            } catch (e: ApiException) {
                if (e.httpStatus == 404) return false
                if (attempt == 1) throw e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || attempt == 1) throw e
            }
            kotlinx.coroutines.delay(1500)
        }
        return false
    }

    suspend fun startTemplate(templateId: String, context: String? = null): WriterDocumentDto =
        Network.call { service.createWriterDocument(DocumentCreateDto(userId(), "template", sourceId = templateId, context = context)) }

    suspend fun startPolicyForm(
        policyId: Long,
        checklistItemId: Long? = null,
        context: String? = null,
        formId: String? = null,
    ): WriterDocumentDto =
        Network.call {
            service.createWriterDocument(
                DocumentCreateDto(
                    userId(), "policy_form", policyId = policyId, checklistItemId = checklistItemId,
                    context = context, formId = formId,
                ),
            )
        }

    /** 공통 양식 (퇴소 신청서 등): 정책 없이 form_id 만. 사용자마다 하나 */
    suspend fun startCommonForm(formId: String, context: String? = null): WriterDocumentDto =
        Network.call {
            service.createWriterDocument(DocumentCreateDto(userId(), "policy_form", formId = formId, context = context))
        }

    /** 공통 양식 목록. 못 받으면 빈 목록 */
    suspend fun commonForms(): List<com.project.on_road.data.remote.FormLinkDto> =
        runCatching { Network.call { service.commonForms() }.forms.orEmpty() }.getOrDefault(emptyList())

    /** 받은 양식(사진·PDF) 올리기 → AI 가 칸을 읽은 문서. 같은 파일이면 쓰던 문서 */
    suspend fun upload(file: UploadFile, checklistItemId: Long? = null, policyId: Long? = null): WriterDocumentDto {
        val b64 = android.util.Base64.encodeToString(file.bytes, android.util.Base64.NO_WRAP)
        return Network.call { service.uploadDocument(DocumentUploadDto(userId(), b64, file.name, checklistItemId, policyId)) }
    }

    /** 서류 속 숨겨진 위험 찾기. 파일과 결과는 서버에 남지 않는다 */
    suspend fun riskCheck(file: UploadFile, docType: String, context: RiskContextDto? = null): RiskCheckDto {
        val b64 = android.util.Base64.encodeToString(file.bytes, android.util.Base64.NO_WRAP)
        return Network.call { service.riskCheck(RiskCheckRequestDto(userId(), docType, b64, file.name, context)) }
    }

    suspend fun list(): List<DocumentSummaryDto> = Network.call { service.listDocuments(userId()) }.documents

    suspend fun get(documentId: Long): WriterDocumentDto = Network.call { service.getDocument(documentId, userId()) }

    /** 서류 지우기 (칸까지 모두). 되돌릴 수 없다 */
    suspend fun delete(documentId: Long) {
        Network.call { service.deleteDocument(documentId, userId()).also { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
    }

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
