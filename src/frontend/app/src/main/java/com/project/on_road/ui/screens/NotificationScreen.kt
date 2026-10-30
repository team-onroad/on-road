package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.DDate
import com.project.on_road.data.TimelineRules
import com.project.on_road.ui.components.LoadingState
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.SectionHeader
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.screens.navigation.actionRoute
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 알림 한 줄: 태그(D-3, 진행 중 등) · 제목 · 설명 · 누르면 갈 화면 */
data class NoticeItem(val tag: String, val title: String, val body: String?, val route: String)

/** 알림 묶음 */
data class NoticeGroup(val title: String, val items: List<NoticeItem>)

/**
 * 알림 화면 (홈 상단 종 버튼). 서버에 따로 알림을 쌓지 않고, 지금 챙길 것을 모아 보여 준다.
 * - 일정: 퇴소(목표) D-day 타임라인에서 30일 안에 할 일, 지난 것 중 안 한 일
 * - 진행 중: 끝나지 않은 체크리스트 · 쓰던 서류
 * - 추천: 다음 할 일 상위 2개
 */
class NotificationViewModel : ViewModel() {
    var groups by mutableStateOf<List<NoticeGroup>?>(null)
        private set

    fun load() {
        val id = AppContainer.session.userId ?: run { groups = emptyList(); return }
        viewModelScope.launch {
            val docsJob = async { runCatching { AppContainer.documents.list() }.getOrDefault(emptyList()) }
            val dash = runCatching { AppContainer.api.getDashboard(id) }.getOrNull()
            val actions = runCatching { AppContainer.api.getNextActions(id, dash) }.getOrDefault(emptyList())
            val docs = docsJob.await()

            val schedule = scheduleItems()
            val doing = buildList {
                dash?.checklists.orEmpty().filter { !it.completed }.forEach { c ->
                    add(NoticeItem(
                        "진행 중", "${c.policyName} 체크리스트",
                        "${c.doneSteps}/${c.totalSteps}단계 · 지금 ${c.nextStep?.title ?: "모두 완료"}",
                        Routes.checklist(c.policyId),
                    ))
                }
                docs.filter { it.status != "exported" }.forEach { d ->
                    add(NoticeItem("쓰던 서류", d.title, "${d.progress.completed}/${d.progress.total}칸 작성", Routes.docWriter(documentId = d.documentId)))
                }
            }
            val recommend = actions.take(2).map { NoticeItem("추천", it.title, it.reason ?: it.description, actionRoute(it)) }

            groups = listOf(
                NoticeGroup("일정", schedule),
                NoticeGroup("진행 중", doing),
                NoticeGroup("추천", recommend),
            ).filter { it.items.isNotEmpty() }
        }
    }

    private fun scheduleItems(): List<NoticeItem> {
        val session = AppContainer.session
        val today = LocalDate.now()
        // 퇴소 후: 내가 정한 목표의 할 일
        if (DDate.isGoal(session.status)) {
            return session.goalTasks
                .filter { !it.done }
                .map { it to ChronoUnit.DAYS.between(today, it.date) }
                .filter { (_, d) -> d in -14..30 }
                .sortedBy { (_, d) -> d }
                .map { (t, d) -> NoticeItem(dTag(d), t.title, null, Routes.SIM_TIMELINE) }
        }
        val leave = session.leaveDate ?: return emptyList()
        val done = session.timelineDone
        return TimelineRules.tasks
            .filter { it.id !in done }
            .map { it to ChronoUnit.DAYS.between(today, leave.plusDays(it.offsetDays.toLong())) }
            .filter { (_, d) -> d in -14..30 }  // 2주 지난 것 ~ 30일 안
            .sortedBy { (_, d) -> d }
            .map { (t, d) -> NoticeItem(dTag(d), t.title, t.description, Routes.SIM_TIMELINE) }
    }

    private fun dTag(days: Long): String = when {
        days > 0 -> "D-$days"
        days == 0L -> "D-DAY"
        else -> "D+${-days} 지남"
    }
}

@Composable
fun NotificationScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, vm: NotificationViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    OnRoadScreen("알림", onBack) {
        val groups = vm.groups
        when {
            groups == null -> LoadingState("알림을 모으고 있어요")
            groups.isEmpty() -> {
                Spacer(Modifier.height(40.dp))
                Text("지금 챙길 알림이 없어요.", style = OnRoadType.Body2, color = OnRoadColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${DDate.untilText(AppContainer.session.status, AppContainer.session.goalTitle)} 일정이 다가오면 여기에 알려 드려요.",
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextTertiary,
                )
            }
            else -> groups.forEachIndexed { i, g ->
                if (i > 0) Spacer(Modifier.height(OnRoadDimens.BlockGap))
                SectionHeader(g.title)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    g.items.forEach { n -> NoticeRow(n) { onNavigate(n.route) } }
                }
            }
        }
    }
}

/** 알림 한 줄 (흰 카드 + 얇은 테두리, 태그는 홈 D-day 태그와 같은 모양) */
@Composable
private fun NoticeRow(n: NoticeItem, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        borderColor = OnRoadColors.BorderSlate,
        shape = OnRoadShapes.Inner,
        contentPadding = PaddingValues(15.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                OnRoadTag(n.tag, style = TagStyle.Neutral)
                Spacer(Modifier.height(8.dp))
                Text(n.title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.body?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
        }
    }
}
