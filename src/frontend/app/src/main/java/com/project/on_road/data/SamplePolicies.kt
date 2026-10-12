package com.project.on_road.data

// 데모용 예시 데이터. 실제 금액·조건은 백엔드(공식 자료 기반 RAG) 응답으로 대체된다.
object SamplePolicies {
    private const val CHECKED = "2026.09 (예시)"
    private const val BOKJIRO = "https://www.bokjiro.go.kr"

    val all = listOf(
        Policy(
            id = "jarip-allowance",
            name = "자립수당",
            category = "생활·금융",
            agency = "보건복지부",
            checkedAt = CHECKED,
            summary = "보호종료 후 5년 이내인 자립준비청년 중 보호기간 등 요건을 충족한 사람에게 매월 자립수당을 지급합니다.",
            easySummary = "보호가 끝난 뒤 5년 동안 매달 생활비를 받을 수 있어요. 보호받은 기간 같은 조건이 맞아야 해요.",
            target = "보호종료 후 5년 이내, 보호기간 요건을 충족한 자립준비청년",
            amount = "월 50만 원",
            period = "보호종료 후 최대 5년",
            howToApply = "주민센터 방문 또는 복지로 온라인 신청",
            applyUrl = BOKJIRO,
            requiredDocs = listOf("신분증", "보호종료확인서", "본인 명의 통장 사본"),
            evidence = listOf(
                Evidence("보건복지부 자립준비청년 지원 안내 (예시)", "자립수당은 보호종료 후 5년 이내인 자립준비청년에게 월 50만 원을 지급한다."),
                Evidence("복지로 서비스 안내 (예시)", "신청은 주소지 읍·면·동 주민센터 방문 또는 복지로 온라인으로 가능하다.")
            ),
            keywords = listOf("수당", "생활비", "매달", "매월", "지원금", "돈", "용돈")
        ),
        Policy(
            id = "settlement",
            name = "자립정착금",
            category = "생활·금융",
            agency = "시·도",
            checkedAt = CHECKED,
            summary = "보호종료 시 자립에 필요한 초기 정착 비용을 지원합니다. 지급액은 시·도별로 다릅니다.",
            easySummary = "보호가 끝날 때 새 생활을 시작하는 데 필요한 목돈을 한 번 받아요. 사는 지역마다 금액이 달라요.",
            target = "보호종료(예정) 자립준비청년",
            amount = "지역별 상이 (약 1,000만~2,000만 원)",
            period = "보호종료 시 1회",
            howToApply = "보호종료 전 시설 또는 지자체를 통해 신청",
            applyUrl = BOKJIRO,
            requiredDocs = listOf("신분증", "보호종료(예정)확인서", "본인 명의 통장 사본", "자립 계획서"),
            evidence = listOf(
                Evidence("시·도 자립정착금 지원 기준 (예시)", "자립정착금은 보호종료 시 지급하며, 지급액은 시·도 여건에 따라 달리 정한다.")
            ),
            keywords = listOf("정착금", "목돈", "초기", "이사", "퇴소")
        ),
        Policy(
            id = "didim",
            name = "디딤씨앗통장",
            category = "생활·금융",
            agency = "보건복지부",
            checkedAt = CHECKED,
            summary = "보호대상아동이 저축하면 정부가 적립액의 일정 배수를 매칭하여 적립하는 자산형성 지원 사업입니다.",
            easySummary = "내가 통장에 돈을 모으면 정부가 더 얹어서 같이 모아 줘요. 나중에 자립할 때 쓸 수 있어요.",
            target = "보호대상아동 등",
            amount = "적립액의 2배 매칭 (월 10만 원 한도)",
            period = "가입 후 만기까지",
            howToApply = "시설 또는 지자체를 통해 가입, 보호종료 후 해지·수령",
            applyUrl = BOKJIRO,
            requiredDocs = listOf("신분증", "가입 확인서", "본인 명의 통장 사본"),
            evidence = listOf(
                Evidence("아동발달지원계좌 운영 안내 (예시)", "아동이 적립한 금액에 대해 월 10만 원 한도 내에서 1:2로 매칭하여 지원한다.")
            ),
            keywords = listOf("통장", "저축", "적금", "모으", "자산")
        ),
        Policy(
            id = "lh-housing",
            name = "공공임대주택 우선공급",
            category = "주거",
            agency = "LH 한국토지주택공사",
            checkedAt = CHECKED,
            summary = "자립준비청년에게 공공임대주택을 우선 공급하여 시세보다 낮은 임대료로 거주할 수 있도록 지원합니다.",
            easySummary = "나라에서 관리하는 집을 먼저 신청할 수 있어요. 보통 월세보다 싸게 살 수 있어요.",
            target = "자립준비청년",
            amount = "시세보다 낮은 임대료",
            period = "재계약 시 연장 가능",
            howToApply = "LH 청약플러스 온라인 신청",
            applyUrl = "https://apply.lh.or.kr",
            requiredDocs = listOf("신분증", "보호종료확인서", "주민등록등본", "소득 확인 서류"),
            evidence = listOf(
                Evidence("LH 청년 주거지원 공고 (예시)", "보호종료아동 등 자립준비청년은 공공임대주택 입주자 선정 시 우선공급 대상에 포함된다.")
            ),
            keywords = listOf("집", "주거", "월세", "임대", "방", "살 곳", "주택")
        ),
        Policy(
            id = "jeonse",
            name = "전세임대 지원",
            category = "주거",
            agency = "LH 한국토지주택공사",
            checkedAt = CHECKED,
            summary = "자립준비청년이 원하는 주택을 구하면 LH가 전세계약을 체결한 뒤 저렴하게 재임대합니다.",
            easySummary = "내가 살고 싶은 집을 고르면 LH가 전세로 계약해서 싸게 빌려줘요.",
            target = "자립준비청년",
            amount = "전세보증금 지원 (일정 나이까지 임대료 부담 완화)",
            period = "재계약 시 연장 가능",
            howToApply = "LH 청약플러스 온라인 신청",
            applyUrl = "https://apply.lh.or.kr",
            requiredDocs = listOf("신분증", "보호종료확인서", "주민등록등본"),
            evidence = listOf(
                Evidence("LH 전세임대 공급 안내 (예시)", "입주대상자가 거주할 주택을 물색하면 LH가 전세계약을 체결한 후 재임대한다.")
            ),
            keywords = listOf("전세", "보증금", "집", "방", "주거")
        ),
        Policy(
            id = "medical",
            name = "의료비 지원",
            category = "건강",
            agency = "보건복지부",
            checkedAt = CHECKED,
            summary = "보호종료 후 일정 기간 자립준비청년의 본인부담 의료비 일부를 지원합니다.",
            easySummary = "병원에 갔을 때 내야 하는 돈 일부를 나라에서 대신 내 줘요.",
            target = "보호종료 후 5년 이내 자립준비청년",
            amount = "본인부담 의료비 일부 지원",
            period = "보호종료 후 5년 이내",
            howToApply = "주민센터 문의 후 신청",
            applyUrl = BOKJIRO,
            requiredDocs = listOf("신분증", "보호종료확인서"),
            evidence = listOf(
                Evidence("자립준비청년 의료비 지원 안내 (예시)", "보호종료 5년 이내 자립준비청년의 본인부담 의료비를 의료급여 수준으로 지원한다.")
            ),
            keywords = listOf("병원", "의료", "아프", "아파", "건강", "치료", "약")
        ),
        Policy(
            id = "counsel",
            name = "자립지원전담기관 사후관리",
            category = "상담",
            agency = "시·도 자립지원전담기관",
            checkedAt = CHECKED,
            summary = "시·도 자립지원전담기관에서 보호종료 후 5년간 연락·상담·사례관리를 제공합니다.",
            easySummary = "혼자 지내다 막막할 때 전담 선생님과 상담하고 도움을 연결받을 수 있어요.",
            target = "보호종료 후 5년 이내 자립준비청년",
            amount = "무료",
            period = "보호종료 후 5년",
            howToApply = "거주 지역 자립지원전담기관에 연락",
            applyUrl = BOKJIRO,
            requiredDocs = listOf("신분증"),
            evidence = listOf(
                Evidence("자립준비청년 사후관리 지침 (예시)", "자립지원전담기관은 보호종료 후 5년간 자립준비청년의 사후관리를 수행한다.")
            ),
            keywords = listOf("상담", "고민", "심리", "도움", "연락", "외로", "막막")
        ),
        Policy(
            id = "employment",
            name = "국민취업지원제도",
            category = "취업",
            agency = "고용노동부",
            checkedAt = CHECKED,
            summary = "취업을 원하는 사람에게 취업지원 서비스와 구직활동 기간 중 생계 지원을 제공합니다.",
            easySummary = "일자리를 찾는 동안 상담, 직업훈련, 구직 활동을 도와주고 조건에 따라 수당도 받을 수 있어요.",
            target = "취업을 원하는 청년 등 (소득 요건 확인 필요)",
            amount = "취업지원 서비스 및 조건부 구직촉진수당",
            period = "참여 기간 중",
            howToApply = "고용24 온라인 신청 또는 고용센터 방문",
            applyUrl = "https://www.work24.go.kr",
            requiredDocs = listOf("신분증", "구직 신청서", "소득 확인 서류"),
            evidence = listOf(
                Evidence("고용노동부 국민취업지원제도 안내 (예시)", "취업을 원하는 사람에게 취업지원서비스를 종합적으로 제공하고 저소득 구직자에게는 생계를 지원한다.")
            ),
            keywords = listOf("취업", "일자리", "알바", "직업", "훈련", "구직", "회사")
        )
    )

    fun find(id: String): Policy? = all.find { it.id == id }
}
