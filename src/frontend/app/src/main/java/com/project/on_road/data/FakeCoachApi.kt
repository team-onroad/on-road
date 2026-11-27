package com.project.on_road.data

import kotlinx.coroutines.delay

/** 가짜 모드에서 저장한 롤플레잉 기록 (성장 기록 화면용) */
internal object FakeRecords {
    val roleplays = mutableListOf<RoleplayRecord>()
}

/** 화면 확인용 가짜 AI 코치. 규칙과 템플릿으로 동작하며, 실제 AI 연결 시 RemoteCoachApi로 교체한다. */
/** simulate: 가짜 모드에서 서버를 기다리는 척하는 시간. 실제 서버 모드에서 앱 안 계산(흥미 탐색 · 직업)으로 쓸 때는 끈다 */
class FakeCoachApi(private val simulate: Boolean = true) : CoachApi {

    private suspend fun latency(ms: Long = 600) { if (simulate) delay(ms) }

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

    /** 흥미 문항 30개: 유형마다 5개씩, R I A S E C 를 돌아가며 섞는다. 3단계로 답한다 */
    private val likeItems = listOf(
        Riasec.R to "고장 난 물건을 직접 고쳐 보기",
        Riasec.I to "궁금한 걸 끝까지 찾아보기",
        Riasec.A to "영상이나 사진 편집하기",
        Riasec.S to "친구 고민을 끝까지 들어 주기",
        Riasec.E to "행사에서 사람들 앞에 나서기",
        Riasec.C to "물건이나 서류를 순서대로 정리하기",
        Riasec.R to "요리나 베이킹 해 보기",
        Riasec.I to "수학이나 과학 문제 풀기",
        Riasec.A to "그림 그리거나 꾸미기",
        Riasec.S to "어린아이나 동생 돌보기",
        Riasec.E to "물건을 팔거나 장사해 보기",
        Riasec.C to "용돈이나 가계부 기록하기",
        Riasec.R to "공구로 가구 조립하기",
        Riasec.I to "프로그램이 어떻게 움직이는지 알아보기",
        Riasec.A to "노래하거나 악기 연주하기",
        Riasec.S to "모르는 사람에게 길 알려 주기",
        Riasec.E to "모임이나 동아리 이끌기",
        Riasec.C to "엑셀로 표 만들기",
        Riasec.R to "몸을 움직이며 일하기",
        Riasec.I to "실험하거나 관찰하기",
        Riasec.A to "글이나 이야기 쓰기",
        Riasec.S to "봉사활동 하기",
        Riasec.E to "다른 사람을 설득하기",
        Riasec.C to "계획표 세우고 지키기",
        Riasec.R to "식물이나 동물 돌보기",
        Riasec.I to "자료를 읽고 원인 따져 보기",
        Riasec.A to "옷이나 방을 꾸미기",
        Riasec.S to "다른 사람에게 가르쳐 주기",
        Riasec.E to "새로운 일을 먼저 시작하기",
        Riasec.C to "틀린 곳 꼼꼼히 찾아내기",
    )

