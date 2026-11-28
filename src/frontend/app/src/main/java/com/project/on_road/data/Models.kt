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

/** 보호받던 곳 (서버 care_type). 받는 제도가 갈린다: 자립수당(아동복지시설 · 가정위탁) / 청소년복지시설 퇴소청소년 수당 */
enum class CareType(val code: String, val label: String, val description: String) {
    CHILD_FACILITY("child_facility", "아동복지시설", "보육원, 그룹홈(공동생활가정)"),
    FOSTER("foster", "가정위탁", "위탁가정, 친인척 위탁 포함"),
    YOUTH_FACILITY("youth_facility", "청소년복지시설", "청소년쉼터, 청소년자립지원관");

    companion object {
        fun of(code: String?): CareType? = entries.firstOrNull { it.code == code }
    }
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
    /** 거주 시 · 군 · 구 ("서초구"). 없으면 null → 시 · 군 · 구 정책은 '조건 확인 필요' */
    val district: String? = null,
    /** 자녀 · 동생 (아동 대상 정책 판정) */
    val family: List<FamilyMember> = emptyList(),
    /** 보호받던 곳. 모르면 null */
    val careType: CareType? = null,
    /** 흥미 탐색 상위 유형 ("RA"). 아직 안 했으면 null */
    val interestCode: String? = null,
    /** 고른 관심 분야 (최대 3개, 직업 이름) */
    val interestTags: List<String> = emptyList(),
)

/** 자녀 · 동생 한 명. relation: child | sibling, gender: male | female | null */
data class FamilyMember(
    val relation: String,
    val gender: String?,
    val birthDate: LocalDate,
    val age: Int = 0,
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
    /** 생년월일 (서류 대화에서 고친 생일을 내 정보에도 반영할 때) */
    val birthDate: LocalDate? = null,
    /** 지금 하는 일 */
    val work: WorkStatus? = null,
    /** true 면 지금 하는 일을 지운다 (work 보다 우선) */
    val clearWork: Boolean = false,
    /** 시 · 군 · 구 (null 이면 그대로, clearDistrict 면 지움) */
    val district: String? = null,
    val clearDistrict: Boolean = false,
    /** 가족 목록 (보내면 통째로 바뀜, null 이면 그대로) */
    val family: List<FamilyMember>? = null,
    /** 보호받던 곳 (null 이면 그대로, clearCareType 이면 지움 = 모름) */
    val careType: CareType? = null,
    val clearCareType: Boolean = false,
    /** 흥미 탐색 상위 유형 (null 이면 그대로) */
    val interestCode: String? = null,
    /** 관심 분야 (보내면 통째로 바뀜, null 이면 그대로) */
    val interestTags: List<String>? = null,
)

/** 서버 work 코드 (student · working · job_seeking · resting) */
fun WorkStatus.serverCode(): String = when (this) {
    WorkStatus.STUDENT -> "student"
    WorkStatus.WORKING -> "working"
    WorkStatus.JOB_SEEKING -> "job_seeking"
    WorkStatus.RESTING -> "resting"
}

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
    /** 보호 상태 판정: "now" · "after_leave"(아직 보호 중, 퇴소 후 신청) · null(보호 상태와 상관없음) */
    val leave: String? = null,
    /** 서버 짧은 요약 (검색 결과에만 있음). null 이면 target·amount·period 로 표시 */
    val cardSummary: PolicySummary? = null,
    /** 카드 한 줄 금액 (support_amount 정리). 금액이 없는 정책은 빈 값 → 카드에서 줄을 숨긴다 */
    val amountShort: String = "",
    /** 답변 근거가 아닌 비슷한 정책 (검색 · 상담 결과에서 따로 묶어 보여 준다) */
    val related: Boolean = false,
    /** 가족(자녀 · 동생) 나이로 보이는 아동 대상 정책 (따로 묶는다) */
    val forFamily: Boolean = false,
    /** 문의처 원문 ("보건복지상담센터 129 / 주소지 행정복지센터"). 상세에서만 채워진다 */
    val contact: String = "",
    /** 정책 지역 ("전국", "서울" …). 상세에서만 채워진다 */
    val region: String = "",
    /** 나이 조건 (만 나이). 없으면 null */
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    /** 서버가 맞춘 금액 (원). amountCycle: monthly · yearly · once · null */
    val amountWon: Long? = null,
    val amountCycle: String? = null,
    /** 지원 기간 (년). "보호종료 후 5년" 같은 표현에서만 */
    val periodYears: Int? = null,
    /** 같이 보면 좋은 정책 (상세에서만) */
    val relatedPolicies: List<RelatedPolicyItem> = emptyList(),
    /** 같은 종류 중 이 정책의 자리 (상세에서만, 같은 종류를 2개 이상 받을 수 있을 때) */
    val sameKind: SameKindInfo? = null,
    /** 받는 것 한 줄 ("주거(오피스텔) 지원"). 돈이 아닌 정책도 있어서 금액이 없으면 이걸 보여 준다. 상세에서만 */
    val benefit: String? = null,
    /** 나이 · 지역 말고 따로 확인할 대상 조건 (상세에서만) */
    val conditions: List<String> = emptyList(),
    /** 받을 수 없는 경우 (상세에서만) */
    val exclusions: List<String> = emptyList(),
    /** 신청 방법 정리본: (갈래 이름, 쉬운 문장들). 상세에서만. 비어 있으면 applySteps → howToApply 순으로 쓴다 */
    val applyWays: List<Pair<String, List<String>>> = emptyList(),
    /** 신청 기한 (정리본에 있을 때만) */
    val applyDeadline: String? = null,
    /** 신청 방법을 짧게 나눈 구절 (상세에서만). 비어 있으면 howToApply 원문을 정리해서 쓴다 */
    val applySteps: List<String> = emptyList(),
    /** 필요 서류를 짧게 정리한 구절 (상세에서만). 괄호 안은 언제 내는지. 비어 있으면 requiredDocs */
    val docFacts: List<String> = emptyList(),
    /** 들어야 받을 수 있는 교육 (settlement: 자립정착금 의무교육 · finance: 자립수당 자립교육). 상세에서만 */
    val requiredEducation: String? = null,
    /** 앱에서 바로 쓸 수 있는 신청 양식 (상세에서만). 비어 있으면 '신청서 쓰기'를 숨긴다 */
    val forms: List<PolicyFormLink> = emptyList(),
)

