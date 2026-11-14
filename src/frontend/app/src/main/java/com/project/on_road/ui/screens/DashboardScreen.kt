package com.project.on_road.ui.screens

import com.project.on_road.ui.components.tap

import com.project.on_road.data.TimelinePlanner
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.key
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.project.on_road.data.PlanData
import com.project.on_road.data.PlanKind
import com.project.on_road.data.PlanTask
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.ProgressBar
import com.project.on_road.ui.components.RadioMark
import com.project.on_road.ui.theme.OnRoadMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ActionKind
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.AppContainer
import com.project.on_road.data.ChecklistSummary
import com.project.on_road.data.DDate
import com.project.on_road.data.LatestSimulation
import com.project.on_road.data.NextAction
import com.project.on_road.data.Policy
import com.project.on_road.data.RoleplayLevel
import com.project.on_road.data.RoleplayScenario
import com.project.on_road.data.TimelineRules
import com.project.on_road.data.TimelineTask
import com.project.on_road.data.TopicStat
import com.project.on_road.data.UserStage
import com.project.on_road.ui.components.IconTile
import com.project.on_road.ui.components.LogoSlot
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.OnRoadSearchBar
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.SectionHeader
import com.project.on_road.ui.components.SubtleCard
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.screens.navigation.actionRoute
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class DashboardViewModel : ViewModel() {
    private val userId get() = AppContainer.session.userId

    /** 자립까지 D-day (3.4 user.d_day). null 이면 숨김 */
    var dDay by mutableStateOf<Int?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var latestSimulation by mutableStateOf<LatestSimulation?>(null)
        private set
    /** 진행 중 체크리스트 (이번 주 할 일 카드의 진행 막대) */
    var checklists by mutableStateOf<List<ChecklistSummary>>(emptyList())
        private set

    fun load() {
        val id = userId ?: return
        viewModelScope.launch {
            val dash = runCatching { AppContainer.api.getDashboard(id) }.getOrNull()
            if (dash != null) {
                dash.user?.let { AppContainer.applyDashboardUser(it) }
                dDay = dash.user?.dDay
                loaded = true
                checklists = dash.checklists
                latestSimulation = dash.latestSimulation
            }
            // 할 일은 타임라인과 같은 목록(PlanData)에서 → 홈 · 타임라인 · 알림이 같은 일을 말한다.
            // (예전 서버 추천 '다음 할 일' 요청과 정책 상세 요청은 빼서 홈 요청이 줄었다)
            PlanData.refresh(dash?.checklists)
        }
    }
}

private fun dLabel(days: Long): String = when {
    days > 0 -> "D-$days"
    days == 0L -> "D-DAY"
    else -> "D+${-days}"
}

/** 홈 섹션 사이 간격 (다른 화면의 SectionGap 48dp 는 홈에서 너무 길어 스크롤만 늘어난다) */
private val HomeGap = 28.dp

/**
 * 자립준비청년 홈 (디자인: 자립준비청년 - 홈).
 * 순서: 인사·검색 → 이번 주 할 일 → 바로가기 6칸 → 필수 자립 지식.
 * 색은 메인(Primary #0F172A)과 포인트(Point #34D399)만 쓴다. 포인트는 진행 막대에만.
 */
