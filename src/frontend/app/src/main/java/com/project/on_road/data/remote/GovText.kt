package com.project.on_road.data.remote

/**
 * 정부24 등에서 수집한 정책 원문을 화면에 맞게 정리한다.
 * 줄 앞 글머리 기호(○ · - · ※ · ① 등)를 빼고, 금액 표기(300천원, 10,000,000원)를 "30만 원", "1,000만 원"처럼 맞춘다.
 * 내용은 바꾸지 않는다 (기호 · 띄어쓰기만)
 */
object GovText {
    private val BULLET = Regex("""^\s*(?:[○●◦•▪▫■□◆◇▶▷►※*\-–·ㆍ]+|\(?\d{1,2}[.)](?=\s)|[①-⑳]|[가-하][.)](?=\s))\s*""")
    private val SPACES = Regex("""[ \t ]+""")
    private val THOUSAND = Regex("""(\d[\d,]*)\s*천\s*원""")
    private val WON = Regex("""(\d{1,3}(?:,\d{3})+|\d{5,})\s*원""")
    private val UNIT_WON = Regex("""(\d)\s*(만|억)\s*원""")

    /** 글머리 기호 · 빈 줄을 뺀다. 줄은 유지 (내용이 없으면 null) */
    fun clean(text: String?): String? =
        text.orEmpty().replace("\r", "").split("\n")
            .map { it.replace(BULLET, "").replace(SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }

    /** 금액 한 줄 (카드용). 없으면 null */
    fun amount(text: String?): String? {
        var s = clean(text)?.replace("\n", " ") ?: return null
        s = THOUSAND.replace(s) { m -> m.groupValues[1].replace(",", "").toLongOrNull()?.let { won(it * 1000) } ?: m.value }
        s = WON.replace(s) { m -> m.groupValues[1].replace(",", "").toLongOrNull()?.let { won(it) } ?: m.value }
        return UNIT_WON.replace(s, "$1$2 원")
    }

    private fun won(v: Long): String {
        if (v % 10_000 != 0L) return "%,d원".format(v)
        val man = v / 10_000
        if (man < 10_000) return "%,d만 원".format(man)
        val eok = man / 10_000
        val rest = man % 10_000
        return if (rest == 0L) "${eok}억 원" else "${eok}억 %,d만 원".format(rest)
    }
}
