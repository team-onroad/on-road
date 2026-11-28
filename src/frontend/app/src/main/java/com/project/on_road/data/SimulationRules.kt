package com.project.on_road.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------- 주거 비교 ----------------

enum class HousingType(val label: String, val description: String) {
    MONTHLY("월세", "보증금을 내고 매달 월세를 내요"),
    JEONSE("전세", "큰 보증금을 맡기고 월세는 없어요"),
    PUBLIC("공공임대", "LH 등 공공 주택을 싸게 빌려요")
}

data class HousingCost(
    val type: HousingType,
    val upfront: Int,        // 처음 필요한 내 돈 (보증금, 나중에 돌려받음)
    val monthly: Int,        // 매달 나가는 돈
    val interest: Int,       // 전세대출 월 이자
    val total: Int           // 기간 동안 쓰는 돈 (보증금 제외)
)

object HousingRules {
    fun monthlyRent(deposit: Int, rent: Int, maintenance: Int, months: Int): HousingCost {
        val monthly = rent + maintenance
        return HousingCost(HousingType.MONTHLY, deposit, monthly, 0, monthly * months)
    }

    fun jeonse(deposit: Int, own: Int, ratePercent: Double, maintenance: Int, months: Int): HousingCost {
        val loan = max(deposit - own, 0)
        val interest = (loan * ratePercent / 100.0 / 12.0).roundToInt()
        val monthly = interest + maintenance
        return HousingCost(HousingType.JEONSE, min(own, deposit), monthly, interest, monthly * months)
    }

    fun publicRental(deposit: Int, rent: Int, maintenance: Int, months: Int): HousingCost {
        val monthly = rent + maintenance
        return HousingCost(HousingType.PUBLIC, deposit, monthly, 0, monthly * months)
    }
}

// ---------------- 저축 목표 ----------------

data class SavingsPlan(
    val ownMonthly: Int,
    val allowanceMonthly: Int,
    val matchMonthly: Int,
    val monthlyTotal: Int,
    val monthsToGoal: Int?,      // null이면 도달 불가
    val balances: List<Int>      // 0개월부터 도달(또는 최대 60개월)까지 잔액
)

object SavingsRules {
    const val DIDIM_MATCH_CAP = 10   // 정부 매칭 월 최대 10만원 (예시 기준)
    const val MAX_MONTHS = 120

    fun plan(goal: Int, current: Int, own: Int, allowance: Int, didim: Boolean): SavingsPlan {
        val match = if (didim) min(own * 2, DIDIM_MATCH_CAP) else 0
        val monthly = own + allowance + match
        val need = max(goal - current, 0)
        val months = when {
            need == 0 -> 0
            monthly <= 0 -> null
            else -> ceil(need / monthly.toDouble()).toInt().takeIf { it <= MAX_MONTHS }
        }
        val horizon = months ?: 60
        val balances = (0..max(horizon, 1)).map { current + monthly * it }
        return SavingsPlan(own, allowance, match, monthly, months, balances)
    }
}

// ---------------- 퇴소 D-day 타임라인 ----------------

enum class TimelineLink { BUDGET, HOUSING, SAVINGS, POLICY_SETTLEMENT, POLICY_ALLOWANCE, POLICY_COUNSEL }

data class TimelineTask(
    val id: String,
    val offsetDays: Int,          // 퇴소일 기준 (음수 = 전, 양수 = 후)
    val title: String,
    val description: String,
    val link: TimelineLink? = null,
    val linkLabel: String? = null
)

object TimelineRules {
    val tasks = listOf(
        TimelineTask("counsel", -180, "자립지원전담기관 연락처 알아두기", "퇴소 후에도 상담과 도움을 받을 수 있는 곳이에요.", TimelineLink.POLICY_COUNSEL, "전담기관 알아보기"),
        TimelineTask("bank", -150, "본인 명의 통장과 체크카드 만들기", "수당과 월급을 받을 통장이 필요해요. 신분증을 챙겨 은행에 가요."),
        TimelineTask("saving", -120, "모은 돈과 앞으로 모을 돈 점검하기", "디딤씨앗통장 등 지금까지 모은 돈을 확인해요.", TimelineLink.SAVINGS, "저축 목표 세우기"),
        TimelineTask("house", -90, "살 곳 알아보기", "공공임대, 전세임대, 월세를 비교해 보고 신청 일정을 확인해요.", TimelineLink.HOUSING, "주거비 비교하기"),
        TimelineTask("settlement", -60, "자립정착금 신청 방법 확인하기", "퇴소 전에 시설이나 지자체를 통해 준비하는 경우가 많아요.", TimelineLink.POLICY_SETTLEMENT, "정책 보기"),
        TimelineTask("docs", -45, "필요한 서류 발급 방법 익히기", "주민등록등본, 가족관계증명서 등을 직접 떼어 봐요."),
        TimelineTask("budget", -30, "첫 달 생활비 계획 세우기", "월세, 식비, 교통비를 미리 나눠 봐요.", TimelineLink.BUDGET, "생활비 배분하기"),
        TimelineTask("phone", -14, "휴대폰 명의와 요금제 확인하기", "내 명의인지, 요금이 부담되지 않는지 확인해요."),
        TimelineTask("move", -7, "짐 정리하고 이사 준비하기", "이삿짐, 생활용품 목록을 만들어요."),
        TimelineTask("dday", 0, "퇴소하는 날", "새로운 시작이에요. 필요한 연락처를 한 번 더 확인해요."),
        TimelineTask("register", 14, "전입신고와 확정일자 받기", "이사 후 14일 안에 주민센터에서 전입신고를 해요. 확정일자도 함께 받아 두면 보증금을 지키는 데 도움이 돼요."),
        TimelineTask("allowance", 30, "자립수당 신청하기", "주민센터나 복지로에서 신청해요.", TimelineLink.POLICY_ALLOWANCE, "정책 보기"),
        TimelineTask("health", 30, "건강보험 자격 확인하기", "병원비 지원을 받을 수 있는지도 함께 확인해요."),
        TimelineTask("followup", 90, "전담기관 사후관리 상담 받기", "지내면서 어려운 점을 이야기하고 필요한 도움을 연결받아요.", TimelineLink.POLICY_COUNSEL, "전담기관 알아보기")
    )
}
