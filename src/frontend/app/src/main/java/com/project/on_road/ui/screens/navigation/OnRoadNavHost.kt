package com.project.on_road.ui.screens.navigation

import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import com.project.on_road.ui.components.LocalTabRoot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.project.on_road.ui.components.tap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.navigation.compose.currentBackStackEntryAsState
import com.project.on_road.ui.components.BottomTab
import com.project.on_road.ui.components.OnRoadBottomBar
import com.project.on_road.ui.components.bottomTabs

import com.project.on_road.ui.components.ToastHost
import com.project.on_road.ui.components.clearFocusOnTapOutside
import com.project.on_road.ui.components.RefreshArea
import com.project.on_road.ui.components.SharedScreens
import com.project.on_road.ui.components.ProvideNavAnimScope

import com.project.on_road.data.ActivityStore
import com.project.on_road.data.BasicLink
import com.project.on_road.data.DDate
import com.project.on_road.ui.screens.GoalTimelineScreen
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.project.on_road.ui.theme.OnRoadMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.project.on_road.data.ActionKind
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.NextAction
import com.project.on_road.data.NextStep
import com.project.on_road.data.TimelineLink
import com.project.on_road.data.UserStage
import com.project.on_road.ui.components.LocalGoHome
import com.project.on_road.ui.components.ProvideVoice
import com.project.on_road.ui.screens.BudgetSimulationScreen
import com.project.on_road.ui.screens.ChatScreen
import com.project.on_road.ui.screens.ChecklistListScreen
import com.project.on_road.ui.screens.ChecklistScreen
import com.project.on_road.ui.screens.CompareScreen
import com.project.on_road.ui.screens.DashboardScreen
import com.project.on_road.ui.screens.DocumentScreen
import com.project.on_road.ui.screens.DocPickerScreen
import com.project.on_road.ui.screens.DocWriterScreen
import com.project.on_road.ui.screens.FormGuideScreen
import com.project.on_road.ui.screens.MonthLifeScreen
import com.project.on_road.ui.screens.FormHelperScreen
import com.project.on_road.ui.screens.GrowthScreen
import com.project.on_road.ui.screens.HousingScreen
import com.project.on_road.ui.screens.InterestScreen
import com.project.on_road.ui.screens.CareerRoadmapScreen
import com.project.on_road.ui.screens.NextActionScreen
import com.project.on_road.ui.screens.NotificationScreen
import com.project.on_road.ui.screens.OnboardingScreen
import com.project.on_road.ui.screens.PolicySearchScreen
import com.project.on_road.ui.screens.PolicyDetailScreen
import com.project.on_road.ui.screens.MyScreen
import com.project.on_road.ui.screens.DocsHubScreen
import com.project.on_road.ui.screens.PracticeListScreen
import com.project.on_road.ui.screens.PracticePlayScreen
import com.project.on_road.ui.screens.RiskCheckScreen
import com.project.on_road.ui.screens.RoleplayListScreen
import com.project.on_road.ui.screens.RoleplayPlayScreen
import com.project.on_road.ui.screens.SavingsScreen
import com.project.on_road.ui.screens.SettingsScreen
import com.project.on_road.ui.screens.MyConditionsScreen
import com.project.on_road.ui.screens.SimulationHubScreen
import com.project.on_road.ui.screens.TimelineScreen
import com.project.on_road.ui.theme.OnRoadColors

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    /** 나의 자립 조건 (나이 · 지역 · 상황 · 퇴소일 · 가족 · 확인한 조건) */
    const val MY_CONDITIONS = "settings/my-conditions"
    /** 이수한 교육 · 취득한 자격증 (3.37) */
    const val CREDENTIALS = "settings/credentials"
    const val POLICY = "policy?policyId={policyId}&q={q}"
    const val POLICY_DETAIL = "policy/detail/{policyId}"
    const val MY = "my"
    const val DOCS = "docs"                // 서류 모아 보기
    const val SIMULATION = "simulation"
    const val CHECKLISTS = "checklists"
    const val CHECKLIST = "checklist/{policyId}"
    const val FORM_GUIDE = "form-guide/{policyId}"
    const val COMPARE = "compare?ids={ids}"
    const val NEXT_ACTIONS = "next-actions"
    const val NOTIFICATIONS = "notifications"
    const val READINESS = "readiness"
    const val SIM_BUDGET = "sim/budget"
    const val SIM_MONTH = "sim/month"
    const val SIM_MONTH_PATTERN = "sim/month?income={income}"
    fun monthLife(income: Int? = null) = if (income == null) SIM_MONTH else "sim/month?income=$income"
    const val SIM_RESULT = "sim/budget/{simId}"
    const val SIM_HOUSING = "sim/housing"
    const val SIM_SAVINGS = "sim/savings"
    const val SIM_TIMELINE = "sim/timeline"
    const val PRACTICE = "sim/practice"
    const val PRACTICE_PLAY = "sim/practice/{id}"
    const val GROWTH = "growth"
    const val CHAT = "chat?q={q}&cid={cid}"
    const val INTEREST = "coach/interest"
    /** 진로 로드맵 (취업 · 진학 · 직업훈련) */
    const val CAREER_ROADMAP = "coach/career-roadmap"
    const val ROLEPLAY = "coach/roleplay"
    const val ROLEPLAY_PLAY = "coach/roleplay/{id}?from={from}"
    const val FORMS = "coach/forms"
    const val RISK = "coach/risk"           // 서류 속 숨겨진 위험 찾기
    const val DOCUMENT = "coach/document"
    const val DOC_PICKER = "coach/docs/{policyId}"
    const val DOC_WRITER = "coach/doc?documentId={documentId}&policyId={policyId}&templateId={templateId}&itemId={itemId}&ctx={ctx}"
    const val BASICS = "teen/basics"        // 청소년 자립 기초 미션
    const val BASIC = "teen/basics/{id}"

    fun practice(id: String) = "sim/practice/$id"
    fun basic(id: String) = "teen/basics/$id"
    /** from: 연습을 시작한 곳 (checklist · schedule). 연습이 끝나면 그곳으로 돌아가는 버튼 이름이 바뀐다 */
    fun roleplay(id: String, from: String? = null) = if (from == null) "coach/roleplay/$id" else "coach/roleplay/$id?from=$from"
    fun checklist(policyId: String) = "checklist/$policyId"
    fun formGuide(policyId: String) = "form-guide/$policyId"
    fun docPicker(policyId: String) = "coach/docs/${Uri.encode(policyId)}"

    /**
     * 서류 작성 (문서 AI). 쓰던 서류(documentId) 이어 쓰기, 또는 정책 신청서(policyId)·앱 양식(templateId) 새로 시작.
     * itemId: 연결할 체크리스트 서류 항목 (내보내면 체크), ctx: 상담에서 넘어온 맥락
     */
    fun docWriter(
        documentId: Long? = null,
        policyId: Long? = null,
        templateId: String? = null,
        itemId: Long? = null,
        context: String? = null,
    ): String = buildString {
        append("coach/doc")
        val params = listOfNotNull(
            documentId?.let { "documentId=$it" },
            policyId?.let { "policyId=$it" },
            templateId?.let { "templateId=${Uri.encode(it)}" },
            itemId?.let { "itemId=$it" },
            context?.takeIf { it.isNotBlank() }?.let { "ctx=${Uri.encode(it.take(500))}" },
        )
        if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
    }
    /** 지난 생활비 결과 다시 보기 (3.14) */
    fun simResult(simulationId: String) = "sim/budget/${Uri.encode(simulationId)}"

    fun policyDetail(policyId: String) = "policy/detail/${Uri.encode(policyId)}"

    /** 정책 하나를 보려면(질문 없이 id 만) 정책 상세로, 그 밖에는 정책 찾기로 */
    fun policy(policyId: String? = null, query: String? = null): String =
        if (policyId != null && query.isNullOrBlank()) policyDetail(policyId) else policySearch(policyId, query)

    private fun policySearch(policyId: String?, query: String?): String = buildString {
        append("policy")
        val params = listOfNotNull(
            policyId?.let { "policyId=${Uri.encode(it)}" },
            query?.takeIf { it.isNotBlank() }?.let { "q=${Uri.encode(it)}" },
        )
        if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
    }

    fun chat(question: String? = null, chatId: Long? = null): String = buildString {
        append("chat")
        val params = listOfNotNull(
            question?.takeIf { it.isNotBlank() }?.let { "q=${Uri.encode(it)}" },
            chatId?.let { "cid=$it" },
        )
        if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
    }

    fun compare(ids: List<String> = emptyList()): String =
        if (ids.isEmpty()) "compare" else "compare?ids=${ids.joinToString(",")}"
}

