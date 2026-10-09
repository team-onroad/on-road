package com.project.on_road.data

import kotlinx.coroutines.delay

/** 화면 확인용 가짜 AI 코치. 규칙과 템플릿으로 동작하며, 실제 AI 연결 시 RemoteCoachApi로 교체한다. */
class FakeCoachApi : CoachApi {

    private suspend fun latency(ms: Long = 600) = delay(ms)

    // ───────────── 흥미 탐색 ─────────────

    private val kidQuestions = listOf(
        InterestQuestion("k1", "쉬는 시간에 뭘 하고 싶어?", listOf(
            InterestOption("블록으로 뭔가 만들기", listOf(Riasec.R)),
            InterestOption("그림 그리기", listOf(Riasec.A)),
            InterestOption("친구랑 같이 놀기", listOf(Riasec.S)),
            InterestOption("신기한 책 읽기", listOf(Riasec.I))
        )),
        InterestQuestion("k2", "반에서 맡고 싶은 일은?", listOf(
            InterestOption("반장", listOf(Riasec.E)),
            InterestOption("정리 도우미", listOf(Riasec.C)),
            InterestOption("화분 돌보기", listOf(Riasec.R, Riasec.S)),
            InterestOption("게시판 꾸미기", listOf(Riasec.A))
        )),
        InterestQuestion("k3", "어떤 게 더 재밌어?", listOf(
            InterestOption("과학 실험", listOf(Riasec.I)),
            InterestOption("노래·춤", listOf(Riasec.A)),
            InterestOption("동생 돌봐 주기", listOf(Riasec.S)),
            InterestOption("용돈 기록하기", listOf(Riasec.C))
        ))
    )

    private val questions = listOf(
        InterestQuestion("q1", "주말에 하루가 비었어요. 가장 끌리는 건?", listOf(
            InterestOption("고장 난 물건 고치기, 만들기", listOf(Riasec.R)),
            InterestOption("궁금한 걸 찾아보고 공부하기", listOf(Riasec.I)),
            InterestOption("그림, 음악, 영상 만들기", listOf(Riasec.A)),
            InterestOption("친구 고민 들어 주기", listOf(Riasec.S))
        )),
        InterestQuestion("q2", "팀 과제를 하면 주로 맡는 역할은?", listOf(
            InterestOption("앞에서 발표하고 이끌기", listOf(Riasec.E)),
            InterestOption("자료 정리와 일정 관리", listOf(Riasec.C)),
            InterestOption("아이디어 내고 꾸미기", listOf(Riasec.A)),
            InterestOption("팀원 챙기고 분위기 맞추기", listOf(Riasec.S))
        )),
        InterestQuestion("q3", "해 보고 싶은 아르바이트는?", listOf(
            InterestOption("카페·매장에서 손님 응대", listOf(Riasec.S, Riasec.E)),
            InterestOption("물류·제조 현장", listOf(Riasec.R)),
            InterestOption("사무 보조, 데이터 입력", listOf(Riasec.C)),
            InterestOption("공부방 보조 교사", listOf(Riasec.S, Riasec.I))
        )),
        InterestQuestion("q4", "칭찬받으면 가장 기분 좋은 말은?", listOf(
            InterestOption("\"손재주가 좋다\"", listOf(Riasec.R)),
            InterestOption("\"똑똑하다, 논리적이다\"", listOf(Riasec.I)),
            InterestOption("\"감각 있다\"", listOf(Riasec.A)),
            InterestOption("\"믿음직하다, 꼼꼼하다\"", listOf(Riasec.C))
        )),
        InterestQuestion("q5", "나중에 일할 때 중요하게 여기는 건?", listOf(
            InterestOption("내 사업이나 성과를 키우기", listOf(Riasec.E)),
            InterestOption("사람들에게 도움이 되기", listOf(Riasec.S)),
            InterestOption("안정적이고 규칙적인 일", listOf(Riasec.C)),
            InterestOption("새로운 걸 배우고 알아내기", listOf(Riasec.I))
        ))
    )

