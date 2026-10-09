package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class GrowthViewModel : ViewModel() {
    var record by mutableStateOf<GrowthRecord?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            record = runCatching {
                AppContainer.api.getGrowth(AppContainer.session.userId.orEmpty())
            }.getOrDefault(GrowthRecord(emptyList(), emptyList(), emptyList()))
        }
    }
}

/** 성장 기록 */
@Composable
fun GrowthScreen(
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    onOpenChecklist: (String) -> Unit,
    vm: GrowthViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    var tab by rememberSaveable { mutableStateOf(0) }
    val record = vm.record

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar("성장 기록", onBack)
        if (record == null) {
            LoadingState("기록을 불러오고 있어요")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ScreenHeadline("지금까지 쌓아 온 기록이에요.", "작은 걸음이 모여 길이 됐어요.")
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("저장한 정책", record.savedPolicies.size, Modifier.weight(1f))
                    Stat("완료한 신청", record.completedChecklists.size, Modifier.weight(1f))
                    Stat("생활비 계획", record.simulations.size, Modifier.weight(1f))
                }
                Spacer(Modifier.height(OnRoadDimens.BlockGap))
                SegmentedTabs(listOf("저장 정책", "완료", "생활비"), tab, { tab = it })
                Spacer(Modifier.height(16.dp))
            }

            when (tab) {
                0 -> if (record.savedPolicies.isEmpty()) {
                    item { EmptyState(Icons.Outlined.FavoriteBorder, "저장한 정책이 없어요", "정책 카드의 하트를 누르면 여기에 모여요.") }
                } else {
                    items(record.savedPolicies, key = { it.id }) { p -> PolicyRow(p) { onOpenPolicy(p.id) } }
                }

                1 -> if (record.completedChecklists.isEmpty()) {
                    item { EmptyState(Icons.Outlined.TaskAlt, "완료한 체크리스트가 없어요", "신청 준비 단계를 모두 마치면 여기에 기록돼요.") }
                } else {
                    items(record.completedChecklists, key = { it.id }) { c ->
                        ListRow(
                            c.policyName,
                            "${c.steps.size}단계 모두 완료",
                            icon = Icons.Outlined.TaskAlt,
                            trailing = { OnRoadTag("완료", style = TagStyle.PrimaryFilled) },
                            onClick = { onOpenChecklist(c.policyId) },
                        )
                    }
                }

                else -> if (record.simulations.isEmpty()) {
                    item { EmptyState(Icons.Outlined.Calculate, "시뮬레이션 기록이 없어요", "생활비 배분 결과를 보면 여기에 쌓여요.") }
                } else {
                    items(record.simulations, key = { it.id }) { s -> SimulationHistoryCard(s) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier) {
    SubtleCard(modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)) {
        Text("$value", style = OnRoadType.Title2, color = OnRoadColors.Primary)
        Text(label, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, maxLines = 1)
    }
}

@Composable
private fun SimulationHistoryCard(s: SimulationResult) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderSlate,
        shape = OnRoadShapes.Inner,
        contentPadding = PaddingValues(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.createdAt, style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
                Text("한 달 ${s.income}만원 계획", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            }
            if (s.shortages.isEmpty()) OnRoadTag("균형", style = TagStyle.PrimarySoft)
            else OnRoadTag("부족 ${s.shortages.size}개", style = TagStyle.Danger)
        }
        Spacer(Modifier.height(12.dp))
        StackedBar(BudgetCategory.entries.map { (s.allocation[it] ?: 0).toFloat() to it.color })
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BudgetCategory.entries.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(c.color, 6.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("${s.allocation[c] ?: 0}", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
                }
            }
        }
    }
}
