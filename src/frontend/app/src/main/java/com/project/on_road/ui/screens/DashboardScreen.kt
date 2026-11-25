package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.ApplyResults
import com.project.on_road.data.ChecklistSummary
import com.project.on_road.data.LatestSimulation
import com.project.on_road.data.PlanData
import com.project.on_road.data.UserProfile
import com.project.on_road.data.UserStage
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.LogoSlot
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.SectionHeader
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.components.stagger
import com.project.on_road.ui.components.tap
import com.project.on_road.ui.components.reveal
import androidx.compose.animation.animateContentSize
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period

class DashboardViewModel : ViewModel() {
    private val userId get() = AppContainer.session.userId

    /** 자립까지 D-day (3.4 user.d_day). null 이면 숨김 */
    var dDay by mutableStateOf<Int?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var latestSimulation by mutableStateOf<LatestSimulation?>(null)
        private set
    /** 신청 체크리스트 (진행 중인 신청) */
    var checklists by mutableStateOf<List<ChecklistSummary>>(emptyList())
        private set
    /** 나의 자립 조건 (나이 · 지역 · 상태) */
    var profile by mutableStateOf<UserProfile?>(com.project.on_road.data.ProfileStore.current)
        private set
    /** 나에게 맞는 지원 정책 (서버 추천 순서). null 이면 아직 받는 중. 받아 둔 게 있으면 그걸 먼저 보여 준다 */
    var recommended by mutableStateOf<List<HomePolicy>?>(userId?.let { com.project.on_road.data.HomeCache.get<HomePolicy>(it) })
        private set

    fun load() {
        val id = userId ?: return
        viewModelScope.launch { fetchDashboard(id) }
        viewModelScope.launch { fetchProfile(id) }
        viewModelScope.launch { fetchRecommended(id) }
    }

    /**
     * 홈 추천 정책: 조건 맞음 > 신청 시기 가까움 > 현금이 큰 순 (서버 점수 순서 그대로).
     * 추천 API 를 못 쓰면(서버가 아직 예전 버전 · 연결 실패) 조건으로 고른 정책 목록을 받아 앱에서 같은 기준으로 줄 세운다.
     */
    private suspend fun fetchRecommended(id: String) {
        val list = runCatching { AppContainer.api.getRecommendedPolicies(id) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.map { r ->
                HomePolicy(
                    id = r.policyId,
                    category = r.category,
                    name = r.name,
                    whenText = r.whenText,
                    // 현금이 아닌 지원(주거 · 상담 · 의료)은 금액 자리에 무엇을 받는지 분야로
                    unit = r.amountUnit ?: "지원",
                    amount = r.amountValue ?: NON_CASH_WORD[r.category] ?: r.category,
                )
            }
            ?: runCatching { AppContainer.api.browsePolicies(id, null).policies }.getOrNull()?.let { fromBrowse(it) }
            ?: run {
                // 둘 다 못 받으면 받아 둔 목록 그대로. 받아 둔 것도 없으면 빈 자리 대신 안내를 보여 준다
                if (recommended == null && id == userId) recommended = emptyList()
                return
            }
        if (id != userId) return // 그 사이 계정을 바꿨으면 버린다
        recommended = list
        com.project.on_road.data.HomeCache.put(id, list)
    }

    /** 조건으로 고른 정책 목록 → 홈 카드 (기본 조건 해당 > 매월 받는 돈 > 한 번 받는 돈 > 나머지, 8개) */
    private fun fromBrowse(policies: List<com.project.on_road.data.Policy>): List<HomePolicy> =
        policies
            .filter { !it.forFamily }
            .sortedWith(
                compareBy<com.project.on_road.data.Policy> { if (it.eligibility == "match") 0 else 1 }
                    .thenBy { amountRank(it.amountShort) }
            )
            .take(8)
            .map { p ->
                val (unit, amount) = splitAmount(p.amountShort)
                HomePolicy(
                    id = p.id,
                    category = p.category,
                    name = p.name,
                    whenText = p.agency,
                    unit = unit ?: "지원",
                    amount = amount ?: NON_CASH_WORD[p.category] ?: p.category,
                )
            }

    private fun amountRank(text: String): Int = when (splitAmount(text).first) {
        "매월" -> 0
        null -> 2
        else -> 1
    }

    /**
     * 금액 글에서 카드에 넣을 첫 금액만: "월 50만 원 (인/월)" → ("매월", "50만 원"), "최대 2,000만 원 이내" → ("최대", "2,000만 원").
     * 금액이 없으면 (null, null)
     */
    private fun splitAmount(text: String): Pair<String?, String?> {
        val m = MONEY.find(text) ?: return null to null
        val before = text.substring(0, m.range.first)
        // 내가 내는 돈(임대료 · 본인부담)은 받는 돈이 아니다
        if (PAID.any { before.contains(it) }) return null to null
        val unit = when {
            Regex("(매\\s?월|월)\\s*$").containsMatchIn(before) || text.contains("/월") || text.contains("(인/월)") -> "매월"
            Regex("(매\\s?년|연)\\s*$").containsMatchIn(before) -> "매년"
            before.contains("최대") -> "최대"
            else -> "지원"
        }
        return unit to m.value.trim()
    }

    private val PAID = listOf("임대료", "본인부담")
    private val MONEY = Regex("""\d[\d,]*\s?(?:억\s?(?:\d[\d,]*\s?만\s?)?|만\s?)?원""")

    /** 당겨서 새로고침: load() 와 같은 것을 받고, 다 받을 때까지 새로고침 표시를 보여 준다 */
    var refreshing by mutableStateOf(false)
        private set

    fun refresh() {
        val id = userId ?: return
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            val started = android.os.SystemClock.uptimeMillis()
            kotlinx.coroutines.coroutineScope {
                launch { fetchDashboard(id) }
                launch { fetchProfile(id) }
                launch { fetchRecommended(id) }
            }
            val rest = com.project.on_road.ui.components.REFRESH_MIN_MS - (android.os.SystemClock.uptimeMillis() - started)
            if (rest > 0) kotlinx.coroutines.delay(rest)
            refreshing = false
        }
    }