    private val freeTextKeywords = mapOf(
        Riasec.R to listOf("만들", "고치", "운동", "요리", "기계", "자동차"),
        Riasec.I to listOf("과학", "궁금", "실험", "컴퓨터", "코딩", "수학"),
        Riasec.A to listOf("그림", "음악", "노래", "영상", "디자인", "글"),
        Riasec.S to listOf("돕", "상담", "아이", "친구", "봉사", "가르"),
        Riasec.E to listOf("리더", "사업", "장사", "발표", "이끌"),
        Riasec.C to listOf("정리", "계획", "회계", "기록", "꼼꼼")
    )

    override suspend fun interestQuestions(stage: UserStage): List<InterestQuestion> {
        latency(300)
        return if (stage == UserStage.CHILD) kidQuestions else questions
    }

    override suspend fun analyzeInterest(userId: String, answers: List<InterestOption>, freeText: String): InterestProfile {
        latency(900)
        val scores = Riasec.entries.associateWith { 0 }.toMutableMap()
        answers.forEach { a -> a.types.forEach { scores[it] = scores.getValue(it) + 2 } }
        freeTextKeywords.forEach { (type, words) ->
            if (words.any { freeText.contains(it) }) scores[type] = scores.getValue(type) + 1
        }
        return InterestProfile(scores)
    }

    private val jobs = listOf(
        Triple("barista", "바리스타", listOf(Riasec.R, Riasec.S)) to "커피를 만들고 손님을 맞이해요.",
        Triple("mechanic", "자동차 정비사", listOf(Riasec.R, Riasec.I)) to "자동차를 점검하고 고쳐요.",
        Triple("cook", "조리사", listOf(Riasec.R, Riasec.A)) to "음식을 만들고 메뉴를 개발해요.",
        Triple("developer", "소프트웨어 개발자", listOf(Riasec.I, Riasec.C)) to "앱과 프로그램을 만들어요.",
        Triple("lab", "임상병리사", listOf(Riasec.I, Riasec.C)) to "병원에서 검사를 하고 결과를 분석해요.",
        Triple("designer", "시각 디자이너", listOf(Riasec.A, Riasec.E)) to "포스터, 로고, 화면을 디자인해요.",
        Triple("video", "영상 편집자", listOf(Riasec.A, Riasec.I)) to "영상을 편집하고 효과를 넣어요.",
        Triple("social", "사회복지사", listOf(Riasec.S, Riasec.C)) to "도움이 필요한 사람을 지원해요.",
        Triple("care", "보육교사", listOf(Riasec.S, Riasec.A)) to "어린이집에서 아이들을 돌보고 가르쳐요.",
        Triple("nurse", "간호조무사", listOf(Riasec.S, Riasec.R)) to "병원에서 환자를 돌봐요.",
        Triple("sales", "매장 관리자", listOf(Riasec.E, Riasec.S)) to "매장을 운영하고 직원을 관리해요.",
        Triple("office", "사무·회계 담당자", listOf(Riasec.C, Riasec.E)) to "서류와 돈 관리를 꼼꼼하게 해요."
    )

    override suspend fun recommendJobs(userId: String, profile: InterestProfile, stage: UserStage): List<JobRecommendation> {
        latency(1000)
        val top = profile.top
        return jobs
            .map { (t, desc) ->
                val score = t.third.sumOf { profile.scores[it] ?: 0 }
                Triple(t, desc, score)
            }
            .sortedByDescending { it.third }
            .take(4)
            .map { (t, desc, _) ->
                val matched = t.third.filter { it in top }.ifEmpty { t.third.take(1) }
                JobRecommendation(
                    id = t.first,
                    name = t.second,
                    description = desc,
                    reason = "${matched.joinToString("·") { it.label }} 성향이 높게 나왔어요. " +
                        "이 직업은 ${t.third.joinToString("·") { it.label }} 성향과 잘 맞는 일이에요.",
                    types = t.third
                )
            }
    }

