package com.project.on_road

import com.project.on_road.data.remote.AnswerFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerPartsTest {
    private val life = "서리풀 희망사다리 프로젝트 자립준비청년(보호종료아동) 생활보조수당 지원"
    private val settle = "서리풀 희망사다리 프로젝트 자립준비청년(보호종료아동) 자립정착금 지원"

    private val raw = "$life 은 서초구에서 1년 이상 거주중인 자립준비청년(보호종료아동)에게 생활보조수당을 지원합니다. (출처: 정부24) " +
        "$settle 은 서초구에서 1년 이상 거주한 자립준비청년(보호종료아동)에게 자립정착금을 지원합니다. (출처: 정부24) " +
        "질문하신 내용 중 일부는 제공된 자료에서 확인되지 않습니다. 담당 기관에 문의하시기 바랍니다. " +
        "자세한 조건은 공식 원문에서 꼭 확인하시기 바랍니다.\n\n" +
        "[내 조건으로 보면]\n" +
        "- 서리풀 생활보조수당 지원: 나이 · 지역 조건은 맞아요. 보호가 끝난 뒤 신청할 수 있어요. 서초구 1년 이상 거주, 기준중위소득 50% 이하인지 확인해 보세요.\n" +
        "- 서리풀 자립정착금 지원: 지금 받고 있는 지원이에요."

    @Test
    fun splitsAnswerIntoParts() {
        val p = AnswerFormat.parts(AnswerFormat.answer(raw), listOf(life, settle))
        assertEquals(2, p.body.size)
        assertTrue(p.body[0].startsWith("서리풀 생활보조수당 지원"))
        assertFalse(p.body.any { "출처" in it || "공식 원문" in it || "일부는" in it })
        assertEquals(listOf("정부24"), p.sources)
        assertTrue(p.notice != null)

        assertEquals(2, p.myCheck.size)
        val first = p.myCheck[0]
        assertEquals("서리풀 생활보조수당 지원", first.name)
        assertEquals(
            listOf(
                AnswerFormat.CheckItem("나이 · 지역 조건", AnswerFormat.CheckKind.OK),
                AnswerFormat.CheckItem("보호가 끝난 뒤 신청 가능", AnswerFormat.CheckKind.OK),
                AnswerFormat.CheckItem("서초구 1년 이상 거주", AnswerFormat.CheckKind.CHECK),
                AnswerFormat.CheckItem("기준중위소득 50% 이하", AnswerFormat.CheckKind.CHECK),
            ),
            first.items,
        )
        assertEquals(listOf(AnswerFormat.CheckItem("지금 받고 있어요", AnswerFormat.CheckKind.OK)), p.myCheck[1].items)
        assertEquals(
            listOf(
                AnswerFormat.SummaryLine("해당", listOf("나이, 사는 곳")),
                AnswerFormat.SummaryLine("확인 필요", listOf("서초구 1년 이상 거주", "기준중위소득 50% 이하")),
            ),
            AnswerFormat.summary(first),
        )
        assertEquals(listOf(AnswerFormat.SummaryLine(null, listOf("지금 받고 있는 지원이에요"))), AnswerFormat.summary(p.myCheck[1]))
    }

    @Test
    fun betterPolicyIsInfo() {
        val p = AnswerFormat.parts(
            "자립수당은 지원합니다.\n\n[내 조건으로 보면]\n- 자립수당: 나이나 지역 조건을 더 확인해야 해요. 같은 종류 중에는 서리풀 생활보조수당 지원이 더 유리해요.",
        )
        assertEquals(AnswerFormat.CheckKind.CHECK, p.myCheck[0].items[0].kind)
        assertEquals(AnswerFormat.CheckKind.INFO, p.myCheck[0].items[1].kind)
    }

    @Test
    fun plainTextForSpeakAndCopy() {
        val text = AnswerFormat.plain(AnswerFormat.parts(AnswerFormat.answer(raw), listOf(life, settle)))
        assertFalse("[내 조건으로 보면]" in text || "(출처" in text || "- 서리풀" in text)
        assertTrue("출처: 정부24" in text)
        assertTrue("확인 필요: 서초구 1년 이상 거주, 기준중위소득 50% 이하" in text)
    }

    @Test
    fun rowsFindTheirPolicy() {
        val names = listOf(life, settle)
        assertEquals(0, AnswerFormat.matchPolicy("서리풀 생활보조수당 지원", names))
        assertEquals(1, AnswerFormat.matchPolicy("서리풀 자립정착금지원", names)) // 띄어쓰기가 달라도
        assertEquals(-1, AnswerFormat.matchPolicy("자립수당", names))
    }

    @Test
    fun shortNames() {
        assertEquals("서리풀 생활보조수당 지원", AnswerFormat.shortName(life))
        assertEquals("자립수당", AnswerFormat.shortName("자립수당"))
    }

    @Test
    fun answerWithoutExtras() {
        val p = AnswerFormat.parts("자립수당은 매월 50만 원 지급됩니다.", listOf("자립수당"))
        assertEquals(listOf("자립수당은 매월 50만 원 지급됩니다."), p.body)
        assertTrue(p.myCheck.isEmpty() && p.notice == null && p.sources.isEmpty())
    }
}