    private suspend fun fetchDashboard(id: String) {
        val dash = runCatching { AppContainer.api.getDashboard(id) }.getOrNull()
        if (dash != null) {
            dash.user?.let { AppContainer.applyDashboardUser(it) }
            dDay = dash.user?.dDay
            loaded = true
            checklists = dash.checklists
            latestSimulation = dash.latestSimulation
        }
        // 타임라인 · 알림이 쓰는 할 일 목록도 함께 새로 고친다
        PlanData.refresh(dash?.checklists)
    }

    private suspend fun fetchProfile(id: String) {
        val p = com.project.on_road.data.ProfileStore.refresh() ?: return
        if (p.userId == id) profile = p
    }
}

/** 현금이 아닌 지원의 금액 자리 글 (분야 이름 → 받는 것) */
private val NON_CASH_WORD = mapOf(
    "주거" to "주거", "건강" to "의료비", "자립" to "자립 관리", "취업" to "취업 지원", "교육" to "교육비", "생활·금융" to "생활 지원",
)

/**
 * 나에게 맞는 지원 정책 카드 한 개 (서버 추천 · 화면 확인용 목업 공통).
 * id 가 있으면 정책 상세로, 없으면 이름으로 정책 검색을 연다.
 */
data class HomePolicy(
    val id: String?,
    val category: String,
    val name: String,
    val whenText: String,
    val unit: String,
    val amount: String,
)

private val MockPolicies = listOf(
    HomePolicy("jarip-allowance", "생활·금융", "자립수당", "상시 신청", "매월", "50만 원"),
    HomePolicy("settlement", "자립", "자립정착금", "퇴소 시 1회", "최대", "2,000만 원"),
    HomePolicy("jeonse", "주거", "청년 전세임대", "연중 모집", "보증금", "최대 1.2억"),
    HomePolicy("employment", "취업", "국민취업지원제도", "연중 신청", "월", "50만 원"),
    HomePolicy(null, "교육", "자립준비청년 장학금", "학기별 모집", "최대", "등록금 전액"),
    HomePolicy("medical", "건강", "의료비 지원", "상시 신청", "본인부담", "의료급여 2종"),
    HomePolicy(null, "상담", "전문 심리상담", "상시 신청", "비용", "무료"),
)