    override suspend fun buildRoadmap(userId: String, job: JobRecommendation, stage: UserStage): Roadmap {
        latency(1100)
        val steps = if (stage == UserStage.TEEN) listOf(
            RoadmapStep("지금", "직업 알아보기", "${job.name}가 하는 일을 영상이나 직업 체험으로 알아봐요. 커리어넷 직업백과를 참고해도 좋아요."),
            RoadmapStep("이번 학기", "관련 활동 해 보기", "동아리, 봉사, 방과후 활동 중 ${job.name}와 이어지는 것을 하나 골라 참여해요."),
            RoadmapStep("1년 안", "진학 방향 정하기", "특성화고·일반고·대학 학과 중 어떤 길이 맞는지 선생님과 상담해요."),
            RoadmapStep("졸업 전", "자격증·기록 준비", "관련 자격증을 알아보고, 활동을 기록해 진학용 활동 기록을 채워요.")
        ) else listOf(
            RoadmapStep("이번 달", "직무 알아보기", "${job.name} 채용 공고 3개를 찾아 자주 나오는 조건을 정리해요."),
            RoadmapStep("1~3개월", "직업훈련 찾기", "국민내일배움카드로 들을 수 있는 관련 훈련 과정을 알아봐요."),
            RoadmapStep("3~6개월", "자격증 준비", "직무에 필요한 자격증 시험 일정을 공고 일정에 담아 준비해요."),
            RoadmapStep("6개월 이후", "지원하기", "이력서와 자기소개서를 만들고, 면접 연습을 한 뒤 지원해요.")
        )
        return Roadmap(job, steps, "기간과 순서는 예시예요. 상황에 맞게 조정해 보세요.")
    }

    // ───────────── 롤플레이 ─────────────

    private class Script(
        val scenario: RoleplayScenario,
        val opening: String,
        val lines: List<String>,
        val hardLines: List<String> = emptyList(),
        val checkpoints: List<Pair<List<String>, String>>,
        val documents: List<String>,
        val better: List<Pair<String, String>>
    )

    private val adult = setOf(UserStage.YOUNG_ADULT)
    private val teenAdult = setOf(UserStage.TEEN, UserStage.YOUNG_ADULT)

