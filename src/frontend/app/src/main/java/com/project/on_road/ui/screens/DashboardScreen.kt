package com.project.on_road.ui.screens

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

    /** 전체 체크리스트 수, 완료 수 (3.4 checklists) */
    var checklistCount by mutableStateOf(0)
        private set
    var completedCount by mutableStateOf(0)
        private set
    /** 자립까지 D-day (3.4 user.d_day). null 이면 숨김 */
    var dDay by mutableStateOf<Int?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var latestSimulation by mutableStateOf<LatestSimulation?>(null)
        private set
    var action by mutableStateOf<NextAction?>(null)
        private set
    /** 지금 할 일에 연결된 정책 (저장한 정책으로 시작할 때만) */
    var policy by mutableStateOf<Policy?>(null)
        private set
    /** 지금 할 일 카드의 진행 중 체크리스트 (3.4 checklists[0]) */
    var checklist by mutableStateOf<ChecklistSummary?>(null)
        private set
    /** 실전 연습 대표 시나리오와 상대역의 첫 마디 */
    var practice by mutableStateOf<RoleplayScenario?>(null)
        private set
    var practiceLine by mutableStateOf<String?>(null)
        private set

    fun load() {
        val id = userId ?: return
        viewModelScope.launch {
            val dash = runCatching { AppContainer.api.getDashboard(id) }.getOrNull()
            if (dash != null) {
                dash.user?.let { AppContainer.applyDashboardUser(it) }
                dDay = dash.user?.dDay
                loaded = true
                checklistCount = dash.checklists.size
                completedCount = dash.checklists.count { it.completed }
                latestSimulation = dash.latestSimulation
            }
            // 지금 할 일: 진행 중 체크리스트(checklists[0])가 있으면 그것, 없으면 추천 첫 번째
            val current = dash?.checklists?.firstOrNull()?.takeIf { !it.completed }
            checklist = current
            val first = runCatching { AppContainer.api.getNextActions(id, dash) }.getOrDefault(emptyList())
                .let { list -> if (current != null) list.firstOrNull { it.policyId == current.policyId } ?: list.firstOrNull() else list.firstOrNull() }
            action = first
            policy = if (current == null && first?.kind == ActionKind.START_CHECKLIST) {
                first.policyId?.let { pid -> runCatching { AppContainer.api.getPolicy(pid) }.getOrNull() }
            } else null
        }
        // (예전 홈의 실전 연습 카드용 시나리오·첫 대사 요청은 화면에서 안 써서 뺐다 → 홈 열 때 요청 2개 줄임)
    }
}

/** 타임라인에서 오늘 이후 아직 안 한 첫 할 일 */
private data class UpcomingTask(val task: TimelineTask, val daysLeft: Long)

private fun upcomingTask(leaveDate: LocalDate?): UpcomingTask? {
    leaveDate ?: return null
    val done = AppContainer.session.timelineDone
    val today = LocalDate.now()
    return TimelineRules.tasks
        .sortedBy { it.offsetDays }
        .map { it to ChronoUnit.DAYS.between(today, leaveDate.plusDays(it.offsetDays.toLong())) }
        .firstOrNull { (t, d) -> t.id !in done && d >= 0 }
        ?.let { (t, d) -> UpcomingTask(t, d) }
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
 * 순서: 인사·검색 → 지금 할 일 → 다음 일정(1개) → 바로가기 8칸 → 필수 자립 지식.
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
    val upcoming = remember(leaveDate, goal) { upcomingFor(goal, leaveDate) }
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
            onProfile = { onNavigate(Routes.GROWTH) },
        )

        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(12.dp))
            // 서버 d_day 를 쓰고, 아직 못 받았으면 저장된 퇴소일로 계산
            val days: Long? = if (vm.loaded) vm.dDay?.toLong() else leaveDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
            if (days != null) {
                OnRoadTag(
                    "${DDate.untilText(session.status, session.goalTitle)} ${dLabel(days)}",
                    style = TagStyle.Neutral,
                    modifier = Modifier.clickable(onClickLabel = "타임라인 보기") { onNavigate(Routes.SIM_TIMELINE) },
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

            // ── 1. 지금 할 일 ──
            Spacer(Modifier.height(HomeGap))
            SectionHeader("지금 할 일", actionText = "전체보기", onAction = { onNavigate(Routes.NEXT_ACTIONS) })
            Spacer(Modifier.height(12.dp))
            TodoCard(
                action = vm.action,
                policy = vm.policy,
                checklist = vm.checklist,
                onStart = { a -> onNavigate(if (a != null) actionRoute(a) else Routes.policy()) },
            )
            // ── 2. 다음 일정 (1개) ──
            Spacer(Modifier.height(8.dp))
            ScheduleRow(upcoming, if (goal) "목표 D-day" else "퇴소 D-day") { onNavigate(Routes.SIM_TIMELINE) }

            // ── 3. 바로가기: 모든 기능을 한 화면에 ──
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
                    Shortcut(Icons.Outlined.EditNote, "서류 작성", "AI와 같이 쓰기") { onNavigate(Routes.FORMS) },
                    Shortcut(
                        Icons.Outlined.RecordVoiceOver, "연습·계산", "미리 해 보기",
                        choices = listOf(
                            Choice("실전 연습", "은행·주민센터·면접을 AI와 연습해요") { onNavigate(Routes.ROLEPLAY) },
                            Choice("생활 계산", "생활비·주거·저축을 미리 계산해요") { onNavigate(Routes.SIMULATION) },
                        ),
                    ),
                    Shortcut(Icons.Outlined.Explore, "진로 탐색", "흥미로 직업 찾기") { onNavigate(Routes.INTEREST) },
                    Shortcut(Icons.Outlined.Insights, "내 기록", "지금까지 해 온 것") { onNavigate(Routes.GROWTH) },
                ),
            )

            // ── 4. 필수 자립 지식 ──
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
        Row(Modifier.clickable(onClick = onStage), verticalAlignment = Alignment.CenterVertically) {
            Text(stageLabel, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Icon(Icons.Filled.KeyboardArrowDown, "단계·프로필 설정", tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAlarm) {
            Icon(Icons.Outlined.Notifications, "알림", tint = OnRoadColors.TextPrimary)
        }
        IconButton(onClick = onProfile) {
            Icon(Icons.Filled.AccountCircle, "내 기록", tint = OnRoadColors.TextPrimary)
        }
    }
}

