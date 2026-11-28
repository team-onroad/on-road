package com.project.on_road.data

/**
 * 상담 탭의 질문 칩. 사람들이 잘 모르는 정책 질문을 눌러서 이어 간다 (AI 없이 규칙).
 * 질문은 실태조사 · 당사자 인터뷰 · 상담센터 FAQ 에 자주 나온 헷갈리는 지점에서 골랐다.
 * - 첫 화면: 내 조건(퇴소 전 · 후, 받는 중, 학생, 동생 · 자녀)에 맞는 질문
 * - 답변 뒤: 방금 나온 정책 종류의 질문 2개 + 내 조건 질문 2개 (이미 보낸 질문은 빼고)
 */
object ChatSuggestions {

    /** 칩을 만들 때 보는 내 조건 */
    data class Context(
        val youngAdult: Boolean,
        val student: Boolean = false,
        val sibling: Boolean = false,
        val child: Boolean = false,
        val receiving: Boolean = false,
        val certificates: Boolean = false,
        /** 사는 시군구 ("서초구"). 구 자체 사업이 있는 곳은 그 사업 이름으로 묻는다 */
        val district: String? = null,
    ) {
        val seocho: Boolean get() = district == "서초구"
    }

    /**
     * 정책 종류별 헷갈리는 질문 (이름에 낱말이 있으면 그 종류). 순서가 우선순위.
     * 실제 AI 로 리허설해서 정책 원문 근거로 답이 나온 표현만 넣는다 (2026-11-27, 로컬 서버).
     * 원문 낱말(서리풀 · 미혼 · 교육비 · 생계급여)을 넣어야 검색이 맞는 정책을 찾는다. "구청 · 등록금 지원 · 결혼"은 근거 없음이 났다
     */
    private val BY_KIND: List<Pair<Regex, List<String>>> = listOf(
        Regex("""교육비|등록금|장학|대학""") to listOf(
            "서리풀 교육비 등록금은 얼마인가요?",
            "학원비도 교육비로 지원되나요?",
        ),
        Regex("""생활보조|희망사다리|서리풀""") to listOf(
            "전입 1년 미만도 서리풀 지원되나요?",
            "생활보조수당 소득 기준은 얼마인가요?",
            "서리풀과 자립수당 중복 수혜되나요?",
        ),
        Regex("""자립\s*(지원)?\s*수당""") to listOf(
            "자립수당 받으면 생계급여 줄어드나요?",
            "재보호 중에도 자립수당이 나오나요?",
            "자립수당 지급일은 언제인가요?",
        ),
        Regex("""(?<!전세)임대\s*주택|매입\s*임대|쉼터퇴소""") to listOf(
            "임대주택은 미혼만 신청할 수 있나요?",
            "임대주택은 최장 몇 년 거주하나요?",
        ),
        Regex("""전세\s*임대|전세주택""") to listOf(
            "전세임대 주택은 직접 구하나요?",
        ),
        Regex("""디딤씨앗|아동발달지원계좌""") to listOf(
            "동생 디딤씨앗 매칭액은 얼마인가요?",
            "디딤씨앗통장 추가 적립 한도는?",
        ),
        Regex("""의료비""") to listOf("의료비 지원 범위는 어디까지인가요?"),
        Regex("""전담기관""") to listOf("자립지원전담기관은 무엇을 돕나요?"),
        Regex("""정착금""") to listOf("자립정착금은 분할 지급인가요?"),
    )

    /** 내 조건 질문 (앞쪽이 먼저 나온다) */
    private fun general(c: Context): List<String> = buildList {
        if (c.youngAdult) {
            if (c.receiving) add("자립수당 받으면 생계급여 줄어드나요?")
            if (c.seocho) {
                add("서리풀과 자립수당 중복 수혜되나요?")
                add("전입 1년 미만도 서리풀 지원되나요?")
            }
            if (c.sibling) add("동생 디딤씨앗 매칭액은 얼마인가요?")
            if (c.student && c.seocho) add("서리풀 교육비 등록금은 얼마인가요?")
            add("임대주택은 미혼만 신청할 수 있나요?")
            add("자립지원전담기관은 무엇을 돕나요?")
            add("의료비 지원 범위는 어디까지인가요?")
        } else {
            add("보호종료 전 사전 신청 제도는?")
            add("보호연장 시 자립수당도 받나요?")
            add("자립정착금은 분할 지급인가요?")
            if (c.student) add("대학 진학 시 등록금 지원은?")
            add("보호종료확인서 발급처는 어디인가요?")
        }
    }

    /** 칩 글자 수 상한 (입력창 아래 자동완성처럼 한 줄에 보이게) */
    const val MAX_LENGTH = 20

    /** 모든 질문 (글자 수 확인용) */
    fun allQuestions(): List<String> {
        val every = Context(youngAdult = true, student = true, sibling = true, child = true, receiving = true, certificates = true, district = "서초구")
        return (BY_KIND.flatMap { it.second } + general(every) + general(every.copy(youngAdult = false))).distinct()
    }

    /** 첫 인사 아래 칩 (4개) */
    fun starters(c: Context): List<String> = general(c).distinct().take(4)

    /**
     * 답변 아래 칩 (최대 4개). policies: 방금 답에 나온 정책 이름 (앞쪽이 중심 정책).
     * asked: 지금까지 보낸 질문
     */
    fun followUps(c: Context, policies: List<String>, asked: List<String>): List<String> {
        val done = asked.map(::norm).toSet()
        fun fresh(list: List<String>) = list.filter { norm(it) !in done }
        // 답에 나온 정책 종류마다 질문을 모아, 앞 정책 것부터 2개
        val about = policies.flatMap { name -> BY_KIND.filter { it.first.containsMatchIn(name) }.flatMap { it.second } }
        val first = fresh(about.distinct()).take(2)
        val rest = fresh(general(c)).filterNot { it in first }
        return (first + rest).distinct().take(4)
    }

    private fun norm(s: String) = s.replace(" ", "").replace("?", "").replace(",", "")
}
