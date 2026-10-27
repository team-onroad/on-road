package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.navigation.Routes
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class GrowthViewModel : ViewModel() {
    var record by mutableStateOf<GrowthRecord?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            record = runCatching {
                AppContainer.api.getGrowth(AppContainer.session.userId.orEmpty())
            }.getOrDefault(GrowthRecord.EMPTY)
        }
    }
}

/** 같은 상황을 여러 번 연습한 기록 묶음 (최근 순) */
private data class PracticeGroup(val scenarioId: String, val title: String, val attempts: List<PracticeAttempt>) {
    val latest get() = attempts.first()
    val previous get() = attempts.getOrNull(1)
}

private val Tabs = listOf("신청", "생활비", "연습", "상담", "진로")

/**
 * 성장 기록.
 * 서버 기록(저장 정책·체크리스트·생활비·저장한 연습) + 휴대폰 안 기록(상담 대화·관심사·연습 비교·진로·아동 때 기록)을 한곳에서 본다.
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
    // 청소년은 '진로' 탭부터 (신청·생활비 탭은 청년 위주)
    var tab by rememberSaveable { mutableStateOf(if (AppState.stage == UserStage.TEEN) 4 else 0) }
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

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar("성장 기록", onBack)
        if (record == null) {
            LoadingState("기록을 불러오고 있어요")
            return@Column
        }
        val practiceCount = if (practiceGroups.isNotEmpty()) practiceGroups.sumOf { it.attempts.size } else record.roleplays.size

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ScreenHeadline("지금까지 쌓아 온 기록이에요.", "작은 걸음이 모여 길이 됐어요.")
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("저장 정책", record.savedPolicies.size, Modifier.weight(1f))
                    Stat("완료 신청", record.completedChecklists.size, Modifier.weight(1f))
                    Stat("상담", chats.size, Modifier.weight(1f))
                    Stat("연습", practiceCount, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                InterestCard(topics)
                Spacer(Modifier.height(OnRoadDimens.BlockGap))
                SegmentedTabs(Tabs, tab, { tab = it })
                Spacer(Modifier.height(16.dp))
            }

            when (tab) {
                // ───── 신청 ─────
                0 -> {
                    AppContainer.session.leaveDate?.let {
                        item {
                            val s = AppContainer.session
                            if (DDate.isGoal(s.status)) {
                                // 퇴소 후: 내가 정한 목표와 일정
                                val tasks = s.goalTasks
                                ListRow(
                                    "${s.goalTitle?.takeIf { t -> t.isNotBlank() } ?: "목표"} D-day",
                                    "내 일정 ${tasks.size}개 중 ${tasks.count { t -> t.done }}개 완료",
                                    icon = Icons.Outlined.Event,
                                    onClick = { onNavigate(Routes.SIM_TIMELINE) },
                                )
                            } else {
                                ListRow(
                                    "퇴소 준비 타임라인",
                                    "${TimelineRules.tasks.size}개 중 ${s.timelineDone.size}개 완료",
                                    icon = Icons.Outlined.Event,
                                    onClick = { onNavigate(Routes.SIM_TIMELINE) },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item { SubHeader("저장한 정책", trailingText = "${record.savedPolicies.size}개") }
                    if (record.savedPolicies.isEmpty()) {
                        item { EmptyState(Icons.Outlined.FavoriteBorder, "저장한 정책이 없어요", "정책 카드의 하트를 누르면 여기에 모여요.") }
                    } else {
                        items(record.savedPolicies, key = { "p" + it.id }) { p -> PolicyRow(p) { onOpenPolicy(p.id) } }
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                        SubHeader("완료한 신청", trailingText = "${record.completedChecklists.size}건")
                    }
                    if (record.completedChecklists.isEmpty()) {
                        item { EmptyState(Icons.Outlined.TaskAlt, "완료한 체크리스트가 없어요", "신청 준비 단계를 모두 마치면 여기에 기록돼요.") }
                    } else {
                        items(record.completedChecklists, key = { "c" + it.id }) { c ->
                            ListRow(
                                c.policyName,
                                "${c.totalSteps}단계 모두 완료" + if (c.policy.isActive) "" else " · 더 이상 운영되지 않는 정책",
                                icon = Icons.Outlined.TaskAlt,
                                trailing = { OnRoadTag("완료", style = TagStyle.PrimaryFilled) },
                                onClick = { onOpenChecklist(c.policyId) },
                            )
                        }
                    }
                }

                // ───── 생활비 ─────
                1 -> if (record.simulations.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Outlined.Calculate, "시뮬레이션 기록이 없어요", "생활비 배분 결과를 보면 여기에 쌓여요.",
                            actionLabel = "생활비 계산하기", onAction = { onNavigate(Routes.SIM_BUDGET) },
                        )
                    }
                } else {
                    // 서버는 최신순으로 준다: [0] 이번, [1] 지난번
                    val now = record.simulations[0]
                    val before = record.simulations.getOrNull(1)
                    if (before != null) {
                        item {
                            val diff = before.shortageCount - now.shortageCount
                            NoticeBox(
                                when {
                                    diff > 0 -> "지난 계획보다 부족한 항목이 ${diff}개 줄었어요. 남는 돈은 ${before.remaining}만원 → ${now.remaining}만원이에요."
                                    diff < 0 -> "지난 계획보다 부족한 항목이 ${-diff}개 늘었어요. 대안 배분이나 관련 정책을 살펴봐요."
                                    now.shortageCount == 0 -> "두 번 연속 모든 항목이 권장 금액을 넘었어요. 균형 잡힌 계획이에요."
                                    else -> "부족한 항목 수는 지난번과 같아요(${now.shortageCount}개). 한 항목만 바꿔 봐도 좋아요."
                                },
                                title = "지난번과 비교",
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    items(record.simulations, key = { it.id }) { s -> SimulationHistoryCard(s) { onOpenSimulation(s.id) } }
                }

                // ───── 연습 ─────
                2 -> when {
                    practiceGroups.isNotEmpty() -> items(practiceGroups, key = { it.scenarioId }) { g -> PracticeGroupRow(g) { onOpenRoleplay(g.scenarioId) } }
                    record.roleplays.isNotEmpty() -> items(record.roleplays, key = { it.id }) { r ->
                        ListRow(
                            r.title,
                            "${r.createdAt} · 잘 챙긴 것 ${r.goodCount}개 · 빠뜨린 것 ${r.missedCount}개",
                            icon = Icons.Outlined.QuestionAnswer,
                            onClick = { onOpenRoleplay(r.scenarioId) },
                        )
                    }
                    else -> item {
                        EmptyState(
                            Icons.Outlined.QuestionAnswer, "연습 기록이 없어요", "실전 연습을 끝내면 여기에 쌓이고, 같은 상황을 다시 하면 지난번과 비교해 드려요.",
                            actionLabel = "연습하러 가기", onAction = { onNavigate(Routes.ROLEPLAY) },
                        )
                    }
                }

                // ───── 상담 ─────
                3 -> if (chats.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Outlined.Forum, "상담 기록이 없어요", "AI 상담에서 질문하면 대화가 이 휴대폰에 남고, 이어서 물어볼 수 있어요.",
                            actionLabel = "상담 시작하기", onAction = { onNavigate(Routes.chat()) },
                        )
                    }
                } else {
                    item {
                        Text("누르면 이어서 상담할 수 있어요.", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                        Spacer(Modifier.height(4.dp))
                    }
                    items(chats, key = { it.id }) { c ->
                        ListRow(
                            c.title,
                            dayText(c.updatedAt) + if (c.topics.isNotEmpty()) " · " + c.topics.take(2).joinToString(", ") { it.label } else "",
                            icon = Icons.Outlined.Forum,
                            onClick = { onOpenChat(c.id) },
                        )
                    }
                }

                // ───── 진로 ─────
                else -> {
                    item {
                        CareerInterestCard(interests) { onNavigate(Routes.INTEREST) }
                        Spacer(Modifier.height(8.dp))
                    }
                    roadmap?.let { r ->
                        item {
                            RoadmapProgressCard(r)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (AppState.stage == UserStage.TEEN || basicsDone > 0) {
                        item {
                            ListRow(
                                "자립 기초 미션",
                                "${BasicMissions.all.size}개 중 ${basicsDone}개 완료",
                                icon = Icons.Outlined.School,
                                trailing = { if (basicsDone == BasicMissions.all.size) OnRoadTag("모두 완료", style = TagStyle.PrimaryFilled) },
                                onClick = { onNavigate(Routes.BASICS) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (kidLiked.isNotEmpty()) {
                        item { KidLikedCard(kidLiked) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier) {
    SubtleCard(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
        Text("$value", style = OnRoadType.Title2, color = OnRoadColors.Primary)
        Text(label, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, maxLines = 1)
    }
}

/** 나의 관심사: 최근 60일 상담·검색·저장·생활비 기록에서 많이 나온 분야 (막대만, 글은 짧게) */
@Composable
private fun InterestCard(topics: List<TopicStat>) {
    OnRoadCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("나의 관심사", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("최근 60일", style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
        }
        Spacer(Modifier.height(4.dp))
        if (topics.isEmpty()) {
            Text("상담하거나 정책을 찾아보면 여기에 보여요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@OnRoadCard
        }
        Text("다음 할 일 추천에 반영돼요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        val max = topics.first().count.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            topics.take(4).forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.topic.label,
                        style = OnRoadType.Caption.copy(fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal),
                        color = if (i == 0) OnRoadColors.Primary else OnRoadColors.TextSecondary,
                        modifier = Modifier.width(76.dp),
                        maxLines = 1,
                    )
                    ProgressBar(
                        t.count / max.toFloat(),
                        Modifier.weight(1f),
                        color = if (i == 0) OnRoadColors.Primary else OnRoadColors.BorderSage,
                        height = 8.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${t.count}", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, modifier = Modifier.width(20.dp))
                }
            }
        }
    }
}

