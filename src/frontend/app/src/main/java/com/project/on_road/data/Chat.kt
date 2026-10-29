package com.project.on_road.data

import kotlinx.coroutines.delay

enum class ChatRole { USER, ASSISTANT }

/**
 * 답변 종류 (3.15 type).
 * LOCAL: 첫 인사·오류 안내처럼 앱이 만든 문구 (서버로 보내는 이전 대화에서 뺀다)
 */
enum class ChatKind { POLICY, COUNSEL, CRISIS, DOCUMENT, LOCAL }

/** 상담 → 문서 AI 로 넘길 서류 후보 (3.15 handoff). policyId 가 있으면 정책 신청서, 아니면 앱 양식(templateId) */
data class DocCandidate(val title: String, val policyId: Long?, val templateId: String?)

/** 도움 연락처 (위기 안내에 함께 옴) */
data class HelpLine(val name: String, val phone: String)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val evidence: List<Evidence> = emptyList(),
    val policies: List<Policy> = emptyList(),
    val needsCheck: Boolean = false,
    val kind: ChatKind = ChatKind.POLICY,
    val contacts: List<HelpLine> = emptyList(),
    /** kind = DOCUMENT: 서류 작성 후보와 상담 맥락 (문서 AI 가 처음부터 묻지 않게 넘긴다) */
    val candidates: List<DocCandidate> = emptyList(),
    val handoffContext: String? = null,
    /** 대화에서 찾은 내 정보 변경 후보 (확인 카드) */
    val profileChanges: List<ProfileChange> = emptyList(),
)

/** AI 상담 API 계약 (3.15). history: 지금까지 대화 (방금 보낸 message 포함 가능, 구현체가 정리해서 보냄) */
interface ChatApi {
    suspend fun reply(userId: String, history: List<ChatMessage>, message: String): ChatMessage
}

/** 위기 신호일 때 보여 줄 연락처 (서버 고정값과 같음, 가짜 모드용) */
val CRISIS_LINES = listOf(
    HelpLine("자살예방상담전화", "109"),
    HelpLine("청소년상담전화", "1388"),
    HelpLine("보건복지상담센터", "129"),
    HelpLine("경찰", "112"),
)

/** 화면 확인용 가짜 답변. 키워드로 예시 정책을 찾아 템플릿 답변을 만든다. */
class FakeChatApi : ChatApi {
    private val feelingWords = listOf("힘들", "외로", "무서", "불안", "우울", "막막", "걱정", "지쳐")
    private val crisisWords = listOf("죽고싶", "자살", "자해", "사라지고싶")

    override suspend fun reply(userId: String, history: List<ChatMessage>, message: String): ChatMessage {
        delay(1200)
        val q = message.replace(" ", "")
        val matched = SamplePolicies.all
            .filter { p -> p.keywords.any { q.contains(it.replace(" ", "")) } }
            .take(3)
        val feeling = feelingWords.any { q.contains(it) }

        if (crisisWords.any { q.contains(it) }) {
            return ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = "많이 힘드셨겠어요. 혼자 견디지 않아도 괜찮아요. 지금 바로 이야기할 수 있는 곳이에요.",
                kind = ChatKind.CRISIS,
                contacts = CRISIS_LINES,
            )
        }

        return when {
            matched.isNotEmpty() -> ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = buildString {
                    if (feeling) append("걱정되는 마음이 느껴져요. 함께 하나씩 살펴봐요.\n\n")
                    append("말씀하신 내용과 관련해 살펴볼 만한 제도가 있어요.\n")
                    matched.forEach { append("\n${it.name}\n${it.easySummary}\n") }
                    append("\n정확한 금액과 자격은 담당 기관에서 꼭 확인해 주세요.")
                },
                evidence = matched.flatMap { it.evidence }.take(3),
                policies = matched
            )
            feeling -> ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = "이야기해 줘서 고마워요. 그런 마음이 드는 건 자연스러운 일이에요.\n\n" +
                    "혼자 감당하기 어렵다면 믿을 수 있는 어른이나 자립지원전담기관 선생님과 이야기해 보는 것도 방법이에요. " +
                    "위쪽의 '도움 연락처'에서 바로 연결할 수도 있어요.",
                kind = ChatKind.COUNSEL
            )
            else -> ChatMessage(
                id = 0,
                role = ChatRole.ASSISTANT,
                text = "그 부분은 지금 가진 공식 자료에서 근거를 찾지 못했어요. 추측해서 답하지 않을게요.\n\n" +
                    "질문을 조금 더 구체적으로 해 주시거나, 보건복지상담센터(129)에 확인해 보세요.",
                needsCheck = true
            )
        }
    }
}
