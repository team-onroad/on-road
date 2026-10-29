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
    val stage: UserStage,
    val phone: String? = null,
    /** D-day 기준일 (퇴소 예정일 또는 목표일). 없으면 null */
    val dDate: LocalDate? = null,
    /** dDate 까지 남은 날 (오늘 0, 지나면 음수). dDate 없으면 null */
    val dDay: Int? = null,
)

/** 회원정보 수정에서 바꿀 값. null 이면 그대로 둔다 */
data class UserUpdate(
    val status: LivingStatus? = null,
    /** 새 퇴소 예정일 / 목표일 */
    val dDate: LocalDate? = null,
    /** true 면 퇴소일·목표일을 지운다 (dDate 보다 우선) */
    val clearDDate: Boolean = false,
    /** 이름 (개명) */
    val name: String? = null,
    /** 사는 지역 코드 (예: "부산") */
    val region: String? = null,
    /** 휴대폰 번호 (하이픈 없이) */
    val phone: String? = null,
)

data class Evidence(val source: String, val snippet: String)

/** 필요 서류. condition 이 있으면 경우에 따라 필요한 서류 (예: "대리 신청 시") */
data class RequiredDoc(val name: String, val condition: String? = null) {
    /** 화면 표시용: "위임장 [대리 신청 시]" */
    val label: String get() = if (condition.isNullOrBlank()) name else "$name [$condition]"
}

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
    val requiredDocs: List<RequiredDoc>,
    val evidence: List<Evidence>,
    val keywords: List<String>,
    /** 확인일 180일 초과 시 true → "오래된 정보" 경고 */
    val isOutdated: Boolean = false,
    /** 공식 원문 링크 */
    val sourceUrl: String = "",
    /** 조건 판정: "match"(기본 조건 해당) / "needs_check"(조건 확인 필요) / null(판정 없음) */
    val eligibility: String? = null,
    /** 서버 짧은 요약 (검색 결과에만 있음). null 이면 target·amount·period 로 표시 */
    val cardSummary: PolicySummary? = null,
)

/** 정책 짧은 요약: 지원 대상 / 받을 수 없는 경우 / 지원 내용 / 지원 기간 / 신청 방법 */
data class PolicySummary(
    val sections: List<SummarySection>,
    /** 자격 단정 금지 안내 (작게 표시) */
    val notice: String?,
    /** false 면 "검토 중" 안내 */
    val verified: Boolean,
)

data class SummarySection(val label: String, val items: List<String>)

data class SearchResult(
    val question: String,
    val grounded: Boolean,
    val message: String,
    val policies: List<Policy>,
    /** 서버(RAG)가 만든 답변 문장. 근거가 없거나 가짜 모드면 null */
    val answer: String? = null,
)

// ───────────── 체크리스트 (3.9 ~ 3.11) ─────────────

/** code: 서버 step_key */
enum class StepType(val title: String, val code: String) {
    TARGET("대상 확인", "target_check"),
    CONDITION("조건 확인", "condition_check"),
    DOCS("서류 준비", "doc_prepare"),
    APPLY("신청", "apply");

    companion object {
        fun of(code: String): StepType? = entries.find { it.code == code }
    }
}

/** 체크리스트에 붙는 정책 정보 (명세 1.5) */
data class ChecklistPolicy(
    val id: String,
    val name: String,
    val agency: String,
    /** 화면 표시용 분야 이름 (주거, 생활·금융 …) */
    val category: String,
    /** 문의처. 없으면 "문의" 문구 숨김 */
    val contact: String?,
    /** 공식 신청 페이지. 없으면 "공식 신청 바로가기" 버튼 숨김 */
    val applyUrl: String?,
    val checkedAt: String,
    /** false 면 더 이상 운영되지 않는 정책 (정책 상세로 이동하지 않음) */
    val isActive: Boolean,
)

data class ChecklistStep(
    /** 서버 항목 id (체크할 때 사용) */
    val itemId: Long,
    val type: StepType,
    val description: String,
    /** 쉬운 말 설명. 없으면 "쉬운 말로 보기"를 숨긴다 (검증되지 않은 쉬운 말은 보여주지 않음) */
    val easyDescription: String?,
    val done: Boolean = false,
    /** 조건 확인 단계: 항목별 결과. 서버 DB 에 없는 조건은 모두 "확인 필요" */
    val checks: List<ConditionCheck> = emptyList(),
    /** 신청 단계: 방법별 안내 (방문·온라인·우편·팩스) */
    val methods: List<ApplyMethod> = emptyList(),
    /** 서버 짧은 요약. 있으면 원문 대신 이걸 보여준다 */
    val summary: PolicySummary? = null,
)

/** 조건 한 줄. ok=null 이면 확인 필요 */
data class ConditionCheck(val label: String, val ok: Boolean?)

/** 신청 방법 한 개 */
data class ApplyMethod(val label: String, val text: String)