@Composable
fun DashboardScreen(
    onNavigate: (String) -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val session = AppContainer.session
    val leaveDate = session.leaveDate
    // 퇴소 전: 퇴소 예정일 기준 / 퇴소 후: 내가 정한 목표일 기준 (같은 d_date, 다른 뜻)
    val goal = DDate.isGoal(session.status)
    var query by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(OnRoadColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        HomeHeader(
            stageLabel = UserStage.YOUNG_ADULT.label,
            onStage = { onNavigate(Routes.SETTINGS) },
            onAlarm = { onNavigate(Routes.NOTIFICATIONS) },
            onProfile = { onNavigate(Routes.SETTINGS) },
        )

        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(12.dp))
            // 서버 d_day 를 쓰고, 아직 못 받았으면 저장된 퇴소일로 계산
            val days: Long? = if (vm.loaded) vm.dDay?.toLong() else leaveDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
            if (days != null) {
                OnRoadTag(
                    "${DDate.untilText(session.status, session.goalTitle)} ${dLabel(days)}",
                    style = TagStyle.Neutral,
                    modifier = Modifier.tap(onClickLabel = "타임라인 보기") { onNavigate(Routes.SIM_TIMELINE) },
                )
                Spacer(Modifier.height(10.dp))
            }
            Text("${session.nickname.ifBlank { "회원" }}님,", style = OnRoadType.DisplayRegular, color = OnRoadColors.TextPrimary)
            Text("오늘 할 일부터 챙겨볼까요?", style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(18.dp))
            OnRoadSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "자립정착금, 공공임대, 근로계약 검토",
                onSearch = { q -> onNavigate(Routes.policy(query = q)) },
            )

            // ── 1. 이번 주 할 일 (타임라인과 같은 목록) ──
            Spacer(Modifier.height(HomeGap))
            SectionHeader("이번 주 할 일", actionText = "전체보기", onAction = { onNavigate(Routes.SIM_TIMELINE) })
            Spacer(Modifier.height(12.dp))
            WeekTasks(goal, leaveDate, vm.checklists, onNavigate)

            // ── 자립 준비도: 내 기록 안에만 있던 걸 홈에서 바로 (점수가 오면 부드럽게 펼쳐진다) ──
            val readiness = PlanData.readiness
            androidx.compose.animation.AnimatedVisibility(visible = readiness != null) {
                readiness?.let { r ->
                    Column {
                        Spacer(Modifier.height(12.dp))
                        ReadinessRow(r) { onNavigate(Routes.READINESS) }
                    }
                }
            }

            // ── 2. 바로가기: 모든 기능을 한 화면에 ──
            Spacer(Modifier.height(HomeGap))
            SectionHeader("바로가기")
            Spacer(Modifier.height(12.dp))
            ShortcutGrid(
                listOf(
                    Shortcut(
                        Icons.Outlined.Search, "정책 찾기", "상담 · 검색",
                        choices = listOf(
                            Choice("AI 상담", "내 상황을 말하면 맞는 정책을 찾아 줘요") { onNavigate(Routes.chat()) },
                            Choice("정책 검색", "이름·분야로 직접 찾아봐요") { onNavigate(Routes.policy()) },
                        ),
                    ),
                    Shortcut(Icons.Outlined.Checklist, "체크리스트", "신청 단계별 준비") { onNavigate(Routes.CHECKLISTS) },
                    Shortcut(Icons.Outlined.EditNote, "서류 작성", "신청서·서류 쓰기") { onNavigate(Routes.DOCS) },
                    Shortcut(
                        Icons.Outlined.RecordVoiceOver, "연습하기", "미리 해 보기",
                        choices = listOf(
                            Choice("실전 연습", "은행·주민센터·면접") { onNavigate(Routes.ROLEPLAY) },
                            Choice("한 달 살아보기", "생활비로 한 달 버티기") { onNavigate(Routes.SIM_MONTH) },
                            Choice("생활 계산", "주거비·저축") { onNavigate(Routes.SIMULATION) },
                        ),
                    ),
                    Shortcut(Icons.Outlined.Event, "타임라인", "퇴소까지 할 일") { onNavigate(Routes.SIM_TIMELINE) },
                    Shortcut(Icons.Outlined.Insights, "내 기록", "지금까지 해 온 것") { onNavigate(Routes.GROWTH) },
                ),
            )

            // ── 3. 필수 자립 지식 ──
            Spacer(Modifier.height(HomeGap))
            SectionHeader("필수 자립 지식")
            Spacer(Modifier.height(12.dp))
            KnowledgeCard(
                category = "주거 상식",
                readTime = "AI에게 물어보기",
                title = "전입신고와 확정일자, 왜 계약 당일 바로 해야 할까요?",
                summary = "보증금을 지키는 대항력과 우선변제권을 쉽게 알려 드려요.",
            ) { onNavigate(Routes.chat("전입신고와 확정일자는 왜 계약 당일 바로 해야 해?")) }

            Spacer(Modifier.height(20.dp))
            Text(
                "금액·자격·신청 가능 여부는 담당 기관에서 최종 확인해 주세요.",
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextTertiary,
            )
            Spacer(Modifier.height(32.dp))
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
        Row(Modifier.tap(onClick = onStage), verticalAlignment = Alignment.CenterVertically) {
            Text(stageLabel, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Icon(Icons.Filled.KeyboardArrowDown, "단계·프로필 설정", tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAlarm) {
            Icon(Icons.Outlined.Notifications, "알림", tint = OnRoadColors.TextPrimary)
        }
        IconButton(onClick = onProfile) {
            Icon(Icons.Filled.AccountCircle, "계정·설정", tint = OnRoadColors.TextPrimary)
        }
    }
}

