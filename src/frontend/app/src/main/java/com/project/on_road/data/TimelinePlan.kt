package com.project.on_road.data

import java.time.LocalDate

/*
 * 퇴소 D-day 타임라인 개인화 (앱 안에서만 계산, 서버 · DB 변경 없음)
 * - 퇴소일 기준 기본 할 일 + 퇴소 후 첫 30일 동행 (날짜를 촘촘하게)
 * - 지금 하는 일(학생 · 일함 · 구직 중 · 쉬는 중)에 따라 할 일을 더한다
 * - 사는 지역으로 자립정착금 금액을 알려 준다
 * - 진행 중인 체크리스트 · 저장만 한 정책은 '이번 주'에 올린다 (마감일은 아직 데이터에 없음)
 * - 할 일마다 바로 할 수 있는 기능(전화 연습 · 서류 위험 찾기 · 계산기 · 체크리스트)을 잇는다
 * - 체크리스트로 신청을 끝낸 정책은 관련 할 일을 자동으로 끝낸다 (예: 자립수당 신청 완료 → '자립수당 신청 확인')
 * - 자립 준비도에서 가장 약한 영역의 다음 할 일을 '이번 주'에 올린다
 * - 자립수당 · 사후관리가 끝나는 5년 뒤를 미리 알려 준다
 * - 오래 지난 할 일은 '놓친 일'에 쌓아 두지 않고 지난 일로 넘긴다
 */

/** 지금 하는 일. 가입 때 받지 않고 타임라인에서 고른다 (서버에 보내지 않음) */
enum class WorkStatus(val label: String) {
    STUDENT("학생"),
    WORKING("일해요"),
    JOB_SEEKING("일 찾는 중"),
    RESTING("쉬는 중"),
}

/** 할 일에서 바로 갈 곳. 화면 이동 경로는 UI 에서 정한다 */
sealed interface PlanGo {
    data class Practice(val scenarioId: String) : PlanGo
    data object RiskCheck : PlanGo
    data class Checklist(val policyId: String) : PlanGo
    data class PolicySearch(val query: String) : PlanGo
    data object Budget : PlanGo
    data object Housing : PlanGo
    data object Savings : PlanGo
    data object MonthLife : PlanGo
    data object Forms : PlanGo
    data class Chat(val question: String?) : PlanGo
}

data class PlanAction(val label: String, val go: PlanGo)

enum class PlanKind { BASE, WORK, POLICY }

data class PlanTask(
    val id: String,
    /** 퇴소일 기준 (정책 할 일은 null = 지금) */
    val offsetDays: Int?,
    val date: LocalDate,
    val title: String,
    val description: String,
    val actions: List<PlanAction> = emptyList(),
    val kind: PlanKind = PlanKind.BASE,
    /** 날짜 대신 보여 줄 말 ("진행 중인 신청" 등). null 이면 D-n · 날짜 */
    val label: String? = null,
)

/** 진행 중인 정책 신청 (체크리스트) 또는 저장만 한 정책 */
data class PlanPolicy(val policyId: String, val name: String, val nextStep: String?, val completed: Boolean = false)

/** 자립 준비도에서 가장 약한 영역 (label: "돈 관리" 등) 과 그 다음 할 일 */
data class PlanWeakArea(val label: String, val step: NextStep)

/** 개인화에 쓰는 내 정보 (모두 없어도 된다) */
data class PlanInput(
    val leaveDate: LocalDate,
    val today: LocalDate,
    val region: String? = null,
    val work: WorkStatus? = null,
    val policies: List<PlanPolicy> = emptyList(),
    val weak: PlanWeakArea? = null,
)

/** 할 일이 자동으로 끝난 까닭 (체크리스트 완료 등) */
data class AutoDone(val taskId: String, val reason: String)

object TimelinePlanner {

    /**
     * 자립정착금 1인당 지급 기준 (아동권리보장원 「2026 자립정보북」, 2026년 1월 지자체 조사).
     * 시설이 있는 시·도에서 주는 것이 원칙이라, 사는 곳과 다르면 시설 지역 금액을 봐야 한다.
     */
    private val settlementManwon = mapOf(
        "서울" to 2000,
        "부산" to 1500, "대전" to 1500, "경기" to 1500, "경남" to 1500, "제주" to 1500,
    )
    private const val SETTLEMENT_DEFAULT = 1000

