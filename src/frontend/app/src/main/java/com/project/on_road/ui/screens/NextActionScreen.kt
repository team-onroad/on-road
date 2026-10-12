package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ActionKind
import com.project.on_road.data.AppContainer
import com.project.on_road.data.NextAction
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class NextActionViewModel : ViewModel() {
    var actions by mutableStateOf<List<NextAction>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    fun load() {
        viewModelScope.launch {
            loading = actions.isEmpty()
            actions = runCatching {
                AppContainer.api.getNextActions(AppContainer.session.userId.orEmpty())
            }.getOrDefault(emptyList())
            loading = false
        }
    }
}

private fun ActionKind.icon(): ImageVector = when (this) {
    ActionKind.OPEN_CHECKLIST -> Icons.Outlined.Checklist
    ActionKind.START_CHECKLIST -> Icons.Outlined.Flag
    ActionKind.RUN_SIMULATION -> Icons.Outlined.Calculate
    ActionKind.SEARCH_POLICY -> Icons.Outlined.Search
}

private fun ActionKind.button(): String = when (this) {
    ActionKind.START_CHECKLIST -> "신청 준비 시작하기"
    ActionKind.OPEN_CHECKLIST -> "이어서 작성하기"
    ActionKind.RUN_SIMULATION -> "생활비 계산하기"
    ActionKind.SEARCH_POLICY -> "정책 찾기"
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
        when {
            vm.loading -> LoadingState("할 일을 정리하고 있어요")
            vm.actions.isEmpty() -> EmptyState(
                icon = Icons.Outlined.TaskAlt,
                title = "지금은 추천할 일이 없어요",
                message = "진행 중인 일을 모두 잘 챙기고 있어요.",
            )
            else -> {
                ScreenHeadline("지금 상황에 맞춰", "먼저 하면 좋은 순서예요.")
                Spacer(Modifier.height(OnRoadDimens.BlockGap))

                val first = vm.actions.first()
                OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary) {
                    OnRoadTag("가장 먼저", style = TagStyle.PrimaryFilled)
                    Spacer(Modifier.height(12.dp))
                    Text(first.title, style = OnRoadType.Title2, color = OnRoadColors.Primary)
                    Spacer(Modifier.height(6.dp))
                    Text(first.description, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
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
                        vm.actions.drop(1).forEach { a ->
                            ListRow(a.title, a.description, icon = a.kind.icon()) { onAction(a) }
                        }
                    }
                }
            }
        }
    }
}
