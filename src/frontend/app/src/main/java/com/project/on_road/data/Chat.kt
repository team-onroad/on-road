package com.project.on_road.data

import kotlinx.coroutines.delay

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val evidence: List<Evidence> = emptyList(),
    val policies: List<Policy> = emptyList(),
    val needsCheck: Boolean = false
)

/** AI 상담 API 계약. 백엔드(RAG + LLM) 준비 후 서버 구현체로 교체한다. */
interface ChatApi {
    suspend fun reply(userId: String, history: List<ChatMessage>, message: String): ChatMessage
}

/** 화면 확인용 가짜 답변. 키워드로 예시 정책을 찾아 템플릿 답변을 만든다. */
class FakeChatApi : ChatApi {
    private val feelingWords = listOf("힘들", "외로", "무서", "불안", "우울", "막막", "걱정", "지쳐")

    override suspend fun reply(userId: String, history: List<ChatMessage>, message: String): ChatMessage {
        delay(1200)
        val q = message.replace(" ", "")
        val matched = SamplePolicies.all
            .filter { p -> p.keywords.any { q.contains(it.replace(" ", "")) } }
            .take(3)
        val feeling = feelingWords.any { q.contains(it) }

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
                policies = listOfNotNull(SamplePolicies.find("counsel"))
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