fun actionRoute(action: NextAction): String = when (action.kind) {
    ActionKind.OPEN_CHECKLIST, ActionKind.START_CHECKLIST ->
        action.policyId?.let(Routes::checklist) ?: Routes.CHECKLISTS
    ActionKind.RUN_SIMULATION -> Routes.SIM_BUDGET
    ActionKind.SEARCH_POLICY -> Routes.policy()
    ActionKind.SEARCH_TOPIC -> Routes.policy(query = action.query)
    ActionKind.OPEN_TIMELINE -> Routes.SIM_TIMELINE
    ActionKind.PRACTICE -> action.scenarioId?.let(Routes::roleplay) ?: Routes.ROLEPLAY
    ActionKind.EXPLORE_CAREER, ActionKind.ROADMAP -> Routes.INTEREST
    ActionKind.BASIC_MISSION -> Routes.SIMULATION
    ActionKind.MONTH_LIFE -> Routes.SIM_MONTH
}

/** 이어서 해 볼 것 → 화면 */
fun stepRoute(step: NextStep): String = when (step.kind) {
    "search" -> Routes.policy(query = step.query)
    "practice" -> step.scenarioId?.let(Routes::roleplay) ?: Routes.ROLEPLAY
    "budget" -> Routes.SIM_BUDGET
    "month" -> Routes.monthLife(step.query?.toIntOrNull())
    "forms" -> Routes.DOCS
    "chat" -> Routes.chat(question = step.query)
    "career" -> Routes.CAREER_ROADMAP
    "interest" -> Routes.INTEREST
    // 서류를 다 쓰고 그 정책 체크리스트로 돌아가기 (query = 정책 id)
    "checklist" -> step.query?.let(Routes::checklist) ?: Routes.CHECKLISTS
    else -> Routes.DASHBOARD
}

fun basicLinkRoute(link: BasicLink): String = when (link) {
    BasicLink.SAVINGS -> Routes.SIM_SAVINGS
    BasicLink.BUDGET -> Routes.SIM_BUDGET
    BasicLink.PRACTICE -> Routes.ROLEPLAY
}