/** 정책 신청 양식: 누르면 서류 작성을 이 양식으로 연다. docName: 이 양식으로 쓰는 필요 서류 (없으면 null) */
data class PolicyFormLink(val formId: String, val title: String, val notice: String? = null, val docName: String? = null)

/**
 * 받는 것 한 줄 (목록 · 카드용). 돈이 아닌 정책(주거 · 의료 · 상담)이 많아서 금액만 보여 주지 않는다.
 * 금액이 있으면 금액, 없으면 지원 내용 (상세의 benefit → 검색 카드 요약의 '지원 내용')
 */
val Policy.receives: String
    get() = amountShort.ifBlank {
        benefit ?: cardSummary?.sections?.firstOrNull { it.label == "지원 내용" }?.items?.firstOrNull().orEmpty()
    }

/** 정책 상세의 같이 보면 좋은 정책 한 개 */
data class RelatedPolicyItem(
    val id: String,
    val name: String,
    val agency: String,
    val category: String,
    val amountText: String?,
    /** 받는 것 한 줄 (금액이 없을 때 대신 보여 준다) */
    val benefit: String? = null,
)

/** 홈 '나에게 맞는 지원 정책' 한 개 (서버가 추천 순서대로 준다). category 는 분야 이름 ("주거" …) */
data class RecommendedPolicy(
    val policyId: String,
    val name: String,
    val category: String,
    val eligibility: String,
    val whenText: String,
    /** 현금이면 "매월" · "매년" · "한 번"과 "50만 원". 현금이 아니면 null */
    val amountUnit: String?,
    val amountValue: String?,
    val benefit: String?,
    /** 같은 종류 중 이걸 고른 까닭 ("같은 종류 2개 중 받는 돈이 가장 커요"). 묶인 게 없으면 null */
    val pickReason: String? = null,
)

/** 같은 종류 전체 (다른 지역 것까지). rule: 왜 하나만 받는지 한 줄 */
data class SameKindGroup(
    val kindTitle: String,
    val rule: String,
    val total: Int,
    val picked: SameKindPick?,
    val others: List<SameKindRow>,
)

data class SameKindPick(val policyId: String, val name: String, val region: String, val amountText: String?, val reason: String)

/** mine: 내 조건(나이 · 지역)으로 받을 수 있음 */
data class SameKindRow(val policyId: String, val name: String, val region: String, val amountText: String?, val mine: Boolean)

/** 같은 종류 정책 묶음 안에서의 자리. isBest 가 false 면 best 가 내 조건에서 더 유리하다 */
data class SameKindInfo(
    val kindTitle: String,
    val isBest: Boolean,
    val reason: String,
    val best: RoadmapAlternative,
    val others: List<RoadmapAlternative>,
)

/** 맞춤 신청 로드맵 (3.32): 받을 수 있는 정책을 신청 시점별로. stage: before_leave · at_leave · now */
data class PolicyRoadmap(
    val baseDate: LocalDate?,
    val dDay: Int?,
    /** 조건이 맞는 정책의 현금 합계 (원) */
    val monthlyWon: Long,
    val onceWon: Long,
    val matchCount: Int,
    val stages: List<RoadmapStage>,
    /** 받고 있는 정책 수 · 그 월 금액, 받고 있는 것 말고 더 받을 수 있는 월 · 1회 금액 (3.36) */
    val receivingCount: Int = 0,
    val receivingMonthlyWon: Long = 0,
    val newMonthlyWon: Long = 0,
    val newOnceWon: Long = 0,
)

data class RoadmapStage(val stage: String, val title: String, val items: List<RoadmapItem>)