/**
 * 이번 주 할 일: 놓친 일 + 7일 안 (최대 3개). 없으면 가장 가까운 다음 할 일 하나.
 * 첫 번째는 크게 (바로 하기 버튼 · 신청 진행 막대), 나머지는 한 줄. 동그라미를 누르면 체크되며 목록에서 빠진다.
 */
@Composable
private fun WeekTasks(goal: Boolean, leaveDate: LocalDate?, checklists: List<ChecklistSummary>, onNavigate: (String) -> Unit) {
    val today = remember { LocalDate.now() }
    if (goal) {
        GoalWeek(today, onNavigate)
        return
    }
    if (leaveDate == null) {
        EmptyState("퇴소일을 정하면 할 일을 날짜별로 알려 드려요.", actionLabel = "날짜 정하기", onAction = { onNavigate(Routes.SIM_TIMELINE) })
        return
    }
    val week = PlanData.thisWeek(today)
    val shown = week.take(3).ifEmpty { listOfNotNull(PlanData.next(today)) }
    if (shown.isEmpty()) {
        EmptyState("남은 할 일을 모두 챙겼어요.", actionLabel = "타임라인 보기", onAction = { onNavigate(Routes.SIM_TIMELINE) })
        return
    }
    Column(
        Modifier.fillMaxWidth().animateContentSize(OnRoadMotion.move()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEachIndexed { i, t ->
            key(t.id) {
                if (i == 0) HeroTask(t, today, checklists.firstOrNull { "cl-${it.policyId}" == t.id }, onNavigate)
                else TaskLine(t, today, onNavigate)
            }
        }
    }
}

/** 첫 번째 할 일: 흰 카드 · 제목 · 한 줄 설명 · (신청이면 진행 막대) · 바로 하기 */
@Composable
private fun HeroTask(t: PlanTask, today: LocalDate, checklist: ChecklistSummary?, onNavigate: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    OnRoadCard(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        onClick = { onNavigate(t.route()) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag(t.whenText(today), style = TagStyle.Neutral)
            Spacer(Modifier.weight(1f))
            // 정책 할 일은 체크리스트에서 끝낸다
            if (t.kind != PlanKind.POLICY) {
                Text(
                    "끝냈어요",
                    style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier
                        .clip(OnRoadShapes.Chip)
                        .tap {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            PlanData.toggleDone(t.id)
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(t.title, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(t.description, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // 진행 중 신청이면 진행 막대 + 지금 단계 (포인트 색은 여기에만)
        if (checklist != null && checklist.totalSteps > 0) {
            Spacer(Modifier.height(14.dp))
            ProgressBar(checklist.progress, color = OnRoadColors.Point, track = OnRoadColors.SurfaceMuted, height = 6.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                "${checklist.doneSteps}/${checklist.totalSteps}단계 · 지금 ${checklist.nextStep?.title ?: "모두 완료"}",
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
        OnRoadButton(
            text = t.actions.firstOrNull()?.label ?: "타임라인에서 보기",
            onClick = { onNavigate(t.route()) },
            modifier = Modifier.fillMaxWidth(),
            height = 44.dp,
            textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
        )
    }
}

/** 나머지 할 일 한 줄: 동그라미(끝내기) · 날짜 · 제목 · › */
@Composable
private fun TaskLine(t: PlanTask, today: LocalDate, onNavigate: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    // 누르면 체크가 톡 찍히는 걸 잠깐 보여 준 뒤 목록에서 빠진다
    var checking by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(checking) {
        if (checking) {
            kotlinx.coroutines.delay(380)
            PlanData.toggleDone(t.id)
        }
    }
    SubtleCard(Modifier.fillMaxWidth().heightIn(min = 64.dp), onClick = { onNavigate(t.route()) }, contentPadding = PaddingValues(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.kind != PlanKind.POLICY) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .tap(enabled = !checking, onClickLabel = "끝냈어요") {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            checking = true
                        },
                    contentAlignment = Alignment.Center,
                ) { RadioMark(checking) }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t.whenText(today), style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(t.title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
        }
    }
}

/** 퇴소 후 목표 D-day 를 쓰는 사람: 내가 정한 목표 할 일 */
@Composable
private fun GoalWeek(today: LocalDate, onNavigate: (String) -> Unit) {
    val s = AppContainer.session
    val open = s.goalTasks.filter { !it.done && !it.date.isBefore(today) }.sortedBy { it.date }
    val shown = open.filter { !it.date.isAfter(today.plusDays(7)) }.take(3).ifEmpty { open.take(1) }
    if (shown.isEmpty()) {
        EmptyState("목표까지 할 일을 정해 두면 여기에서 챙겨 드려요.", actionLabel = "할 일 정하기", onAction = { onNavigate(Routes.SIM_TIMELINE) })
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { g ->
            ContinueRow(s.goalTitle?.takeIf { it.isNotBlank() } ?: "목표 할 일", g.title, dLabel(ChronoUnit.DAYS.between(today, g.date))) {
                onNavigate(Routes.SIM_TIMELINE)
            }
        }
    }
}

/** 바로가기 한 칸 */
private class Shortcut(
    val icon: ImageVector,
    val label: String,
    val subtitle: String,
    /** 묶은 바로가기: 누르면 고르는 시트 (예: 정책 찾기 → AI 상담 / 정책 검색) */
    val choices: List<Choice> = emptyList(),
    val onClick: () -> Unit = {},
)

/** 묶은 바로가기 안의 선택지 */
private class Choice(val title: String, val subtitle: String, val onClick: () -> Unit)

/** 바로가기: 3칸 × 2줄. 비슷한 기능은 한 칸에 묶고 누르면 고른다 */
@Composable
private fun ShortcutGrid(items: List<Shortcut>) {
    var open by remember { mutableStateOf<Shortcut?>(null) }
    // 전체 테두리 없이, 칸마다 다음 일정과 같은 연회색 카드
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    ShortcutCell(item, Modifier.weight(1f)) {
                        if (item.choices.isEmpty()) item.onClick() else open = item
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    open?.let { group -> ChoiceSheet(group, onDismiss = { open = null }) }
}

/** 묶은 바로가기를 누르면 나오는 시트 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(group: Shortcut, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color.White, tonalElevation = 0.dp) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(group.label, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.choices.forEach { c ->
                    SubtleCard(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        onClick = { onDismiss(); c.onClick() },
                        contentPadding = PaddingValues(15.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                                Text(c.subtitle, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShortcutCell(item: Shortcut, modifier: Modifier, onClick: () -> Unit) {
    // 연회색 카드 + 왼쪽 위 아이콘 상자 → 이름 → 한 줄 설명
    SubtleCard(
        modifier.heightIn(min = 104.dp),
        onClick = onClick,
        contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
    ) {
        IconTile(item.icon)
        Spacer(Modifier.height(14.dp))
        Text(item.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(item.subtitle, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 한 줄 카드: 작은 이름 · 제목 · 오른쪽 값 (아이콘 없음) */
@Composable
private fun ContinueRow(label: String, title: String, trailing: String? = null, onClick: () -> Unit) {
    SubtleCard(Modifier.fillMaxWidth().heightIn(min = 64.dp), onClick = onClick, contentPadding = PaddingValues(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
        }
    }
}


@Composable
private fun KnowledgeCard(category: String, readTime: String, title: String, summary: String, onClick: () -> Unit) {
    OnRoadCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag(category, style = TagStyle.Neutral)
            Spacer(Modifier.weight(1f))
            Text(readTime, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(summary, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
    }
}

/** 홈 자립 준비도 한 줄: 점수 · 진행 막대(포인트 색) · 더 챙길 영역 */
@Composable
private fun ReadinessRow(r: com.project.on_road.data.Readiness, onClick: () -> Unit) {
    val weak = r.areas.firstOrNull { it.key == r.weakest }
    SubtleCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "자립 준비도",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (r.weekDelta > 0) {
                Text("이번 주 +${r.weekDelta} · ", style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
            }
            Text("${r.score}점", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(r.score / 100f, color = OnRoadColors.Point, track = OnRoadColors.SurfaceMuted, height = 6.dp)
        val next = r.nextLevel
        if (weak != null || next != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(
                    next?.let { (label, points) -> "‘$label’까지 ${points}점" },
                    weak?.let { "더 챙길 영역 · ${it.label}" },
                ).joinToString("  ·  "),
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextSecondary,
            )
        }
    }
}