/** 홈 여백 · 크기 (숫자만 바꿔서 조절) */
private object HomeSpace {
    /** 헤더 → 인사 */
    val GreetTop = 26.dp
    /** 인사 → 검색창 */
    val SearchTop = 22.dp
    /** 섹션 사이 (앞 섹션 끝 → 다음 제목) */
    val SectionTop = 42.dp
    /** 섹션 제목 → 내용 */
    val TitleBottom = 14.dp
    /** 정책 카드 */
    val PolicyWidth = 298.dp
    val PolicyHeight = 112.dp
    val PolicyGap = 10.dp
    /** 정책 카드: 분야 → 제목 */
    val CategoryGap = 4.dp
    /** 정책 카드 분야 아이콘: 크기 · 돌린 각도 · 바깥으로 나간 정도 · 진하기 (메인 색 카드는 2배) */
    val ArtSize = 80.dp
    const val ArtRotate = -25f
    val ArtOutX = 20.dp
    val ArtOutY = 28.dp
    const val ArtAlpha = 0.08f
    /** 진행 중인 신청: 큰 카드 → 한 줄 카드 사이 */
    val RowGap = 8.dp
    val RowHeight = 50.dp
    /** 자립 준비도 칸 */
    val TileGap = 8.dp
    val TileIconBox = 34.dp
    val TileTitleTop = 12.dp
    val TileDescTop = 3.dp
    /** 맨 아래 여백 */
    val Bottom = 32.dp
}

/**
 * 자립준비청년 홈.
 * 순서: 인사 · 검색 → 나의 자립 조건 → 나에게 맞는 지원 정책(가로 카드) → 진행 중인 신청 → 자립 준비도 함께해요.
 * 정책 카드는 첫 장만 메인 색, 나머지는 흰 카드. 오른쪽 위에 분야 아이콘을 크게 비스듬히 옅게 깐다.
 */