    /** 지난 할 일을 '놓친 일'로 보여 주는 기간. 더 오래된 일은 지난 일로 넘긴다 */
    const val MISSED_DAYS = 30

    /** 자립수당 · 사후관리 지원 기간 (보호종료 후 5년) */
    private const val SUPPORT_YEARS = 5L

    /** 체크리스트로 신청을 끝낸 정책 이름 → 함께 끝나는 할 일 */
    private val doneByPolicy = listOf(
        "자립수당" to listOf("allowance"),
        "자립정착금" to listOf("settlement"),
        "의료비" to listOf("health"),
        "전세임대" to listOf("house"),
        "공공임대" to listOf("house"),
        "내일배움" to listOf("w-card"),
        "장학" to listOf("w-scholarship"),
    )

    /** 신청을 끝낸 정책 때문에 자동으로 끝난 할 일 */
    fun autoDone(policies: List<PlanPolicy>): List<AutoDone> =
        policies.filter { it.completed }.flatMap { p ->
            doneByPolicy.filter { (word, _) -> word in p.name }.flatMap { (_, ids) -> ids.map { AutoDone(it, "${p.name} 신청을 끝냈어요") } }
        }.distinctBy { it.taskId }

    private fun goOf(step: NextStep): PlanGo? = when (step.kind) {
        "search" -> PlanGo.PolicySearch(step.query ?: step.label)
        "practice" -> step.scenarioId?.let { PlanGo.Practice(it) }
        "budget" -> PlanGo.Budget
        "month" -> PlanGo.MonthLife
        "forms" -> PlanGo.Forms
        "chat" -> PlanGo.Chat(step.query)
        else -> null
    }

    fun settlement(region: String?): Int? = region?.let { settlementManwon[it] ?: SETTLEMENT_DEFAULT }

    private data class Rule(
        val id: String,
        val offset: Int,
        val title: String,
        val description: String,
        val actions: List<PlanAction> = emptyList(),
    )

