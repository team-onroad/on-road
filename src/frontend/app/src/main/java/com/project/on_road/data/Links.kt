package com.project.on_road.data

/**
 * 기능끼리 잇는 규칙 (앱 안).
 * - 서류를 내보내면 → 제출할 곳 연습 (주민센터·은행·면접 …)
 * - 실전 연습 · 한 달 살아보기 결과 → 관심 분야 신호 (홈 '지금 할 일' 추천에 쓰임)
 * 롤플레잉 뒤 이어서 해 볼 것은 서버(roleplay_scenarios.json follow_ups)가 정한다.
 */
object Links {
    /** 서류 제목 → 내기 전에 연습해 볼 상황 (scenarioId, 상황 이름) */
    private val DOC_PRACTICE = listOf(
        listOf("전입", "확정일자", "주민센터") to ("community-center" to "주민센터 전입신고"),
        listOf("통장", "계좌", "은행", "금융") to ("bank" to "은행 계좌 개설"),
        listOf("임대", "주거", "월세", "전세", "주택") to ("realestate" to "원룸 계약하기"),
        listOf("장학", "입학", "진학") to ("school" to "진학 면접"),
        listOf("입사", "이력서", "취업", "자기소개") to ("job" to "취업 면접"),
        listOf("구직", "취업지원", "훈련", "고용") to ("jobcenter" to "고용센터 취업 상담"),
        listOf("의료", "진료", "병원") to ("hospital" to "병원 진료 받기"),
    )

    fun practiceForDoc(title: String): Pair<String, String>? {
        val t = title.replace(" ", "")
        return DOC_PRACTICE.firstOrNull { (words, _) -> words.any { t.contains(it) } }?.second
    }

    /** 서류를 내보낸 뒤 이어서 해 볼 것 */
    fun afterDocExport(title: String): List<NextStep> = buildList {
        practiceForDoc(title)?.let { (id, name) -> add(NextStep("practice", "내러 가기 전에 ‘$name’ 연습하기", scenarioId = id)) }
        add(NextStep("chat", "제출할 때 챙길 것 물어보기", query = "‘$title’ 낼 때 같이 챙겨야 할 서류랑 주의할 점 알려줘"))
    }

    /** 관심 분야 → 이어서 해 볼 것 (상담 · 정책에서 씀) */
    fun forTopic(topic: Topic): List<NextStep> = when (topic) {
        Topic.HOUSING -> listOf(
            NextStep("practice", "원룸 계약하기 연습하기", scenarioId = "realestate"),
            NextStep("month", "이 집으로 한 달 살아보기"),
        )
        Topic.MONEY -> listOf(
            NextStep("budget", "한 달 생활비 나눠 보기"),
            NextStep("practice", "수상한 전화 대처 연습하기", scenarioId = "scam-call"),
        )
        Topic.JOB -> listOf(
            NextStep("practice", "취업 면접 연습하기", scenarioId = "job"),
            NextStep("practice", "고용센터 취업 상담 연습하기", scenarioId = "jobcenter"),
        )
        Topic.STUDY -> listOf(
            NextStep("practice", "진학 면접 연습하기", scenarioId = "school"),
            NextStep("search", "장학금 찾아보기", query = "자립준비청년 장학금 교육 지원"),
        )
        Topic.HEALTH -> listOf(
            NextStep("practice", "병원 진료 받기 연습하기", scenarioId = "hospital"),
            NextStep("search", "의료비 지원 찾아보기", query = "자립준비청년 의료비 지원"),
        )
        Topic.MIND -> listOf(
            NextStep("search", "무료 마음 상담 찾아보기", query = "자립준비청년 심리상담 지원"),
            NextStep("chat", "요즘 마음 이야기하기", query = "요즘 마음이 힘든데 이야기 좀 들어줄래?"),
        )
    }

