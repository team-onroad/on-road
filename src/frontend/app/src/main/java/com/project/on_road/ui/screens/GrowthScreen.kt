package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.screens.navigation.stepRoute
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class GrowthViewModel : ViewModel() {
    var record by mutableStateOf<GrowthRecord?>(null)
        private set
    /** 자립 준비도 (서버). 못 받으면 카드를 숨긴다 */
    var readiness by mutableStateOf<Readiness?>(null)
        private set

    fun load() {
        val id = AppContainer.session.userId.orEmpty()
        viewModelScope.launch {
            record = runCatching { AppContainer.api.getGrowth(id) }.getOrDefault(GrowthRecord.EMPTY)
        }
        viewModelScope.launch { readiness = runCatching { AppContainer.api.getReadiness(id) }.getOrNull() }
    }
}

/** 같은 상황을 여러 번 연습한 기록 묶음 (최근 순) */
private data class PracticeGroup(val scenarioId: String, val title: String, val attempts: List<PracticeAttempt>) {
    val latest get() = attempts.first()
    val previous get() = attempts.getOrNull(1)
}

/** 한 묶음에서 처음 보여 줄 줄 수 (나머지는 '더 보기') */
private const val PREVIEW = 3

/**
 * 성장 기록 (흰 바탕).
 * 자립 준비도 → 한눈에 보는 숫자 → 관심사 → 신청 · 생활비 · 연습 · 상담 · 진로 (전환 버튼으로 하나씩).
 * 서버 기록(저장 정책·체크리스트·생활비·자립 준비도) + 휴대폰 안 기록(상담·연습·한 달 살아보기·진로)을 함께 본다.
 */
@Composable
fun GrowthScreen(
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    onOpenChecklist: (String) -> Unit,
    onOpenSimulation: (String) -> Unit = {},
    onOpenRoleplay: (String) -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    onNavigate: (String) -> Unit = {},
    vm: GrowthViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val record = vm.record

    // 휴대폰 안 기록 (바뀌면 다시 읽는다)
    val version = ActivityStore.version
    val topics = remember(version) { ActivityStore.topicStats() }
    val chats = remember(version) { ActivityStore.chats() }
    val practiceGroups = remember(version) {
        ActivityStore.practices().groupBy { it.scenarioId }
            .map { (id, list) -> PracticeGroup(id, list.first().title, list) }
            .sortedByDescending { it.latest.at }
    }
    val interests = remember(version) { ActivityStore.interests() }
    val roadmap = remember(version) { ActivityStore.roadmap() }
    val basicsDone = remember(version) { ActivityStore.basicsDone().size }
    val kidLiked = remember(version) { ActivityStore.likedCounts() }
    val months = remember(version) { ActivityStore.monthLives() }

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar("성장 기록", onBack)
        if (record == null) {
            LoadingState("기록을 불러오고 있어요")
            return@Column
        }
        val teen = AppState.stage == UserStage.TEEN
        // 청소년은 '진로'부터
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (teen) 4 else 0) }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            ScreenHeadline("지금까지 쌓아 온 기록이에요.", "작은 걸음이 모여 길이 됐어요.")

            // ── 자립 준비도 ──
            vm.readiness?.let { r ->
                Spacer(Modifier.height(OnRoadDimens.BlockGap))
                ReadinessSummaryCard(r, onOpen = { onNavigate(Routes.READINESS) }) { s -> onNavigate(stepRoute(s)) }
            }

            if (topics.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                InterestCard(topics)
            }

            // ── 묶음: 청소년은 진로부터 ──
            val sections: List<@Composable () -> Unit> = listOf(
                {
                    ApplySection(record, onOpenPolicy, onOpenChecklist, onNavigate)
                },
                {
                    MoneySection(record, months, onOpenSimulation, onNavigate)
                },
                {
                    Section("실전 연습") {
                        when {
                            practiceGroups.isNotEmpty() -> Expandable(practiceGroups) { g -> PracticeGroupRow(g) { onOpenRoleplay(g.scenarioId) } }
                            record.roleplays.isNotEmpty() -> Expandable(record.roleplays) { r ->
                                RecordRow(r.title, "${r.createdAt} · 빠뜨린 것 ${r.missedCount}개") { onOpenRoleplay(r.scenarioId) }
                            }
                            else -> EmptyLine("아직 연습한 상황이 없어요.", "연습하기") { onNavigate(Routes.ROLEPLAY) }
                        }
                    }
                },
                {
                    Section("상담") {
                        if (chats.isEmpty()) {
                            EmptyLine("아직 상담한 대화가 없어요.", "상담하기") { onNavigate(Routes.chat()) }
                        } else {
                            Expandable(chats) { c ->
                                RecordRow(
                                    c.title,
                                    dayText(c.updatedAt) + if (c.topics.isNotEmpty()) " · " + c.topics.take(2).joinToString(", ") { it.label } else "",
                                ) { onOpenChat(c.id) }
                            }
                        }
                    }
                },
                {
                    Section("진로") {
                        CareerInterestCard(interests) { onNavigate(Routes.INTEREST) }
                        roadmap?.let {
                            Spacer(Modifier.height(8.dp))
                            RoadmapProgressCard(it)
                        }
                        if (teen || basicsDone > 0) {
                            Spacer(Modifier.height(8.dp))
                            RecordRow(
                                "자립 기초 미션",
                                "${BasicMissions.all.size}개 중 ${basicsDone}개 완료",
                                tag = if (basicsDone == BasicMissions.all.size) "모두 완료" else null,
                            ) { onNavigate(Routes.BASICS) }
                        }
                        if (kidLiked.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            KidLikedCard(kidLiked)
                        }
                    }
                },
            )
            // ── 묶음 전환: 서류 작성의 '대화로 보기' 버튼처럼 (고른 것만 메인 색 · 흰 글씨) ──
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            ToggleTabs(GROWTH_TABS, tab) { tab = it }
            sections[tab]()

            Spacer(Modifier.height(40.dp))
        }
    }
}

