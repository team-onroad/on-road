package com.project.on_road

import com.project.on_road.data.ChatSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionsTest {
    private val bada = ChatSuggestions.Context(
        youngAdult = true, student = true, sibling = true, receiving = true, certificates = true, district = "서초구",
    )
    private val seori = "서리풀 희망사다리 프로젝트 자립준비청년(보호종료아동) 생활보조수당 지원"
    private val seoriEdu = "서리풀 희망사다리 프로젝트 자립준비청년(보호종료아동) 교육비 지원"

    @Test
    fun badaStarters() {
        assertEquals(
            listOf(
                "자립수당 받으면 생계급여 줄어드나요?",
                "서리풀과 자립수당 중복 수혜되나요?",
                "전입 1년 미만도 서리풀 지원되나요?",
                "동생 디딤씨앗 매칭액은 얼마인가요?",
            ),
            ChatSuggestions.starters(bada),
        )
    }

    @Test
    fun noSeoriQuestionsOutsideSeocho() {
        val other = bada.copy(district = "강남구")
        assertTrue(ChatSuggestions.starters(other).none { "서리풀" in it })
    }

    @Test
    fun badaDemoFlow() {
        val asked = mutableListOf("서리풀과 자립수당 중복 수혜되나요?")
        val one = ChatSuggestions.followUps(bada, listOf(seori, "자립준비청년 자립수당 지급"), asked)
        assertEquals(listOf("전입 1년 미만도 서리풀 지원되나요?", "생활보조수당 소득 기준은 얼마인가요?"), one.take(2))

        asked += "전입 1년 미만도 서리풀 지원되나요?"
        val two = ChatSuggestions.followUps(bada, listOf(seoriEdu), asked)
        assertEquals("서리풀 교육비 등록금은 얼마인가요?", two[0])
        assertTrue(two.none { it in asked })
        assertEquals(4, two.size)
    }

    @Test
    fun leaseAndRentalAreDifferentKinds() {
        val lh = ChatSuggestions.followUps(bada, listOf("기존주택 전세임대주택 지원사업"), emptyList())
        assertEquals("전세임대 주택은 직접 구하나요?", lh[0])
        val sh = ChatSuggestions.followUps(bada, listOf("보호종료아동 및 쉼터퇴소 청소년 대상 임대주택 지원"), emptyList())
        assertEquals("임대주택은 미혼만 신청할 수 있나요?", sh[0])
        assertFalse(sh.contains("전세임대 주택은 직접 구하나요?"))
    }

    @Test
    fun everyQuestionIsShort() {
        val long = ChatSuggestions.allQuestions().filter { it.length > ChatSuggestions.MAX_LENGTH }
        assertTrue("20자 넘는 질문: $long", long.isEmpty())
    }
}
