package com.project.on_road.data.remote

import com.project.on_road.data.Evidence

/**
 * RAG 답변·근거를 앱에서 읽기 좋게 다듬는다 (서버 응답은 그대로 두고 화면용으로만 바꾼다).
 * - 답변: "문장. (출처: 복지로) 문장. (출처: …) 자세한 조건은…" → 문장마다 줄바꿈, 마지막 안내는 한 줄 띄움
 * - 근거: 청크 전체 → 머리말 줄([정책 개요], 구분 내용 등)을 빼고 답변과 겹치는 부분만 짧게 발췌
 */
object AnswerFormat {
    /** 정책 검색 화면 맨 위 답변 자리에 항상 보여주는 문장 */
    const val SEARCH_NOTICE = "자세한 조건은 공식 원문에서 꼭 확인하시기 바랍니다."

    private const val SNIPPET_MAX = 160
    private const val CHECK_NOTICE = "자세한 조건은 공식 원문에서"
    private const val MISSING_NOTICE = "질문하신 내용 중 일부는"

    private val CITE = Regex("""\s*(\(출처:[^)]*\))\s*""")
    private val SOURCE_LINE = Regex("""^\[출처:\s*([^\]]+)]$""")
    private val HEADER_LINE = Regex("""^(\[[^\]]*]|구분\s*내용|대상|내용)$""")
    private val BULLET = Regex("""^[■□●•▪◦※*\-–]\s*""")
    private val TOKEN = Regex("""[0-9A-Za-z가-힣]{2,}""")

    /**
     * 문장 아래 줄에 출처를 쓰고, 그 뒤에 한 줄을 띄운다. 끝의 확인 안내 문장 앞도 한 줄 띄운다.
     *   자립수당은 매월 50만 원 지급됩니다.
     *   (출처: 복지로)
     *
     *   다음 문장...
     */
    fun answer(text: String): String {
        val out = text.trim().replace(CITE) { "\n${it.groupValues[1]}\n\n" }.trim()
        val i = listOf(MISSING_NOTICE, CHECK_NOTICE).map { out.indexOf(it) }.filter { it > 0 }.minOrNull()
            ?: return out
        return out.substring(0, i).trimEnd() + "\n\n" + out.substring(i)
    }

    /** 서버 근거 원문 → 화면용 근거 (출처 이름 + 짧은 발췌) */
    fun evidence(content: String, fallbackSource: String, answer: String = ""): Evidence {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val source = lines.firstNotNullOfOrNull { SOURCE_LINE.find(it)?.groupValues?.get(1)?.trim() }
        val body = lines.filter { !HEADER_LINE.matches(it) }.map { it.replace(BULLET, "") }.filter { it.isNotBlank() }
        val label = if (source != null) "$source · $fallbackSource" else fallbackSource
        return Evidence(label, snippet(body, answer).ifBlank { content.trim().take(SNIPPET_MAX) })
    }

    private fun snippet(lines: List<String>, answer: String): String {
        if (lines.isEmpty()) return ""
        val want = TOKEN.findAll(answer).map { it.value }.toSet()
        val start = if (want.isEmpty()) 0 else lines.indices.maxBy { i ->
            // 겹치는 단어가 많을수록, 같으면 앞쪽 줄
            TOKEN.findAll(lines[i]).count { it.value in want } * 1000 - i
        }
        val sb = StringBuilder()
        for (line in lines.drop(start)) {
            val next = if (sb.isEmpty()) line else "$sb $line"
            if (next.length > SNIPPET_MAX) {
                if (sb.isEmpty()) sb.append(line.take(SNIPPET_MAX).trimEnd())
                sb.append(" …")
                break
            }
            sb.clear().append(next)
        }
        return sb.toString()
    }
}