    private val scripts = listOf(
        Script(
            RoleplayScenario("store", "가게에서 물건 사기", "생활", "가게 점원", "문구점에서 공책을 사려고 해요.", "원하는 물건을 말하고 계산하기", RoleplayLevel.EASY, setOf(UserStage.CHILD)),
            opening = "어서 오세요! 뭘 찾고 있어요?",
            lines = listOf("공책은 저쪽에 있어요. 줄 공책이랑 무지 공책 중에 뭐가 좋아요?", "좋아요. 1,500원이에요. 어떻게 계산할래요?", "고마워요. 영수증 필요해요?"),
            checkpoints = listOf(listOf("주세요", "있어요", "찾") to "원하는 물건 말하기", listOf("감사", "고마") to "고맙다고 인사하기"),
            documents = emptyList(),
            better = listOf("저거요" to "공책 한 권 주세요")
        ),
        Script(
            RoleplayScenario("bank", "은행 계좌 개설", "금융", "은행 창구 직원", "처음으로 내 이름의 통장을 만들러 왔어요.", "필요한 서류를 확인하고 계좌 만들기", RoleplayLevel.EASY, teenAdult),
            opening = "안녕하세요, 어떤 업무 도와드릴까요?",
            lines = listOf("네, 신규 계좌 개설이요. 신분증 가지고 오셨나요?", "계좌를 어디에 쓰실 예정이세요? 목적을 확인해야 해서요.", "체크카드도 같이 만드시겠어요?", "마지막으로 비밀번호 4자리를 입력해 주세요."),
            checkpoints = listOf(listOf("신분증", "학생증") to "신분증 준비", listOf("월급", "용돈", "수당", "저축", "목적") to "계좌 만드는 목적 설명", listOf("체크카드", "카드") to "체크카드 발급 여부"),
            documents = listOf("신분증 (주민등록증·학생증 등)", "계좌 목적 증빙 (예: 근로계약서, 재직증명서)"),
            better = listOf("통장 만들러 왔어요" to "처음으로 입출금 통장을 만들고 싶어요. 월급 받을 계좌예요")
        ),
        Script(
            RoleplayScenario("community-center", "주민센터 전입신고", "생활", "주민센터 직원", "새 집으로 이사하고 전입신고를 하러 왔어요.", "전입신고와 확정일자까지 챙기기", RoleplayLevel.NORMAL, adult),
            opening = "다음 분 오세요. 무슨 일로 오셨어요?",
            lines = listOf("전입신고시군요. 신분증 주시겠어요?", "새 주소가 어디세요? 세대주로 하실 건가요?", "임대차 계약서 가져오셨어요? 확정일자도 같이 받으실 수 있어요.", "처리됐습니다. 더 궁금한 거 있으세요?"),
            checkpoints = listOf(listOf("신분증") to "신분증 준비", listOf("확정일자") to "확정일자 함께 받기", listOf("계약서") to "임대차 계약서 챙기기"),
            documents = listOf("신분증", "임대차 계약서 (확정일자용)"),
            better = listOf("이사 왔어요" to "이사해서 전입신고하고, 확정일자도 같이 받고 싶어요")
        ),
        Script(
            RoleplayScenario("landlord", "집주인에게 수리 요청 전화", "주거", "집주인", "보일러가 고장 나서 집주인에게 전화해요.", "상황을 설명하고 수리 일정 정하기", RoleplayLevel.NORMAL, adult),
            opening = "여보세요? 네, 무슨 일이에요?",
            lines = listOf("보일러요? 언제부터 그랬어요? 혹시 사용하다가 망가뜨린 건 아니죠?", "음… 이번 주는 좀 바쁜데, 다음 주에 보면 안 될까요?", "알겠어요. 기사님한테 연락해 볼게요. 수리비는 어떻게 할까요?"),
            checkpoints = listOf(listOf("언제", "부터", "어제", "오늘") to "고장 난 시점 설명", listOf("빨리", "이번 주", "내일", "추워") to "수리가 급한 이유", listOf("수리비", "비용", "부담") to "수리비 부담 확인"),
            documents = listOf("고장 부위 사진", "임대차 계약서 (수리 책임 조항 확인)"),
            better = listOf("보일러 고장났어요" to "어제부터 보일러에서 온수가 안 나와요. 날이 추워서 이번 주 안에 수리가 필요해요")
        ),
        Script(
            RoleplayScenario("parttime", "아르바이트 면접", "면접", "카페 점장", "동네 카페 아르바이트 면접을 봐요.", "나를 소개하고 근무 조건 확인하기", RoleplayLevel.NORMAL, teenAdult),
            opening = "반가워요. 간단하게 자기소개 해 줄래요?",
            lines = listOf("일할 수 있는 요일이랑 시간이 어떻게 돼요?", "손님이 많을 때 실수하면 어떻게 할 거예요?", "궁금한 거 있으면 물어봐요."),
            hardLines = listOf("경력이 없네요. 그럼 우리가 왜 뽑아야 하죠?", "주말에 갑자기 나와 달라고 하면 나올 수 있어요?"),
            checkpoints = listOf(listOf("요일", "시간", "주말", "평일") to "근무 가능한 시간", listOf("시급", "급여", "근로계약서", "계약서") to "시급·근로계약서 확인"),
            documents = listOf("신분증", "보건증 (음식점·카페)", "통장 사본", "청소년은 부모님 또는 후견인 동의서"),
            better = listOf("아무 때나 돼요" to "평일 오후 4시 이후와 토요일에 일할 수 있어요")
        ),
        Script(
            RoleplayScenario("school", "진학 면접", "면접", "면접관 선생님", "특성화고·대학 입학 면접을 봐요.", "지원 동기와 활동을 설명하기", RoleplayLevel.NORMAL, setOf(UserStage.TEEN)),
            opening = "지원자, 우리 학교(학과)에 지원한 이유를 말해 볼까요?",
            lines = listOf("그 분야에 관심을 갖게 된 계기가 있나요?", "관련해서 직접 해 본 활동이 있다면 소개해 주세요.", "입학하면 가장 해 보고 싶은 게 뭔가요?"),
            checkpoints = listOf(listOf("계기", "때문", "관심") to "관심을 갖게 된 계기", listOf("활동", "동아리", "봉사", "대회") to "직접 해 본 활동 예시"),
            documents = listOf("생활기록부", "자기소개서"),
            better = listOf("그냥 좋아서요" to "중학교 때 동아리에서 ○○를 해 보고 이 분야에 관심이 생겼어요")
        ),
        Script(
            RoleplayScenario("job", "취업 면접 (실전)", "면접", "회사 면접관", "첫 정규직 면접이에요. 꼬리 질문이 이어져요.", "경험을 근거로 답하고 압박 질문에 침착하게 대응하기", RoleplayLevel.HARD, adult),
            opening = "자기소개 1분 안에 부탁드립니다.",
            lines = listOf("방금 말한 경험에서 본인이 맡은 역할은 정확히 뭐였죠?", "그 과정에서 가장 힘들었던 점과 어떻게 해결했는지 말해 주세요.", "우리 회사에 지원한 이유는요?", "마지막으로 하고 싶은 말 있으세요?"),
            hardLines = listOf("솔직히 경력이 부족해 보이는데, 어떻게 생각해요?", "입사하고 1년 안에 그만두지 않을 거라는 걸 어떻게 믿죠?"),
            checkpoints = listOf(listOf("역할", "맡", "담당") to "경험 속 나의 역할", listOf("해결", "그래서", "결과") to "해결 과정과 결과", listOf("회사", "지원", "관심") to "구체적인 지원 동기"),
            documents = listOf("이력서", "자기소개서", "자격증 사본", "졸업(예정)증명서"),
            better = listOf("열심히 하겠습니다" to "○○ 경험에서 배운 꼼꼼함으로 입사 후 3개월 안에 업무를 익히겠습니다")
        )
    )

