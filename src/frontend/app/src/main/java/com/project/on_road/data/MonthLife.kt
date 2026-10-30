package com.project.on_road.data

import kotlin.random.Random

/**
 * 한 달 살아보기: 수입·집·비상금을 정하고 4주를 보내 본다.
 * 일마다 한 주에 일어날 확률이 정해져 있고, 매주 그 확률대로 랜덤으로 굴린다 (개수 제한 없음).
 * 서버 없이 앱 안 규칙으로만 계산한다 (금액은 만원, 예시 기준).
 */
object MonthLife {

    /** 사는 곳: 한 달 주거비 (공과금 포함 예시) */
    enum class Home(val label: String, val hint: String, val cost: Int) {
        STUDIO("원룸 월세", "보증금 500 · 월세 40 안팎", 45),
        PUBLIC("공공임대", "LH·SH 청년 임대", 25),
        SHARE("쉐어하우스", "방 따로, 거실·주방 같이", 35),
    }

    const val PHONE = 5
    /** 일주일 식비 + 교통비 */
    const val WEEK_FOOD = 8
    const val WEEK_TRANSPORT = 2
    const val WEEKS = 4
    val INCOME_PRESETS = listOf(90, 120, 150, 180)
    val EMERGENCY_PRESETS = listOf(0, 10, 20, 30)

    data class Setup(val income: Int, val home: Home, val emergency: Int, val seed: Int)

    /**
     * 선택지.
     * money: 생활비 변화, save: 비상금으로 옮기는 돈, later/laterText: 다음 주에 생기는 돈 변화,
     * good: 잘한 선택인지 (null = 상관없음)
     */
    data class Choice(
        val label: String,
        val money: Int,
        val result: String,
        val good: Boolean? = null,
        val save: Int = 0,
        val later: Int = 0,
        val laterText: String? = null,
        val lesson: String? = null,
    )

    data class Event(
        val id: String,
        val title: String,
        val body: String,
        /** 한 주에 일어날 확률 (0~1) */
        val chance: Double,
        val choices: List<Choice>,
        /** 한 달에 한 번만 */
        val once: Boolean = true,
    ) {
        val percent: Int get() = (chance * 100).toInt()
    }

    data class Happening(val event: Event, val picked: Choice? = null)

    data class Week(
        val no: Int,
        val happenings: List<Happening>,
        /** 이번 주 시작에 나간 식비·교통비 */
        val base: Int,
        /** 지난주 선택 때문에 이번 주에 생긴 변화 */
        val carried: Int = 0,
        val carriedText: String? = null,
    ) {
        val current: Happening? get() = happenings.firstOrNull { it.picked == null }
        val done: Boolean get() = happenings.all { it.picked != null }
    }

    data class State(
        val setup: Setup,
        /** 쓸 수 있는 생활비 */
        val cash: Int,
        /** 따로 떼어 둔 비상금 */
        val emergency: Int,
        val weeks: List<Week>,
        /** 다음 주로 넘어갈 돈 변화 */
        val pendingLater: Int = 0,
        val pendingLaterText: String? = null,
        /** 비상금에서 꺼내 쓴 돈 */
        val usedEmergency: Int = 0,
        /** 비상금까지 다 쓰고 모자란 돈 */
        val shortage: Int = 0,
    ) {
        val week: Week get() = weeks.last()
        val current: Happening? get() = week.current
        /** 4주차까지 모든 일을 골랐으면 끝 */
        val done: Boolean get() = week.no == WEEKS && week.done
        val left: Int get() = cash + emergency
        val history: List<Happening> get() = weeks.flatMap { it.happenings }
    }

    data class Report(
        val title: String,
        val summary: String,
        val left: Int,
        val usedEmergency: Int,
        val shortage: Int,
        val good: List<String>,
        val missed: List<String>,
        val tips: List<String>,
        /** 이어서 해 볼 것 (실전 연습 · 정책 찾기 · 생활비 배분) */
        val nextSteps: List<NextStep> = emptyList(),
        /** 아쉬운 선택을 한 일 id */
        val badEvents: List<String> = emptyList(),
    )

