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
                break
            }
            sb.clear().append(next)
        }
        return sb.toString()
    }

    /* ---------------- 상담 답변 화면용 나누기 ---------------- */

    /** 내 조건 항목 종류: 맞아요(✓) · 확인할 것(○) · 참고(같은 종류 중 더 유리한 정책 등) */
    enum class CheckKind { OK, CHECK, INFO }

    data class CheckItem(val text: String, val kind: CheckKind)

    /** 내 조건 한 정책: 짧은 이름 · 항목들 (맞는 것 먼저, 확인할 것, 참고 순) */
    data class MyCheckRow(val name: String, val items: List<CheckItem>)

    /**
     * 답변 한 덩어리 글 → 화면 조각.
     * body: 정책마다 한 문단 (긴 정책 이름은 짧게), sources: 출처 이름 (중복 없이),
     * notice: 끝의 확인 안내를 한 줄로, myCheck: '[내 조건으로 보면]' 아래 줄들
     */
    data class Parts(
        val body: List<String>,
        val names: List<String>,
        val sources: List<String>,
        val notice: String?,
        val myCheck: List<MyCheckRow>,
    )

    private const val MY_CHECK_TITLE = "[내 조건으로 보면]"
    private val CITE_NAME = Regex("""\(출처:\s*([^)]*)\)""")
    private val MISSING_SENTENCE = Regex("""질문하신 내용 중 일부는[^.]*\.\s*(담당 기관에 문의하시기 바랍니다\.)?""")
    private val CHECK_SENTENCE = Regex("""자세한 조건은 공식 원문에서[^.]*\.""")

    /** '서리풀 희망사다리 프로젝트 자립준비청년(보호종료아동) 생활보조수당 지원' → '서리풀 생활보조수당 지원' (서버 my_check.short_name 과 같게) */
    fun shortName(name: String): String {
        var n = name.replace(Regex("""\([^)]*\)"""), " ")
        Regex("""^\s*(\S+)\s.*?프로젝트\s+(.*)$""").find(n)?.let { n = "${it.groupValues[1]} ${it.groupValues[2]}" }
        n = n.replace(Regex("""자립준비청년\s*"""), "")
        return n.replace(Regex("""\s+"""), " ").trim().ifEmpty { name }
    }

    fun parts(text: String, policyNames: List<String> = emptyList()): Parts {
        val at = text.indexOf(MY_CHECK_TITLE)
        var head = if (at >= 0) text.substring(0, at) else text
        val tail = if (at >= 0) text.substring(at + MY_CHECK_TITLE.length) else ""

        val sources = CITE_NAME.findAll(head).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()
        head = head.replace(CITE_NAME, "")
        val missing = MISSING_SENTENCE.containsMatchIn(head)
        head = head.replace(MISSING_SENTENCE, "").replace(CHECK_SENTENCE, "")
        // 긴 정책 이름 → 짧은 이름 (긴 이름부터 바꿔야 겹치는 이름이 안 깨진다)
        val names = policyNames.distinct().sortedByDescending { it.length }
        names.forEach { full -> head = head.replace(full, shortName(full)) }

        val body = head.split(Regex("""\n\s*\n""")).map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }.filter { it.isNotEmpty() }
        val notice = if (missing) "질문 중 일부는 자료에서 확인되지 않았어요. 담당 기관에 물어보세요." else null
        val rows = tail.lines().map { it.trim() }.filter { it.startsWith("- ") }.mapNotNull { row(it.removePrefix("- ")) }
        return Parts(body, policyNames.map(::shortName).distinct(), sources, notice, rows)
    }

    /** 행 아래 한 묶음: 머리말("해당" · "확인 필요", 없으면 null)과 값들. 값이 여럿이면 머리말 뒤 같은 자리에 한 줄씩 */
    data class SummaryLine(val label: String?, val values: List<String>)

    /**
     * 목록 행 아래 쉬운 말:
     *   해당: 나이, 사는 곳
     *   확인 필요: 서초구 1년 이상 거주
     *              기준중위소득 50% 이하
     * 받는 중이면 "지금 받고 있는 지원이에요", 참고(더 유리한 정책)는 머리말 없이 마지막
     */
    fun summary(row: MyCheckRow): List<SummaryLine> {
        val ok = row.items.filter { it.kind == CheckKind.OK }.map { it.text }
        val check = row.items.filter { it.kind == CheckKind.CHECK }.map { it.text }
        val info = row.items.filter { it.kind == CheckKind.INFO }.map { it.text }
        return buildList {
            when {
                "지금 받고 있어요" in ok -> add(SummaryLine(null, listOf("지금 받고 있는 지원이에요")))
                "나이 · 지역 조건" in ok -> add(SummaryLine("해당", listOf("나이, 사는 곳")))
            }
            val todo = check.map { if (it == "나이 · 지역 조건") "나이, 사는 곳" else it }
            if (todo.isNotEmpty()) add(SummaryLine("확인 필요", todo))
            info.forEach { add(SummaryLine(null, listOf(it))) }
        }
    }

    private val TO_CHECK = Regex("""^(.*?)(인지|이 맞는지)\s*확인해\s*보세요$""")

    private fun row(line: String): MyCheckRow? {
        val i = line.indexOf(": ")
        if (i <= 0) return null
        val name = line.substring(0, i).trim()
        val items = mutableListOf<CheckItem>()
        line.substring(i + 2).split(Regex("""\.\s*""")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { s ->
            val check = TO_CHECK.find(s)
            when {
                s.startsWith("지금 받고 있는") -> items += CheckItem("지금 받고 있어요", CheckKind.OK)
                s.startsWith("나이 · 지역 조건은 맞아요") -> items += CheckItem("나이 · 지역 조건", CheckKind.OK)
                s.startsWith("나이나 지역 조건을 더 확인") -> items += CheckItem("나이 · 지역 조건", CheckKind.CHECK)
                s.startsWith("보호가 끝난 뒤 신청할 수 있어요") -> items += CheckItem("보호가 끝난 뒤 신청 가능", CheckKind.OK)
                // "서초구 1년 이상 거주, 기준중위소득 50% 이하인지 확인해 보세요" → 조건마다 한 줄
                check != null -> check.groupValues[1].split(", ").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { items += CheckItem(it, CheckKind.CHECK) }
                s.endsWith("먼저 들어야 해요") -> items += CheckItem(s.removeSuffix("을 먼저 들어야 해요").removeSuffix("를 먼저 들어야 해요") + " 듣기", CheckKind.CHECK)
                else -> items += CheckItem(s, CheckKind.INFO)
            }
        }
        return MyCheckRow(name, items.sortedBy { it.kind.ordinal })
    }

    /**
     * 듣기 · 복사용 글 (화면에 보이는 그대로, 기호 없이).
     *   문단들 / 출처: 정부24 / 내 조건으로 보면. 서리풀 생활보조수당 지원. 해당: 나이, 사는 곳. 확인 필요: …
     */
    fun plain(parts: Parts): String = buildString {
        append(parts.body.joinToString("\n\n"))
        if (parts.sources.isNotEmpty()) append("\n\n출처: ").append(parts.sources.joinToString(", "))
        parts.notice?.let { append("\n").append(it) }
        if (parts.myCheck.isNotEmpty()) {
            append("\n\n내 조건으로 보면")
            parts.myCheck.forEach { r ->
                append("\n").append(r.name)
                summary(r).forEach { line ->
                    append("\n").append(line.label?.let { "$it: " }.orEmpty()).append(line.values.joinToString(", "))
                }
            }
        }
    }

    private fun flat(s: String) = s.replace(" ", "").replace("·", "")

    /**
     * '내 조건으로 보면' 행 이름 → 답에 나온 정책 중 몇 번째인지 (없으면 -1).
     * 짧은 이름이 같으면 그것, 아니면 띄어쓰기 · 가운뎃점을 빼고 비교, 그래도 없으면 한쪽이 다른 쪽을 품는 것 (하나일 때만)
     */
    fun matchPolicy(rowName: String, policyNames: List<String>): Int {
        policyNames.indexOfFirst { shortName(it) == rowName }.takeIf { it >= 0 }?.let { return it }
        val want = flat(rowName)
        policyNames.indexOfFirst { flat(shortName(it)) == want }.takeIf { it >= 0 }?.let { return it }
        val loose = policyNames.withIndex().filter { (_, n) -> flat(n).contains(want) || want.contains(flat(shortName(n))) }
        return if (loose.size == 1) loose[0].index else -1
    }
}