fun timelineRoute(link: TimelineLink): String = when (link) {
    TimelineLink.BUDGET -> Routes.SIM_BUDGET
    TimelineLink.HOUSING -> Routes.SIM_HOUSING
    TimelineLink.SAVINGS -> Routes.SIM_SAVINGS
    // 서버 정책 id는 숫자라서 예시 id 대신 검색어로 찾는다
    TimelineLink.POLICY_SETTLEMENT -> Routes.policy(query = "자립정착금")
    TimelineLink.POLICY_ALLOWANCE -> Routes.policy(query = "자립수당")
    TimelineLink.POLICY_COUNSEL -> Routes.policy(query = "자립지원전담기관 상담")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnRoadNavHost() {
    val nav = rememberNavController()
    // 저장된 user_id가 있으면 바로 홈, 없으면 온보딩
    val start = remember { if (AppContainer.session.userId != null) Routes.DASHBOARD else Routes.ONBOARDING }

    // 쓰는 중에 서버에 계정이 없다고 하면: 한 번 알리고 처음부터 다시 (서버 DB 를 새로 만든 경우 등)
    if (com.project.on_road.data.AppState.accountMissing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { androidx.compose.material3.Text("처음부터 다시 시작해 주세요") },
            text = { androidx.compose.material3.Text("서버에서 이 계정을 찾지 못했어요. 서버가 새로 바뀌었을 수 있어요. 다시 시작하면 새 계정으로 이어서 쓸 수 있어요.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.project.on_road.data.AppState.accountMissing = false
                    AppContainer.session.userId?.let { AppContainer.session.removeAccount(it) }
                    com.project.on_road.data.ScreenCache.clear()
                    AppState.updateStage(UserStage.YOUNG_ADULT)
                    nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
                }) { androidx.compose.material3.Text("다시 시작하기") }
            },
        )
    }

    // 홈을 띄운 채로 서버에서 user_id 확인. 없는 사용자(404)면 온보딩으로 보낸다.
    LaunchedEffect(Unit) {
        if (start != Routes.DASHBOARD) return@LaunchedEffect
        AppContainer.verifySession()
        if (AppContainer.session.userId == null) {
            nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }
    // 두 번 눌러 같은 화면이 두 번 열리는 것만 막는다 (아주 짧은 간격만 무시).
    // 예전에는 화면 전환이 끝날 때까지 누른 걸 모두 무시해서, 새 화면에서 바로 누르면 반응이 없었다
    // 화면이 넘어가는 중에 또 이동하면 진행 중이던 전환이 중간에 '뚝' 끊긴다.
    // 그래서 전환 중에 누른 건 버리지 않고, 전환이 거의 끝났을 때 이어서 실행한다 (누른 건 반드시 실행된다)
    val navScope = androidx.compose.runtime.rememberCoroutineScope()
    val gate = remember { TapGate(navScope) }
    /**
     * 탭 첫 화면으로 옮기기: 홈 위에 그 탭 하나만 남긴다.
     * 예전에는 탭을 옮길 때 보던 자리를 저장 · 복원했는데, 한 탭 안에서 다른 탭 화면을 열면
     * (신청 관리 → '정책 찾기' 버튼 → 검색) 그 자리가 신청 관리 쪽에 저장돼서, 신청 관리 탭을 눌러도 검색 화면으로 돌아왔다.
     */
    val toTabRoot: (String) -> Unit = remember(nav) {
        { target ->
            if (target == Routes.DASHBOARD) {
                nav.popBackStack(Routes.DASHBOARD, inclusive = false)
            } else {
                nav.navigate(target) {
                    popUpTo(Routes.DASHBOARD) { inclusive = false }
                    launchSingleTop = true
                }
            }
            Unit
        }
    }
    // 화면 안 버튼이 탭 첫 화면(검색어 없는 정책 찾기 · 신청 관리 · 생활 가이드 · 마이 · 홈)을 열면 탭을 옮긴 것과 똑같이 처리한다
    val go: (String) -> Unit = remember(nav) {
        { route ->
            if (route in TAB_TARGETS) gate.run(OnRoadMotion.CONTENT) { toTabRoot(route) }
            else gate.run(OnRoadMotion.SCREEN_IN) { nav.navigate(route) { launchSingleTop = true } }
        }
    }
    // 알림을 눌러 열었으면 그 화면으로 (로그인한 상태일 때만)
    val pending = com.project.on_road.notification.PendingRoute.route
    LaunchedEffect(pending) {
        if (pending != null && AppContainer.session.userId != null) {
            com.project.on_road.notification.PendingRoute.take()?.let { r -> runCatching { nav.navigate(r) } }
        }
    }
    // 전환 중 뒤로가기를 또 누르면 두 화면이 한꺼번에 닫히거나 첫 화면에서 빈 화면이 되던 것을 막는다
    val back: () -> Unit = remember(nav) {
        { gate.run(OnRoadMotion.SCREEN_OUT) { if (nav.previousBackStackEntry != null) nav.popBackStack() }; Unit }
    }
    // 하단 메뉴: 탭을 옮겨도 홈 위에 한 겹만 쌓인다
    val currentEntry by nav.currentBackStackEntryAsState()
    // 알림 권한: 가입(로그인) 뒤 처음 한 번만 묻고, 허용하면 알림(D-day · 오늘 챙길 것 · 신청 예정일)을 켠다
    val notifyContext = androidx.compose.ui.platform.LocalContext.current
    val notifyAsk = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) com.project.on_road.notification.Notifications.setEnabled(notifyContext, true) }
    LaunchedEffect(currentEntry?.destination?.route) {
        if (AppContainer.notifyAsked || AppContainer.session.userId == null) return@LaunchedEffect
        AppContainer.notifyAsked = true
        if (AppContainer.session.ddayAlarm) return@LaunchedEffect
        val needs = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(notifyContext, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            needs -> notifyAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            // 안드로이드 12 이하는 권한을 묻지 않고 기본으로 허용이라 켜 둔다 (이미 허용했는데 꺼 둔 경우는 그대로)
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ->
                com.project.on_road.notification.Notifications.setEnabled(notifyContext, true)
        }
    }
    // 홈 버튼은 탭 첫 화면에서 두 단계 이상 들어갔을 때만 (한 단계면 뒤로 한 번이면 메뉴가 보인다)
    val stack by nav.currentBackStack.collectAsState()
    val showHomeFor: (String) -> Boolean = { id ->
        val list = stack.filter { it.destination.route != null }
        val i = list.indexOfFirst { it.id == id }
        val lastTab = if (i < 0) -1 else list.subList(0, i + 1).indexOfLast { it.destination.route in TAB_ROUTES }
        i >= 0 && lastTab >= 0 && i - lastTab >= 2
    }
    val currentRoute = currentEntry?.destination?.route
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(currentEntry?.id) { focusManager.clearFocus(force = true) }
    val tabs = remember { bottomTabs(Routes.DASHBOARD, Routes.POLICY, Routes.CHECKLISTS, Routes.SIMULATION, Routes.MY) }
    val selectTab: (BottomTab) -> Unit = remember(nav) {
        { tab ->
            val now = nav.currentBackStackEntry?.destination?.route
            // 지금 탭을 또 누르면 맨 위로
            if (tab.route == now) com.project.on_road.ui.components.TabReselect.fire()
            else {
                gate.run(OnRoadMotion.CONTENT) {
                    // 기다리는 사이 이미 그 탭이 됐으면 다시 열지 않는다
                    if (nav.currentBackStackEntry?.destination?.route != tab.route) {
                        toTabRoot(if (tab.route == Routes.POLICY) Routes.policy() else tab.route)
                    }
                }
            }
        }
    }
    val goHome: () -> Unit = remember(nav) {
        { gate.run(OnRoadMotion.SCREEN_OUT) { nav.popBackStack(Routes.DASHBOARD, inclusive = false) }; Unit }
    }

    val navGap = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() * 0.6f
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    // 키보드가 올라오면 하단 메뉴를 숨긴다 (검색할 때 자리를 차지하지 않게)
    val imeVisible = WindowInsets.isImeVisible
    // 메뉴가 떠 있는지 화면들에 알려 준다 (화면 아래 그라데이션과 메뉴 그라데이션이 겹치지 않게)
    val barOn = currentRoute in TAB_ROUTES && !imeVisible
    androidx.compose.runtime.SideEffect { com.project.on_road.ui.components.BarShown.value = barOn }

    ProvideVoice {
        // 화면마다 아래에 화면이 남아 있는지 (시스템 뒤로가기를 연타 방지 문으로 보낼지 정한다)
        val canBackFor: (String) -> Boolean = { id ->
            val list = stack.filter { it.destination.route != null }
            list.indexOfFirst { it.id == id } > 0
        }
        CompositionLocalProvider(
            LocalGoHome provides goHome,
            LocalShowHome provides showHomeFor,
            LocalGatedBack provides back,
            LocalCanBack provides canBackFor,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(OnRoadColors.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    // 아래 시스템 바를 숨겨도 그 자리의 60%만큼은 띄운다 (하단 메뉴 · 버튼이 화면 끝에 붙지 않게).
                    // 키보드나 시스템 바가 올라와 이미 아래가 띄워져 있으면 더하지 않는다
                    .padding(bottom = (navGap - safeBottom).coerceAtLeast(0.dp)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        // 화면 사이에 같은 요소(정책 이름)가 이어져 움직이도록 전체를 한 번 감싼다
                        SharedScreens { OnRoadGraph(nav, start, go, back) }
                    }
                    // 하단 메뉴: 네 탭의 첫 화면에서만 내용 위에 떠 있고, 들어가고 나올 때 아래로 접히며 사라진다
                    AnimatedVisibility(
                        visible = currentRoute in TAB_ROUTES && !imeVisible,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        // clip = false: 메뉴 위로 그리는 흐려지는 띠가 잘리지 않게
                        enter = expandVertically(OnRoadMotion.spatialSize, expandFrom = Alignment.Top, clip = false) + fadeIn(OnRoadMotion.enter()),
                        exit = shrinkVertically(OnRoadMotion.spatialSize, shrinkTowards = Alignment.Top, clip = false) + fadeOut(OnRoadMotion.exit()),
                    ) {
                        // 바깥에서 띄운 아래 여백만큼 빼서, 메뉴 위 · 양옆 · 아래 여백이 똑같아 보이게
                        OnRoadBottomBar(tabs, selectedRoute = currentRoute, onSelect = selectTab, bottomInset = (navGap - safeBottom).coerceAtLeast(0.dp))
                    }
                }
                // 하단 메뉴 위 둥근 버튼 (검색 탭 '상담하러 가기'): 메뉴 · 그라데이션 위에 그려서 흐려지지 않는다.
                // 오른쪽 끝은 메뉴 오른쪽 끝과 맞추고, 메뉴 바로 위에 틈(Gap)만큼 띄운다
                val barInset = (navGap - safeBottom).coerceAtLeast(0.dp)
                val fabRoute = com.project.on_road.ui.components.FloatingAction.route
                AnimatedVisibility(
                    visible = fabRoute != null && currentRoute in TAB_ROUTES && !imeVisible,
                    enter = fadeIn(OnRoadMotion.enter()) + androidx.compose.animation.scaleIn(OnRoadMotion.spatial(), initialScale = 0.85f),
                    exit = fadeOut(OnRoadMotion.exit()) + androidx.compose.animation.scaleOut(OnRoadMotion.exit(), targetScale = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = com.project.on_road.ui.components.BarSpace.Margin,
                            bottom = com.project.on_road.ui.components.BarSpace.Margin - barInset.coerceAtMost(com.project.on_road.ui.components.BarSpace.Margin) +
                                com.project.on_road.ui.components.BarSpace.Top +
                                60.dp + com.project.on_road.ui.components.FloatingAction.Gap,
                        ),
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(com.project.on_road.ui.components.FloatingAction.Size)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(OnRoadColors.Primary)
                            .tap(onClickLabel = "상담하러 가기") { fabRoute?.let(go) },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Outlined.SupportAgent,
                            "상담하러 가기",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                // 검색 탭 'n개 담았어요' 바: 메뉴 · 그라데이션 '위'에 그린다 (흐려지지 않게).
                // 메뉴가 있으면 메뉴 바로 위에, 메뉴가 숨으면(키보드 등) 화면 맨 아래에
                val trayCount = com.project.on_road.ui.components.CompareTray.count
                var lastTray by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                if (trayCount > 0) lastTray = trayCount
                val barShown = currentRoute in TAB_ROUTES && !imeVisible
                val trayBottom by androidx.compose.animation.core.animateDpAsState(
                    if (barShown) 60.dp + (com.project.on_road.ui.components.BarSpace.Margin - barInset).coerceAtLeast(0.dp) else 0.dp,
                    OnRoadMotion.move(),
                    label = "tray-bottom",
                )
                AnimatedVisibility(
                    visible = trayCount > 0,
                    enter = androidx.compose.animation.slideInVertically(OnRoadMotion.enter()) { it } + fadeIn(OnRoadMotion.enter()),
                    exit = androidx.compose.animation.slideOutVertically(OnRoadMotion.exit()) { it } + fadeOut(OnRoadMotion.exit()),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = trayBottom),
                ) {
                    com.project.on_road.ui.screens.CompareBar(count = lastTray) { com.project.on_road.ui.components.CompareTray.onCompare() }
                }
                // 저장했어요·복사했어요 같은 짧은 메시지가 모든 화면 위에 뜬다
                ToastHost()
            }
        }
    }
}