    /** 기본 할 일. id 는 예전 타임라인과 같게 둬서 체크해 둔 기록이 그대로 이어진다 */
    private fun baseRules(region: String?): List<Rule> {
        val money = settlement(region)
        val settlementText = if (money != null) {
            "$region 기준 1인당 ${"%,d".format(money)}만 원이에요. 시설이 있는 시·도에서 주니, 지역이 다르면 그곳 금액을 확인해요."
        } else {
            "지역마다 1,000만~2,000만 원이에요. 시설이나 지자체를 통해 준비해요."
        }
        return listOf(
            Rule("counsel", -180, "자립지원전담기관 연락처 알아두기", "퇴소 후에도 상담과 도움을 받을 수 있는 곳이에요.",
                listOf(PlanAction("전담기관 찾기", PlanGo.PolicySearch("자립지원전담기관 상담")))),
            Rule("bank", -150, "내 이름 통장과 체크카드 만들기", "수당과 월급을 받을 통장이 필요해요. 신분증을 챙겨 은행에 가요.",
                listOf(PlanAction("은행 연습", PlanGo.Practice("bank")))),
            Rule("saving", -120, "모은 돈과 앞으로 모을 돈 점검하기", "디딤씨앗통장 등 지금까지 모은 돈을 확인해요.",
                listOf(PlanAction("저축 목표", PlanGo.Savings))),
            Rule("house", -90, "살 곳 알아보기", "공공임대, 전세임대, 월세를 비교하고 신청 일정을 확인해요.",
                listOf(PlanAction("주거비 비교", PlanGo.Housing), PlanAction("원룸 계약 연습", PlanGo.Practice("realestate")))),
            Rule("settlement", -60, "자립정착금 준비하기", settlementText,
                listOf(PlanAction("정책 보기", PlanGo.PolicySearch("자립정착금")))),
            Rule("docs", -45, "필요한 서류 떼는 법 익히기", "주민등록등본, 가족관계증명서를 정부24에서 직접 떼어 봐요."),
            Rule("lease-sign", -21, "집 계약서 서명 전에 살펴보기", "등기부등본과 계약서를 찍어 올리면 숨은 위험을 찾아 줘요.",
                listOf(PlanAction("위험 찾기", PlanGo.RiskCheck))),
            Rule("budget", -30, "첫 달 생활비 나눠 보기", "월세, 식비, 교통비를 미리 나눠 봐요.",
                listOf(PlanAction("생활비 배분", PlanGo.Budget))),
            Rule("phone", -14, "휴대폰 명의와 요금제 확인하기", "내 명의인지, 요금이 부담되지 않는지 확인해요.",
                listOf(PlanAction("개통 연습", PlanGo.Practice("phone-store")))),
            Rule("move", -7, "짐 정리하고 이사 준비하기", "이삿짐과 생활용품 목록을 만들어요."),
            Rule("dday", 0, "퇴소하는 날", "새로운 시작이에요. 필요한 연락처를 한 번 더 확인해요."),
            // 퇴소 후 첫 30일 동행
            Rule("tell-center", 1, "전담기관에 퇴소했다고 알리기", "사는 곳과 연락처를 알려 두면 필요한 도움을 먼저 챙겨 줘요.",
                listOf(PlanAction("전담기관 찾기", PlanGo.PolicySearch("자립지원전담기관 상담")))),
            Rule("utilities", 3, "전기·가스·수도 명의 바꾸기", "이사한 집의 요금을 내 이름으로 바꿔요. 관리사무소나 집주인에게 물어봐요."),
            Rule("lease-recheck", 3, "집 계약서 다시 확인하기", "잔금 낸 뒤 등기부등본을 한 번 더 떼어 새 빚이 생겼는지 봐요.",
                listOf(PlanAction("위험 찾기", PlanGo.RiskCheck))),
            Rule("register", 7, "전입신고와 확정일자 받기", "이사하고 14일 안에 주민센터에서 해요. 확정일자는 보증금을 지켜 줘요.",
                listOf(PlanAction("주민센터 연습", PlanGo.Practice("community-center")))),
            Rule("split-settlement", 7, "정착금 나눠 쓰기 계획 세우기", "보증금, 몇 달치 생활비, 비상금으로 나눠 두면 오래 버틸 수 있어요.",
                listOf(PlanAction("한 달 살아보기", PlanGo.MonthLife))),
            Rule("health", 10, "건강보험 자격 확인하기", "주소가 바뀌면 자격이 달라질 수 있어요. 병원비 지원도 같이 확인해요.",
                listOf(PlanAction("의료비 지원", PlanGo.PolicySearch("자립준비청년 의료비 지원")))),
            Rule("allowance", 14, "자립수당 신청 확인하기", "주민센터나 복지로에서 신청해요. 이미 받고 있다면 통장으로 들어오는지 봐요.",
                listOf(PlanAction("정책 보기", PlanGo.PolicySearch("자립수당")))),
            Rule("first-spend", 21, "3주 동안 쓴 돈 돌아보기", "계획과 실제 지출이 얼마나 다른지 맞춰 봐요.",
                listOf(PlanAction("생활비 배분", PlanGo.Budget))),
            Rule("first-month", 30, "첫 달 잘 지냈는지 점검하기", "어려웠던 점을 적어 두고 전담기관 상담 때 이야기해요.",
                listOf(PlanAction("상담 정책", PlanGo.PolicySearch("자립지원전담기관 상담")))),
            Rule("followup", 90, "전담기관 사후관리 상담 받기", "지내면서 어려운 점을 이야기하고 필요한 도움을 연결받아요.",
                listOf(PlanAction("전담기관 찾기", PlanGo.PolicySearch("자립지원전담기관 상담")))),
        )
    }

