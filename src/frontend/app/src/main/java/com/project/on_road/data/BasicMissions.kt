package com.project.on_road.data

/*
 * 청소년 자립 기초 미션.
 * 짧은 설명 → 알아 둘 것 → 확인 퀴즈 1문제. 맞히면 완료(ActivityStore.completeBasic).
 * 금액·나이 기준은 바뀔 수 있으니 note 로 확인 안내를 함께 둔다.
 */

enum class BasicArea(val label: String) { MONEY("돈 관리"), PAPERS("행정·서류"), WORK("일·안전") }

/** 미션에서 앱 기능으로 이어지는 링크 */
enum class BasicLink(val label: String) { SAVINGS("저축 목표 세워 보기"), BUDGET("생활비 나눠 보기"), PRACTICE("상황 연습 해 보기") }

data class BasicQuiz(val question: String, val options: List<String>, val answer: Int, val explain: String)

data class BasicMission(
    val id: String,
    val area: BasicArea,
    val title: String,
    val why: String,
    val points: List<String>,
    val quiz: BasicQuiz,
    val note: String? = null,
    val link: BasicLink? = null,
)

object BasicMissions {
    val all = listOf(
        BasicMission(
            id = "pocket",
            area = BasicArea.MONEY,
            title = "용돈 기입장 써 보기",
            why = "들어온 돈과 나간 돈을 적어 보면 내가 어디에 돈을 많이 쓰는지 보여요. 혼자 살 때 생활비를 나누는 연습이 돼요.",
            points = listOf("받은 날과 금액을 먼저 적어요", "쓸 때마다 날짜·어디에·얼마를 적어요", "한 주에 한 번 남은 돈을 확인해요"),
            quiz = BasicQuiz(
                "한 달 용돈 5만 원 중 2만 원을 쓰고 1만 원을 저축했어요. 지금 쓸 수 있는 돈은 얼마일까요?",
                listOf("1만 원", "2만 원", "3만 원"),
                answer = 1,
                explain = "5만 원 − 쓴 돈 2만 원 − 저축 1만 원 = 2만 원이에요. 저축한 돈은 쓸 돈에서 빼고 생각해요.",
            ),
            link = BasicLink.BUDGET,
        ),
        BasicMission(
            id = "card",
            area = BasicArea.MONEY,
            title = "체크카드와 신용카드 구분하기",
            why = "체크카드는 통장에 있는 돈만큼만 쓸 수 있어서 쓴 만큼 바로 빠져나가요. 신용카드는 나중에 갚는 빚이라 더 조심해야 해요.",
            points = listOf("체크카드: 통장 잔액 안에서 바로 결제", "신용카드: 다음 달에 한꺼번에 갚는 방식", "처음에는 체크카드로 쓰는 습관을 들여요"),
            quiz = BasicQuiz(
                "통장에 있는 돈만큼만 바로 빠져나가는 카드는 무엇일까요?",
                listOf("체크카드", "신용카드", "둘 다 같아요"),
                answer = 0,
                explain = "체크카드는 결제하는 순간 내 통장에서 돈이 빠져나가요.",
            ),
        ),
        BasicMission(
            id = "didim",
            area = BasicArea.MONEY,
            title = "디딤씨앗통장 알아보기",
            why = "보호대상아동이 저축하면 나라가 같은 기간에 돈을 더 넣어 주는 통장이에요. 자립할 때 쓸 목돈이 돼요.",
            points = listOf("내가 저축한 금액에 나라가 매칭해 줘요", "지금 통장이 있는지 선생님께 물어봐요", "모인 돈은 정해진 나이 이후 자립·학업 등에 쓸 수 있어요"),
            quiz = BasicQuiz(
                "내가 한 달에 3만 원을 저축하면, 1:2로 매칭될 때 나라가 넣어 주는 돈은 얼마일까요?",
                listOf("3만 원", "6만 원", "9만 원"),
                answer = 1,
                explain = "1:2 매칭이면 내가 넣은 돈의 2배를 넣어 줘요. 3만 원 × 2 = 6만 원이에요.",
            ),
            note = "매칭 비율과 한도는 바뀔 수 있어요. 정확한 내용은 선생님이나 아동권리보장원에서 확인해 주세요.",
            link = BasicLink.SAVINGS,
        ),
        BasicMission(
            id = "idcard",
            area = BasicArea.PAPERS,
            title = "주민등록증 만들 때 알아 두기",
            why = "주민등록증은 은행, 병원, 아르바이트처럼 나를 증명해야 할 때 꼭 필요해요.",
            points = listOf("만 17세가 되면 발급 대상이 돼요", "주민센터에 가서 신청해요", "사진(최근 6개월 안에 찍은 것)을 챙겨요"),
            quiz = BasicQuiz(
                "주민등록증은 몇 살이 되면 만들 수 있을까요?",
                listOf("만 14세", "만 17세", "만 19세"),
                answer = 1,
                explain = "만 17세가 되면 주민등록증 발급 대상이 돼요. 주민센터에서 신청해요.",
            ),
            note = "준비물은 주민센터나 정부24에서 한 번 더 확인해 주세요.",
        ),
        BasicMission(
            id = "gov24",
            area = BasicArea.PAPERS,
            title = "서류 직접 떼어 보기",
            why = "정책을 신청하거나 집을 구할 때 주민등록등본 같은 서류를 자주 내야 해요. 미리 떼어 보면 나중에 덜 막막해요.",
            points = listOf("정부24에서 온라인으로 뗄 수 있어요", "주민센터 무인민원발급기도 쓸 수 있어요", "어떤 서류가 필요한지 신청 안내문을 먼저 확인해요"),
            quiz = BasicQuiz(
                "주민등록등본을 집에서 온라인으로 뗄 수 있는 곳은 어디일까요?",
                listOf("정부24", "복지로", "커리어넷"),
                answer = 0,
                explain = "정부24에서 주민등록등본 같은 민원 서류를 온라인으로 발급받을 수 있어요.",
            ),
        ),
        BasicMission(
            id = "contract",
            area = BasicArea.WORK,
            title = "아르바이트 전 근로계약서 챙기기",
            why = "근로계약서에는 일하는 시간, 시급, 쉬는 날이 적혀 있어요. 나중에 문제가 생겼을 때 나를 지켜 주는 증거가 돼요.",
            points = listOf("일을 시작하기 전에 써요", "한 부는 꼭 내가 받아 둬요", "만 18세 미만은 보호자 동의서가 필요해요"),
            quiz = BasicQuiz(
                "아르바이트를 시작할 때 꼭 쓰고 한 부를 받아 둬야 하는 것은 무엇일까요?",
                listOf("이력서", "근로계약서", "자기소개서"),
                answer = 1,
                explain = "근로계약서는 일하는 조건을 적은 약속이에요. 사장님은 써서 한 부를 줘야 해요.",
            ),
            link = BasicLink.PRACTICE,
        ),
        BasicMission(
            id = "wage",
            area = BasicArea.WORK,
            title = "최저임금 알아 두기",
            why = "최저임금은 나이와 상관없이 일한 사람이 받아야 하는 가장 낮은 시급이에요. 청소년도 똑같이 적용돼요.",
            points = listOf("시급이 최저임금보다 낮으면 안 돼요", "일한 시간을 따로 적어 두면 좋아요", "못 받은 돈이 있으면 고용노동부(1350)에 물어볼 수 있어요"),
            quiz = BasicQuiz(
                "2026년 최저임금(시급)은 얼마일까요?",
                listOf("9,860원", "10,030원", "10,320원"),
                answer = 2,
                explain = "2026년 최저임금은 시간당 10,320원이에요. 해마다 바뀌니 새해에 한 번씩 확인해요.",
            ),
            note = "최저임금은 해마다 바뀌어요. 최저임금위원회나 고용노동부에서 확인할 수 있어요.",
        ),
        BasicMission(
            id = "scam",
            area = BasicArea.WORK,
            title = "통장·카드 빌려주지 않기",
            why = "\"용돈 줄게, 카드 잠깐 빌려줘\" 같은 부탁은 범죄에 쓰일 수 있어요. 빌려준 사람도 처벌받을 수 있어요.",
            points = listOf("통장·카드·비밀번호는 누구에게도 빌려주지 않아요", "고액 알바를 미끼로 카드를 달라는 경우를 조심해요", "이상하면 바로 믿을 수 있는 어른에게 말해요"),
            quiz = BasicQuiz(
                "모르는 사람이 \"돈 줄게, 체크카드 잠깐만 빌려줘\"라고 하면 어떻게 해야 할까요?",
                listOf("돈을 먼저 받고 빌려준다", "거절하고 믿을 수 있는 어른에게 알린다", "친구 카드를 대신 빌려준다"),
                answer = 1,
                explain = "통장·카드를 빌려주면 범죄에 쓰일 수 있어요. 거절하고 어른에게 알려요.",
            ),
        ),
    )

    fun of(id: String): BasicMission? = all.firstOrNull { it.id == id }
}
