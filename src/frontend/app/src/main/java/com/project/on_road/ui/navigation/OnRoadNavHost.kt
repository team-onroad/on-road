package com.project.on_road.ui.navigation

import com.project.on_road.ui.child.ChildCoachScreen
import com.project.on_road.ui.child.ChildFindScreen
import com.project.on_road.ui.child.StickerBoardScreen
import com.project.on_road.ui.child.StickerShopScreen
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.project.on_road.data.TimelineLink
import com.project.on_road.data.UserStage
import com.project.on_road.ui.child.ChildHomeScreen
import com.project.on_road.ui.components.LocalGoHome
import com.project.on_road.ui.components.ProvideVoice
import com.project.on_road.ui.screens.BudgetSimulationScreen
import com.project.on_road.ui.screens.ChatScreen
import com.project.on_road.ui.screens.ChecklistListScreen
import com.project.on_road.ui.screens.ChecklistScreen
import com.project.on_road.ui.screens.CompareScreen
import com.project.on_road.ui.screens.DashboardScreen
import com.project.on_road.ui.screens.DocumentScreen
import com.project.on_road.ui.screens.FormHelperScreen
import com.project.on_road.ui.screens.GrowthScreen
import com.project.on_road.ui.screens.HousingScreen
import com.project.on_road.ui.screens.InterestScreen
import com.project.on_road.ui.screens.NextActionScreen
import com.project.on_road.ui.screens.OnboardingScreen
import com.project.on_road.ui.screens.PolicySearchScreen
import com.project.on_road.ui.screens.PracticeListScreen
import com.project.on_road.ui.screens.PracticePlayScreen
import com.project.on_road.ui.screens.RoleplayListScreen
import com.project.on_road.ui.screens.RoleplayPlayScreen
import com.project.on_road.ui.screens.SavingsScreen
import com.project.on_road.ui.screens.SettingsScreen
import com.project.on_road.ui.screens.SimulationHubScreen
import com.project.on_road.ui.screens.TimelineScreen
import com.project.on_road.ui.teen.TeenHomeScreen
import com.project.on_road.ui.theme.OnRoadColors

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val POLICY = "policy?policyId={policyId}&q={q}"
    const val SIMULATION = "simulation"
    const val CHECKLISTS = "checklists"
    const val CHECKLIST = "checklist/{policyId}"
    const val COMPARE = "compare?ids={ids}"
    const val NEXT_ACTIONS = "next-actions"
    const val SIM_BUDGET = "sim/budget"
    const val SIM_HOUSING = "sim/housing"
    const val SIM_SAVINGS = "sim/savings"
    const val SIM_TIMELINE = "sim/timeline"
    const val PRACTICE = "sim/practice"
    const val PRACTICE_PLAY = "sim/practice/{id}"
    const val GROWTH = "growth"
    const val CHAT = "chat?q={q}"
    const val INTEREST = "coach/interest"
    const val ROLEPLAY = "coach/roleplay"
    const val ROLEPLAY_PLAY = "coach/roleplay/{id}"
    const val FORMS = "coach/forms"
    const val DOCUMENT = "coach/document"
    const val STICKER_SHOP = "child/shop"
    const val STICKER_BOARD = "child/board"
    const val CHILD_COACH = "child/coach"   // 아동 전용 상담하기
    const val CHILD_FIND = "child/find"     // 아동 전용 좋아하는 거 찾기

    fun practice(id: String) = "sim/practice/$id"
    fun roleplay(id: String) = "coach/roleplay/$id"
    fun checklist(policyId: String) = "checklist/$policyId"

    fun policy(policyId: String? = null, query: String? = null): String = buildString {
        append("policy")
        val params = listOfNotNull(
            policyId?.let { "policyId=${Uri.encode(it)}" },
            query?.takeIf { it.isNotBlank() }?.let { "q=${Uri.encode(it)}" },
        )
        if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
    }

    fun chat(question: String? = null): String =
        if (question.isNullOrBlank()) "chat" else "chat?q=${Uri.encode(question)}"

    fun compare(ids: List<String> = emptyList()): String =
        if (ids.isEmpty()) "compare" else "compare?ids=${ids.joinToString(",")}"
}