    /** 정책 (분야 이름) → 이어서 해 볼 것: 신청하러 갈 곳 연습 + 관련 기능 */
    fun forPolicy(category: String, name: String): List<NextStep> {
        val topic = Topic.ofPolicyCategory(category) ?: Topic.classify(name).firstOrNull()
        val practice = when (topic) {
            Topic.HOUSING -> NextStep("practice", "주민센터 전입신고 연습하기", scenarioId = "community-center")
            Topic.MONEY -> NextStep("practice", "은행 계좌 개설 연습하기", scenarioId = "bank")
            Topic.JOB -> NextStep("practice", "고용센터 취업 상담 연습하기", scenarioId = "jobcenter")
            Topic.STUDY -> NextStep("practice", "진학 면접 연습하기", scenarioId = "school")
            Topic.HEALTH -> NextStep("practice", "병원 진료 받기 연습하기", scenarioId = "hospital")
            else -> null
        }
        return listOfNotNull(
            NextStep("chat", "‘$name’ 궁금한 점 물어보기", query = "‘$name’ 신청할 때 주의할 점이랑 자주 묻는 것 알려줘"),
            practice,
            NextStep("forms", "신청서 같이 쓰기"),
            if (topic == Topic.MONEY || topic == Topic.HOUSING) NextStep("month", "이 지원을 받으면서 한 달 살아보기") else null,
        ).take(3)
    }

    /** 생활비 배분 결과 → 부족한 곳에 맞춰 */
    fun afterBudget(shortages: List<BudgetCategory>): List<NextStep> = buildList {
        if (BudgetCategory.HOUSING in shortages) add(NextStep("search", "청년 월세 지원 찾아보기", query = "청년 월세 지원 주거"))
        if (BudgetCategory.FOOD in shortages) add(NextStep("chat", "식비 아끼는 법 물어보기", query = "혼자 살 때 식비를 아끼는 현실적인 방법 알려줘"))
        if (BudgetCategory.PHONE in shortages) add(NextStep("practice", "휴대폰 개통하기 연습하기", scenarioId = "phone-store"))
        add(NextStep("month", "이 계획으로 한 달 살아보기"))
        add(NextStep("practice", "은행 계좌 개설 연습하기", scenarioId = "bank"))
    }.take(3)

    /** 주거비 비교 뒤 */
    fun afterHousing(): List<NextStep> = listOf(
        NextStep("practice", "원룸 계약하기 연습하기", scenarioId = "realestate"),
        NextStep("search", "공공임대·전세임대 찾아보기", query = "자립준비청년 주거 지원 공공임대 전세임대"),
        NextStep("month", "고른 집으로 한 달 살아보기"),
    )

    /** 저축 목표 계산 뒤 */
    fun afterSavings(): List<NextStep> = listOf(
        NextStep("search", "청년 저축 지원 찾아보기", query = "자립준비청년 자산형성 저축 지원"),
        NextStep("budget", "저축할 돈 떼고 생활비 나눠 보기"),
        NextStep("practice", "은행 계좌 개설 연습하기", scenarioId = "bank"),
    )

    /** 진로 로드맵 뒤 */
    fun afterCareer(jobName: String?): List<NextStep> = listOfNotNull(
        NextStep("practice", "아르바이트 면접 연습하기", scenarioId = "parttime"),
        NextStep("practice", "취업 면접 연습하기", scenarioId = "job"),
        jobName?.let { NextStep("search", "‘$it’ 관련 훈련·취업 지원 찾아보기", query = "$it 직업훈련 취업 지원 청년") },
    )

    /** 실전 연습 상황 → 관심 분야 */
    fun topicOfScenario(scenarioId: String): Topic? = when (scenarioId) {
        "community-center", "landlord", "realestate", "neighbor" -> Topic.HOUSING
        "bank", "scam-call", "phone-store" -> Topic.MONEY
        "parttime", "job", "wage", "jobcenter" -> Topic.JOB
        "school" -> Topic.STUDY
        "hospital" -> Topic.HEALTH
        else -> null
    }

    fun stepKindLabel(kind: String): String = when (kind) {
        "search" -> "정책 찾기"
        "practice" -> "실전 연습"
        "budget" -> "생활비 배분"
        "month" -> "한 달 살아보기"
        "forms" -> "서류 작성"
        "chat" -> "AI 상담"
        else -> "바로 가기"
    }
}