    private val questions = likeItems.mapIndexed { i, (type, activity) ->
        InterestQuestion(
            id = "l${i + 1}",
            prompt = activity,
            options = listOf(
                InterestOption("좋아요", listOf(type), weight = 2),
                InterestOption("보통이에요", listOf(type), weight = 1),
                InterestOption("별로예요", emptyList(), weight = 0),
            ),
            activity = activity,
        )
    }

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
        answers.forEach { a -> a.types.forEach { scores[it] = scores.getValue(it) + a.weight } }
        freeTextKeywords.forEach { (type, words) ->
            if (words.any { freeText.contains(it) }) scores[type] = scores.getValue(type) + 1
        }
        return InterestProfile(scores)
    }

    /**
     * 직업 하나. types: 가까운 흥미 유형 (앞이 더 가깝다), prep: 일을 시작하기까지, values: 이 일에서 얻기 쉬운 것,
     * keyword: 훈련 과정 · 직무 정보를 찾을 검색어, teen: 청소년 경로 (없으면 공통 문장)
     */
    private class JobDef(
        val id: String,
        val name: String,
        val description: String,
        val types: List<Riasec>,
        val prep: PrepTime,
        val place: WorkPlace,
        val values: List<WorkValue>,
        val certTag: String,
        val certificates: List<String>,
        val keyword: String?,
        val policyQuery: String,
        val tasks: List<String>,
        val youth: List<String>,
        val teen: List<String>? = null,
    )

    private fun t(s: String) = s.map { Riasec.valueOf(it.toString()) }

    private val jobs: List<JobDef> = listOf(
        JobDef("barista", "바리스타", "커피를 만들고 손님을 맞이해요.", t("RS"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HANDS, WorkValue.HELP), "민간자격", listOf("바리스타(민간)"), "바리스타", "직업훈련",
            listOf("에스프레소와 음료를 만들어요", "손님 주문을 받고 응대해요", "원두와 매장 위생을 관리해요"),
            listOf("국민내일배움카드로 바리스타 과정 듣기", "카페 아르바이트로 경력 쌓기", "바리스타 자격 취득 후 매장 지원"),
            listOf("카페 체험이나 방과후 바리스타 수업 들어 보기", "조리·외식 계열 특성화고 알아보기", "바리스타 2급 자격 준비")),
        JobDef("mechanic", "자동차 정비사", "자동차를 점검하고 고쳐요.", t("RI"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.HANDS, WorkValue.STABLE), "국가자격", listOf("자동차정비기능사", "자동차정비산업기사"), "자동차정비", "직업훈련",
            listOf("자동차 상태를 점검해요", "고장 난 부품을 고치고 바꿔요", "정비 기록을 남겨요"),
            listOf("폴리텍대학·직업훈련 정비 과정 알아보기", "자동차정비기능사 취득", "정비소 보조로 시작해 경력 쌓기"),
            listOf("자동차·기계 계열 특성화고 알아보기", "정비 체험 프로그램 참여", "자동차정비기능사 공부 시작")),
        JobDef("cook", "조리사", "음식을 만들고 메뉴를 개발해요.", t("RA"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HANDS, WorkValue.FREE), "국가자격", listOf("한식조리기능사", "양식조리기능사"), "조리", "직업훈련",
            listOf("재료를 손질하고 음식을 만들어요", "새 메뉴를 만들어 봐요", "주방 위생과 재료를 관리해요"),
            listOf("내일배움카드로 조리 과정 듣기", "한식·양식조리기능사 취득", "식당 주방 보조로 경력 쌓기"),
            listOf("조리 동아리나 요리 체험 해 보기", "조리 계열 특성화고·학과 알아보기", "한식조리기능사 공부 시작")),
        JobDef("baker", "제과제빵사", "빵과 과자를 만들고 새 제품을 개발해요.", t("RA"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HANDS, WorkValue.FREE), "국가자격", listOf("제빵기능사", "제과기능사"), "제과제빵", "직업훈련",
            listOf("원료를 계량하고 반죽해요", "모양을 만들어 굽고 장식해요", "재료와 작업장 위생을 관리해요"),
            listOf("내일배움카드로 제과제빵 과정 듣기", "제빵기능사, 제과기능사 취득", "베이커리 생산이나 보조로 경력 쌓기")),
        JobDef("developer", "소프트웨어 개발자", "앱과 프로그램을 만들어요.", t("IC"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.LEARN, WorkValue.MONEY), "국가자격", listOf("정보처리기능사", "정보처리기사"), "소프트웨어", "직업훈련",
            listOf("필요한 기능을 정리하고 설계해요", "코드를 짜서 앱과 프로그램을 만들어요", "오류를 찾아 고쳐요"),
            listOf("K-디지털 트레이닝 등 무료 개발 교육 알아보기", "작은 프로젝트로 포트폴리오 만들기", "정보처리기능사·기사 준비"),
            listOf("무료 코딩 강의로 첫 프로그램 만들어 보기", "정보·소프트웨어 동아리 참여", "소프트웨어 학과·특성화고 알아보기")),
        JobDef("web", "웹 디자이너", "웹사이트와 앱 화면을 디자인해요.", t("AI"), PrepTime.SHORT, WorkPlace.OFFICE,
            listOf(WorkValue.LEARN, WorkValue.FREE), "국가자격", listOf("웹디자인기능사"), "웹디자인", "직업훈련",
            listOf("웹사이트와 앱 화면을 디자인해요", "디자인한 화면을 코드로 옮겨요", "사용하기 편한지 살펴요"),
            listOf("내일배움카드로 웹디자인 과정 듣기", "작업물로 포트폴리오 만들기", "웹디자인기능사 취득")),
        JobDef("data", "데이터 분석가", "데이터에서 의미를 찾아 보고서를 써요.", t("IC"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.LEARN, WorkValue.MONEY), "국가자격", listOf("빅데이터분석기사"), "데이터분석", "직업훈련",
            listOf("데이터를 모으고 정리해요", "숫자에서 의미를 찾아 보고서를 써요", "그래프로 보기 쉽게 만들어요"),
            listOf("K-디지털 트레이닝 데이터 과정 알아보기", "공공데이터로 분석 연습하기", "빅데이터분석기사 준비")),
        JobDef("network", "네트워크 관리자", "회사 인터넷과 서버가 잘 돌아가게 관리해요.", t("IR"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.STABLE, WorkValue.LEARN), "국가자격", listOf("정보처리산업기사", "네트워크관리사(민간)"), "네트워크", "직업훈련",
            listOf("회사 인터넷과 서버를 관리해요", "장애가 나면 원인을 찾아 고쳐요", "보안을 점검해요"),
            listOf("내일배움카드로 네트워크 과정 듣기", "네트워크관리사 취득", "전산실 보조로 경력 쌓기")),
        JobDef("lab", "임상병리사", "병원에서 검사를 하고 결과를 분석해요.", t("IC"), PrepTime.LONG, WorkPlace.OFFICE,
            listOf(WorkValue.STABLE, WorkValue.HELP), "국가면허", listOf("임상병리사 면허"), "임상병리", "장학금",
            listOf("혈액 등 검체를 검사해요", "검사 장비를 다루고 관리해요", "결과를 정리해 의료진에게 전해요"),
            listOf("임상병리학과(전문대·대학) 진학 알아보기", "장학금·학자금 지원 확인", "임상병리사 국가시험 준비"),
            listOf("생명과학·화학 과목에 관심 갖기", "보건 계열 진로 체험 참여", "임상병리학과 입시 정보 알아보기")),
        JobDef("designer", "시각 디자이너", "포스터, 로고, 화면을 디자인해요.", t("AE"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.FREE, WorkValue.LEARN), "국가자격", listOf("컴퓨터그래픽스운용기능사", "시각디자인기사"), "시각디자인", "직업훈련",
            listOf("포스터·로고·화면을 디자인해요", "고객의 요청을 듣고 시안을 만들어요", "디자인 도구로 작업물을 다듬어요"),
            listOf("내일배움카드로 디자인 툴 과정 듣기", "작업물로 포트폴리오 만들기", "컴퓨터그래픽스운용기능사 준비"),
            listOf("그림·디자인 동아리 참여", "디자인 프로그램 무료 강의 들어 보기", "디자인 계열 학과·특성화고 알아보기")),
        JobDef("video", "영상 편집자", "영상을 편집하고 효과를 넣어요.", t("AI"), PrepTime.SHORT, WorkPlace.OFFICE,
            listOf(WorkValue.FREE, WorkValue.LEARN), "국가자격", listOf("멀티미디어콘텐츠제작전문가"), "영상편집", "직업훈련",
            listOf("찍은 영상을 자르고 이어 붙여요", "자막·음악·효과를 넣어요", "완성본을 다듬어 올려요"),
            listOf("내일배움카드로 영상 편집 과정 듣기", "작업 영상으로 포트폴리오 만들기", "편집 아르바이트·외주로 경력 쌓기"),
            listOf("휴대폰으로 짧은 영상 만들어 보기", "방송·영상 동아리 참여", "영상 계열 학과·특성화고 알아보기")),
        JobDef("gameart", "게임 그래픽 디자이너", "게임 캐릭터와 배경을 그려요.", t("AI"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.FREE, WorkValue.LEARN), "국가자격", listOf("게임그래픽전문가"), "게임그래픽", "직업훈련",
            listOf("게임 캐릭터와 배경을 그려요", "3D 모델과 효과를 만들어요", "기획자와 맞춰 수정해요"),
            listOf("내일배움카드로 게임 그래픽 과정 듣기", "캐릭터·배경 작업으로 포트폴리오 만들기", "게임 회사 아트 직군 지원")),
        JobDef("photo", "사진작가", "인물, 제품, 행사 사진을 찍고 보정해요.", t("AR"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.FREE, WorkValue.HANDS), "국가자격", listOf("사진기능사"), "사진", "직업훈련",
            listOf("인물, 제품, 행사 사진을 찍어요", "찍은 사진을 보정해요", "촬영 장비를 관리해요"),
            listOf("내일배움카드로 사진·보정 과정 듣기", "스튜디오 보조로 경력 쌓기", "사진기능사 취득")),
        JobDef("writer", "콘텐츠 작가", "글, 대본, 웹소설을 써요.", t("AI"), PrepTime.NOW, WorkPlace.OFFICE,
            listOf(WorkValue.FREE, WorkValue.LEARN), "자격 없이 시작", emptyList(), "콘텐츠", "창업",
            listOf("글, 대본, 웹소설을 써요", "자료를 찾아 이야기를 만들어요", "독자 반응을 보고 다듬어요"),
            listOf("블로그나 플랫폼에 꾸준히 글 올리기", "공모전에 참여하기", "콘텐츠 제작 과정 들어 보기")),
        JobDef("florist", "플로리스트", "꽃을 손질하고 꽃다발과 장식을 만들어요.", t("AR"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HANDS, WorkValue.FREE), "국가자격", listOf("화훼장식기능사"), "플라워", "직업훈련",
            listOf("꽃을 손질하고 꽃다발을 만들어요", "행사장 꽃 장식을 해요", "꽃 주문과 재고를 관리해요"),
            listOf("내일배움카드로 플라워 과정 듣기", "꽃집 보조로 경력 쌓기", "화훼장식기능사 취득")),
        JobDef("interior", "실내건축 시공원", "도면에 맞춰 집과 가게 안을 꾸며요.", t("RA"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.HANDS, WorkValue.MONEY), "국가자격", listOf("실내건축기능사"), "인테리어", "직업훈련",
            listOf("도면에 맞춰 벽, 바닥, 천장을 시공해요", "자재를 고르고 치수를 재요", "현장을 정리하고 마감해요"),
            listOf("내일배움카드로 인테리어 시공 과정 듣기", "실내건축기능사 취득", "시공 현장 보조로 경력 쌓기")),
        JobDef("hair", "미용사", "머리를 자르고 염색, 펌을 해요.", t("AS"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HANDS, WorkValue.FREE), "국가자격", listOf("미용사(일반)"), "헤어미용", "직업훈련",
            listOf("머리를 자르고 염색, 펌을 해요", "손님에게 어울리는 스타일을 제안해요", "도구와 매장을 관리해요"),
            listOf("내일배움카드로 헤어 미용 과정 듣기", "미용사(일반) 자격 취득", "미용실 스태프로 시작해 경력 쌓기")),
        JobDef("nail", "네일 아티스트", "손톱을 다듬고 디자인해요.", t("AR"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HANDS, WorkValue.FREE), "국가자격", listOf("미용사(네일)"), "네일", "직업훈련",
            listOf("손톱을 다듬고 젤, 아트를 해요", "손님 취향에 맞게 디자인해요", "도구를 소독하고 관리해요"),
            listOf("내일배움카드로 네일 과정 듣기", "미용사(네일) 자격 취득", "네일숍 스태프로 경력 쌓기")),
        JobDef("makeup", "메이크업 아티스트", "얼굴형과 피부에 맞게 화장해요.", t("AS"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.FREE, WorkValue.HANDS), "국가자격", listOf("미용사(메이크업)"), "메이크업", "직업훈련",
            listOf("얼굴형과 피부에 맞게 화장해요", "촬영, 행사, 웨딩 메이크업을 해요", "도구와 위생을 관리해요"),
            listOf("내일배움카드로 메이크업 과정 듣기", "미용사(메이크업) 자격 취득", "숍이나 촬영 현장 보조로 경력 쌓기")),
        JobDef("petgroom", "반려동물 미용사", "반려동물 털을 다듬고 돌봐요.", t("RA"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HANDS, WorkValue.HELP), "민간자격", listOf("반려동물미용사(민간)"), "반려동물", "직업훈련",
            listOf("반려동물 털을 다듬고 목욕시켜요", "피부와 건강 상태를 살펴요", "보호자에게 관리법을 알려 줘요"),
            listOf("내일배움카드로 반려동물 미용 과정 듣기", "민간 자격 취득", "애견숍 보조로 경력 쌓기")),
        JobDef("social", "사회복지사", "도움이 필요한 사람을 지원해요.", t("SC"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가자격", listOf("사회복지사 2급", "사회복지사 1급"), "사회복지", "장학금",
            listOf("도움이 필요한 사람을 만나 상담해요", "필요한 지원을 찾아 연결해요", "지원 기록을 정리해요"),
            listOf("사회복지학과 진학 또는 학점은행제 알아보기", "실습 기관 찾기", "사회복지사 2급 취득 후 기관 지원"),
            listOf("봉사활동 꾸준히 해 보기", "사회복지 진로 체험 참여", "사회복지학과 입시 정보 알아보기")),
        JobDef("care", "보육교사", "어린이집에서 아이들을 돌보고 가르쳐요.", t("SA"), PrepTime.MID, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가자격", listOf("보육교사 2급"), "보육", "장학금",
            listOf("아이들을 돌보고 놀이로 가르쳐요", "하루 활동을 계획해요", "부모님과 아이 이야기를 나눠요"),
            listOf("보육교사 자격 과정(학과·학점은행) 알아보기", "어린이집 실습", "보육교사 2급 취득 후 지원"),
            listOf("아이 돌봄 봉사 해 보기", "유아교육·보육 진로 체험", "관련 학과 입시 정보 알아보기")),
        JobDef("caregiver", "요양보호사", "어르신의 생활을 곁에서 도와요.", t("SR"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가자격", listOf("요양보호사"), "요양보호사", "직업훈련",
            listOf("어르신의 식사, 목욕, 이동을 도와요", "건강 상태를 살피고 기록해요", "말벗이 되고 생활을 지원해요"),
            listOf("요양보호사 교육기관에서 교육 이수", "요양보호사 자격시험 합격", "요양원이나 방문요양센터에 지원")),
        JobDef("nurse", "간호조무사", "병원에서 환자를 돌봐요.", t("SR"), PrepTime.MID, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가자격", listOf("간호조무사"), "간호조무사", "직업훈련",
            listOf("환자 상태를 살피고 돌봐요", "진료를 준비하고 돕는 일을 해요", "병원 물품을 관리해요"),
            listOf("간호조무사 교육기관·내일배움카드 과정 알아보기", "병원 실습", "간호조무사 자격 시험 준비"),
            listOf("보건 계열 진로 체험 참여", "보건 계열 특성화고 알아보기", "응급처치 교육 들어 보기")),
        JobDef("rn", "간호사", "병원에서 환자를 돌보고 치료를 도와요.", t("SI"), PrepTime.LONG, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가면허", listOf("간호사 면허"), "간호", "장학금",
            listOf("환자를 돌보고 상태를 살펴요", "처방에 따라 투약과 처치를 해요", "환자와 가족에게 설명해요"),
            listOf("간호학과 진학 알아보기", "장학금·학자금 지원 확인", "간호사 국가시험 합격 후 면허 취득")),
        JobDef("physio", "물리치료사", "운동과 기구로 재활 치료를 해요.", t("SR"), PrepTime.LONG, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가면허", listOf("물리치료사 면허"), "물리치료", "장학금",
            listOf("운동과 기구로 재활 치료를 해요", "환자 상태를 평가해요", "치료 기록을 남겨요"),
            listOf("물리치료학과(전문대·대학) 진학 알아보기", "장학금·학자금 지원 확인", "국가시험 합격 후 면허 취득")),
        JobDef("dental", "치과위생사", "치과에서 구강 검사와 예방 치료를 도와요.", t("SC"), PrepTime.LONG, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가면허", listOf("치과위생사 면허"), "치과위생", "장학금",
            listOf("스케일링과 구강 검사를 해요", "올바른 양치법을 알려 줘요", "치과 진료를 준비해요"),
            listOf("치위생학과(전문대·대학) 진학 알아보기", "장학금·학자금 지원 확인", "국가시험 합격 후 면허 취득")),
        JobDef("vettech", "동물보건사", "동물병원에서 진료와 간호를 도와요.", t("SI"), PrepTime.MID, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.LEARN), "국가자격", listOf("동물보건사"), "동물보건", "장학금",
            listOf("동물병원에서 진료를 도와요", "입원한 동물을 돌봐요", "검사와 장비를 준비해요"),
            listOf("동물보건 관련 학과 진학 알아보기", "동물병원 실습", "동물보건사 자격시험 준비")),
        JobDef("trainer", "생활스포츠지도사", "운동을 가르치고 운동 계획을 짜 줘요.", t("SR"), PrepTime.MID, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.HANDS), "국가자격", listOf("생활스포츠지도사 2급"), "스포츠", "취업",
            listOf("운동 방법을 가르쳐요", "회원에게 맞는 운동 계획을 짜요", "자세와 안전을 살펴요"),
            listOf("운동 종목 하나를 정해 실력 쌓기", "생활스포츠지도사 2급 필기·실기 준비", "헬스장이나 체육센터에 지원")),
        JobDef("youthguide", "청소년지도사", "청소년 활동을 만들고 고민을 함께 나눠요.", t("SE"), PrepTime.MID, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.LEARN), "국가자격", listOf("청소년지도사 2급"), "청소년", "장학금",
            listOf("청소년 활동 프로그램을 만들고 운영해요", "청소년의 고민을 듣고 도와요", "수련관이나 센터 일을 해요"),
            listOf("관련 학과 진학이나 학점 이수 알아보기", "청소년 기관 봉사·실습", "청소년지도사 2급 준비")),
        JobDef("jobcounsel", "직업상담사", "일을 찾는 사람과 상담해 맞는 일을 찾아 줘요.", t("SE"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.HELP, WorkValue.STABLE), "국가자격", listOf("직업상담사 2급"), "직업상담", "취업",
            listOf("구직자와 상담해 맞는 일을 찾아 줘요", "이력서와 면접 준비를 도와요", "채용 정보를 모아 안내해요"),
            listOf("내일배움카드로 직업상담사 과정 듣기", "직업상담사 2급 취득", "고용센터나 취업 지원 기관에 지원")),
        JobDef("cs", "고객상담원", "전화나 채팅으로 고객 문의에 답해요.", t("SC"), PrepTime.NOW, WorkPlace.OFFICE,
            listOf(WorkValue.REGULAR, WorkValue.HELP), "자격 없이 시작", emptyList(), "고객상담", "취업",
            listOf("전화나 채팅으로 문의에 답해요", "문제를 기록하고 담당 부서에 연결해요", "고객 정보를 정리해요"),
            listOf("상담 센터 채용 공고 찾아보기", "입사 후 상담 교육 받기", "경력 쌓아 팀장이나 교육 담당으로")),
        JobDef("hotel", "호텔리어", "호텔에서 손님을 맞이하고 머무는 동안 도와요.", t("SE"), PrepTime.MID, WorkPlace.PEOPLE,
            listOf(WorkValue.HELP, WorkValue.LEARN), "국가자격", listOf("호텔서비스사"), "호텔", "취업",
            listOf("체크인, 체크아웃을 도와요", "손님 요청을 해결해요", "예약과 객실을 관리해요"),
            listOf("호텔관광 관련 과정이나 학과 알아보기", "호텔 아르바이트로 경험 쌓기", "호텔서비스사 취득")),
        JobDef("guide", "관광 가이드", "여행객에게 일정과 관광지를 안내해요.", t("SE"), PrepTime.MID, WorkPlace.FIELD,
            listOf(WorkValue.LEARN, WorkValue.FREE), "국가자격", listOf("국내여행안내사", "관광통역안내사"), "관광", "취업",
            listOf("여행객에게 일정을 안내해요", "관광지 역사와 이야기를 설명해요", "여행 중 생기는 문제를 해결해요"),
            listOf("국내여행안내사 준비", "여행사 보조로 경력 쌓기", "외국어를 익혀 관광통역안내사 도전")),
        JobDef("sales", "매장 관리자", "매장을 운영하고 직원을 관리해요.", t("ES"), PrepTime.NOW, WorkPlace.PEOPLE,
            listOf(WorkValue.LEAD, WorkValue.MONEY), "국가자격", listOf("유통관리사 2급"), "유통", "취업",
            listOf("매장 매출과 재고를 관리해요", "직원 일정과 업무를 나눠요", "손님 불편을 해결해요"),
            listOf("매장 아르바이트로 시작해 경력 쌓기", "유통관리사 준비", "관리자 채용 공고에 지원"),
            listOf("학교 행사·동아리 운영 맡아 보기", "판매 아르바이트 체험", "유통·경영 계열 학과 알아보기")),
        JobDef("salesrep", "영업 사원", "고객을 만나 상품을 소개하고 계약해요.", t("ES"), PrepTime.NOW, WorkPlace.PEOPLE,
            listOf(WorkValue.MONEY, WorkValue.LEAD), "자격 없이 시작", emptyList(), "영업", "취업",
            listOf("고객을 만나 상품을 소개해요", "계약을 맺고 관리해요", "판매 목표를 세우고 확인해요"),
            listOf("영업직 신입 채용 공고 찾아보기", "면접 연습하기", "입사 후 상품 교육 받기")),
        JobDef("marketer", "마케터", "상품을 알릴 방법을 기획하고 광고를 운영해요.", t("EA"), PrepTime.SHORT, WorkPlace.OFFICE,
            listOf(WorkValue.LEAD, WorkValue.LEARN), "자격 없이 시작", emptyList(), "마케팅", "직업훈련",
            listOf("상품을 알릴 방법을 기획해요", "SNS와 광고를 운영해요", "반응을 분석해 다음 계획을 세워요"),
            listOf("내일배움카드로 디지털 마케팅 과정 듣기", "SNS 계정을 직접 운영해 보기", "마케팅 인턴에 지원")),
        JobDef("owner", "가게 창업", "내 가게를 열고 운영해요.", t("EC"), PrepTime.SHORT, WorkPlace.PEOPLE,
            listOf(WorkValue.FREE, WorkValue.LEAD), "자격 없이 시작", emptyList(), "창업", "창업",
            listOf("가게를 열 준비를 하고 운영해요", "상품과 가격을 정해요", "돈 관리와 홍보를 해요"),
            listOf("하고 싶은 업종에서 먼저 일해 보기", "청년 창업 지원과 교육 알아보기", "작게 시작해 계획 다듬기")),
        JobDef("office", "사무·회계 담당자", "서류와 돈 관리를 꼼꼼하게 해요.", t("CE"), PrepTime.SHORT, WorkPlace.OFFICE,
            listOf(WorkValue.STABLE, WorkValue.REGULAR), "국가자격", listOf("컴퓨터활용능력 2급", "전산회계 2급(민간)"), "사무", "직업훈련",
            listOf("서류를 만들고 정리해요", "돈이 들어오고 나간 것을 기록해요", "엑셀로 자료를 정리해요"),
            listOf("내일배움카드로 사무·회계 과정 듣기", "컴퓨터활용능력·전산회계 취득", "사무직 채용 공고에 지원"),
            listOf("엑셀·문서 프로그램 익히기", "상업·회계 계열 특성화고 알아보기", "전산회계 자격 공부 시작")),
        JobDef("tax", "세무 사무원", "장부를 정리하고 세금 신고를 도와요.", t("CI"), PrepTime.SHORT, WorkPlace.OFFICE,
            listOf(WorkValue.STABLE, WorkValue.REGULAR), "민간자격", listOf("전산세무 2급(민간)", "전산회계 1급(민간)"), "전산세무", "직업훈련",
            listOf("장부를 정리하고 세금 신고를 도와요", "영수증과 서류를 확인해요", "고객 문의에 답해요"),
            listOf("내일배움카드로 전산세무 과정 듣기", "전산회계·전산세무 자격 취득", "세무사 사무실에 지원")),
        JobDef("librarian", "사서", "도서관 자료를 관리하고 찾아 줘요.", t("CI"), PrepTime.LONG, WorkPlace.OFFICE,
            listOf(WorkValue.STABLE, WorkValue.LEARN), "국가자격", listOf("정사서 2급"), "도서관", "장학금",
            listOf("책과 자료를 정리하고 관리해요", "이용자에게 자료를 찾아 줘요", "독서 프로그램을 운영해요"),
            listOf("문헌정보학과 진학 알아보기", "도서관 봉사·근로로 경험 쌓기", "정사서 2급 취득 후 지원")),
        JobDef("logistics", "물류 관리원", "물건이 들어오고 나가는 걸 관리해요.", t("CR"), PrepTime.NOW, WorkPlace.FIELD,
            listOf(WorkValue.STABLE, WorkValue.REGULAR), "국가자격", listOf("물류관리사"), "물류", "취업",
            listOf("물건이 들어오고 나가는 걸 관리해요", "재고를 확인하고 기록해요", "배송 일정을 맞춰요"),
            listOf("물류센터 입고·출고 업무로 시작", "현장 자격(지게차 등) 취득", "물류관리사 준비")),
        JobDef("forklift", "지게차 운전원", "지게차로 무거운 물건을 옮기고 쌓아요.", t("RC"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.HANDS, WorkValue.MONEY), "국가자격", listOf("지게차운전기능사"), "지게차", "직업훈련",
            listOf("지게차로 물건을 옮기고 쌓아요", "작업 전 장비를 점검해요", "창고 안전 수칙을 지켜요"),
            listOf("내일배움카드로 지게차 과정 듣기", "지게차운전기능사 취득", "물류센터·공장에 지원")),
        JobDef("driver", "배송 기사", "정해진 경로로 물건을 배송해요.", t("RC"), PrepTime.NOW, WorkPlace.FIELD,
            listOf(WorkValue.FREE, WorkValue.MONEY), "운전면허", emptyList(), "운송", "취업",
            listOf("정해진 경로로 물건을 배송해요", "배송 앱으로 일정을 관리해요", "차량을 점검해요"),
            listOf("운전면허 취득", "배송 업체 아르바이트로 시작", "영업용 화물차는 화물운송종사자격 알아보기")),
        JobDef("security", "보안 요원", "건물 출입을 관리하고 순찰해요.", t("RC"), PrepTime.NOW, WorkPlace.FIELD,
            listOf(WorkValue.STABLE, WorkValue.REGULAR), "교육 이수", emptyList(), "경비", "취업",
            listOf("건물 출입을 관리해요", "순찰하며 이상한 점을 살펴요", "사고가 나면 신고하고 대응해요"),
            listOf("일반경비원 신임교육 이수", "경비·보안 업체에 지원", "경력 쌓아 시설관리로")),
        JobDef("facility", "시설관리원", "건물 전기, 기계 설비를 점검하고 고쳐요.", t("RC"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.STABLE, WorkValue.REGULAR), "국가자격", listOf("전기기능사", "공조냉동기계기능사"), "건물관리", "직업훈련",
            listOf("건물 전기, 기계 설비를 점검해요", "고장 난 곳을 고치거나 업체를 불러요", "안전 점검을 기록해요"),
            listOf("내일배움카드로 건물 설비 과정 듣기", "전기기능사나 공조냉동기계기능사 취득", "아파트·빌딩 관리사무소에 지원")),
        JobDef("electric", "전기 기술자", "건물에 전기 설비를 설치하고 고쳐요.", t("RC"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.HANDS, WorkValue.STABLE), "국가자격", listOf("전기기능사", "전기산업기사"), "전기", "직업훈련",
            listOf("건물 전기 설비를 설치해요", "고장 난 배선을 점검하고 고쳐요", "안전 기준에 맞는지 확인해요"),
            listOf("내일배움카드로 전기 과정 듣기", "전기기능사 취득", "전기공사 업체 보조로 경력 쌓기")),
        JobDef("welder", "용접원", "금속을 녹여 붙여 구조물을 만들어요.", t("RC"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.HANDS, WorkValue.MONEY), "국가자격", listOf("용접기능사"), "용접", "직업훈련",
            listOf("금속을 녹여 붙여요", "도면을 보고 구조물을 만들어요", "용접 부위를 검사해요"),
            listOf("내일배움카드나 폴리텍 용접 과정 듣기", "용접기능사 취득", "조선·건설·제조 현장에 지원")),
        JobDef("hvac", "냉동공조 기술자", "에어컨과 냉동 설비를 설치하고 고쳐요.", t("RI"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.HANDS, WorkValue.STABLE), "국가자격", listOf("공조냉동기계기능사"), "공조냉동", "직업훈련",
            listOf("에어컨, 냉동 설비를 설치해요", "고장을 점검하고 수리해요", "정기적으로 유지 관리해요"),
            listOf("내일배움카드로 공조냉동 과정 듣기", "공조냉동기계기능사 취득", "설비 업체 보조로 경력 쌓기")),
        JobDef("farmer", "스마트팜 농업인", "센서와 앱으로 농장을 관리하며 작물을 길러요.", t("RI"), PrepTime.SHORT, WorkPlace.FIELD,
            listOf(WorkValue.HANDS, WorkValue.FREE), "국가자격", listOf("유기농업기능사"), "스마트팜", "취업",
            listOf("온도, 습도를 조절하며 작물을 길러요", "센서와 앱으로 농장을 관리해요", "수확한 작물을 판매해요"),
            listOf("스마트팜 교육 과정 알아보기", "농장 실습으로 경험 쌓기", "청년 농업인 지원 알아보기")),
        JobDef("civil", "행정 공무원", "주민에게 필요한 행정 일을 해요.", t("CS"), PrepTime.MID, WorkPlace.OFFICE,
            listOf(WorkValue.STABLE, WorkValue.REGULAR), "공채 시험", emptyList(), null, "취업",
            listOf("민원을 처리하고 서류를 발급해요", "정책과 사업을 운영해요", "주민에게 필요한 일을 지원해요"),
            listOf("공무원 시험 과목 알아보기", "9급 공채 시험 준비", "저소득층 구분모집도 알아보기")),
        JobDef("police", "경찰관", "사건과 사고를 막고 시민을 지켜요.", t("SE"), PrepTime.MID, WorkPlace.FIELD,
            listOf(WorkValue.HELP, WorkValue.STABLE), "공채 시험", emptyList(), null, "취업",
            listOf("순찰하며 사건, 사고를 막아요", "신고를 받고 현장에 출동해요", "범죄를 수사해요"),
            listOf("경찰공무원 채용 시험 알아보기", "필기와 체력 시험 준비", "경찰행정학과 진학도 방법")),
        JobDef("firefighter", "소방관", "불을 끄고 위험에 처한 사람을 구해요.", t("RS"), PrepTime.MID, WorkPlace.FIELD,
            listOf(WorkValue.HELP, WorkValue.STABLE), "공채 시험", emptyList(), null, "취업",
            listOf("화재를 진압하고 사람을 구해요", "응급 환자를 이송해요", "화재 예방 점검을 해요"),
            listOf("소방공무원 채용 시험 알아보기", "필기와 체력 시험 준비", "소방 관련 학과 진학도 방법")),
        JobDef("nco", "부사관", "군에서 병사를 이끌고 장비를 운용해요.", t("RE"), PrepTime.MID, WorkPlace.FIELD,
            listOf(WorkValue.STABLE, WorkValue.LEAD), "선발 시험", emptyList(), null, "취업",
            listOf("부대에서 병사를 이끌고 관리해요", "장비를 운용하고 정비해요", "훈련을 계획하고 진행해요"),
            listOf("육·해·공군 부사관 모집 공고 확인", "필기·체력·면접 준비", "부사관 관련 학과 진학도 방법")),
    )

    private val jobById get() = jobs.associateBy { it.id }

    /** 직업 적합도 (0~1): 흥미 유형 65% · 일에서 중요한 것 15% · 준비 기간 12% · 일하는 곳 8% */
    private fun fit(j: JobDef, p: InterestProfile): Double {
        val max = p.max.toDouble()
        val type = ((p.scores[j.types[0]] ?: 0) / max) * 0.62 +
            (j.types.getOrNull(1)?.let { (p.scores[it] ?: 0) / max } ?: 0.0) * 0.38
        val values = j.values.count { it in p.values }.coerceAtMost(2) / 2.0
        val prep = when (val mine = p.prep) {
            null -> 0.5
            else -> if (j.prep.months <= mine.months) 1.0 else (1.0 - (j.prep.ordinal - mine.ordinal) * 0.5).coerceAtLeast(0.0)
        }
        val place = when (p.place) {
            null, WorkPlace.ANY -> 0.5
            j.place -> 1.0
            else -> 0.0
        }
        return type * 0.65 + values * 0.15 + prep * 0.12 + place * 0.08
    }

    /** 추천 이유 한 줄: 가장 맞는 유형 + 고른 것 중 하나 */
    private fun reasonOf(j: JobDef, p: InterestProfile): String {
        val first = j.types.firstOrNull { it in p.top3 } ?: j.types.first()
        val lead = when (first) {
            p.top3.firstOrNull() -> "${first.label}이 가장 높고"
            in p.top3 -> "${first.label} 점수가 높고"
            else -> "${first.label} 성향과 맞고"
        }
        val value = j.values.firstOrNull { it in p.values }
        val tail = when {
            value != null -> "'${value.label}'을 골랐어요"
            p.place != null && p.place == j.place -> "${j.place.label}에서 일하고 싶다고 했어요"
            p.prep != null && j.prep.months <= p.prep.months -> when (j.prep) {
                PrepTime.NOW -> "바로 시작할 수 있어요"
                PrepTime.SHORT -> "6개월 안에 시작할 수 있어요"
                PrepTime.MID -> "1~2년 준비하면 시작할 수 있어요"
                PrepTime.LONG -> "대학을 거쳐 시작하는 일이에요"
            }
            else -> "이 유형과 잘 맞는 일이에요"
        }
        return "$lead, $tail"
    }

    override suspend fun recommendJobs(userId: String, profile: InterestProfile, stage: UserStage): List<JobRecommendation> {
        latency(700)
        return jobs
            .map { it to fit(it, profile) }
            .sortedByDescending { it.second }
            .map { (j, score) ->
                JobRecommendation(
                    id = j.id,
                    name = j.name,
                    description = j.description,
                    reason = reasonOf(j, profile),
                    types = j.types,
                    match = (score * 100).toInt().coerceIn(35, 98),
                    prep = j.prep,
                    certTag = j.certTag,
                )
            }
    }

    override suspend fun jobDetail(userId: String, job: JobRecommendation, stage: UserStage): JobDetail {
        latency(200)
        val def = jobById[job.id]
        val teen = stage == UserStage.TEEN || stage == UserStage.CHILD
        val practices = if (teen) listOf(JobPractice("parttime", "아르바이트 면접"), JobPractice("school", "진학 면접"))
        else listOf(JobPractice("parttime", "아르바이트 면접"), JobPractice("job", "취업 면접 (실전)"), JobPractice("jobcenter", "고용센터 취업 상담"))
        val teenPath = def?.let {
            it.teen ?: listOfNotNull(
                "${it.name} 직업 체험이나 관련 동아리 해 보기",
                "관련 학과나 특성화고 알아보기",
                it.certificates.firstOrNull()?.let { c -> "$c 공부 시작" },
            )
        }
        return JobDetail(
            id = job.id,
            name = job.name,
            description = job.description,
            types = job.types,
            tasks = def?.tasks ?: listOf(job.description),
            path = def?.let { if (teen) teenPath.orEmpty() else it.youth } ?: buildRoadmap(userId, job, stage).steps.map { it.title },
            certificates = def?.certificates.orEmpty(),
            policyQuery = def?.policyQuery,
            practices = practices,
            keyword = def?.keyword,
        )
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
            RoleplayScenario("store", "가게에서 물건 사기", "생활", "가게 점원", "문구점에서 공책을 사려고 해요.", "원하는 물건 사고 계산하기", RoleplayLevel.EASY, setOf(UserStage.CHILD)),
            opening = "어서 오세요! 뭘 찾고 있어요?",
            lines = listOf("공책은 저쪽에 있어요. 줄 공책이랑 무지 공책 중에 뭐가 좋아요?", "좋아요. 1,500원이에요. 어떻게 계산할래요?", "고마워요. 영수증 필요해요?"),
            checkpoints = listOf(listOf("주세요", "있어요", "찾") to "원하는 물건 말하기", listOf("감사", "고마") to "고맙다고 인사하기"),
            documents = emptyList(),
            better = listOf("저거요" to "공책 한 권 주세요")
        ),
        Script(
            RoleplayScenario("bank", "은행 계좌 개설", "금융", "은행 창구 직원", "처음으로 내 이름의 통장을 만들러 왔어요.", "서류 확인하고 계좌 만들기", RoleplayLevel.EASY, teenAdult),
            opening = "안녕하세요, 어떤 업무 도와드릴까요?",
            lines = listOf("네, 신규 계좌 개설이요. 신분증 가지고 오셨나요?", "계좌를 어디에 쓰실 예정이세요? 목적을 확인해야 해서요.", "체크카드도 같이 만드시겠어요?", "마지막으로 비밀번호 4자리를 입력해 주세요."),
            checkpoints = listOf(listOf("신분증", "학생증") to "신분증 준비", listOf("월급", "용돈", "수당", "저축", "목적") to "계좌 만드는 목적 설명", listOf("체크카드", "카드") to "체크카드 발급 여부"),
            documents = listOf("신분증 (주민등록증·학생증 등)", "계좌 목적 증빙 (예: 근로계약서, 재직증명서)"),
            better = listOf("통장 만들러 왔어요" to "처음으로 입출금 통장을 만들고 싶어요. 월급 받을 계좌예요")
        ),
        Script(
            RoleplayScenario("community-center", "주민센터 전입신고", "생활", "주민센터 직원", "새 집으로 이사하고 전입신고를 하러 왔어요.", "전입신고·확정일자 챙기기", RoleplayLevel.NORMAL, adult),
            opening = "다음 분 오세요. 무슨 일로 오셨어요?",
            lines = listOf("전입신고시군요. 신분증 주시겠어요?", "새 주소가 어디세요? 세대주로 하실 건가요?", "임대차 계약서 가져오셨어요? 확정일자도 같이 받으실 수 있어요.", "처리됐습니다. 더 궁금한 거 있으세요?"),
            checkpoints = listOf(listOf("신분증") to "신분증 준비", listOf("확정일자") to "확정일자 함께 받기", listOf("계약서") to "임대차 계약서 챙기기"),
            documents = listOf("신분증", "임대차 계약서 (확정일자용)"),
            better = listOf("이사 왔어요" to "이사해서 전입신고하고, 확정일자도 같이 받고 싶어요")
        ),
        Script(
            RoleplayScenario("landlord", "집주인에게 수리 요청", "주거", "집주인", "보일러가 고장 나서 집주인에게 전화해요.", "수리 일정 정하기", RoleplayLevel.NORMAL, adult),
            opening = "여보세요? 네, 무슨 일이에요?",
            lines = listOf("보일러요? 언제부터 그랬어요? 혹시 사용하다가 망가뜨린 건 아니죠?", "음, 이번 주는 좀 바쁜데, 다음 주에 보면 안 될까요?", "알겠어요. 기사님한테 연락해 볼게요. 수리비는 어떻게 할까요?"),
            checkpoints = listOf(listOf("언제", "부터", "어제", "오늘") to "고장 난 시점 설명", listOf("빨리", "이번 주", "내일", "추워") to "수리가 급한 이유", listOf("수리비", "비용", "부담") to "수리비 부담 확인"),
            documents = listOf("고장 부위 사진", "임대차 계약서 (수리 책임 조항 확인)"),
            better = listOf("보일러 고장났어요" to "어제부터 보일러에서 온수가 안 나와요. 날이 추워서 이번 주 안에 수리가 필요해요")
        ),
        Script(
            RoleplayScenario("parttime", "아르바이트 면접", "면접", "카페 점장", "동네 카페 아르바이트 면접을 봐요.", "나를 소개하고 조건 묻기", RoleplayLevel.NORMAL, teenAdult),
            opening = "반가워요. 간단하게 자기소개 해 줄래요?",
            lines = listOf("일할 수 있는 요일이랑 시간이 어떻게 돼요?", "손님이 많을 때 실수하면 어떻게 할 거예요?", "궁금한 거 있으면 물어봐요."),
            hardLines = listOf("경력이 없네요. 그럼 우리가 왜 뽑아야 하죠?", "주말에 갑자기 나와 달라고 하면 나올 수 있어요?"),
            checkpoints = listOf(listOf("요일", "시간", "주말", "평일") to "근무 가능한 시간", listOf("시급", "급여", "근로계약서", "계약서") to "시급·근로계약서 확인"),
            documents = listOf("신분증", "보건증 (음식점·카페)", "통장 사본", "청소년은 부모님 또는 후견인 동의서"),
            better = listOf("아무 때나 돼요" to "평일 오후 4시 이후와 토요일에 일할 수 있어요")
        ),
        Script(
            RoleplayScenario("school", "진학 면접", "면접", "면접관 선생님", "특성화고·대학 입학 면접을 봐요.", "지원 동기와 활동 말하기", RoleplayLevel.NORMAL, setOf(UserStage.TEEN)),
            opening = "지원자, 우리 학교(학과)에 지원한 이유를 말해 볼까요?",
            lines = listOf("그 분야에 관심을 갖게 된 계기가 있나요?", "관련해서 직접 해 본 활동이 있다면 소개해 주세요.", "입학하면 가장 해 보고 싶은 게 뭔가요?"),
            checkpoints = listOf(listOf("계기", "때문", "관심") to "관심을 갖게 된 계기", listOf("활동", "동아리", "봉사", "대회") to "직접 해 본 활동 예시"),
            documents = listOf("생활기록부", "자기소개서"),
            better = listOf("그냥 좋아서요" to "중학교 때 동아리에서 ○○를 해 보고 이 분야에 관심이 생겼어요")
        ),
        Script(
            RoleplayScenario("job", "취업 면접 (실전)", "면접", "회사 면접관", "첫 정규직 면접이에요. 꼬리 질문이 이어져요.", "경험으로 침착하게 답하기", RoleplayLevel.HARD, adult),
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

    override suspend fun roleplayReply(userId: String, scenarioId: String, stage: UserStage, history: List<RoleplayTurn>): RoleplayReply {
        latency(900)
        val s = script(scenarioId)
        val hard = levelFor(s.scenario, stage) == RoleplayLevel.HARD
        // 실전 난이도는 압박·꼬리 질문을 사이사이에 섞는다
        val lines = if (hard && s.hardLines.isNotEmpty()) {
            s.lines.flatMapIndexed { i, l -> listOfNotNull(l, s.hardLines.getOrNull(i)) }
        } else s.lines
        val partnerTurns = history.count { !it.fromUser } - 1  // 첫 인사 제외
        val line = lines.getOrNull(partnerTurns)
        return RoleplayReply(line ?: "네, 오늘은 여기까지 할게요. 수고했어요.", finished = line == null)
    }

    /** 챙길 것 · 힌트 (서버 규칙과 같게: 내가 한 말에 낱말이 나오면 챙김). 연습 전 준비 화면에도 쓴다 */
    override suspend fun roleplayHint(scenarioId: String, history: List<RoleplayTurn>, plan: RoleplayPlan): RoleplayHint {
        latency(200)
        val s = script(scenarioId)
        val said = history.filter { it.fromUser }.joinToString(" ") { it.text }
        val checks = s.checkpoints.map { (words, label) -> RoleplayCheck(label, words.any { said.contains(it) }, "check", null) }
        val next = checks.firstOrNull { !it.ok }
        val example = s.better.firstOrNull()?.second ?: "${next?.label ?: "궁금한 점"}에 대해 물어봐요"
        return RoleplayHint(
            done = next == null,
            label = next?.label ?: "다 챙겼어요",
            example = if (next == null) "궁금한 게 더 없으면 인사하고 마무리해요" else example,
            kind = "check",
            remaining = checks.count { !it.ok },
            checks = checks,
        )
    }

    override suspend fun roleplayFeedback(userId: String, scenarioId: String, history: List<RoleplayTurn>, save: Boolean): RoleplayFeedback {
        latency(1200)
        val s = script(scenarioId)
        val said = history.filter { it.fromUser }.joinToString(" ") { it.text }
        val good = mutableListOf<String>()
        val missed = mutableListOf<String>()
        s.checkpoints.forEach { (words, msg) ->  // msg: 확인할 항목 이름
            if (words.any { said.contains(it) }) good.add(msg) else missed.add(msg)
        }
        if (history.count { it.fromUser } >= 3) good.add("끝까지 대화를 이어 갔어요")
        val roleplayId = if (save) {
            val record = RoleplayRecord(
                id = FakeRecords.roleplays.size + 1L,
                scenarioId = s.scenario.id,
                title = s.scenario.title,
                goodCount = good.size,
                missedCount = missed.size,
                createdAt = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.KOREA).format(java.util.Date()),
            )
            FakeRecords.roleplays.add(record)
            record.id
        } else null
        return RoleplayFeedback(good, missed, s.documents, s.better, roleplayId)
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
                outline = listOf("지금 나의 상황", "장학금이 필요한 이유", "받게 되면 어디에 쓸지", "앞으로의 계획")),
            FormField("plan", "학업 계획", "앞으로 공부 계획", "이번 학기·1년 동안 무엇을 할지 구체적으로 써요.", multiline = true, essay = true,
                outline = listOf("이번 학기 목표", "이를 위해 할 일", "1년 뒤 모습"))
        )
        "program" -> commonFields.take(3) + listOf(
            FormField("guardian", "보호자(담당자) 연락처", "시설 선생님 연락처", "보호자 대신 시설 담당 선생님 연락처를 써도 돼요."),
            FormField("motive", "참가 동기", "참가하고 싶은 이유", "이 프로그램에서 기대하는 것을 짧게 써요.", multiline = true, essay = true,
                outline = listOf("프로그램을 알게 된 계기", "해 보고 싶은 것", "끝나고 얻고 싶은 것"))
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
                outline = listOf("한 줄로 나를 표현하기", "직무와 이어지는 경험", "그 경험에서 배운 점", "입사 후 하고 싶은 일"))
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
        // hwpx · pdf 에서 실제 항목을 찾는다 (읽지 못하면 FormParser.ReadError)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            FormParser.parse(bytes, mimeType)
        }
    }

    // ───────────── 이력서·자기소개서·활동 기록 ─────────────

    override suspend fun generateDocument(userId: String, type: DocType, appRecords: AppRecords): GeneratedDoc {
        latency(1300)
        val growth = runCatching { AppContainer.api.getGrowth(userId) }.getOrNull()
        val checklists = growth?.completedChecklists.orEmpty()
        val savedCount = appRecords.savedPolicyCount
        val sims = growth?.simulations.orEmpty()

        val records = buildList {
            checklists.forEach { add(DocSentence("${it.policyName} 신청 절차를 스스로 확인하고 서류를 준비해 신청을 마쳤습니다.", "체크리스트 완료 · ${it.policyName}")) }
            if (sims.isNotEmpty()) add(DocSentence("생활비 계획을 ${sims.size}번 세워 보며 지출을 관리하는 연습을 했습니다.", "생활 시뮬레이션 ${sims.size}회"))
            if (savedCount > 0) add(DocSentence("자립에 필요한 제도 ${savedCount}가지를 찾아보고 정리했습니다.", "저장한 정책 ${savedCount}개"))
        }
        val interestLine = appRecords.interestSummary?.takeIf { it.isNotBlank() }?.let { DocSentence(it.trim(), "흥미 탐색 결과") }
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