@Composable
private fun OnRoadGraph(
    nav: NavHostController,
    start: String,
    go: (String) -> Unit,
    back: () -> Unit,
) {
    NavHost(
        navController = nav,
        startDestination = start,
        // 앞으로: 새 화면이 오른쪽에서 조금(폭의 1/8) 밀려 들어오며 나타나고, 지금 화면은 왼쪽으로 살짝 물러난다.
        // 뒤로: 지금 화면이 오른쪽으로 조금 밀리며 사라지고, 아래 있던 화면이 제자리로 돌아온다.
        // 폭 전체를 0.5초 넘게 밀면 눈이 따라가느라 어지러워서, 짧은 거리 + 0.3초로 '넘어가는' 느낌만 남겼다.
        // 화면마다 바탕을 꽉 채워 두어(screen) 겹치는 동안 아래 화면이 비치지 않는다.
        // 하단 메뉴로 탭끼리 옮길 때는 밀지 않고 겹쳐서 바꾼다 (같은 높이의 화면이라는 느낌)
        enterTransition = {
            if (isTabSwitch()) fadeIn(tween(OnRoadMotion.CONTENT, easing = OnRoadMotion.EmphasizedDecelerate))
            // 화면 폭 전체가 아니라 조금만 밀려 들어오며 나타난다 (눈이 덜 따라가게)
            else slideInHorizontally(tween(OnRoadMotion.SCREEN_IN, easing = OnRoadMotion.Emphasized)) { w -> w / OnRoadMotion.SCREEN_SHIFT } +
                fadeIn(tween(OnRoadMotion.SCREEN_IN, easing = OnRoadMotion.Emphasized))
        },
        exitTransition = {
            if (isTabSwitch()) fadeOut(tween(OnRoadMotion.FAST))
            // 물러나는 화면은 살짝 어두워지듯 옅어져서 새 화면이 위에 올라오는 느낌
            else slideOutHorizontally(tween(OnRoadMotion.SCREEN_IN, easing = OnRoadMotion.Emphasized)) { w -> -w / OnRoadMotion.BACK_SHIFT } +
                fadeOut(tween(OnRoadMotion.SCREEN_IN, easing = OnRoadMotion.Emphasized), targetAlpha = OnRoadMotion.BACK_ALPHA)
        },
        // 뒤로: 두 화면이 같은 시간 · 같은 곡선으로 함께 움직인다 (예전엔 스프링과 시간이 섞여 따로 놀고,
        // 아래 화면이 많이 어두웠다가 밝아져서 어색했다)
        popEnterTransition = {
            if (isTabSwitch()) fadeIn(tween(OnRoadMotion.CONTENT, easing = OnRoadMotion.EmphasizedDecelerate))
            else slideInHorizontally(tween(OnRoadMotion.SCREEN_OUT, easing = OnRoadMotion.Emphasized)) { w -> -w / OnRoadMotion.BACK_SHIFT } +
                fadeIn(tween(OnRoadMotion.SCREEN_OUT, easing = OnRoadMotion.Emphasized), initialAlpha = OnRoadMotion.BACK_ALPHA)
        },
        popExitTransition = {
            if (isTabSwitch()) fadeOut(tween(OnRoadMotion.FAST))
            else slideOutHorizontally(tween(OnRoadMotion.SCREEN_OUT, easing = OnRoadMotion.Emphasized)) { w -> w / OnRoadMotion.SCREEN_SHIFT } +
                fadeOut(tween(OnRoadMotion.SCREEN_OUT, easing = OnRoadMotion.Emphasized))
        },
    ) {
        screen(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                // 마지막 버튼을 두 번 눌러도 홈이 두 겹으로 쌓이지 않게
                nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.ONBOARDING) { inclusive = true }; launchSingleTop = true }
            })
        }

        // 대상은 퇴소 전후 자립준비청년 하나로 좁혔다 (청소년·아동 전용 화면은 뺐다)
        screen(Routes.DASHBOARD) {
            // 홈 화면과 같은 뷰모델 (이 화면 단위로 하나)
            val vm: com.project.on_road.ui.screens.DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            RefreshArea(vm.refreshing, vm::refresh, Modifier.fillMaxSize()) { DashboardScreen(go, vm) }
        }

        screen(Routes.MY) { MyScreen(onNavigate = go) }
        screen(Routes.DOCS) { DocsHubScreen(onBack = back, onNavigate = go) }

        screen(Routes.SETTINGS) {
            SettingsScreen(
                onBack = back,
                onReset = {
                    AppContainer.session.clear()
                    AppState.updateStage(UserStage.YOUNG_ADULT)
                    nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
                },
                // 계정을 바꾸면 그 계정 단계의 홈으로 새로 연다
                onSwitched = { nav.navigate(Routes.DASHBOARD) { popUpTo(nav.graph.id) { inclusive = true } } },
                onOpenConditions = { go(Routes.MY_CONDITIONS) },
                onOpenCredentials = { go(Routes.CREDENTIALS) },
            )
        }
        screen(Routes.MY_CONDITIONS) { MyConditionsScreen(onBack = back) }
        screen(Routes.CREDENTIALS) { com.project.on_road.ui.screens.CredentialsScreen(onBack = back) }

        screen(
            Routes.POLICY,
            arguments = listOf(
                navArgument("policyId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            PolicySearchScreen(
                initialPolicyId = entry.arguments?.getString("policyId"),
                initialQuery = entry.arguments?.getString("q"),
                onBack = back,
                onPrepare = { go(Routes.checklist(it)) },
                onCompare = { go(Routes.compare(it)) },
                onOpen = { go(Routes.policyDetail(it)) },
                onNavigate = go,
            )
        }

        screen(
            Routes.POLICY_DETAIL,
            arguments = listOf(navArgument("policyId") { type = NavType.StringType }),
        ) { entry ->
            PolicyDetailScreen(
                policyId = entry.arguments?.getString("policyId").orEmpty(),
                onBack = back,
                onPrepare = { go(Routes.checklist(it)) },
                onWrite = { id -> go(Routes.docWriter(policyId = id.toLongOrNull())) },
                onAsk = { q -> go(Routes.chat(question = q)) },
                onOpenPolicy = { go(Routes.policyDetail(it)) },
            )
        }

        screen(Routes.SIMULATION) { SimulationHubScreen(onBack = back, onNavigate = go) }
        screen(
            Routes.SIM_MONTH_PATTERN,
            arguments = listOf(navArgument("income") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            MonthLifeScreen(onBack = back, onNavigate = go, income = entry.arguments?.getString("income")?.toIntOrNull())
        }
        screen(Routes.SIM_BUDGET) { BudgetSimulationScreen(onBack = back, onOpenPolicy = { go(Routes.policy(it)) }, onNavigate = go) }
        screen(
            Routes.SIM_RESULT,
            arguments = listOf(navArgument("simId") { type = NavType.StringType }),
        ) { entry ->
            BudgetSimulationScreen(
                onBack = back,
                onOpenPolicy = { go(Routes.policy(it)) },
                simulationId = entry.arguments?.getString("simId"),
                onNavigate = go,
            )
        }
        screen(Routes.SIM_HOUSING) { HousingScreen(onBack = back, onOpenPolicy = { go(Routes.policy(it)) }, onNavigate = go) }
        screen(Routes.SIM_SAVINGS) { SavingsScreen(onBack = back, onOpenPolicy = { go(Routes.policy(it)) }, onNavigate = go) }
        screen(Routes.SIM_TIMELINE) {
            // 퇴소 전: 퇴소일 기준 타임라인 / 퇴소 후: 내가 정한 목표 D-day (같은 d_date 를 다른 뜻으로 쓰므로 화면을 나눈다)
            // 퇴소 후(목표 D-day)도 같은 타임라인: 목표 카드 · 내 일정 · 퇴소 후 할 일 · 정책 · 진로가 한 줄에
            TimelineScreen(onBack = back, onNavigate = go)
        }

        screen(Routes.PRACTICE) { PracticeListScreen(onBack = back, onOpen = { go(Routes.practice(it)) }) }
        screen(
            Routes.PRACTICE_PLAY,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            PracticePlayScreen(
                scenarioId = entry.arguments?.getString("id").orEmpty(),
                onBack = back,
                onOpenBudget = { go(Routes.SIM_BUDGET) },
            )
        }

        screen(Routes.CHECKLISTS) {
            ChecklistListScreen(
                onBack = back,
                onOpen = { go(Routes.checklist(it)) },
                onSearch = { go(Routes.policy()) },
                onNavigate = go,
            )
        }
        screen(
            Routes.CHECKLIST,
            arguments = listOf(navArgument("policyId") { type = NavType.StringType }),
        ) { entry ->
            ChecklistScreen(
                policyId = entry.arguments?.getString("policyId").orEmpty(),
                onBack = back,
                onFormGuide = { go(Routes.formGuide(it)) },
                onOpenDocs = { go(Routes.docPicker(it)) },
                onOpenDocument = { go(Routes.docWriter(documentId = it)) },
                onNavigate = go,
            )
        }
        screen(
            Routes.FORM_GUIDE,
            arguments = listOf(navArgument("policyId") { type = NavType.StringType }),
        ) { entry ->
            FormGuideScreen(policyId = entry.arguments?.getString("policyId").orEmpty(), onBack = back)
        }

        screen(
            Routes.COMPARE,
            arguments = listOf(navArgument("ids") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val ids = entry.arguments?.getString("ids").orEmpty().split(",").filter { it.isNotBlank() }
            CompareScreen(
                ids = ids,
                onBack = back,
                onSearch = { go(Routes.policy()) },
                onPrepare = { go(Routes.checklist(it)) },
            )
        }

        screen(Routes.NEXT_ACTIONS) { NextActionScreen(onBack = back, onAction = { go(actionRoute(it)) }) }
        screen(Routes.NOTIFICATIONS) { NotificationScreen(onBack = back, onNavigate = go) }
        screen(Routes.READINESS) { com.project.on_road.ui.screens.ReadinessScreen(onBack = back, onNavigate = go) }

        screen(
            Routes.CHAT,
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("cid") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ChatScreen(
                initialQuestion = entry.arguments?.getString("q"),
                onBack = back,
                onOpenPolicy = { go(Routes.policy(it)) },
                onPrepare = { go(Routes.checklist(it)) },
                onWriteDocument = { c, ctx -> go(Routes.docWriter(policyId = c.policyId, templateId = c.templateId, context = ctx)) },
                chatId = entry.arguments?.getString("cid")?.toLongOrNull(),
                onNavigate = go,
            )
        }

        screen(Routes.GROWTH) {
            GrowthScreen(
                onBack = back,
                onOpenPolicy = { go(Routes.policy(it)) },
                onOpenChecklist = { go(Routes.checklist(it)) },
                onOpenSimulation = { go(Routes.simResult(it)) },
                onOpenRoleplay = { go(Routes.roleplay(it)) },
                onOpenChat = { go(Routes.chat(chatId = it)) },
                onNavigate = go,
            )
        }

        // 관심 분야를 고르면 흥미 탐색을 닫고 나의 진로로 (뒤로 가면 흥미 탐색이 다시 나오지 않게)
        screen(Routes.INTEREST) {
            InterestScreen(
                onBack = back, onRoleplay = { go(Routes.ROLEPLAY) }, onNavigate = go,
                onCareer = { nav.navigate(Routes.CAREER_ROADMAP) { popUpTo(Routes.INTEREST) { inclusive = true }; launchSingleTop = true } },
            )
        }
        // 관심 분야가 없으면 나의 진로 대신 흥미 탐색부터
        screen(Routes.CAREER_ROADMAP) {
            CareerRoadmapScreen(
                onBack = back, onNavigate = go,
                onStartInterest = { nav.navigate(Routes.INTEREST) { popUpTo(Routes.CAREER_ROADMAP) { inclusive = true }; launchSingleTop = true } },
            )
        }
        screen(Routes.ROLEPLAY) { RoleplayListScreen(onBack = back, onOpen = { go(Routes.roleplay(it)) }) }
        screen(
            Routes.ROLEPLAY_PLAY,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            // 체크리스트 · 일정에서 연습하러 왔으면, 끝난 뒤 그곳으로 돌아간다 (뒤로 한 번)
            val doneLabel = when (entry.arguments?.getString("from")) {
                "checklist" -> "체크리스트로 돌아가기"
                "schedule" -> "일정으로 돌아가기"
                else -> "목록으로"
            }
            RoleplayPlayScreen(scenarioId = entry.arguments?.getString("id").orEmpty(), onBack = back, onNavigate = { go(it) }, doneLabel = doneLabel)
        }
        screen(Routes.FORMS) {
            FormHelperScreen(
                onBack = back,
                onOpenDocument = { go(Routes.docWriter(documentId = it)) },
                onStartTemplate = { go(Routes.docWriter(templateId = it)) },
                onRiskCheck = { go(Routes.RISK) },
            )
        }
        screen(Routes.RISK) { RiskCheckScreen(onBack = back, onPractice = { go(Routes.roleplay(it)) }, onNavigate = go) }
        screen(
            Routes.DOC_PICKER,
            arguments = listOf(navArgument("policyId") { type = NavType.StringType }),
        ) { entry ->
            DocPickerScreen(
                policyId = entry.arguments?.getString("policyId").orEmpty(),
                onBack = back,
                onWrite = { policyId, templateId, itemId ->
                    go(Routes.docWriter(policyId = policyId, templateId = templateId, itemId = itemId))
                },
                onOpenDocument = { go(Routes.docWriter(documentId = it)) },
            )
        }
        screen(
            Routes.DOC_WRITER,
            arguments = listOf("documentId", "policyId", "templateId", "itemId", "ctx").map {
                navArgument(it) { type = NavType.StringType; nullable = true; defaultValue = null }
            },
        ) { entry ->
            val args = entry.arguments
            DocWriterScreen(
                documentId = args?.getString("documentId")?.toLongOrNull(),
                policyId = args?.getString("policyId")?.toLongOrNull(),
                templateId = args?.getString("templateId"),
                checklistItemId = args?.getString("itemId")?.toLongOrNull(),
                context = args?.getString("ctx"),
                onBack = back,
                onNavigate = { go(it) },
            )
        }
        screen(Routes.DOCUMENT) { DocumentScreen(onBack = back) }
    }
}

/**
 * 화면 이동 문.
 *  - 같은 걸 연달아 두 번 누른 것([TAP_GAP_MS] 안)은 버린다.
 *  - 화면이 넘어가는 중에 누른 건 버리지 않고, 진행 중인 전환이 거의 끝났을 때 실행한다
 *    (전환 도중에 새 이동을 시작하면 지금 움직임이 중간에 뚝 끊기기 때문).
 *  - 기다리는 동안 또 다른 걸 누르면 마지막에 누른 것만 실행한다.
 */
private class TapGate(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var lastTap = 0L
    private var busyUntil = 0L
    private var pending: kotlinx.coroutines.Job? = null

    /** [durationMs]: 이 이동이 만드는 전환 시간 */
    fun run(durationMs: Int, action: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTap < TAP_GAP_MS) return
        lastTap = now
        pending?.cancel()
        val wait = busyUntil - now
        if (wait <= 0) {
            start(durationMs, action)
        } else {
            pending = scope.launch {
                kotlinx.coroutines.delay(wait)
                start(durationMs, action)
            }
        }
    }

    private fun start(durationMs: Int, action: () -> Unit) {
        // 곡선 끝부분(거의 멈춘 구간)까지는 기다리지 않는다: 남은 움직임이 눈에 띄지 않을 만큼만
        busyUntil = android.os.SystemClock.uptimeMillis() + (durationMs * SETTLE_RATIO).toLong()
        action()
    }
}