@Composable
fun DashboardScreen(
    onNavigate: (String) -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    // 설정에서 지역 · 시군구 · 상황을 바꾸면 추천도 다시 받는다
    val profileVersion = com.project.on_road.data.ProfileStore.version
    LaunchedEffect(profileVersion) { if (profileVersion > 0) vm.load() }
    val pad = Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)

    Column(
        Modifier
            .fillMaxSize()
            .background(OnRoadColors.Background)
            .verticalScroll(com.project.on_road.ui.components.rememberTabScrollState()),
    ) {
        HomeHeader(
            stageLabel = UserStage.YOUNG_ADULT.label,
            onStage = { onNavigate(Routes.SETTINGS) },
            onAlarm = { onNavigate(Routes.NOTIFICATIONS) },
            onProfile = { onNavigate(Routes.SETTINGS) },
        )

        // 인사 · 검색
        Column(pad.stagger(0)) {
            Spacer(Modifier.height(HomeSpace.GreetTop))
            Text("나에게 맞는 정책 지원,", style = OnRoadType.DisplayRegular, color = OnRoadColors.TextPrimary)
            Text("지금 확인해 볼까요?", style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(HomeSpace.SearchTop))
            HomeSearchField(onSearch = { q -> onNavigate(Routes.policy(query = q.trim().ifBlank { null })) })
        }

        // 나의 자립 조건
        Column(pad.stagger(1)) {
            Spacer(Modifier.height(HomeSpace.SectionTop))
            SectionHeader("나의 자립 조건", actionText = "내 정보 수정", onAction = { onNavigate(Routes.MY_CONDITIONS) })
            Spacer(Modifier.height(HomeSpace.TitleBottom))
            ConditionTags(vm.profile)
        }

        // 나에게 맞는 지원 정책
        Column(Modifier.stagger(2)) {
            Spacer(Modifier.height(HomeSpace.SectionTop))
            SectionHeader(
                "나에게 맞는 지원 정책",
                modifier = pad,
                actionText = "전체보기",
                onAction = { onNavigate(Routes.policy()) },
            )
            Spacer(Modifier.height(HomeSpace.TitleBottom))
            // 서버 추천 (조건 맞음 > 신청 시기 > 현금 순). 서버가 없는 화면 확인 모드에서만 목업
            val list = vm.recommended ?: if (AppContainer.api is com.project.on_road.data.FakeOnRoadApi) MockPolicies else null
            // 빈 자리 → 카드로 바뀔 때 서서히 (추천 순서가 바뀌어도 같은 방식)
            com.project.on_road.ui.components.FadeSwitch(list, label = "home-recommend") { shown ->
                when {
                    shown == null -> PolicyRailLoading()
                    shown.isEmpty() -> EmptyState(
                        "조건에 맞는 정책을 찾지 못했어요.",
                        modifier = pad,
                        actionLabel = "정책 찾기",
                        onAction = { onNavigate(Routes.policy()) },
                    )
                    else -> PolicyRail(shown) { p -> onNavigate(p.id?.let(Routes::policyDetail) ?: Routes.policy(query = p.name)) }
                }
            }
        }

        // 진행 중인 신청
        Column(pad.stagger(3)) {
            Spacer(Modifier.height(HomeSpace.SectionTop))
            SectionHeader("진행 중인 신청", actionText = "전체보기", onAction = { onNavigate(Routes.CHECKLISTS) })
            Spacer(Modifier.height(HomeSpace.TitleBottom))
            // 진행 중인 신청은 받아 온 뒤에 서서히 (받기 전에 '없어요'가 먼저 뜨지 않게)
            com.project.on_road.ui.components.FadeSwitch(vm.loaded, label = "home-applications") { ready ->
                if (ready) Applications(vm.checklists, onNavigate) else Spacer(Modifier.height(HomeSpace.PolicyHeight))
            }
        }

        // 자립 준비도 함께해요
        Column(pad.stagger(4)) {
            Spacer(Modifier.height(HomeSpace.SectionTop))
            SectionHeader("자립 준비도 함께해요")
            Spacer(Modifier.height(HomeSpace.TitleBottom))
            Row(horizontalArrangement = Arrangement.spacedBy(HomeSpace.TileGap)) {
                ReadyTile(Icons.Outlined.AccountBalanceWallet, "한 달 생활비 계산하기", "미리 계산해 봐요", Modifier.weight(1f)) {
                    onNavigate(Routes.SIMULATION)
                }
                ReadyTile(Icons.Outlined.ChatBubbleOutline, "정책에 대해 물어보기", "쉽게 알려 드려요", Modifier.weight(1f)) {
                    onNavigate(Routes.chat())
                }
            }
            Spacer(Modifier.height(HomeSpace.Bottom))
            com.project.on_road.ui.components.TabBarSpacer()
        }
    }
}

/* ---------------------------- 구성 요소 ---------------------------- */

@Composable
private fun HomeHeader(
    stageLabel: String,
    onStage: () -> Unit,
    onAlarm: () -> Unit,
    onProfile: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoSlot(size = 36.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            stageLabel,
            style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
            color = OnRoadColors.TextPrimary,
            modifier = Modifier.tap(onClickLabel = "단계·프로필 설정", onClick = onStage),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAlarm) {
            Icon(Icons.Outlined.Notifications, "알림", tint = OnRoadColors.TextPrimary)
        }
        IconButton(onClick = onProfile) {
            Icon(Icons.Outlined.AccountCircle, "계정·설정", tint = OnRoadColors.TextPrimary)
        }
    }
}

