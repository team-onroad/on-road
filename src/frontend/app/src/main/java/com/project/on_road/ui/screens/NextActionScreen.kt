package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ActionKind
import com.project.on_road.data.AppContainer
import com.project.on_road.data.NextAction
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class NextActionViewModel : ViewModel() {
    var actions by mutableStateOf(com.project.on_road.data.ScreenCache.get<List<NextAction>>("next-actions") ?: emptyList())
        private set
    var loading by mutableStateOf(actions.isEmpty())
        private set

    override fun onCleared() {
        com.project.on_road.data.ScreenCache.put("next-actions", actions.ifEmpty { null })
    }
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            loading = actions.isEmpty()
            runCatching { AppContainer.api.getNextActions(AppContainer.session.userId.orEmpty()) }
                .onSuccess { actions = it; error = null }
                .onFailure { if (actions.isEmpty()) error = it.userMessage("할 일을 불러오지 못했어요.") }
            loading = false
        }
    }
}

internal fun ActionKind.icon(): ImageVector = when (this) {
    ActionKind.OPEN_CHECKLIST -> Icons.Outlined.Checklist
    ActionKind.START_CHECKLIST -> Icons.Outlined.Flag
    ActionKind.RUN_SIMULATION -> Icons.Outlined.Calculate
    ActionKind.SEARCH_POLICY, ActionKind.SEARCH_TOPIC -> Icons.Outlined.Search
    ActionKind.OPEN_TIMELINE -> Icons.Outlined.Event
    ActionKind.PRACTICE -> Icons.Outlined.RecordVoiceOver
    ActionKind.EXPLORE_CAREER, ActionKind.ROADMAP -> Icons.Outlined.Explore
    ActionKind.BASIC_MISSION -> Icons.Outlined.School
    ActionKind.MONTH_LIFE -> Icons.Outlined.CalendarMonth
}

internal fun ActionKind.button(): String = when (this) {
    ActionKind.START_CHECKLIST -> "신청 준비 시작하기"
    ActionKind.OPEN_CHECKLIST -> "이어서 작성하기"
    ActionKind.RUN_SIMULATION -> "생활비 계산하기"
    ActionKind.SEARCH_POLICY, ActionKind.SEARCH_TOPIC -> "정책 찾기"
    ActionKind.OPEN_TIMELINE -> "타임라인 보기"
    ActionKind.PRACTICE -> "연습하러 가기"
    ActionKind.EXPLORE_CAREER -> "흥미 탐색하기"
    ActionKind.ROADMAP -> "로드맵 보기"
    ActionKind.BASIC_MISSION -> "미션 하러 가기"
    ActionKind.MONTH_LIFE -> "한 달 살아보기"
}

/** 추천 이유 한 줄 ("왜 추천했나요?") */
@Composable
internal fun ReasonLine(reason: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lightbulb, null, tint = OnRoadColors.Primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(reason, style = OnRoadType.Micro, color = OnRoadColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 다음 할 일 */
@Composable
fun NextActionScreen(
    onBack: () -> Unit,
    onAction: (NextAction) -> Unit,
    vm: NextActionViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    OnRoadScreen("다음 할 일", onBack) {
        FadeSwitch(vm.loading, label = "next-actions") { loading ->
            when {
                loading -> SkeletonList(rows = 4)
                vm.error != null -> ErrorState(vm.error, onRetry = vm::load)
                vm.actions.isEmpty() -> EmptyState("지금 챙길 일은 모두 끝냈어요. 새 일이 생기면 여기에 알려 드릴게요.")
                else -> {
                    Spacer(Modifier.height(8.dp))

                    val first = vm.actions.first()
                    OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary) {
                        OnRoadTag("가장 먼저", style = TagStyle.Neutral)
                        Spacer(Modifier.height(12.dp))
                        Text(first.title, style = OnRoadType.Title2, color = OnRoadColors.Primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        // 설명은 한 줄: 추천 이유가 있으면 이유, 없으면 설명
                        first.reason?.let { ReasonLine(it) }
                            ?: Text(first.description, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(16.dp))
                        OnRoadButton(
                            first.kind.button(), { onAction(first) }, Modifier.fillMaxWidth(),
                            height = 40.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                        )
                    }

                    if (vm.actions.size > 1) {
                        Spacer(Modifier.height(OnRoadDimens.SectionGap))
                        SectionHeader("그다음 할 일", trailingText = "${vm.actions.size - 1}개")
                        Spacer(Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.actions.drop(1).forEachIndexed { si, a -> Box(Modifier.stagger(si)) {
                                ListRow(a.title, a.reason ?: a.description, icon = a.kind.icon()) { onAction(a) }
                            } }
                        }
                    }
                }
            }
        }
    }
}