    fun fixedCost(home: Home): Int = home.cost + PHONE
    fun weekCost(): Int = WEEK_FOOD + WEEK_TRANSPORT

    /** 수입에서 집·통신비를 내고, 비상금을 떼고 남은 생활비 (모자라면 음수) */
    fun startingCash(setup: Setup): Int = setup.income - fixedCost(setup.home) - setup.emergency

    fun start(setup: Setup): State {
        val s = State(setup, cash = 0, emergency = setup.emergency, weeks = emptyList())
        // 첫 달 생활비가 처음부터 모자라면 비상금 → 부족 순으로 메운다
        return beginWeek(spend(s, startingCash(setup)), 1)
    }

    /** 생활비에 delta 반영. 모자라면 비상금 → 그래도 모자라면 부족으로 */
    private fun spend(s: State, delta: Int): State {
        var cash = s.cash + delta
        var emergency = s.emergency
        var used = s.usedEmergency
        var short = s.shortage
        if (cash < 0) {
            val take = minOf(-cash, emergency)
            emergency -= take
            used += take
            cash += take
            if (cash < 0) {
                short += -cash
                cash = 0
            }
        }
        return s.copy(cash = cash, emergency = emergency, usedEmergency = used, shortage = short)
    }

    /** 새 주 시작: 식비·교통비와 지난주 영향이 나가고, 일마다 확률대로 굴려 이번 주 일을 정한다 */
    private fun beginWeek(s0: State, no: Int): State {
        val carried = s0.pendingLater
        val s = spend(s0, -weekCost() + carried)
        val rnd = Random(s.setup.seed * 31 + no)
        val seen = s.history.map { it.event.id }.toSet()
        val rolled = EVENTS
            .filter { e -> !(e.once && e.id in seen) }
            .filter { e -> rnd.nextDouble() < e.chance }
            .shuffled(rnd)
            .ifEmpty { listOf(QUIET) }
        val week = Week(no, rolled.map { Happening(it) }, base = weekCost(), carried = carried, carriedText = s0.pendingLaterText)
        return s.copy(weeks = s.weeks + week, pendingLater = 0, pendingLaterText = null)
    }

    /** 지금 일에 대한 선택 */
    fun pick(state: State, choice: Choice): State {
        val week = state.week
        val i = week.happenings.indexOfFirst { it.picked == null }
        if (i < 0) return state
        var s = spend(state, choice.money - choice.save)
        s = s.copy(
            emergency = s.emergency + choice.save,
            pendingLater = s.pendingLater + choice.later,
            pendingLaterText = listOfNotNull(s.pendingLaterText, choice.laterText).joinToString(" · ").ifEmpty { null },
        )
        val list = week.happenings.toMutableList()
        list[i] = list[i].copy(picked = choice)
        return s.copy(weeks = s.weeks.dropLast(1) + week.copy(happenings = list))
    }

    /** 이번 주 일을 다 골랐으면 다음 주로 */
    fun nextWeek(state: State): State =
        if (state.week.done && state.week.no < WEEKS) beginWeek(state, state.week.no + 1) else state