    private fun script(id: String) = scripts.find { it.scenario.id == id } ?: scripts.first()

    private fun levelFor(s: RoleplayScenario, stage: UserStage): RoleplayLevel = when {
        stage == UserStage.CHILD -> RoleplayLevel.EASY
        s.id == "parttime" && stage == UserStage.YOUNG_ADULT -> RoleplayLevel.HARD
        else -> s.level
    }

    override suspend fun roleplayScenarios(stage: UserStage): List<RoleplayScenario> {
        latency(300)
        return scripts.map { it.scenario }.filter { stage in it.stages }.map { it.copy(level = levelFor(it, stage)) }
    }

    override suspend fun roleplayOpening(scenarioId: String, stage: UserStage): RoleplayTurn {
        latency(400)
        return RoleplayTurn(false, script(scenarioId).opening)
    }

    override suspend fun roleplayReply(userId: String, scenarioId: String, stage: UserStage, history: List<RoleplayTurn>): RoleplayTurn {
        latency(900)
        val s = script(scenarioId)
        val hard = levelFor(s.scenario, stage) == RoleplayLevel.HARD
        // 실전 난이도는 압박·꼬리 질문을 사이사이에 섞는다
        val lines = if (hard && s.hardLines.isNotEmpty()) {
            s.lines.flatMapIndexed { i, l -> listOfNotNull(l, s.hardLines.getOrNull(i)) }
        } else s.lines
        val partnerTurns = history.count { !it.fromUser } - 1  // 첫 인사 제외
        return RoleplayTurn(
            false,
            lines.getOrElse(partnerTurns) { "네, 오늘은 여기까지 할게요. 수고했어요." }
        )
    }

    override suspend fun roleplayFeedback(userId: String, scenarioId: String, history: List<RoleplayTurn>): RoleplayFeedback {
        latency(1200)
        val s = script(scenarioId)
        val said = history.filter { it.fromUser }.joinToString(" ") { it.text }
        val good = mutableListOf<String>()
        val missed = mutableListOf<String>()
        s.checkpoints.forEach { (words, msg) ->  // msg: 확인할 항목 이름
            if (words.any { said.contains(it) }) good.add(msg) else missed.add(msg)
        }
        if (history.count { it.fromUser } >= 3) good.add("끝까지 대화를 이어 갔어요")
        return RoleplayFeedback(good, missed, s.documents, s.better)
    }

    // ───────────── 서류 양식 ─────────────

    private val templates = listOf(
        FormTemplate("scholarship", "장학금 신청서", "학교·재단 장학금 신청에 자주 쓰는 양식", setOf(UserStage.TEEN, UserStage.YOUNG_ADULT)),
        FormTemplate("program", "프로그램 참가 신청서", "캠프·교육·체험 프로그램 신청", setOf(UserStage.TEEN)),
        FormTemplate("allowance", "자립수당 신청서", "보호종료 자립준비청년 자립수당 신청", setOf(UserStage.YOUNG_ADULT)),
        FormTemplate("move-in", "전입신고서", "이사 후 주민센터에 내는 서류", setOf(UserStage.YOUNG_ADULT)),
        FormTemplate("resume-form", "입사지원서 (기본형)", "회사 지원 시 자주 쓰는 이력서 양식", setOf(UserStage.YOUNG_ADULT))
    )

