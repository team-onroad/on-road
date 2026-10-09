package com.project.on_road.data

import java.time.LocalDate

/** 화면 분기 기준이 되는 성장 단계 */
enum class UserStage(val label: String) {
    CHILD("아동"),
    TEEN("청소년"),
    YOUNG_ADULT("자립준비청년")
}

enum class LivingStatus(val label: String, val description: String) {
    IN_FACILITY("시설 생활", "보육원·그룹홈 등에서 지내고 있어요"),
    LEAVING_SOON("퇴소 예정", "1년 안에 보호가 끝날 예정이에요"),
    LEFT("퇴소 후", "보호가 끝나고 혼자 생활하고 있어요")
}

val REGIONS = listOf(
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기",
    "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"
)

/** stage는 서버가 만 나이 + 현재 상태로 계산해서 내려준다. */
data class UserProfile(
    val userId: String,
    val name: String,
    val birthDate: LocalDate,
    val region: String,
    val status: LivingStatus,
    val stage: UserStage
)

data class Evidence(val source: String, val snippet: String)

data class Policy(
    val id: String,
    val name: String,
    val category: String,
    val agency: String,
    val checkedAt: String,
    val summary: String,
    val easySummary: String,
    val target: String,
    val amount: String,
    val period: String,
    val howToApply: String,
    val applyUrl: String,
    val requiredDocs: List<String>,
    val evidence: List<Evidence>,
    val keywords: List<String>,
    /** 확인일 180일 초과 시 true → "오래된 정보" 경고 */
    val isOutdated: Boolean = false,
    /** 공식 원문 링크 */
    val sourceUrl: String = "",
    /** 조건 판정: "match"(기본 조건 해당) / "needs_check"(조건 확인 필요) / null(판정 없음) */
    val eligibility: String? = null
)

data class SearchResult(
    val question: String,
    val grounded: Boolean,
    val message: String,
    val policies: List<Policy>
)

enum class StepType(val title: String) {
    TARGET("대상 확인"),
    CONDITION("조건 확인"),
    DOCS("서류 준비"),
    APPLY("신청")
}

data class ChecklistStep(
    val type: StepType,
    val description: String,
    val easyDescription: String,
    val done: Boolean = false
)

data class Checklist(
    val id: String,
    val policyId: String,
    val policyName: String,
    val steps: List<ChecklistStep>,
    val requiredDocs: List<String>,
    val checkedDocs: Set<String>,
    val applyUrl: String
) {
    val doneCount: Int get() = steps.count { it.done }
    val progress: Float get() = if (steps.isEmpty()) 0f else doneCount / steps.size.toFloat()
    val completed: Boolean get() = steps.isNotEmpty() && steps.all { it.done }
}

/** minimum: 1인 가구 기준 권장 최소 금액(만원, 예시 기준) */
enum class BudgetCategory(val label: String, val hint: String, val minimum: Int) {
    HOUSING("주거비", "월세·관리비·공과금", 30),
    FOOD("식비", "장보기·외식", 30),
    TRANSPORT("교통비", "대중교통·이동", 6),
    PHONE("통신비", "휴대폰·인터넷", 4),
    ETC("기타", "생활용품·저축·비상금", 10)
}

data class Shortage(val category: BudgetCategory, val allocated: Int, val recommended: Int)

data class Scenario(
    val title: String,
    val description: String,
    val allocation: Map<BudgetCategory, Int>
)

data class SimulationResult(
    val id: String,
    val income: Int,
    val allocation: Map<BudgetCategory, Int>,
    val shortages: List<Shortage>,
    val scenarios: List<Scenario>,
    val relatedPolicies: List<Policy>,
    val createdAt: String
)

data class UserProgress(
    val checklistCount: Int,
    val completedChecklists: Int,
    val overallProgress: Float,
    val simulationCount: Int,
    val savedPolicyCount: Int
) {
    companion object {
        val EMPTY = UserProgress(0, 0, 0f, 0, 0)
    }
}

enum class ActionKind { OPEN_CHECKLIST, START_CHECKLIST, RUN_SIMULATION, SEARCH_POLICY }

data class NextAction(
    val kind: ActionKind,
    val title: String,
    val description: String,
    val policyId: String? = null,
    val priority: Int = 5
)

data class GrowthRecord(
    val savedPolicies: List<Policy>,
    val completedChecklists: List<Checklist>,
    val simulations: List<SimulationResult>
)