/** 같은 상황 연습 묶음: 최근 결과 + 지난번 대비 */
@Composable
private fun PracticeGroupRow(g: PracticeGroup, onClick: () -> Unit) {
    val prev = g.previous
    val diff = prev?.let { it.missed - g.latest.missed }
    ListRow(
        g.title,
        "${g.attempts.size}번 연습 · 빠뜨린 것 ${g.latest.missed}개",
        icon = Icons.Outlined.QuestionAnswer,
        trailing = {
            when {
                diff == null -> OnRoadTag("첫 연습", style = TagStyle.Slate)
                diff > 0 -> OnRoadTag("빠뜨린 것 −$diff", style = TagStyle.PrimarySoft)
                diff < 0 -> OnRoadTag("빠뜨린 것 +${-diff}", style = TagStyle.Warning)
                else -> OnRoadTag("지난번과 같음", style = TagStyle.Slate)
            }
        },
        onClick = onClick,
    )
}

/** 흥미 탐색 결과와 변화 */
@Composable
private fun CareerInterestCard(list: List<InterestSnapshot>, onExplore: () -> Unit) {
    if (list.isEmpty()) {
        ListRow("흥미 탐색", "나에게 맞는 직업 찾기", icon = Icons.Outlined.Explore, onClick = onExplore)
        return
    }
    val now = list[0]
    val before = list.getOrNull(1)
    OnRoadCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), onClick = onExplore) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag("흥미 유형", style = TagStyle.PrimarySoft)
            Spacer(Modifier.weight(1f))
            Text(dayText(now.at), style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
        }
        Spacer(Modifier.height(10.dp))
        Text("${now.top.joinToString("·") { it.label }} 성향", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                before == null -> "다시 하면 지난번과 비교해 줘요"
                before.top.firstOrNull() == now.top.firstOrNull() -> "지난번에도 ${now.top.first().label}, 꾸준해요"
                else -> "지난번엔 ${before.top.joinToString("·") { it.label }}"
            },
            style = OnRoadType.Caption,
            color = OnRoadColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 아동 때 '좋아하는 거 찾기'에서 고른 것 (단계가 바뀌어도 이어지는 기록) */
@Composable
private fun KidLikedCard(liked: List<Pair<String, Int>>) {
    SubtleCard(Modifier.fillMaxWidth()) {
        Text("어릴 때 좋아했던 것", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            liked.take(4).joinToString(" · ") { it.first },
            style = OnRoadType.Caption,
            color = OnRoadColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 지난 생활비 계획 요약 (3.13). 누르면 전체 결과(3.14) */
@Composable
private fun SimulationHistoryCard(s: SimulationSummary, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderSlate,
        shape = OnRoadShapes.Inner,
        contentPadding = PaddingValues(15.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.createdAt, style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
                Text("한 달 ${s.income}만원 계획", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Text("남는 돈 ${s.remaining}만원", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
            }
            if (s.shortageCount == 0) OnRoadTag("균형", style = TagStyle.PrimarySoft)
            else OnRoadTag("부족 ${s.shortageCount}개", style = TagStyle.Danger)
        }
    }
}