    private val commonFields = listOf(
        FormField("name", "성명", "이름", "주민등록증에 적힌 이름 그대로 써요.", example = "홍길동"),
        FormField("birth", "생년월일", "태어난 날", "숫자 8자리로 써요.", example = "2007.03.15"),
        FormField("phone", "연락처", "전화번호", "연락이 잘 되는 번호를 써요.", example = "010-1234-5678"),
        FormField("address", "주소", "사는 곳 주소", "도로명 주소로 써요. 동·호수까지 빠뜨리지 않아요.")
    )

    private fun fieldsFor(id: String): List<FormField> = when (id) {
        "scholarship" -> commonFields + listOf(
            FormField("school", "소속(학교·학년)", "다니는 학교와 학년", "학교 이름과 학년, 반을 써요."),
            FormField("reason", "신청 사유", "장학금이 필요한 이유", "지금 상황과 장학금을 어디에 쓸지 솔직하게 써요.", caution = "과장하지 말고 사실만 써요. 확인 서류를 요구할 수 있어요.", multiline = true, essay = true,
                outline = "1) 지금 나의 상황\n2) 장학금이 필요한 이유\n3) 받게 되면 어디에 쓸지\n4) 앞으로의 계획"),
            FormField("plan", "학업 계획", "앞으로 공부 계획", "이번 학기·1년 동안 무엇을 할지 구체적으로 써요.", multiline = true, essay = true,
                outline = "1) 이번 학기 목표\n2) 이를 위해 할 일\n3) 1년 뒤 모습")
        )
        "program" -> commonFields.take(3) + listOf(
            FormField("guardian", "보호자(담당자) 연락처", "시설 선생님 연락처", "보호자 대신 시설 담당 선생님 연락처를 써도 돼요."),
            FormField("motive", "참가 동기", "참가하고 싶은 이유", "이 프로그램에서 기대하는 것을 짧게 써요.", multiline = true, essay = true,
                outline = "1) 프로그램을 알게 된 계기\n2) 해 보고 싶은 것\n3) 끝나고 얻고 싶은 것")
        )
        "allowance" -> commonFields + listOf(
            FormField("end-date", "보호종료일", "보호가 끝난 날", "시설에서 받은 보호종료확인서의 날짜를 그대로 써요.", caution = "날짜가 다르면 심사가 늦어질 수 있어요."),
            FormField("account", "입금 계좌", "돈 받을 통장", "본인 이름의 통장만 가능해요.", caution = "다른 사람 명의 계좌는 쓸 수 없어요.")
        )
        "move-in" -> listOf(
            commonFields[0],
            FormField("before", "전 주소", "이사 오기 전 주소", "이전에 살던 곳 주소를 써요."),
            FormField("after", "새 주소", "이사 온 집 주소", "동·호수까지 정확히 써요."),
            FormField("head", "세대주 관계", "세대주와의 관계", "혼자 살면 '본인'이라고 써요.", example = "본인")
        )
        "resume-form" -> commonFields + listOf(
            FormField("edu", "학력사항", "다닌 학교", "최종 학교부터 거꾸로 써요.", multiline = true),
            FormField("career", "경력사항", "일한 경험", "아르바이트도 기간과 한 일을 함께 쓰면 경력이 돼요.", multiline = true, example = "2025.03~2025.08 ○○카페 아르바이트 (음료 제조, 재고 정리)"),
            FormField("license", "자격증", "가진 자격증", "자격증 이름, 취득일, 발급 기관을 써요."),
            FormField("intro", "자기소개", "나를 소개하는 글", "직무와 연결되는 경험 위주로 써요.", multiline = true, essay = true,
                outline = "1) 한 줄로 나를 표현하기\n2) 직무와 이어지는 경험\n3) 그 경험에서 배운 점\n4) 입사 후 하고 싶은 일")
        )
        else -> commonFields
    }