/** 홈 검색창: 정책 검색 화면 검색창과 같은 모양 (흰 바탕 · 회색 테두리, 누르면 테두리가 메인 색) */
@Composable
private fun HomeSearchField(onSearch: (String) -> Unit) {
    var value by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val shape = OnRoadShapes.Inner
    val line by androidx.compose.animation.animateColorAsState(
        if (focused) OnRoadColors.Primary else OnRoadColors.BorderSlate,
        com.project.on_road.ui.theme.OnRoadMotion.move(),
        label = "home-search-line",
    )
    val active = value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(OnRoadColors.Surface)
            .border(1.dp, line, shape)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = OnRoadType.Body2.copy(color = OnRoadColors.TextPrimary),
            cursorBrush = SolidColor(OnRoadColors.Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (value.isEmpty()) Text("필요한 자립 지원을 검색해 보세요", style = OnRoadType.Body2, color = OnRoadColors.TextQuaternary, maxLines = 1)
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clip(OnRoadShapes.Field).tap(onClickLabel = "검색어 지우기") { value = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, "지우기", tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(2.dp))
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(OnRoadShapes.Field)
                .background(if (active) OnRoadColors.Primary else Color.Transparent)
                .tap(onClickLabel = "찾기") { onSearch(value) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Search, "찾기", tint = if (active) Color.White else OnRoadColors.TextTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** 나의 자립 조건: 만 나이 · 지역 · 단계 · 지금 상태 (회색 태그) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionTags(profile: UserProfile?) {
    val session = AppContainer.session
    val tags = buildList {
        profile?.let { add("만 ${Period.between(it.birthDate, LocalDate.now()).years}세") }
        profile?.region?.takeIf { it.isNotBlank() }?.let { add(listOfNotNull(it, profile?.district).joinToString(" ") + " 거주") }
        add((profile?.stage ?: session.stage).label)
        (profile?.status ?: session.status)?.let { add(it.label.replace(" ", "")) }
        // 퇴소 후: 직접 넣은 퇴소한 날 (기간 조건 계산 기준)
        if (com.project.on_road.data.MyConditions.version >= 0 && (profile?.status ?: session.status) == com.project.on_road.data.LivingStatus.LEFT) {
            com.project.on_road.data.MyConditions.leftDate?.let { add("${it.year}.${it.monthValue}.${it.dayOfMonth} 퇴소") }
        }
    }
    FlowRow(
        Modifier.animateContentSize(com.project.on_road.ui.theme.OnRoadMotion.move()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 내 정보를 받아 새로 생긴 태그만 서서히 나타난다
        tags.forEach { t -> androidx.compose.runtime.key(t) { OnRoadTag(t, Modifier.reveal(), style = TagStyle.Neutral) } }
    }
}

/** 나에게 맞는 지원 정책: 옆으로 넘기는 카드 (카드 한 장씩 맞춰 멈춘다) */
@Composable
private fun PolicyRail(policies: List<HomePolicy>, onOpen: (HomePolicy) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        contentPadding = PaddingValues(horizontal = OnRoadDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(HomeSpace.PolicyGap),
    ) {
        // 이름이 같은 정책(기관만 다른 것)이 있어도 겹치지 않게 id 로 (id 가 없으면 순서로). 같은 key 가 두 번 나오면 앱이 꺼진다
        itemsIndexed(policies, key = { i, it -> it.id ?: "p$i" }) { _, p ->
            PolicyCard(p, main = p === policies.first()) { onOpen(p) }
        }
    }
}

/** 추천을 받는 동안: 카드와 같은 크기의 빈 자리 (받고 나서 아래 섹션이 밀려 내려가지 않게) */
@Composable
private fun PolicyRailLoading() {
    Row(
        Modifier.padding(horizontal = OnRoadDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(HomeSpace.PolicyGap),
    ) {
        repeat(2) {
            Box(
                Modifier
                    .width(HomeSpace.PolicyWidth)
                    .height(HomeSpace.PolicyHeight)
                    .clip(OnRoadShapes.Card)
                    .background(OnRoadColors.SurfaceSubtle),
            )
        }
    }
}

/** 정책 카드: 왼쪽 분야 · 이름 · 신청 시기, 오른쪽 아래 금액. 오른쪽 위에 분야 아이콘 */
@Composable
private fun PolicyCard(p: HomePolicy, main: Boolean, onClick: () -> Unit) {
    val shape = OnRoadShapes.Card
    val sub = if (main) OnRoadColors.TextQuaternary else OnRoadColors.TextTertiary
    val fg = if (main) Color.White else OnRoadColors.TextPrimary
    Box(
        Modifier
            .width(HomeSpace.PolicyWidth)
            .height(HomeSpace.PolicyHeight)
            .clip(shape)
            .background(if (main) OnRoadColors.Primary else OnRoadColors.Surface)
            .then(if (main) Modifier else Modifier.border(1.dp, OnRoadColors.Border, shape))
            .tap(onClickLabel = "${p.name} 보기", onClick = onClick),
    ) {
        Icon(
            CategoryArt.of(p.category),
            null,
            tint = (if (main) Color.White else OnRoadColors.Primary).copy(alpha = if (main) HomeSpace.ArtAlpha * 2 else HomeSpace.ArtAlpha),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = HomeSpace.ArtOutX, y = -HomeSpace.ArtOutY)
                .size(HomeSpace.ArtSize)
                .rotate(HomeSpace.ArtRotate),
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                // 분야는 제목 바로 위에 붙여서 한 묶음으로 (신청 시기는 아래 끝)
                Column {
                    Text(p.category, style = OnRoadType.Micro, color = sub, maxLines = 1)
                    Spacer(Modifier.height(HomeSpace.CategoryGap))
                    Text(p.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1, overflow = TextOverflow.Clip)
                }
                Text(p.whenText, style = OnRoadType.Caption2, color = sub, maxLines = 1, overflow = TextOverflow.Clip)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
                Text(p.unit, style = OnRoadType.Micro, color = sub)
                Text(p.amount, style = OnRoadType.Title1.copy(fontSize = 22.sp, letterSpacing = (-0.4).sp), color = fg, maxLines = 1)
            }
        }
    }
}

/** 진행 중인 신청: 맨 앞 하나는 큰 카드(다음 단계 버튼), 나머지는 한 줄 */
@Composable
private fun Applications(checklists: List<ChecklistSummary>, onNavigate: (String) -> Unit) {
    val ongoing = checklists
        .filter { ApplyResults.statusOf(it.policyId, it.completed) != com.project.on_road.data.ApplyStatus.REJECTED }
        .sortedBy { if (it.completed) 1 else 0 }
    if (ongoing.isEmpty()) {
        EmptyState("아직 진행 중인 신청이 없어요.", actionLabel = "정책 찾기", onAction = { onNavigate(Routes.policy()) })
        return
    }
    val first = ongoing.first()
    OnRoadCard(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp),
        onClick = { onNavigate(Routes.checklist(first.policyId)) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                first.policyName,
                style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            Text(
                ApplyResults.statusOf(first.policyId, first.completed).label,
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${first.totalSteps}단계 중 ${first.doneSteps}단계 끝냈어요",
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            // 신청 예정일 (신청 관리 목록 카드와 같은 표시)
            first.dueDate?.takeIf { !first.completed }?.let { due ->
                val urgent = com.project.on_road.ui.components.DueDate.urgent(due)
                Text(
                    if (urgent) com.project.on_road.ui.components.DueDate.dText(due)
                    else "${com.project.on_road.ui.components.DueDate.day(due)} · ${com.project.on_road.ui.components.DueDate.dText(due)}",
                    style = if (urgent) OnRoadType.Caption.copy(fontWeight = FontWeight.Bold) else OnRoadType.Caption,
                    color = com.project.on_road.ui.components.DueDate.color(due),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OnRoadButton(
            text = first.nextStep?.let { "${it.title}하기" } ?: "신청 결과 확인하기",
            onClick = { onNavigate(Routes.checklist(first.policyId)) },
            modifier = Modifier.fillMaxWidth(),
            height = 46.dp,
            textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
        )
    }
    ongoing.drop(1).take(2).forEach { c ->
        Spacer(Modifier.height(HomeSpace.RowGap))
        ApplicationRow(
            name = c.policyName,
            state = c.nextStep?.let { "지금 · ${it.title}" } ?: ApplyResults.statusOf(c.policyId, c.completed).label,
            due = c.dueDate?.takeIf { !c.completed },
        ) { onNavigate(Routes.checklist(c.policyId)) }
    }
}

/** 한 줄 신청: 이름(굵게) · 오른쪽 상태 · › */
@Composable
private fun ApplicationRow(name: String, state: String, due: java.time.LocalDate? = null, onClick: () -> Unit) {
    val shape = OnRoadShapes.Inner
    Row(
        Modifier
            .fillMaxWidth()
            .height(HomeSpace.RowHeight)
            .clip(shape)
            .background(OnRoadColors.Surface)
            .border(1.dp, OnRoadColors.Border, shape)
            .tap(onClick = onClick)
            .padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = OnRoadType.Body3.copy(fontWeight = FontWeight.Bold),
            color = OnRoadColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f),
        )
        // 신청 예정일이 있으면 상태 앞에 D-day만 짧게
        if (due != null) {
            val urgent = com.project.on_road.ui.components.DueDate.urgent(due)
            Text(
                com.project.on_road.ui.components.DueDate.dText(due),
                style = if (urgent) OnRoadType.Caption.copy(fontWeight = FontWeight.Bold) else OnRoadType.Caption,
                color = com.project.on_road.ui.components.DueDate.color(due),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(state, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
    }
}

/** 자립 준비도 칸: 연회색 바탕 · 회색 테두리. 왼쪽 위 흰 아이콘 상자, 오른쪽 위 ›, 아래 제목 · 짧은 설명 */
@Composable
private fun ReadyTile(icon: ImageVector, title: String, desc: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = OnRoadShapes.Card
    Column(
        modifier
            .clip(shape)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, shape)
            .tap(onClickLabel = title, onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val box = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .size(HomeSpace.TileIconBox)
                    .clip(box)
                    .background(OnRoadColors.Surface)
                    .border(1.dp, OnRoadColors.Border, box),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = OnRoadColors.TextPrimary, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(HomeSpace.TileTitleTop))
        Text(title, style = OnRoadType.Body3.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Clip)
        Spacer(Modifier.height(HomeSpace.TileDescTop))
        Text(desc, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
    }
}

/**
 * 정책 카드 분야 아이콘 (단색 선). 주거 집 · 생활·금융 원화 · 자립 새싹 · 취업 가방 · 교육 책 · 건강 알약 · 상담 말풍선.
 * 24 칸 기준 선 그림이라 크기를 키워도 선이 깔끔하다.
 */
private object CategoryArt {
    private class Part(val d: String, val rotate: Float = 0f)

    private fun line(name: String, vararg parts: Part): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        parts.forEach { part ->
            if (part.rotate != 0f) b.addGroup(rotate = part.rotate, pivotX = 12f, pivotY = 12f)
            b.addPath(
                pathData = addPathNodes(part.d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            if (part.rotate != 0f) b.clearGroup()
        }
        return b.build()
    }

    private fun line(name: String, vararg paths: String): ImageVector = line(name, *paths.map { Part(it) }.toTypedArray())

    private val house by lazy { line("house", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z") }
    private val won by lazy {
        line("won", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M7.5 8.5l2 7 2.5-6 2.5 6 2-7M7 11.5h10")
    }
    private val sprout by lazy {
        line(
            "sprout",
            "M12 21v-9",
            "M12 12C12 7.5 8.5 5 4 5c0 4.5 3.5 7 8 7z",
            "M12 14c0-4 3-6.5 8-6.5 0 4-3 6.5-8 6.5z",
        )
    }
    private val bag by lazy {
        line(
            "bag",
            "M5 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z",
            "M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M3 12.5h18M11 12.5v2h2v-2",
        )
    }
    private val book by lazy {
        line("book", "M3 5c3-1 6-1 9 1 3-2 6-2 9-1v14c-3-1-6-1-9 1-3-2-6-2-9-1z", "M12 6v14")
    }
    private val pill by lazy {
        line(
            "pill",
            Part("M6.5 8.5h11a3.5 3.5 0 0 1 0 7h-11a3.5 3.5 0 0 1 0-7z", rotate = -35f),
            Part("M10 9.2l4 5.6"),
        )
    }
    private val bubble by lazy { line("bubble", "M4 12a8 7 0 1 1 3.5 5.8L4 19l1.2-3.2A6.6 6.6 0 0 1 4 12z") }

    fun of(category: String): ImageVector = when (category) {
        "주거" -> house
        "생활·금융", "생활비", "금융" -> won
        "취업" -> bag
        "교육" -> book
        "건강", "의료" -> pill
        "상담" -> bubble
        else -> sprout // 자립 · 기타
    }
}
