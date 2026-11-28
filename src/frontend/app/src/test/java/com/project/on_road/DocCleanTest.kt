package com.project.on_road

import com.project.on_road.data.DocClean
import com.project.on_road.data.RequiredDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 정책 원문 줄로 확인 (data/policies/policies.json, 2026-11-28) */
class DocCleanTest {
    private fun names(vararg lines: String) = DocClean.clean(lines.map { RequiredDoc(it) })

    @Test
    fun dropsLinesThatAreNotDocuments() {
        assertTrue(names("문의처: 전북특별자치도 여성가족과/063-280-4788").isEmpty())
        assertTrue(names("인천인재평생교육진흥원 홈페이지 확인").isEmpty())
        assertTrue(names("시도 자립지원 전담기관 정보는 시도, 시군구 누리집, 아동권리보장원 누리집 등에 게재").isEmpty())
        assertTrue(names("주민등록주소지 주민센터 방문", "충북가정위탁지원센터(043-250-1226)").isEmpty())
        assertTrue(names("아동발달지원계좌로 입금", "공고 확인", "제출서류 없음").isEmpty())
    }

    @Test
    fun headersBecomeWhen() {
        val docs = names("<1차 신청>", "1차 자립정착금 신청서", "<2차 신청>", "2차 자립정착금 신청서")
        assertEquals(listOf(RequiredDoc("1차 자립정착금 신청서", "1차 신청 시"), RequiredDoc("2차 자립정착금 신청서", "2차 신청 시")), docs)
        val round = names("2차 지원 신청시", "자립정착금 사용계획서에 따른 증빙서류")
        assertEquals("2차 신청 시", round.single().condition)
        val target = names("의심아동", "신청서", "진단아동", "종합심리평가보고서")
        assertEquals(listOf("의심아동 해당 시", "진단아동 해당 시"), target.map { it.condition })
    }

    @Test
    fun conditionBeforeColon() {
        val d = names("선별 체크리스트 1.18점 미만: 추천서").single()
        assertEquals(RequiredDoc("추천서", "선별 체크리스트 1.18점 미만"), d)
        // 앞이 서류 이름이면 그대로 (하나만 내는 서류)
        assertEquals("신분증: 주민등록증 · 여권 중 1종", names("신분증: 주민등록증 · 여권 중 1종").single().name)
    }

    @Test
    fun splitsMergedAndRemovesDuplicates() {
        val merged = names("1차 의무교육확인서(자립전담기관에서 발급)5. 보호종료확인서 1부.")
        assertEquals(listOf("1차 의무교육확인서(자립전담기관에서 발급)", "보호종료확인서 1부"), merged.map { it.name })
        val dup = DocClean.clean(
            listOf(
                RequiredDoc("대학입학등록금 영수증 또는 재학증명서 등 입학 확인서류", "대학입학준비금"),
                RequiredDoc("수강확인증 (학습비)"),
                RequiredDoc("대학입학등록금 영수증 또는 재학증명서 등 입학 확인서류", "대학입학준비금"),
            ),
        )
        assertEquals(2, dup.size)
        assertEquals(1, names("신분증 및 통장 사본 1부", "신분증 및 통장 사본 1부.").size)
        assertEquals("자립정착금 사용계획서", names("자립정착금 사용계획서 작성").single().name)
    }

    @Test
    fun keepsRealDocuments() {
        val real = listOf("신청서 및 개인정보 수집·이용동의서", "신청자 신분증", "통장사본", "재학증명서", "등록금 납입증명서",
            "자동차등록원부(갑)", "구직단념청년 문답표", "중장기프로그램 참여대상 측정도구", "신분증 및 인감도장", "선별 체크리스트", "위임장", "간병인 인적사항 등 (간병비)")
        assertEquals(real, names(*real.toTypedArray()).map { it.name })
    }
}