data class RoadmapItem(
    val policyId: String,
    val name: String,
    val agency: String,
    /** 분야 코드 (housing · medical …) */
    val category: String,
    /** match · needs_check */
    val eligibility: String,
    val amountText: String?,
    val benefit: String?,
    /** 신청 시작일 (퇴소 예정일 기준). null 이면 지금 */
    val applyFrom: LocalDate?,
    val applyNote: String,
    /** 지원이 끝나는 날 (퇴소 예정일 + 기간) */
    val until: LocalDate? = null,
    /** 나이 상한으로 신청할 수 있는 마지막 날 · 상한 (만 29세) */
    val ageUntil: LocalDate? = null,
    val ageMax: Int? = null,
    /** 정책 지역 ("전국", "인천") */
    val region: String = "전국",
    /** 같은 종류 이름 ("자립정착금"). 같은 종류가 없으면 null */
    val kindTitle: String? = null,
    /** 같은 종류 중 이걸 고른 까닭 */
    val pickReason: String? = null,
    /** 같은 종류의 다른 정책 (받는 돈이 큰 순서) */
    val alternatives: List<RoadmapAlternative> = emptyList(),
    /** 지금 받고 있다고 표시한 정책 (새로 신청할 일정에는 넣지 않는다) */
    val receiving: Boolean = false,
    val startedOn: LocalDate? = null,
    /** 들어야 받을 수 있는 교육 (자립정착금 의무교육 등)과 이수 여부. 교육 조건이 없으면 null */
    val education: RoadmapEducation? = null,
)

/** 로드맵 항목의 의무교육 (3.37). note: "자립지원전담기관 의무교육을 먼저 들어야 받을 수 있어요" */
data class RoadmapEducation(val code: String, val name: String, val done: Boolean, val doneOn: LocalDate?, val note: String)

/** 이수한 교육 · 취득한 자격증 (3.37). kind: education · certificate, code: settlement · finance (정해진 교육) · null */
data class Credential(val id: Long, val kind: String, val code: String?, val name: String, val doneOn: LocalDate?) {
    val isEducation: Boolean get() = kind == EDUCATION

    companion object {
        const val EDUCATION = "education"
        const val CERTIFICATE = "certificate"
    }
}

/** 정책 조건과 이어지는 교육 (교육 추가에서 고름) */
data class EducationOption(val code: String, val name: String, val description: String)

data class RoadmapAlternative(val policyId: String, val name: String, val region: String, val amountText: String?)

/** 상담 정책 답변에 붙는 내 조건 요약 (넓은 질문일 때만) */
data class ChatPlan(
    val baseDate: LocalDate?,
    val dDay: Int?,
    val monthlyWon: Long,
    val onceWon: Long,
    val matchCount: Int,
    val items: List<ChatPlanItem>,
)

data class ChatPlanItem(
    val policyId: String,
    val name: String,
    val amountText: String?,
    val benefit: String?,
    val applyFrom: LocalDate?,
    val applyNote: String,
    /** 신청서 작성 도우미 양식이 있음 */
    val hasForm: Boolean,
)

/** 진로 로드맵 (3.33). path: job · college · training */
data class CareerRoadmap(val recommended: String, val paths: List<CareerPathPlan>)

data class CareerPathPlan(val path: String, val title: String, val steps: List<CareerStep>, val policies: List<CareerPolicy>)

/** kind: roleplay(scenarioId) · document(templateId) · link(url, phone) · app(target: interest · risk) · search(query) */
data class CareerStep(
    /** 바뀌지 않는 이름 ("job-jobcenter"). 끝낸 일 · 일정 id 에 쓴다 */
    val stepId: String,
    val kind: String,
    val title: String,
    val detail: String,
    val scenarioId: String?,
    val templateId: String?,
    val url: String?,
    val phone: String?,
    val target: String? = null,
    val query: String? = null,
    /** 펼치면 보이는 '이렇게 해요' 줄 */
    val guide: List<String> = emptyList(),
    /** 내 조건 때문에 더한 할 일이면 그 까닭 */
    val reason: String? = null,
)

data class CareerPolicy(
    val policyId: String,
    val name: String,
    val agency: String,
    val eligibility: String,
    val amountText: String?,
    val benefit: String?,
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
    /** 이 서류를 바로 쓸 수 있는 서버 양식 */
    val forms: List<ChecklistForm> = emptyList(),
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
    /** 신청 예정일 (사용자가 정함). 없으면 null */
    val dueDate: java.time.LocalDate? = null,
    /** 서류 목록에는 없지만 이 정책에 쓰는 서버 양식 */
    val extraForms: List<ChecklistForm> = emptyList(),
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

/** 체크리스트에서 바로 쓰는 서버 양식. notice: 공식 양식 대신 쓰는 양식일 때 서버 안내 */
data class ChecklistForm(val formId: String, val title: String, val notice: String? = null)

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
    /** 신청 예정일. 없으면 null */
    val dueDate: java.time.LocalDate? = null,
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
enum class ActionKind { OPEN_CHECKLIST, START_CHECKLIST, RUN_SIMULATION, SEARCH_POLICY, SEARCH_TOPIC, OPEN_TIMELINE, PRACTICE, EXPLORE_CAREER, ROADMAP, BASIC_MISSION, MONTH_LIFE }

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