fun actionRoute(action: NextAction): String = when (action.kind) {
    ActionKind.OPEN_CHECKLIST, ActionKind.START_CHECKLIST ->
        action.policyId?.let(Routes::checklist) ?: Routes.CHECKLISTS
    ActionKind.RUN_SIMULATION -> Routes.SIM_BUDGET
    ActionKind.SEARCH_POLICY -> Routes.policy()
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

@Composable
fun OnRoadNavHost() {
    val nav = rememberNavController()
    // 저장된 user_id가 있으면 바로 홈, 없으면 온보딩
    val start = remember { if (AppContainer.session.userId != null) Routes.DASHBOARD else Routes.ONBOARDING }

    // 홈을 띄운 채로 서버에서 user_id 확인. 없는 사용자(404)면 온보딩으로 보낸다.
    LaunchedEffect(Unit) {
        if (start != Routes.DASHBOARD) return@LaunchedEffect
        AppContainer.verifySession()
        if (AppContainer.session.userId == null) {
            nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }
    val go: (String) -> Unit = remember(nav) { { route -> nav.navigate(route) } }
    val back: () -> Unit = remember(nav) { { nav.popBackStack(); Unit } }
    val goHome: () -> Unit = remember(nav) { { nav.popBackStack(Routes.DASHBOARD, inclusive = false); Unit } }

    ProvideVoice {
        CompositionLocalProvider(LocalGoHome provides goHome) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(OnRoadColors.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                OnRoadGraph(nav, start, go, back)
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
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) + fadeOut(tween(260)) },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }

        composable(Routes.DASHBOARD) {
            when (AppState.stage) {
                // 아동 '처음으로 돌아가기': 저장된 정보를 지우고 온보딩부터 다시
                UserStage.CHILD -> ChildHomeScreen(go, onLogout = {
                    AppContainer.session.clear()
                    AppState.updateStage(UserStage.YOUNG_ADULT)
                    nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
                })
                UserStage.TEEN -> TeenHomeScreen(go)
                UserStage.YOUNG_ADULT -> DashboardScreen(go)
            }
        }

        composable(Routes.STICKER_SHOP) {
            StickerShopScreen(onBack = back)
        }

        composable(Routes.STICKER_BOARD) {
            StickerBoardScreen(onBack = back, onShop = { go(Routes.STICKER_SHOP) })
        }

        composable(Routes.CHILD_COACH) {
            ChildCoachScreen(onBack = back, onFind = { go(Routes.CHILD_FIND) })
        }

        composable(Routes.CHILD_FIND) {
            ChildFindScreen(onBack = back)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back, onReset = {
                AppContainer.session.clear()
                AppState.updateStage(UserStage.YOUNG_ADULT)
                nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
            })
        }

        composable(
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
            )
        }

        composable(Routes.SIMULATION) { SimulationHubScreen(onBack = back, onNavigate = go) }
        composable(Routes.SIM_BUDGET) { BudgetSimulationScreen(onBack = back, onOpenPolicy = { go(Routes.policy(it)) }) }
        composable(Routes.SIM_HOUSING) { HousingScreen(onBack = back, onOpenPolicy = { go(Routes.policy(it)) }) }
        composable(Routes.SIM_SAVINGS) { SavingsScreen(onBack = back, onOpenPolicy = { go(Routes.policy(it)) }) }
        composable(Routes.SIM_TIMELINE) { TimelineScreen(onBack = back, onLink = { go(timelineRoute(it)) }) }

        composable(Routes.PRACTICE) { PracticeListScreen(onBack = back, onOpen = { go(Routes.practice(it)) }) }
        composable(
            Routes.PRACTICE_PLAY,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            PracticePlayScreen(
                scenarioId = entry.arguments?.getString("id").orEmpty(),
                onBack = back,
                onOpenBudget = { go(Routes.SIM_BUDGET) },
            )
        }

        composable(Routes.CHECKLISTS) {
            ChecklistListScreen(
                onBack = back,
                onOpen = { go(Routes.checklist(it)) },
                onSearch = { go(Routes.policy()) },
            )
        }
        composable(
            Routes.CHECKLIST,
            arguments = listOf(navArgument("policyId") { type = NavType.StringType }),
        ) { entry ->
            ChecklistScreen(policyId = entry.arguments?.getString("policyId").orEmpty(), onBack = back)
        }

        composable(
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

        composable(Routes.NEXT_ACTIONS) { NextActionScreen(onBack = back, onAction = { go(actionRoute(it)) }) }

        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            ChatScreen(
                initialQuestion = entry.arguments?.getString("q"),
                onBack = back,
                onOpenPolicy = { go(Routes.policy(it)) },
                onPrepare = { go(Routes.checklist(it)) },
            )
        }

        composable(Routes.GROWTH) {
            GrowthScreen(
                onBack = back,
                onOpenPolicy = { go(Routes.policy(it)) },
                onOpenChecklist = { go(Routes.checklist(it)) },
            )
        }

        composable(Routes.INTEREST) { InterestScreen(onBack = back, onRoleplay = { go(Routes.ROLEPLAY) }) }
        composable(Routes.ROLEPLAY) { RoleplayListScreen(onBack = back, onOpen = { go(Routes.roleplay(it)) }) }
        composable(
            Routes.ROLEPLAY_PLAY,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            RoleplayPlayScreen(scenarioId = entry.arguments?.getString("id").orEmpty(), onBack = back)
        }
        composable(Routes.FORMS) { FormHelperScreen(onBack = back) }
        composable(Routes.DOCUMENT) { DocumentScreen(onBack = back) }
    }
}