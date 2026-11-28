package com.project.on_road.data

/**
 * 정책 상세 '필요 서류' 정리. 공식 원문 줄을 그대로 받아서, 서류가 아닌 안내 · 제목 · 겹치는 줄이 섞여 있다.
 * - 서류가 아닌 줄은 뺀다: "문의처: 063-…", "○○ 홈페이지 확인", "누리집 등에 게재", "주민센터 방문", "공고 확인", "계좌로 입금"
 * - 제목 줄("<1차 신청>", "2차 지원 신청시", "의심아동")은 빼고, 그 아래 서류의 '언제 내는지'로 붙인다
 * - "선별 체크리스트 18점 미만: 추천서" → 서류 "추천서", 언제 "선별 체크리스트 18점 미만"
 * - 한 줄에 붙은 두 서류("확인서(…)5. 보호종료확인서")는 나눈다, 끝의 "작성" · 마침표는 지운다
 * - 같은 서류(띄어쓰기 · 마침표 · "1부"만 다른 것 포함)는 한 번만
 */
object DocClean {
    /** 서류 이름에 들어가는 말 (하나도 없으면 서류가 아니다) */
    private val DOC_WORD = Regex(
        "서|증|본|통장|서류|내역|명세|사진|문답표|질문지|측정도구|체크리스트|출석부|도장|고지서|카드|원부|계좌번호|위임장|인감|인적사항|자료",
    )
    /** 서류처럼 보여도 안내인 줄 */
    private val NOT_DOC = listOf(
        Regex("""^문의"""),
        Regex("""\d{2,4}\s*-\s*\d{3,4}\s*-\s*\d{4}"""), // 전화번호
        Regex("""홈페이지|누리집|공고(문)?\s*(확인|참고)$|게재$"""),
        Regex("""(방문|입금|문의)\s*$"""),
        Regex("""^((구비|제출)\s*)?서류\s*없음|^없음$"""),
    )
    private val ANGLE_HEADER = Regex("""^<\s*(.+?)\s*>$""")
    private val ROUND_HEADER = Regex("""^(\d+\s*차)\s*(지원\s*)?신청\s*시?$""")
    private val MERGED = Regex("""(?<=\))\s*\d{1,2}\.\s*(?=\S)""")
    /** 콜론 앞이 '언제 · 누구'인 말 ("18점 미만", "신혼부부 해당") */
    private val WHEN_WORD = Regex("""(미만|이상|이하|초과|해당|대상|경우|\s시)$""")
    private val COPIES = Regex("""\s*(각\s*)?\d+\s*부\s*$""")

    private fun isDoc(s: String) = DOC_WORD.containsMatchIn(s) && NOT_DOC.none { it.containsMatchIn(s) }

    private fun key(d: RequiredDoc) =
        d.name.replace(COPIES, "").replace(Regex("""[\s.·,]"""), "") + "|" + d.condition.orEmpty().replace(" ", "")

    fun clean(docs: List<RequiredDoc>): List<RequiredDoc> {
        var group: String? = null
        val out = mutableListOf<RequiredDoc>()
        for (d in docs) {
            val line = d.name.trim()
            // 제목 줄 → 아래 서류의 '언제'
            val angle = ANGLE_HEADER.find(line)
            if (angle != null) {
                val g = angle.groupValues[1]
                group = if (g.endsWith("신청")) "$g 시" else g
                continue
            }
            val round = ROUND_HEADER.find(line)
            if (round != null) {
                group = round.groupValues[1].replace(" ", "") + " 신청 시"
                continue
            }
            if (!isDoc(line)) {
                // 서류 말이 없는 짧은 줄("의심아동")은 아래 서류의 대상 제목
                if (line.length <= 8 && NOT_DOC.none { it.containsMatchIn(line) } && !line.any(Char::isDigit)) group = "$line 해당 시"
                continue
            }
            for (part in line.split(MERGED)) {
                var name = part.trim().removeSuffix(".").trim().removeSuffix(" 작성").trim()
                var cond = d.condition?.takeIf { it.isNotBlank() }
                // "대상: 서류" → 서류 + 언제 (앞이 서류 이름이면 "신분증: 주민등록증 · 여권 중 1종" 처럼 그대로)
                val colon = Regex("""^(.+?)\s*[:：]\s*(.+)$""").find(name)
                val headIsWhen = colon != null &&
                    (!DOC_WORD.containsMatchIn(colon.groupValues[1]) || WHEN_WORD.containsMatchIn(colon.groupValues[1]))
                if (colon != null && headIsWhen && DOC_WORD.containsMatchIn(colon.groupValues[2])) {
                    name = colon.groupValues[2].trim()
                    cond = cond ?: colon.groupValues[1].trim()
                }
                if (name.isBlank() || !isDoc(name)) continue
                val doc = RequiredDoc(name, cond ?: group)
                if (out.none { key(it) == key(doc) }) out += doc
            }
        }
        return out
    }
}