    override suspend fun formTemplates(stage: UserStage): List<FormTemplate> {
        latency(300)
        return templates.filter { stage in it.stages }
    }

    override suspend fun analyzeTemplate(templateId: String): FormDraft {
        latency(700)
        val t = templates.find { it.id == templateId } ?: templates.first()
        return FormDraft(t.id, t.name, fieldsFor(t.id), originalExport = true)
    }

    override suspend fun analyzeUpload(userId: String, bytes: ByteArray, mimeType: String): FormDraft {
        latency(1800)
        return FormDraft(
            templateId = null,
            title = "올린 양식",
            fields = commonFields + FormField(
                "etc", "기타 기재사항", "그 밖에 적을 내용", "양식에서 요구하는 내용을 확인하고 적어요.",
                caution = "AI가 읽은 항목이 실제 양식과 다를 수 있어요. 원본과 꼭 비교해 주세요.", multiline = true
            ),
            originalExport = false
        )
    }

    // ───────────── 이력서·자기소개서·활동 기록 ─────────────

    override suspend fun generateDocument(userId: String, type: DocType): GeneratedDoc {
        latency(1300)
        val growth = runCatching { AppContainer.api.getGrowth(userId) }.getOrNull()
        val checklists = growth?.completedChecklists.orEmpty()
        val policies = growth?.savedPolicies.orEmpty()
        val sims = growth?.simulations.orEmpty()
        val interest = AppContainer.session.interestTypes

        val records = buildList {
            checklists.forEach { add(DocSentence("${it.policyName} 신청 절차를 스스로 확인하고 서류를 준비해 신청을 마쳤습니다.", "체크리스트 완료 · ${it.policyName}")) }
            if (sims.isNotEmpty()) add(DocSentence("생활비 계획을 ${sims.size}번 세워 보며 지출을 관리하는 연습을 했습니다.", "생활 시뮬레이션 ${sims.size}회"))
            if (policies.isNotEmpty()) add(DocSentence("자립에 필요한 제도 ${policies.size}가지를 찾아보고 정리했습니다.", "저장한 정책 ${policies.size}개"))
        }
        val interestLine = interest.firstOrNull()?.let {
            DocSentence("저는 ${it.description.replace("좋아해요", "좋아합니다")}.", "흥미 탐색 결과 · ${it.label}")
        }
        val blank = { hint: String -> DocSentence(hint, null) }

        val sections = when (type) {
            DocType.ACTIVITY_RECORD -> listOf(
                DocSection("관심 분야", listOfNotNull(interestLine, blank("관심을 갖게 된 계기를 직접 적어 주세요."))),
                DocSection("활동 내용", records.ifEmpty { listOf(blank("참여한 활동(동아리, 봉사, 프로그램)을 적어 주세요.")) }),
                DocSection("배운 점과 계획", listOf(blank("활동을 통해 배운 점과 앞으로의 계획을 적어 주세요.")))
            )
            DocType.RESUME -> listOf(
                DocSection("기본 정보", listOf(blank("이름, 연락처, 이메일을 적어 주세요."))),
                DocSection("학력", listOf(blank("최종 학교부터 적어 주세요."))),
                DocSection("경험·활동", records.ifEmpty { listOf(blank("아르바이트, 교육, 봉사 경험을 적어 주세요.")) }),
                DocSection("자격증", listOf(blank("가진 자격증을 적어 주세요.")))
            )
            DocType.COVER_LETTER -> listOf(
                DocSection("나를 소개합니다", listOfNotNull(interestLine, blank("나를 한 문장으로 표현해 보세요."))),
                DocSection("준비해 온 과정", records.ifEmpty { listOf(blank("목표를 위해 해 온 일을 적어 주세요.")) }),
                DocSection("앞으로의 계획", listOf(blank("지원한 곳에서 하고 싶은 일을 적어 주세요.")))
            )
        }
        return GeneratedDoc(
            type = type,
            sections = sections,
            notice = if (records.isEmpty()) "앱에 쌓인 기록이 아직 적어서 빈칸이 많아요. 기록이 쌓일수록 초안이 풍부해져요."
            else "앱에 기록된 내용만 근거로 만들었어요. 없는 경험은 넣지 않았어요."
        )
    }
}