@Composable
private fun TodoCard(
    action: NextAction?,
    policy: Policy?,
    checklist: ChecklistSummary?,
    onStart: (NextAction?) -> Unit,
) {
    val category = checklist?.policy?.category ?: policy?.category
    val button = action?.kind?.button() ?: "정책 찾기"
    val title = action?.title ?: "받을 수 있는 지원, 한 번에 찾아볼까요?"

    OnRoadCard(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        onClick = { onStart(action) },
    ) {
        category?.let {
            OnRoadTag(it, style = TagStyle.Neutral)
            Spacer(Modifier.height(10.dp))
        }
        Text(title, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = action?.reason ?: action?.description
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(sub, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 진행 중 체크리스트면 진행 막대 + 지금 단계
        if (checklist != null && checklist.totalSteps > 0) {
            Spacer(Modifier.height(14.dp))
            val ratio = checklist.doneSteps / checklist.totalSteps.toFloat()
            Box(Modifier.fillMaxWidth().height(6.dp).clip(OnRoadShapes.Field).background(OnRoadColors.SurfaceMuted)) {
                Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight().background(OnRoadColors.Point))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${checklist.doneSteps}/${checklist.totalSteps}단계 · 지금 ${checklist.nextStep?.title ?: "모두 완료"}",
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextSecondary,
            )
        } else if (policy != null) {
            Spacer(Modifier.height(8.dp))
            Text(policy.amount, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        OnRoadButton(
            text = button,
            onClick = { onStart(action) },
            modifier = Modifier.fillMaxWidth(),
            height = 44.dp,
            textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
        )
    }
}

/** 다음 일정 한 줄에 보여 줄 것 */
private data class Upcoming(val title: String, val days: Long?)

private fun upcomingFor(goal: Boolean, leaveDate: LocalDate?): Upcoming {
    val today = LocalDate.now()
    if (goal) {
        val s = AppContainer.session
        val next = s.goalTasks.filter { !it.done && !it.date.isBefore(today) }.minByOrNull { it.date }
        return when {
            next != null -> Upcoming(next.title, ChronoUnit.DAYS.between(today, next.date))
            leaveDate != null -> Upcoming(s.goalTitle?.takeIf { it.isNotBlank() } ?: "목표일", ChronoUnit.DAYS.between(today, leaveDate))
            else -> Upcoming("목표일을 정하면 할 일을 날짜별로 챙겨요", null)
        }
    }
    val u = upcomingTask(leaveDate)
    return when {
        u != null -> Upcoming(u.task.title, u.daysLeft)
        leaveDate != null -> Upcoming("남은 일정을 모두 챙겼어요", null)
        else -> Upcoming("퇴소일을 정하면 할 일을 날짜별로 알려 드려요", null)
    }
}

@Composable
private fun ScheduleRow(upcoming: Upcoming, sub: String, onClick: () -> Unit) {
    ContinueRow("다음 일정 · $sub", upcoming.title, upcoming.days?.let { dLabel(it) }, onClick)
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
