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
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.HorizontalDivider
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
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Checklist
import com.project.on_road.data.NextAction
import com.project.on_road.data.Policy
import com.project.on_road.data.RoleplayLevel
import com.project.on_road.data.RoleplayScenario
import com.project.on_road.data.TimelineRules
import com.project.on_road.data.TimelineTask
import com.project.on_road.data.UserProgress
import com.project.on_road.data.UserStage
import com.project.on_road.ui.components.IconTile
import com.project.on_road.ui.components.ListRow
import com.project.on_road.ui.components.LogoSlot
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.OnRoadSearchBar
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.SectionHeader
import com.project.on_road.ui.components.SubtleCard
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.navigation.Routes
import com.project.on_road.ui.navigation.actionRoute
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class DashboardViewModel : ViewModel() {
    private val userId get() = AppContainer.session.userId

    var progress by mutableStateOf(UserProgress.EMPTY)
        private set
    var action by mutableStateOf<NextAction?>(null)
        private set
    /** 지금 할 일에 연결된 정책·체크리스트 (있을 때만) */
    var policy by mutableStateOf<Policy?>(null)
        private set
    var checklist by mutableStateOf<Checklist?>(null)
        private set
    /** 실전 연습 대표 시나리오와 상대역의 첫 마디 */
    var practice by mutableStateOf<RoleplayScenario?>(null)
        private set
    var practiceLine by mutableStateOf<String?>(null)
        private set

    fun load() {
        val id = userId ?: return
        viewModelScope.launch {
            runCatching { AppContainer.api.getProgress(id) }.onSuccess { progress = it }
            val first = runCatching { AppContainer.api.getNextActions(id) }.getOrDefault(emptyList()).firstOrNull()
            action = first
            policy = first?.policyId?.let { pid -> runCatching { AppContainer.api.getPolicy(pid) }.getOrNull() }
            checklist = first?.policyId?.let { pid -> runCatching { AppContainer.api.getChecklist(id, pid) }.getOrNull() }
        }
        if (practice == null) {
            viewModelScope.launch {
                val list = runCatching { AppContainer.coachApi.roleplayScenarios(UserStage.YOUNG_ADULT) }.getOrDefault(emptyList())
                val pick = list.firstOrNull { it.level != RoleplayLevel.EASY } ?: list.firstOrNull()
                practice = pick
                practiceLine = pick?.let { s ->
                    runCatching { AppContainer.coachApi.roleplayOpening(s.id, UserStage.YOUNG_ADULT).text }.getOrNull()
                }
            }
        }
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

/**
 * 자립준비청년 홈 (디자인: 자립준비청년 - 홈).
 * 흰 바탕 + 얇은 테두리 카드, 메인 녹색은 주요 버튼·실전 연습 카드에만 쓴다. 캐릭터는 넣지 않는다.
 */
@Composable
fun DashboardScreen(
    onNavigate: (String) -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val session = AppContainer.session
    val leaveDate = session.leaveDate
    val upcoming = remember(leaveDate) { upcomingTask(leaveDate) }
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
            onAlarm = { onNavigate(Routes.NEXT_ACTIONS) },
            onProfile = { onNavigate(Routes.GROWTH) },
        )

        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(20.dp))
            if (leaveDate != null) {
                val days = ChronoUnit.DAYS.between(LocalDate.now(), leaveDate)
                OnRoadTag(
                    "자립까지 ${dLabel(days)}",
                    style = TagStyle.Neutral,
                    modifier = Modifier.clickable { onNavigate(Routes.SIM_TIMELINE) },
                )
                Spacer(Modifier.height(14.dp))
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

            // ── 지금 할 일 ──
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("지금 할 일", actionText = "전체보기", onAction = { onNavigate(Routes.NEXT_ACTIONS) })
            Spacer(Modifier.height(16.dp))
            TodoCard(
                action = vm.action,
                policy = vm.policy,
                checklist = vm.checklist,
                onStart = { a -> onNavigate(if (a != null) actionRoute(a) else Routes.policy()) },
            )
            Spacer(Modifier.height(12.dp))
            ScheduleRow(upcoming, hasDate = leaveDate != null) { onNavigate(Routes.SIM_TIMELINE) }

            // ── AI와 실전 준비하기 ──
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("AI와 실전 준비하기", actionText = "전체보기", onAction = { onNavigate(Routes.ROLEPLAY) })
            Spacer(Modifier.height(20.dp))
            vm.practice?.let { s ->
                PracticeCard(s, vm.practiceLine) { onNavigate(Routes.roleplay(s.id)) }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile(Icons.Outlined.NoteAlt, "서류 작성 도우미", "신청서 항목 쉽게 풀기", Modifier.weight(1f).fillMaxHeight()) {
                    onNavigate(Routes.FORMS)
                }
                QuickTile(Icons.Outlined.Explore, "맞춤 진로 탐색", "흥미로 직업 찾기", Modifier.weight(1f).fillMaxHeight()) {
                    onNavigate(Routes.INTEREST)
                }
                QuickTile(Icons.Outlined.TaskAlt, "이력서·자소서", "기록으로 초안 만들기", Modifier.weight(1f).fillMaxHeight()) {
                    onNavigate(Routes.DOCUMENT)
                }
            }

            // ── 더 둘러보기 ──
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("더 둘러보기")
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListRow("AI 상담", "진로, 자립, 고민까지 편하게 물어봐요") { onNavigate(Routes.chat()) }
                ListRow(
                    "신청 체크리스트",
                    if (vm.progress.checklistCount > 0) "준비 중 ${vm.progress.checklistCount}건 · 완료 ${vm.progress.completedChecklists}건"
                    else "단계별로 신청을 준비해요",
                ) { onNavigate(Routes.CHECKLISTS) }
                ListRow("생활 시뮬레이션", "생활비·주거·저축을 미리 계산해요") { onNavigate(Routes.SIMULATION) }
                ListRow("정책 비교", "저장한 정책을 나란히 비교해요") { onNavigate(Routes.compare()) }
                ListRow("성장 기록", "지금까지 해 온 것들") { onNavigate(Routes.GROWTH) }
            }

            // ── 필수 자립 지식 ──
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("필수 자립 지식")
            Spacer(Modifier.height(20.dp))
            KnowledgeCard(
                category = "주거 상식",
                readTime = "AI에게 물어보기",
                title = "전입신고와 확정일자, 왜 계약 당일 바로 해야 할까요?",
                summary = "소중한 보증금을 지키는 대항력과 우선변제권의 핵심 요건을 알기 쉽게 알려 드려요.",
            ) { onNavigate(Routes.chat("전입신고와 확정일자는 왜 계약 당일 바로 해야 해?")) }

            Spacer(Modifier.height(24.dp))
            Text(
                "금액·자격·신청 가능 여부는 담당 기관에서 최종 확인해 주세요.",
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextQuaternary,
            )
            Spacer(Modifier.height(40.dp))
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
            Icon(Icons.Outlined.Notifications, "다음 할 일", tint = OnRoadColors.TextPrimary)
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
    checklist: Checklist?,
    onStart: (NextAction?) -> Unit,
) {
    val button = when (action?.kind) {
        ActionKind.START_CHECKLIST -> "신청 준비 시작하기"
        ActionKind.OPEN_CHECKLIST -> "이어서 작성하기"
        ActionKind.RUN_SIMULATION -> "생활비 계산하기"
        ActionKind.SEARCH_POLICY, null -> "정책 찾기"
    }
    val title = action?.title ?: "받을 수 있는 지원,\n한 번에 찾아볼까요?"

    OnRoadCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (policy != null) {
                Text(policy.agency, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Text("${policy.checkedAt} 확인", style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary, maxLines = 1)
            } else {
                Text("오늘의 추천", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            Spacer(Modifier.weight(1f))
            if (policy != null) {
                Spacer(Modifier.width(8.dp))
                OnRoadTag(policy.category, style = TagStyle.Neutral)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        action?.description?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Border.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checklist != null && checklist.steps.isNotEmpty()) {
                val current = checklist.steps.firstOrNull { !it.done }?.type?.title ?: "모두 완료"
                Text("${checklist.doneCount}/${checklist.steps.size}", style = OnRoadType.Title2, color = OnRoadColors.TextSecondary)
                Spacer(Modifier.width(4.dp))
                Text("($current)", style = OnRoadType.Micro, color = OnRoadColors.TextQuaternary, maxLines = 1)
            } else if (policy != null) {
                Text(policy.amount, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OnRoadButton(
                text = button,
                onClick = { onStart(action) },
                height = OnRoadDimens.ButtonHeightSmall,
                textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
            )
        }
    }
}

@Composable
private fun ScheduleRow(upcoming: UpcomingTask?, hasDate: Boolean, onClick: () -> Unit) {
    SubtleCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.Event)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("다음 일정", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("· 퇴소 D-day 타임라인", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        upcoming != null -> upcoming.task.title
                        hasDate -> "남은 일정을 모두 챙겼어요"
                        else -> "퇴소일을 정하면 할 일을 날짜별로 알려 드려요"
                    },
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (upcoming != null) {
                Text(dLabel(upcoming.daysLeft), style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PracticeCard(s: RoleplayScenario, line: String?, onStart: () -> Unit) {
    OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary) {
        Row(verticalAlignment = Alignment.Top) {
            Text("${s.title} 실전 연습", style = OnRoadType.Title2, color = OnRoadColors.Primary, modifier = Modifier.weight(1f))
            Text("난이도 ${s.level.label}", style = OnRoadType.Tiny, color = OnRoadColors.TextSlate, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("${s.setting} ${s.goal}까지 AI와 미리 연습해요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary.copy(alpha = 0.85f))
        if (line != null) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Inner)
                    .background(Color.White.copy(alpha = 0.9f))
                    .border(1.dp, OnRoadColors.PrimaryOutlineSoft, OnRoadShapes.Inner)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(OnRoadColors.Primary))
                    Spacer(Modifier.width(6.dp))
                    Text("AI ${s.partner}", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(OnRoadColors.Border))
                    Spacer(Modifier.width(10.dp))
                    Text("“$line”", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OnRoadButton(
            text = "시작하기",
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            height = 40.dp,
            textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun QuickTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    SubtleCard(modifier, onClick = onClick, contentPadding = PaddingValues(start = 15.dp, end = 8.dp, top = 15.dp, bottom = 14.dp)) {
        IconTile(icon)
        Spacer(Modifier.height(16.dp))
        Text(title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KnowledgeCard(category: String, readTime: String, title: String, summary: String, onClick: () -> Unit) {
    OnRoadCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag(category, style = TagStyle.PrimarySoft, textStyle = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.weight(1f))
            Text(readTime, style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(summary, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
    }
}
