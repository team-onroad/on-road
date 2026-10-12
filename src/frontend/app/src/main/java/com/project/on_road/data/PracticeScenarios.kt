package com.project.on_road.data

enum class ChoiceTone(val label: String) {
    GOOD("좋은 선택"),
    OKAY("나쁘지 않아요"),
    RISKY("다시 생각해 봐요")
}

data class PracticeChoice(val text: String, val tone: ChoiceTone, val feedback: String)

data class PracticeStep(val situation: String, val choices: List<PracticeChoice>)

data class PracticeScenario(
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    val steps: List<PracticeStep>,
    val tips: List<String>
)

object PracticeScenarios {
    val all = listOf(
        PracticeScenario(
            id = "first-house",
            title = "첫 집 계약하기",
            summary = "처음 방을 구할 때 놓치기 쉬운 순간들",
            category = "주거",
            steps = listOf(
                PracticeStep(
                    "마음에 드는 원룸을 찾았어요. 집주인이 \"오늘 계약하면 월세를 3만 원 깎아 줄게요\"라고 해요.",
                    listOf(
                        PracticeChoice("좋은 조건이니 바로 계약한다", ChoiceTone.RISKY, "서두르게 만드는 건 흔한 수법이에요. 계약 전에 등기부등본으로 집주인이 맞는지, 집에 빚(근저당)이 많은지 먼저 확인해야 해요."),
                        PracticeChoice("등기부등본을 떼어 보고 내일 답하겠다고 한다", ChoiceTone.GOOD, "잘했어요. 등기부등본은 인터넷등기소에서 누구나 발급할 수 있어요. 집주인 이름과 근저당 금액을 꼭 확인해요."),
                        PracticeChoice("친구에게 사진을 보내 물어본다", ChoiceTone.OKAY, "주변에 묻는 건 좋아요. 다만 서류 확인은 직접 하고, 자립지원전담기관에 계약서 검토를 부탁할 수도 있어요.")
                    )
                ),
                PracticeStep(
                    "계약서를 쓰고 보증금을 보내려는데, 중개인이 자기 계좌로 보내 달라고 해요.",
                    listOf(
                        PracticeChoice("말한 대로 중개인 계좌로 보낸다", ChoiceTone.RISKY, "보증금은 등기부등본에 나온 집주인 본인 명의 계좌로 보내야 나중에 돌려받을 때 문제가 적어요."),
                        PracticeChoice("집주인 본인 명의 계좌인지 확인하고 보낸다", ChoiceTone.GOOD, "정확해요. 이체 기록도 남아서 나중에 증거가 돼요."),
                        PracticeChoice("현금으로 건네고 영수증을 받는다", ChoiceTone.OKAY, "영수증을 받았다면 다행이지만, 계좌 이체가 기록이 더 확실하게 남아요.")
                    )
                ),
                PracticeStep(
                    "이사를 마쳤어요. 이제 무엇부터 할까요?",
                    listOf(
                        PracticeChoice("주민센터에서 전입신고하고 확정일자를 받는다", ChoiceTone.GOOD, "전입신고는 이사 후 14일 안에 해야 해요. 확정일자까지 받아 두면 보증금을 지키는 데 도움이 돼요."),
                        PracticeChoice("바쁘니까 한 달 뒤에 한다", ChoiceTone.RISKY, "전입신고는 14일 안에 해야 하고, 늦으면 과태료가 나올 수 있어요. 보증금 보호도 전입신고를 해야 시작돼요."),
                        PracticeChoice("짐 정리부터 끝내고 생각한다", ChoiceTone.OKAY, "정리도 중요하지만 전입신고와 확정일자는 가능한 한 빨리 챙겨요.")
                    )
                )
            ),
            tips = listOf(
                "계약 전 등기부등본으로 집주인과 근저당을 확인해요.",
                "보증금은 집주인 본인 명의 계좌로 보내요.",
                "이사 후 14일 안에 전입신고와 확정일자를 챙겨요."
            )
        ),
        PracticeScenario(
            id = "smishing",
            title = "수상한 지원금 문자",
            summary = "지원금, 대출을 미끼로 한 연락에 대처하기",
            category = "금융 안전",
            steps = listOf(
                PracticeStep(
                    "\"[정부지원] 자립준비청년 특별지원금 지급 대상입니다. 아래 링크에서 신청하세요\"라는 문자가 왔어요.",
                    listOf(
                        PracticeChoice("링크를 눌러 바로 신청한다", ChoiceTone.RISKY, "모르는 링크는 악성 앱 설치나 개인정보 유출로 이어질 수 있어요. 이런 문자 사기를 스미싱이라고 해요."),
                        PracticeChoice("문자에 적힌 번호로 전화해 물어본다", ChoiceTone.OKAY, "확인하려는 마음은 좋아요. 하지만 문자 속 번호는 사기범 번호일 수 있으니, 공식 번호로 직접 확인해요."),
                        PracticeChoice("복지로나 보건복지상담센터(129)에 직접 확인한다", ChoiceTone.GOOD, "가장 안전한 방법이에요. 정부 기관은 문자 링크로 신청을 받는 경우가 드물어요.")
                    )
                ),
                PracticeStep(
                    "다른 날, \"수수료만 먼저 보내면 바로 대출해 준다\"는 연락이 왔어요.",
                    listOf(
                        PracticeChoice("수수료를 보낸다", ChoiceTone.RISKY, "정식 금융회사는 대출 전에 수수료나 보증금을 요구하지 않아요. 전형적인 대출 사기예요."),
                        PracticeChoice("수수료를 깎아 달라고 한다", ChoiceTone.RISKY, "금액과 상관없이 돈을 먼저 요구하면 사기예요. 대화를 멈추는 게 좋아요."),
                        PracticeChoice("거절하고 번호를 차단한다", ChoiceTone.GOOD, "잘했어요. 피해가 의심되면 경찰(112)이나 금융감독원(1332)에 신고할 수 있어요.")
                    )
                )
            ),
            tips = listOf(
                "문자 속 링크와 번호 대신 공식 번호로 직접 확인해요.",
                "돈을 먼저 보내라는 대출은 사기예요.",
                "피해가 의심되면 112나 1332에 바로 연락해요."
            )
        ),
        PracticeScenario(
            id = "phone-name",
            title = "휴대폰 명의를 빌려 달래요",
            summary = "내 이름을 지키는 거절 연습",
            category = "금융 안전",
            steps = listOf(
                PracticeStep(
                    "친한 선배가 \"신용이 안 좋아서 그래, 네 이름으로 휴대폰 하나만 개통해 줘\"라고 부탁해요.",
                    listOf(
                        PracticeChoice("친한 사이니까 들어준다", ChoiceTone.RISKY, "요금과 소액결제, 대출이 모두 내 이름으로 쌓일 수 있어요. 범죄에 쓰이면 나도 책임을 질 수 있어요."),
                        PracticeChoice("요금은 선배가 내기로 약속받고 해 준다", ChoiceTone.RISKY, "약속만으로는 피해를 막을 수 없어요. 명의를 빌려주는 것 자체가 위험해요."),
                        PracticeChoice("정중하게 거절한다", ChoiceTone.GOOD, "맞아요. 명의는 한 번 빌려주면 되돌리기 어려워요.")
                    )
                ),
                PracticeStep(
                    "거절했더니 선배가 서운해하며 계속 부탁해요.",
                    listOf(
                        PracticeChoice("미안한 마음에 결국 해 준다", ChoiceTone.RISKY, "관계보다 내 이름과 신용을 지키는 게 먼저예요."),
                        PracticeChoice("\"내 이름이 걸린 일이라 어려워\"라고 솔직하게 말한다", ChoiceTone.GOOD, "이유를 짧고 분명하게 말하면 거절이 훨씬 쉬워져요."),
                        PracticeChoice("전담기관 선생님께 상황을 이야기한다", ChoiceTone.GOOD, "좋아요. 계속 압박을 받는다면 믿을 만한 어른에게 도움을 청하는 게 좋아요.")
                    )
                )
            ),
            tips = listOf(
                "휴대폰, 통장, 카드 명의는 절대 빌려주지 않아요.",
                "거절할 때는 이유를 짧고 분명하게 말해요.",
                "압박이 계속되면 믿을 만한 어른과 상의해요."
            )
        ),
        PracticeScenario(
            id = "short-money",
            title = "월급 전에 돈이 떨어졌어요",
            summary = "급할 때 위험한 선택을 피하는 방법",
            category = "생활",
            steps = listOf(
                PracticeStep(
                    "다음 월급까지 열흘 남았는데 통장에 3만 원밖에 없어요.",
                    listOf(
                        PracticeChoice("휴대폰 소액결제로 버틴다", ChoiceTone.RISKY, "소액결제는 다음 달 요금에 한꺼번에 청구되고, 연체되면 신용에 영향을 줄 수 있어요."),
                        PracticeChoice("전담기관이나 주민센터에 도움을 상담한다", ChoiceTone.GOOD, "좋아요. 급한 생계 위기라면 긴급 지원 제도를 연결받을 수 있어요. 보건복지상담센터(129)에서도 안내해 줘요."),
                        PracticeChoice("식비를 줄이고 남은 기간 계획을 다시 세운다", ChoiceTone.OKAY, "좋은 습관이에요. 다만 혼자 버티기 어렵다면 도움을 요청하는 것도 방법이에요.")
                    )
                ),
                PracticeStep(
                    "앱에서 \"무직자도 즉시 대출, 심사 없음\" 광고를 봤어요.",
                    listOf(
                        PracticeChoice("급하니까 바로 신청한다", ChoiceTone.RISKY, "금리가 매우 높거나 불법 대부업일 수 있어요. 대출이 꼭 필요하다면 서민금융진흥원(1397) 같은 공식 창구에서 먼저 상담해요."),
                        PracticeChoice("광고는 넘기고 다음 달 생활비 계획을 다시 짠다", ChoiceTone.GOOD, "잘했어요. 생활비 시뮬레이션으로 다음 달 계획을 미리 세워 보세요."),
                        PracticeChoice("친구에게 조금 빌린다", ChoiceTone.OKAY, "갚을 날짜를 정확히 정한다면 괜찮아요. 반복되지 않도록 계획을 함께 세워요.")
                    )
                )
            ),
            tips = listOf(
                "소액결제와 심사 없는 대출은 빚을 키울 수 있어요.",
                "급할 때는 전담기관, 주민센터, 129에 먼저 상담해요.",
                "대출이 필요하면 공식 창구(1397)에서 상담해요."
            )
        )
    )

    fun find(id: String): PracticeScenario? = all.find { it.id == id }
}
