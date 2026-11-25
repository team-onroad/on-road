package com.project.on_road.data

/**
 * 준비 서류가 무엇이고 어디서 받는지 한 줄 설명 (정책 상세 · 체크리스트 서류 목록).
 * 서버 데이터에 설명이 없어서 자주 나오는 서류 이름으로 찾는다. 모르는 서류면 null (설명 없이 이름만).
 * 위에 있는 줄이 먼저 맞으므로, 더 구체적인 이름을 앞에 둔다 (주민등록초본 → 주민등록).
 */
object DocGuide {
    private val guides: List<Pair<List<String>, String>> = listOf(
        listOf("보호종료확인서", "보호종료 확인서") to "보호가 끝났다는 확인서예요. 지냈던 시설이나 시·군·구청에 요청해요.",
        listOf("등기부등본", "등기사항") to "집 주인과 집에 걸린 빚이 나온 서류예요. 인터넷등기소에서 떼요.",
        listOf("주민등록초본", "초본") to "그동안 살았던 주소가 나온 서류예요. 정부24나 주민센터에서 무료로 떼요.",
        listOf("주민등록등본", "등본") to "사는 곳과 함께 사는 사람이 나온 서류예요. 정부24나 주민센터에서 무료로 떼요.",
        listOf("가족관계증명서") to "가족 관계가 나온 서류예요. 정부24나 주민센터에서 떼요.",
        listOf("기본증명서") to "내 출생·이름 같은 기본 정보가 나온 서류예요. 정부24나 주민센터에서 떼요.",
        listOf("수급자 증명서", "수급자증명서") to "기초생활수급자임을 보여 주는 서류예요. 정부24나 주민센터에서 떼요.",
        listOf("임대차계약서", "전세계약서", "월세계약서") to "집 계약서 사본이에요. 확정일자를 받았다면 도장이 찍힌 면도 함께 내요.",
        listOf("통장 사본", "통장사본") to "내 이름 통장의 계좌번호가 나온 첫 장이에요. 은행 앱에서 바로 내려받을 수 있어요.",
        listOf("소득금액증명", "소득 증명", "소득증명") to "1년 동안 번 돈이 나온 서류예요. 홈택스나 세무서에서 떼요.",
        listOf("건강보험") to "건강보험 가입·납부 기록이에요. 국민건강보험 홈페이지나 앱에서 떼요.",
        listOf("재학증명서") to "지금 학교에 다닌다는 서류예요. 학교 홈페이지나 행정실에서 떼요.",
        listOf("졸업증명서", "성적증명서") to "학교에서 떼는 서류예요. 학교 홈페이지나 정부24에서 떼요.",
        listOf("위임장") to "다른 사람이 대신 신청할 때, 내가 맡긴다고 쓰는 서류예요. 기관 양식을 써요.",
        listOf("신분증") to "주민등록증, 운전면허증, 여권 중 하나면 돼요.",
        listOf("증명사진", "사진") to "최근에 찍은 증명사진이에요. 크기는 신청하는 곳 안내를 따라요.",
        listOf("이력서", "자기소개서", "자소서") to "직접 쓰는 서류예요. 서류 작성에서 AI와 같이 쓸 수 있어요.",
        listOf("신청서") to "신청하는 곳에서 주는 양식이에요. 서류 작성에서 같이 채울 수 있어요.",
    )

    fun explain(name: String): String? {
        val n = name.replace(" ", "")
        return guides.firstOrNull { (words, _) -> words.any { n.contains(it.replace(" ", "")) } }?.second
    }

    /**
     * "하나만 내면 되는 서류"의 선택지 한 개 안내 (정책 상세 펼침).
     * org: 발급 기관 (이름 앞에서 떼어 냄), title: 서류 이름, way: 받는 방식 태그 (온라인 · 상담, 모르면 null),
     * rows: 어디서 · 어떻게 · 문의 줄. 확인한 안내가 없는 서류는 rows 가 비어 있다.
     */
    data class Option(val org: String?, val title: String, val way: String?, val rows: List<Pair<String, String>>)

    private val ORGS = listOf("국가아동권리보장원", "아동권리보장원", "서민금융진흥원", "국민연금공단", "한국장학재단", "국민건강보험공단", "근로복지공단", "한국주택금융공사", "한국토지주택공사")

    /** 공식 안내(지자체 · 기관 안내문)로 확인한 선택지 설명. 이름에 들어 있는 낱말로 찾는다 */
    private val OPTIONS: List<Triple<List<String>, String?, List<Pair<String, String>>>> = listOf(
        Triple(listOf("서민금융진흥원", "자립교육"), "온라인", listOf(
            "어디서" to "금융교육포털 edu.kinfa.or.kr",
            "어떻게" to "‘보호종료아동 자립교육’ 강의를 듣고 이수증을 출력해요",
        )),
        Triple(listOf("아동권리보장원", "자립교육"), "온라인", listOf(
            "어디서" to "아동복지통합서비스 사이버교육센터",
            "어떻게" to "보호종료(예정)아동 온라인 자립교육을 마치고 이수증을 받아요",
        )),
        Triple(listOf("희망출발", "재무상담"), "상담", listOf(
            "어떻게" to "국민연금공단 재무상담을 받고 확인서를 받아요",
            "문의" to "국민연금 고객센터 1355",
        )),
        Triple(listOf("주민등록증"), null, listOf("어디서" to "주민센터에서 발급해요")),
        Triple(listOf("여권"), null, listOf("확인" to "유효기간이 남아 있어야 해요")),
        Triple(listOf("운전면허증"), null, listOf("어디서" to "운전면허시험장이나 경찰서에서 발급해요")),
        Triple(listOf("장애인등록증"), null, listOf("어디서" to "주민센터에서 발급해요")),
    )

    fun option(raw: String): Option {
        val name = raw.trim()
        val org = ORGS.firstOrNull { name.startsWith(it) }
        val title = org?.let { name.removePrefix(it).trim() }?.ifBlank { null } ?: name
        val key = name.replace(" ", "")
        val hit = OPTIONS.firstOrNull { (words, _, _) -> words.all { key.contains(it.replace(" ", "")) } }
        val way = hit?.second ?: when {
            key.contains("온라인") || key.contains("사이버") -> "온라인"
            else -> null
        }
        val rows = hit?.third ?: explain(name)?.let { listOf("안내" to it) }.orEmpty()
        return Option(org = if (org == "아동권리보장원") "국가아동권리보장원" else org, title = title, way = way, rows = rows)
    }
}