/* ---------------------------- 묶음 ---------------------------- */

@Composable
private fun ApplySection(
    record: GrowthRecord,
    onOpenPolicy: (String) -> Unit,
    onOpenChecklist: (String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Section("신청 준비") {
        val s = AppContainer.session
        if (s.leaveDate != null) {
            if (DDate.isGoal(s.status)) {
                val tasks = s.goalTasks
                RecordRow(
                    "${s.goalTitle?.takeIf { it.isNotBlank() } ?: "목표"} D-day",
                    "내 일정 ${tasks.size}개 중 ${tasks.count { it.done }}개 완료",
                ) { onNavigate(Routes.SIM_TIMELINE) }
            } else {
                RecordRow("퇴소 준비 타임라인", "${TimelineRules.tasks.size}개 중 ${s.timelineDone.size}개 완료") {
                    onNavigate(Routes.SIM_TIMELINE)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        SubLabel("저장한 정책")
        if (record.savedPolicies.isEmpty()) {
            EmptyLine("정책 카드에서 저장하면 여기에 모여요.", "정책 찾기") { onNavigate(Routes.policy()) }
        } else {
            Expandable(record.savedPolicies) { p -> RecordRow(p.name, listOf(p.agency, p.amount).filter { it.isNotBlank() }.joinToString(" · ")) { onOpenPolicy(p.id) } }
        }
        Spacer(Modifier.height(16.dp))
        SubLabel("완료한 신청")
        if (record.completedChecklists.isEmpty()) {
            EmptyLine("신청 준비 단계를 모두 마치면 여기에 남아요.", "신청 준비 보기") { onNavigate(Routes.CHECKLISTS) }
        } else {
            Expandable(record.completedChecklists) { c ->
                RecordRow(
                    c.policyName,
                    "${c.totalSteps}단계 모두 완료" + if (c.policy.isActive) "" else " · 운영이 끝난 정책",
                    tag = "완료",
                ) { onOpenChecklist(c.policyId) }
            }
        }
    }
}

@Composable
private fun MoneySection(
    record: GrowthRecord,
    months: List<MonthLifeRecord>,
    onOpenSimulation: (String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Section("생활비") {
        if (months.isNotEmpty()) {
            MonthLifeRow(months) { onNavigate(Routes.SIM_MONTH) }
            Spacer(Modifier.height(16.dp))
        }
        SubLabel("생활비 계획")
        if (record.simulations.isEmpty()) {
            EmptyLine("수입을 나눠 보면 여기에 쌓여요.", "계산하기") { onNavigate(Routes.SIM_BUDGET) }
        } else {
            // 서버는 최신순: [0] 이번, [1] 지난번
            val now = record.simulations[0]
            record.simulations.getOrNull(1)?.let { before ->
                val diff = before.shortageCount - now.shortageCount
                Text(
                    when {
                        diff > 0 -> "지난 계획보다 부족한 곳이 ${diff}개 줄었어요"
                        diff < 0 -> "지난 계획보다 부족한 곳이 ${-diff}개 늘었어요"
                        now.shortageCount == 0 -> "두 번 연속 모든 항목이 권장 금액을 넘었어요"
                        else -> "부족한 곳 수는 지난번과 같아요"
                    },
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            Expandable(record.simulations) { s ->
                RecordRow(
                    "한 달 ${s.income}만원 계획",
                    "${s.createdAt} · 남는 돈 ${s.remaining}만원",
                    tag = if (s.shortageCount == 0) "균형" else "부족 ${s.shortageCount}개",
                ) { onOpenSimulation(s.id) }
            }
        }
    }
}

/* ---------------------------- 조각 ---------------------------- */

private val GROWTH_TABS = listOf("신청", "생활비", "연습", "상담", "진로")

/** 고른 탭 내용 (제목은 위 전환 버튼이 대신한다) */
@Composable
private fun Section(@Suppress("UNUSED_PARAMETER") title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    trailing?.let {
        Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
    }
    Column(content = content)
}

/** 전환 버튼 줄: 고른 것은 메인 색 바탕 · 흰 글씨, 나머지는 글씨만 (서류 작성 '대화로 보기'와 같은 모양) */
@Composable
private fun ToggleTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Text(
                t,
                style = OnRoadType.Caption.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                color = if (on) androidx.compose.ui.graphics.Color.White else OnRoadColors.TextSecondary,
                modifier = Modifier
                    .clip(OnRoadShapes.Chip)
                    .background(if (on) OnRoadColors.Primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SubLabel(text: String, trailing: String? = null) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary) }
    }
}

/** 처음엔 3줄만, 나머지는 '더 보기' */
@Composable
private fun <T> Expandable(items: List<T>, row: @Composable (T) -> Unit) {
    var open by remember(items.size) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (if (open) items else items.take(PREVIEW)).forEach { row(it) }
        if (items.size > PREVIEW) {
            Text(
                if (open) "접기" else "더 보기",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** 기록 한 줄: 흰 카드, 아이콘 없이 제목 · 설명 · (태그) */
@Composable
private fun RecordRow(title: String, sub: String, tag: String? = null, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(sub, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            tag?.let {
                Spacer(Modifier.width(10.dp))
                OnRoadTag(it, style = TagStyle.Neutral)
            }
        }
    }
}

/** 비었을 때: 큰 아이콘 대신 한 줄 안내 + 바로 하러 가는 버튼 */
@Composable
private fun EmptyLine(text: String, action: String, onAction: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            PrimaryPill(action, onAction)
        }
    }
}

/** 나의 관심사: 최근 60일 상담·검색·저장·연습에서 많이 나온 분야 */
@Composable
private fun InterestCard(topics: List<TopicStat>) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("나의 관심사", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("최근 60일 · 추천에 반영", style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
        }
        Spacer(Modifier.height(12.dp))
        val max = topics.first().count.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            topics.take(4).forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.topic.label,
                        style = OnRoadType.Caption.copy(fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal),
                        color = if (i == 0) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary,
                        modifier = Modifier.width(76.dp),
                        maxLines = 1,
                    )
                    ProgressBar(
                        t.count / max.toFloat(),
                        Modifier.weight(1f),
                        color = if (i == 0) OnRoadColors.Primary else OnRoadColors.BorderChip,
                        height = 6.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${t.count}", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, modifier = Modifier.width(20.dp))
                }
            }
        }
    }
}

/** 한 달 살아보기 기록: 최근 결과와 지난번 비교 */
@Composable
private fun MonthLifeRow(list: List<MonthLifeRecord>, onOpen: () -> Unit) {
    val now = list.first()
    val before = list.getOrNull(1)
    val result = when {
        now.shortage > 0 -> "${now.shortage}만원 모자람"
        now.usedEmergency > 0 -> "비상금 ${now.usedEmergency}만원 사용"
        else -> "${now.left}만원 남김"
    }
    val compare = before?.let { b ->
        val diff = (now.left - now.shortage) - (b.left - b.shortage)
        when {
            diff > 0 -> " · 지난번보다 ${diff}만원 더 남김"
            diff < 0 -> " · 지난번보다 ${-diff}만원 덜 남음"
            else -> ""
        }
    }.orEmpty()
    RecordRow("한 달 살아보기", "최근 $result$compare", onClick = onOpen)
}

@Composable
private fun PracticeGroupRow(g: PracticeGroup, onClick: () -> Unit) {
    val diff = g.previous?.let { it.missed - g.latest.missed }
    RecordRow(
        g.title,
        "빠뜨린 것 ${g.latest.missed}개",
        tag = when {
            diff == null -> "첫 연습"
            diff > 0 -> "빠뜨린 것 −$diff"
            diff < 0 -> "빠뜨린 것 +${-diff}"
            else -> "지난번과 같음"
        },
        onClick = onClick,
    )
}

/** 흥미 탐색 결과와 변화 */
@Composable
private fun CareerInterestCard(list: List<InterestSnapshot>, onExplore: () -> Unit) {
    if (list.isEmpty()) {
        EmptyLine("흥미 유형으로 나에게 맞는 직업을 찾아봐요.", "탐색하기", onExplore)
        return
    }
    val now = list[0]
    val before = list.getOrNull(1)
    RecordRow(
        "${now.top.joinToString("·") { it.label }} 성향",
        when {
            before == null -> "${dayText(now.at)} · 다시 하면 지난번과 비교해 줘요"
            before.top.firstOrNull() == now.top.firstOrNull() -> "${dayText(now.at)} · 지난번에도 ${now.top.first().label}"
            else -> "${dayText(now.at)} · 지난번엔 ${before.top.joinToString("·") { it.label }}"
        },
        tag = "흥미 유형",
        onClick = onExplore,
    )
}

/** 아동 때 '좋아하는 거 찾기'에서 고른 것 (단계가 바뀌어도 이어지는 기록) */
@Composable
private fun KidLikedCard(liked: List<Pair<String, Int>>) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("어릴 때 좋아했던 것", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(liked.take(4).joinToString(" · ") { it.first }, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