/** 준비 서류 한 개 */
data class ChecklistDoc(
    val itemId: Long,
    val name: String,
    /** 경우에 따라 필요한 서류의 조건. 있으면 "해당 없음"을 고를 수 있다 */
    val condition: String?,
    val checked: Boolean = false,
    val notApplicable: Boolean = false,
    /** 서버 짧은 이름 (조건 포함). 있으면 이름 대신 보여주고, 원래 이름은 자세히 보기로 */
    val shortLabel: String? = null,
    /** 문서 AI 로 쓰는 서류면 그 문서 id 와 상태 (writing / exported) */
    val documentId: Long? = null,
    val documentStatus: String? = null,
) {
    /** 문서 AI 로 써서 내보낸 서류: 체크가 문서를 따르고 체크리스트에서 직접 바꾸지 않는다 */
    val written: Boolean get() = documentStatus == "exported"
}

data class Checklist(
    val id: Long,
    val policy: ChecklistPolicy,
    /** 항상 4단계: 대상 확인 → 조건 확인 → 서류 준비 → 신청 */
    val steps: List<ChecklistStep>,
    val docs: List<ChecklistDoc>,
) {
    val policyId: String get() = policy.id
    val policyName: String get() = policy.name
    val doneCount: Int get() = steps.count { it.done }
    val progress: Float get() = if (steps.isEmpty()) 0f else doneCount / steps.size.toFloat()
    val completed: Boolean get() = steps.isNotEmpty() && steps.all { it.done }

    /** 서류 진행률 = 체크한 서류 / (전체 − 해당 없음) */
    val docTotal: Int get() = docs.count { !it.notApplicable }
    val docChecked: Int get() = docs.count { it.checked && !it.notApplicable }
    val docProgress: Float get() = if (docTotal == 0) 0f else docChecked / docTotal.toFloat()
}

/** 체크 결과로 서버가 돌려준 항목 상태 (3.11) */
data class ChecklistItemState(val itemId: Long, val checked: Boolean, val notApplicable: Boolean)

/** 목록·홈·성장 기록용 체크리스트 요약 (3.4, 3.13) */
data class ChecklistSummary(
    val id: Long,
    val policy: ChecklistPolicy,
    val doneSteps: Int,
    val totalSteps: Int,
    /** 체크하지 않은 첫 단계. null 이면 4단계 모두 완료 */
    val nextStep: StepType?,
) {
    val policyId: String get() = policy.id
    val policyName: String get() = policy.name
    val completed: Boolean get() = nextStep == null
    val progress: Float get() = if (totalSteps == 0) 0f else doneSteps / totalSteps.toFloat()
}

// ───────────── 생활비 시뮬레이션 ─────────────

/** minimum: 1인 가구 기준 권장 최소 금액(만원). 서버 기준표(3.7)를 받기 전 기본값 */
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

/** 성장 기록의 시뮬레이션 요약 (금액은 만원). 전체 결과는 id 로 다시 조회 (3.14) */
data class SimulationSummary(
    val id: String,
    val income: Int,
    val remaining: Int,
    val shortageCount: Int,
    val createdAt: String,
)

// ───────────── 홈 (3.4) ─────────────

data class DashboardUser(
    val name: String,
    val stage: UserStage,
    val status: LivingStatus,
    val dDate: LocalDate?,
    val dDay: Int?,
)

data class LatestSimulation(val id: String, val shortageCount: Int, val createdAt: String)

data class Dashboard(
    /** 가짜 모드에서 사용자 정보가 없으면 null */
    val user: DashboardUser?,
    /** 진행 중 먼저, 완료는 뒤 (각각 최근 수정순) */
    val checklists: List<ChecklistSummary>,
    val latestSimulation: LatestSimulation?,
)

/**
 * 다음 할 일 종류.
 * SEARCH_TOPIC: 관심 분야 정책 찾기(query) / OPEN_TIMELINE: 퇴소 D-day 타임라인
 * PRACTICE: 실전 연습(scenarioId 가 있으면 그 상황) / EXPLORE_CAREER: 흥미 탐색
 * ROADMAP: 로드맵 다음 단계 / BASIC_MISSION: 청소년 자립 기초 미션(query = 미션 id)
 */
enum class ActionKind { OPEN_CHECKLIST, START_CHECKLIST, RUN_SIMULATION, SEARCH_POLICY, SEARCH_TOPIC, OPEN_TIMELINE, PRACTICE, EXPLORE_CAREER, ROADMAP, BASIC_MISSION }

data class NextAction(
    val kind: ActionKind,
    val title: String,
    val description: String,
    val policyId: String? = null,
    val priority: Int = 5,
    /** 추천 이유 (예: "최근 상담에서 주거 이야기를 3번 했어요"). 없으면 null */
    val reason: String? = null,
    /** SEARCH_TOPIC 검색어 */
    val query: String? = null,
    /** PRACTICE 시나리오 id */
    val scenarioId: String? = null,
)

// ───────────── 성장 기록 (3.13) ─────────────

/** 저장한 롤플레잉 피드백 기록 */
data class RoleplayRecord(
    val id: Long,
    val scenarioId: String,
    val title: String,
    val goodCount: Int,
    val missedCount: Int,
    val createdAt: String,
)

data class GrowthRecord(
    /** 저장한 정책(찜)은 서버에 없어서 앱에 저장한 것 */
    val savedPolicies: List<Policy>,
    val checklists: List<ChecklistSummary>,
    val simulations: List<SimulationSummary>,
    val roleplays: List<RoleplayRecord>,
) {
    val completedChecklists: List<ChecklistSummary> get() = checklists.filter { it.completed }

    companion object {
        val EMPTY = GrowthRecord(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