    fun report(state: State): Report {
        val picks = state.history.mapNotNull { h -> h.picked?.let { h.event to it } }
        val good = picks.filter { it.second.good == true }.map { (e, c) -> "${e.title} · ${c.label}" }
        val missed = picks.filter { it.second.good == false }.map { (e, c) -> "${e.title} · ${c.lesson ?: c.label}" }
        val (title, summary) = when {
            state.shortage > 0 -> "이번 달은 모자랐어요" to "비상금까지 쓰고도 ${state.shortage}만원이 부족했어요. 실제라면 연체나 빚으로 이어질 수 있어요."
            state.usedEmergency > 0 -> "비상금 덕분에 버텼어요" to "생활비가 모자라 비상금에서 ${state.usedEmergency}만원을 꺼내 썼어요."
            state.left >= state.setup.income / 5 -> "여유 있게 보냈어요" to "다음 달로 ${state.left}만원을 넘길 수 있어요."
            else -> "아슬아슬하게 버텼어요" to "남은 돈이 ${state.left}만원이에요. 갑자기 큰돈이 나가면 어려울 수 있어요."
        }
        val tips = buildList {
            if (state.setup.emergency == 0) add("비상금을 조금이라도 따로 떼어 두면 병원비·수리비 같은 일에 덜 흔들려요.")
            if (state.setup.home == Home.STUDIO) add("주거비가 가장 커요. 공공임대나 청년 월세 지원 같은 제도를 알아봐요.")
            if (startingCash(state.setup) < weekCost() * WEEKS) add("수입에 비해 고정비가 커요. 생활비 배분에서 항목별 금액을 다시 나눠 봐요.")
            if (state.shortage > 0) add("돈이 급할 때는 대출보다 먼저 주민센터에 긴급 지원을 상담해 봐요.")
            if (picks.any { it.first.id == "smishing" && it.second.good == false }) add("모르는 번호의 링크는 누르지 말고, 피해가 생기면 바로 은행과 112에 알려요.")
            if (isEmpty()) add("고정비를 먼저 내고 남은 돈을 주 단위로 나눠 쓰면 이번 달처럼 버틸 수 있어요.")
        }
        val bad = picks.filter { it.second.good == false }.map { it.first.id }
        return Report(title, summary, state.left, state.usedEmergency, state.shortage, good, missed, tips, nextSteps(state, bad), bad)
    }

    /** 이번 달에 겪은 일 → 다음에 해 볼 것. 아쉬웠던 것과 이어지는 것 먼저, 최대 4개 */
    private fun nextSteps(state: State, bad: List<String>): List<NextStep> {
        val happened = state.history.map { it.event.id }.toSet()
        return buildList {
            if ("smishing" in bad) add(NextStep("practice", "수상한 전화 대처 연습하기", scenarioId = "scam-call"))
            if ("loan" in bad) add(NextStep("chat", "급하게 돈이 필요할 때 안전한 방법 물어보기", query = "급하게 생활비가 필요할 때 불법 대출 말고 받을 수 있는 도움 알려줘"))
            if (state.shortage > 0 || state.usedEmergency > 0) add(NextStep("search", "긴급 생활비 지원 찾아보기", query = "긴급복지지원 생활비 자립준비청년"))
            if ("boiler" in bad || "bill" in bad) add(NextStep("practice", "집주인에게 수리 요청 연습하기", scenarioId = "landlord"))
            if ("latepay" in happened) add(NextStep("practice", "밀린 알바비 받기 연습하기", scenarioId = "wage"))
            if ("interview" in happened) add(NextStep("practice", "취업 면접 연습하기", scenarioId = "job"))
            if (setOf("cold", "injury", "tooth").any { it in bad }) add(NextStep("search", "의료비 지원 찾아보기", query = "자립준비청년 의료비 지원"))
            if (state.setup.home == Home.STUDIO) add(NextStep("search", "청년 월세 지원 찾아보기", query = "청년 월세 지원 주거"))
            add(NextStep("budget", "이번 달 수입으로 생활비 나눠 보기"))
        }.distinctBy { it.label }.take(4)
    }

    /** 아무 일도 없는 주 */
    private val QUIET = Event(
        "quiet", "조용한 한 주예요", "특별한 일 없이 지나가고 있어요. 주말에 뭘 할까요?", 0.0,
        listOf(
            Choice("집에서 푹 쉰다", 0, "푹 쉬었어요."),
            Choice("친구랑 가볍게 논다", -3, "카페에서 수다를 떨었어요."),
            Choice("다음 주 장을 미리 본다", -2, "미리 사 둬서 다음 주 식비를 아꼈어요.", good = true, later = 3, laterText = "미리 장 봐 둔 덕분에 식비 3만원 아낌"),
        ),
        once = false,
    )