    private fun workRules(work: WorkStatus?): List<Rule> = when (work) {
        WorkStatus.STUDENT -> listOf(
            Rule("w-scholarship", -60, "장학금·학자금 신청 일정 확인하기", "한국장학재단 신청 기간은 학기마다 정해져 있어요. 놓치지 않게 미리 봐요.",
                listOf(PlanAction("장학금 찾기", PlanGo.PolicySearch("자립준비청년 장학금")))),
            Rule("w-school", 10, "학교에 바뀐 주소 알리기", "학적 정보와 장학금 계좌를 새 주소·통장으로 바꿔요."),
        )
        WorkStatus.WORKING -> listOf(
            Rule("w-contract", -21, "근로계약서 살펴보기", "최저임금, 주휴수당, 그만둘 때 조건을 서명 전에 확인해요.",
                listOf(PlanAction("위험 찾기", PlanGo.RiskCheck))),
            Rule("w-payslip", 30, "첫 월급 명세서 확인하기", "4대보험이 들어갔는지, 약속한 시급대로 받았는지 봐요.",
                listOf(PlanAction("알바비 연습", PlanGo.Practice("wage")))),
        )
        WorkStatus.JOB_SEEKING -> listOf(
            Rule("w-card", -60, "국민내일배움카드 신청하기", "직업훈련비를 지원받을 수 있어요. 고용24에서 신청해요.",
                listOf(PlanAction("정책 보기", PlanGo.PolicySearch("국민내일배움카드")))),
            Rule("w-jobcenter", 14, "고용센터 취업 상담 받기", "자립준비청년 취업 지원을 같이 알아봐 줘요.",
                listOf(PlanAction("상담 연습", PlanGo.Practice("jobcenter")))),
        )
        WorkStatus.RESTING -> listOf(
            Rule("w-plan", 14, "앞으로 계획 같이 세우기", "전담기관 상담에서 쉬는 동안 받을 수 있는 지원과 다음 계획을 이야기해요.",
                listOf(PlanAction("상담 정책", PlanGo.PolicySearch("자립지원전담기관 상담")))),
        )
        null -> emptyList()
    }

    /** 내 타임라인. 날짜순 (같은 날이면 정책 → 기본 → 일 순서) */
    fun plan(input: PlanInput): List<PlanTask> {
        val leaveDate = input.leaveDate
        val today = input.today
        fun task(r: Rule, kind: PlanKind) =
            PlanTask(r.id, r.offset, leaveDate.plusDays(r.offset.toLong()), r.title, r.description, r.actions, kind)

        val base = baseRules(input.region).map { task(it, PlanKind.BASE) }
        val byWork = workRules(input.work).map { task(it, PlanKind.WORK) }
        // 진행 중인 신청 · 저장만 한 정책 (끝낸 신청은 할 일로 올리지 않는다)
        val byPolicy = input.policies.filter { !it.completed }.map { p ->
            if (p.nextStep != null) {
                PlanTask("cl-${p.policyId}", null, today, "${p.name} 신청 이어서 하기", "다음 단계 · ${p.nextStep}",
                    listOf(PlanAction("체크리스트", PlanGo.Checklist(p.policyId))), PlanKind.POLICY, "진행 중인 신청")
            } else {
                PlanTask("sp-${p.policyId}", null, today, "${p.name} 신청 준비 시작하기", "저장해 둔 정책이에요. 마감일은 공고에서 확인해요.",
                    listOf(PlanAction("준비 시작", PlanGo.Checklist(p.policyId))), PlanKind.POLICY, "저장한 정책")
            }
        }
        // 자립 준비도에서 가장 약한 영역
        val byWeak = input.weak?.let { w ->
            PlanTask("weak-${w.label}", null, today, "‘${w.label}’ 준비 채우기", "자립 준비도에서 가장 낮은 영역이에요. ${w.step.label}",
                listOfNotNull(goOf(w.step)?.let { PlanAction("바로 하기", it) }), PlanKind.POLICY, "자립 준비도")
        }
        // 5년 지원이 끝나기 석 달 전
        val endDate = leaveDate.plusYears(SUPPORT_YEARS)
        val byEnd = PlanTask(
            "support-end", null, endDate.minusDays(90), "자립수당·사후관리 끝나기 전 준비하기",
            "퇴소하고 5년이 되는 ${endDate.year}년 ${endDate.monthValue}월에 자립수당과 사후관리가 끝나요. 그 뒤 생활비 계획을 미리 세워요.",
            listOf(PlanAction("생활비 배분", PlanGo.Budget)), PlanKind.BASE,
        )
        val order = mapOf(PlanKind.POLICY to 0, PlanKind.BASE to 1, PlanKind.WORK to 2)
        return (base + byWork + byPolicy + listOfNotNull(byWeak) + byEnd)
            .sortedWith(compareBy({ it.date }, { order.getValue(it.kind) }))
    }
}