/** 전환이 이만큼 지나면 다음 이동을 이어도 끊겨 보이지 않는다 */
private const val SETTLE_RATIO = 0.85f

/** 두 번 누름으로 보는 간격. 화면 전환(OnRoadMotion.SCREEN_IN)보다 조금 짧게 */
private const val TAP_GAP_MS = 250L

/**
 * 화면 하나: 바탕을 꽉 채운 상자 안에 그린다.
 * 화면이 옆으로 밀려 들어오고 나갈 때 바탕이 빈 화면이 있으면 뒤 화면이 비쳐 겹쳐 보이므로.
 */
private fun androidx.navigation.NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    val scope = this
    val tabRoot = entry.destination.route in TAB_ROUTES
    val home = if (!tabRoot && LocalShowHome.current(entry.id)) LocalGoHome.current else null
    // 휴대폰 뒤로가기도 화면 안 뒤로 버튼과 같은 문을 지나게 한다 (전환 중 연타로 움직임이 끊기지 않게).
    // 화면 안에서 따로 정한 뒤로가기(검색창 닫기 등)는 나중에 등록되므로 그쪽이 먼저 받는다
    val gatedBack = LocalGatedBack.current
    val canBack = LocalCanBack.current(entry.id)
    androidx.activity.compose.BackHandler(enabled = gatedBack != null && canBack) { gatedBack?.invoke() }
    ProvideNavAnimScope(scope) {
        CompositionLocalProvider(LocalTabRoot provides tabRoot, LocalGoHome provides home) {
            Box(Modifier.fillMaxSize().background(OnRoadColors.Background).then(Modifier.clearFocusOnTapOutside())) { scope.content(entry) }
        }
    }
}

/** 화면 안에서 열어도 탭 이동으로 처리할 주소 (정책 찾기는 검색어 · 정책 id 가 없을 때만) */
private val TAB_TARGETS = setOf(Routes.DASHBOARD, "policy", Routes.CHECKLISTS, Routes.SIMULATION, Routes.MY)

/** 하단 메뉴 탭의 첫 화면 */
private val TAB_ROUTES = setOf(Routes.DASHBOARD, Routes.POLICY, Routes.CHECKLISTS, Routes.SIMULATION, Routes.MY)

private fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES

/** 연타 방지 문을 지나는 뒤로가기 */
private val LocalGatedBack = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
/** 이 화면 아래에 돌아갈 화면이 있는지 */
private val LocalCanBack = androidx.compose.runtime.compositionLocalOf<(String) -> Boolean> { { false } }

/** 화면마다 홈 버튼을 보일지 (back stack 깊이로 정한다) */
private val LocalShowHome = androidx.compose.runtime.compositionLocalOf<(String) -> Boolean> { { false } }