    /** 한 주에 일어날 확률과 선택지. 확률은 예시 */
    private val EVENTS: List<Event> = listOf(
        // 몸
        Event("cold", "감기에 걸렸어요", "목이 붓고 열이 나요. 내일도 알바가 있어요.", 0.09, listOf(
            Choice("병원에 간다", -3, "진료비와 약값으로 3만원을 썼어요. 금방 나았어요.", good = true),
            Choice("약국 약만 산다", -1, "1만원을 썼지만 잘 낫지 않아 다음 주에 병원에 갔어요.", later = -4, laterText = "감기가 심해져 병원비 4만원"),
            Choice("그냥 참는다", 0, "돈은 아꼈지만 알바를 하루 빠졌어요.", good = false, later = -6, laterText = "알바 하루 못 나가서 수입 6만원 줄어듦", lesson = "참다가 수입까지 줄었어요"),
        )),
        Event("injury", "계단에서 발목을 삐었어요", "걸을 때마다 욱신거려요.", 0.05, listOf(
            Choice("정형외과에 간다", -5, "진료와 물리치료로 5만원을 썼어요.", good = true),
            Choice("파스만 붙인다", -1, "일주일 내내 불편했고 결국 병원에 갔어요.", good = false, later = -5, laterText = "발목 치료비 5만원", lesson = "다친 데를 그냥 두면 더 들어요"),
        )),
        Event("tooth", "이가 시려요", "찬물을 마실 때마다 이가 찌릿해요.", 0.04, listOf(
            Choice("치과에 간다", -6, "충치 치료로 6만원을 썼어요. 초기라 다행이었어요.", good = true),
            Choice("좀 더 참아 본다", 0, "점점 아파져서 다음 주에 신경 치료를 받았어요.", good = false, later = -15, laterText = "치료가 커져서 치과비 15만원", lesson = "치과는 미룰수록 비싸져요"),
        )),
        // 사람
        Event("birthday", "친구 생일 모임이 있어요", "친한 친구들이 저녁을 먹자고 해요.", 0.09, listOf(
            Choice("모임에 간다", -5, "밥값과 선물로 5만원을 썼어요. 즐거웠어요."),
            Choice("선물만 보낸다", -2, "기프티콘을 보내고 축하 메시지를 남겼어요.", good = true),
            Choice("다음에 보자고 한다", 0, "돈은 아꼈어요. 다음에 꼭 만나기로 했어요."),
        )),
        Event("wedding", "아는 언니가 결혼해요", "청첩장을 받았어요.", 0.05, listOf(
            Choice("가서 축의금을 낸다", -5, "축의금 5만원을 냈어요."),
            Choice("축의금만 보낸다", -3, "계좌로 3만원을 보내고 축하 인사를 했어요.", good = true),
            Choice("가지 않는다", 0, "축하 메시지만 보냈어요."),
        )),
        Event("housewarming", "친구가 집들이를 해요", "자취를 시작한 친구가 놀러 오래요.", 0.05, listOf(
            Choice("휴지·세제를 사 간다", -2, "실용적인 선물이라 친구가 좋아했어요.", good = true),
            Choice("빈손으로 간다", 0, "조금 민망했지만 즐거웠어요."),
        )),
        Event("lend", "친구가 돈을 빌려 달래요", "\"10만원만 빌려줄 수 있어? 다음 달에 꼭 갚을게\"", 0.05, listOf(
            Choice("빌려준다", -10, "빌려줬어요. 언제 돌려받을지는 모르겠어요.", good = false, lesson = "생활비를 빌려줬어요"),
            Choice("줄 수 있는 만큼만", -3, "부담 없는 3만원만 줬어요."),
            Choice("지금은 어렵다고 말한다", 0, "솔직하게 말했어요. 친구도 이해했어요.", good = true),
        )),
        Event("treat", "친구가 밥을 사 줬어요", "오랜만에 만난 친구가 저녁을 샀어요.", 0.05, listOf(
            Choice("다음엔 내가 산다고 한다", 0, "다음 주에 커피를 샀어요.", later = -2, laterText = "친구에게 커피 2만원"),
            Choice("고맙다고 인사한다", 0, "고맙다고 메시지를 보냈어요."),
        )),
        // 집
        Event("bill", "관리비 고지서가 왔어요", "날이 추워져서 난방비가 생각보다 많이 나왔어요.", 0.14, listOf(
            Choice("바로 낸다", -6, "6만원을 냈어요.", good = true),
            Choice("다음 달로 미룬다", 0, "이번 달은 넘겼지만 연체료가 붙었어요.", good = false, later = -7, laterText = "미룬 관리비 + 연체료 7만원", lesson = "미루면 연체료가 붙어요"),
        )),
        Event("boiler", "보일러가 고장 났어요", "따뜻한 물이 안 나와요.", 0.04, listOf(
            Choice("집주인에게 먼저 연락한다", 0, "집주인이 수리 기사를 불러 줬어요. 집 시설 고장은 보통 집주인이 고쳐요.", good = true),
            Choice("직접 기사를 부른다", -8, "출장비와 수리비로 8만원을 냈어요.", good = false, lesson = "집 시설 고장은 집주인에게 먼저 알려요"),
        )),
        Event("supplies", "생활용품이 떨어졌어요", "휴지, 세제, 샴푸가 한꺼번에 떨어졌어요.", 0.11, listOf(
            Choice("편의점에서 바로 산다", -4, "급해서 편의점에서 4만원어치 샀어요."),
            Choice("마트·온라인 묶음으로 산다", -2, "묶음으로 사서 2만원에 해결했어요.", good = true),
        )),
        Event("subscription", "모르는 자동결제를 발견했어요", "예전에 무료 체험한 구독이 매달 결제되고 있었어요.", 0.05, listOf(
            Choice("바로 해지한다", 0, "해지하고 다음 달부터 안 나가게 했어요.", good = true),
            Choice("나중에 정리한다", -1, "이번 달에도 1만원이 나갔어요.", good = false, lesson = "안 쓰는 구독은 바로 해지해요"),
        )),
        // 물건·돈
        Event("phone", "휴대폰 액정이 깨졌어요", "떨어뜨려서 화면 한쪽이 잘 안 눌려요.", 0.03, listOf(
            Choice("바로 수리한다", -15, "수리비로 15만원을 썼어요."),
            Choice("보험·보증부터 확인한다", -4, "통신사 보험이 있어서 자기부담금 4만원만 냈어요.", good = true),
            Choice("그냥 쓴다", 0, "쓸 수는 있지만 불편해서 다음 주에 결국 고쳤어요.", later = -15, laterText = "결국 액정 수리 15만원"),
        )),
        Event("card", "교통카드를 잃어버렸어요", "카드에 3만원이 충전돼 있었어요.", 0.04, listOf(
            Choice("분실 신고하고 환불받는다", -1, "등록해 둔 카드라 잔액을 돌려받고 새 카드만 샀어요.", good = true),
            Choice("새로 하나 산다", -4, "새 카드를 사고 다시 충전했어요."),
        )),
        Event("rain", "갑자기 비가 쏟아져요", "우산이 없는데 약속 시간이 다가와요.", 0.09, listOf(
            Choice("편의점 우산을 산다", -1, "1만원짜리 우산을 샀어요."),
            Choice("택시를 탄다", -2, "택시비 2만원이 나왔어요."),
            Choice("조금 기다렸다 간다", 0, "비가 그친 뒤 버스를 탔어요. 조금 늦었어요."),
        )),
        Event("sale", "한정 특가 알림이 떴어요", "\"오늘만 50% 할인! 패딩 12만원\"", 0.09, listOf(
            Choice("지금 산다", -12, "따뜻하지만 생활비가 많이 줄었어요.", good = false, lesson = "계획에 없던 큰 지출"),
            Choice("장바구니에만 담는다", 0, "일주일 뒤에 보니 꼭 필요하진 않았어요.", good = true),
        )),
        Event("delivery", "배달 음식이 당겨요", "냉장고에는 계란과 밥이 있어요.", 0.14, listOf(
            Choice("배달시킨다", -3, "배달비 포함 3만원을 썼어요."),
            Choice("장 봐서 해 먹는다", -1, "1만원으로 두 끼를 해결했어요.", good = true),
        ), once = false),
        Event("resell", "안 쓰는 물건이 쌓였어요", "작아진 옷과 안 쓰는 가방이 있어요.", 0.05, listOf(
            Choice("중고로 판다", 3, "중고 거래로 3만원을 벌었어요. 직거래는 사람 많은 곳에서 했어요.", good = true),
            Choice("그냥 둔다", 0, "그대로 두었어요."),
        )),
        Event("refund", "택배가 잘못 왔어요", "주문한 것과 다른 물건이 왔어요.", 0.03, listOf(
            Choice("반품·환불 신청한다", 2, "환불받은 2만원이 들어왔어요.", good = true),
            Choice("귀찮아서 그냥 쓴다", 0, "쓰지 않는 물건이 하나 늘었어요."),
        )),
        // 일
        Event("overtime", "알바 추가 근무 제안을 받았어요", "주말에 하루 더 나와 줄 수 있냐고 해요.", 0.11, listOf(
            Choice("한다", 7, "7만원을 더 벌었어요. 조금 피곤했어요.", good = true),
            Choice("쉰다", 0, "푹 쉬었어요. 쉬는 것도 중요해요."),
        )),
        Event("bonus", "사장님이 수고비를 주셨어요", "바쁜 주를 잘 버텼다고 5만원을 주셨어요.", 0.03, listOf(
            Choice("비상금에 넣는다", 5, "5만원을 비상금으로 옮겼어요.", good = true, save = 5),
            Choice("생활비로 쓴다", 5, "이번 주 생활비가 넉넉해졌어요."),
        )),
        Event("latepay", "알바비가 늦게 들어온대요", "가게 사정으로 이번 주 알바비 10만원이 다음 주에 들어온대요.", 0.04, listOf(
            Choice("날짜를 문자로 받아 둔다", -10, "언제 주는지 문자로 남겨 두고, 다음 주에 받았어요.", good = true, later = 10, laterText = "늦게 들어온 알바비 10만원"),
            Choice("그냥 기다린다", -10, "다음 주에 들어왔지만 그동안 불안했어요.", later = 10, laterText = "늦게 들어온 알바비 10만원"),
        )),
        Event("interview", "면접이 잡혔어요", "정장이 없는데 다음 주에 면접이 있어요.", 0.04, listOf(
            Choice("정장을 산다", -15, "정장을 한 벌 샀어요."),
            Choice("청년 무료 정장 대여를 찾아본다", 0, "지역 청년 정장 대여 서비스로 빌렸어요.", good = true),
            Choice("단정한 옷으로 간다", 0, "가진 옷 중 가장 단정한 옷을 입었어요."),
        )),
        // 위험
        Event("smishing", "택배 문자가 왔어요", "\"[국제택배] 주소 불일치로 보관 중. 확인: http://bit.ly/…\"", 0.11, listOf(
            Choice("링크를 눌러 확인한다", -30, "가짜 사이트였어요. 앱이 깔리고 계좌에서 30만원이 빠져나갔어요.", good = false, lesson = "모르는 링크를 눌러 피해를 입었어요"),
            Choice("택배사 앱에서 직접 확인한다", 0, "보낸 택배가 없었어요. 사기 문자였어요.", good = true),
            Choice("지우고 스팸 신고한다", 0, "문자를 신고하고 지웠어요.", good = true),
        )),
        Event("loan", "\"무직자도 바로 대출\" 광고를 봤어요", "50만원을 바로 빌려준대요.", 0.1, listOf(
            Choice("신청해 본다", -8, "선이자·수수료로 8만원이 먼저 빠졌어요. 불법 대출이었어요.", good = false, lesson = "급전 대출 광고는 피해가 커요"),
            Choice("주민센터에 상담한다", 0, "긴급 지원과 서민금융 상담 창구를 안내받았어요.", good = true),
            Choice("무시한다", 0, "광고를 닫았어요.", good = true),
        )),
    )
}
