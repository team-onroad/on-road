package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.SameKindGroup
import com.project.on_road.data.SameKindRow
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class SameKindViewModel : ViewModel() {
    var group by mutableStateOf<SameKindGroup?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    fun load(policyId: String) {
        val userId = AppContainer.session.userId ?: run { loading = false; return }
        viewModelScope.launch {
            group = AppContainer.api.getSameKind(userId, policyId)
            loading = false
        }
    }
}

/** 처음에 보여 주는 다른 정책 수 (나머지는 '나머지 n개 보기') */
private const val FIRST_ROWS = 3

/**
 * 같은 종류 정책 전체 (서버 same-kind): 내 조건에서 고른 것 → 같은 종류 n개(왜 하나만 받는지) → 다른 지역 것 금액 순.
 * 줄을 누르면 그 정책 상세로.
 */
@Composable
fun SameKindScreen(
    policyId: String,
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    vm: SameKindViewModel = viewModel(key = "same-kind-$policyId"),
) {
    LaunchedEffect(policyId) { vm.load(policyId) }
    val g = vm.group
    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(g?.kindTitle ?: "같은 종류 지원", onBack, divider = false)
        when {
            vm.loading -> Column(Modifier.padding(OnRoadDimens.ScreenPadding)) { SkeletonList(rows = 4) }
            g == null -> Column(Modifier.padding(OnRoadDimens.ScreenPadding)) { EmptyState("같은 종류 정책을 불러오지 못했어요.") }
            else -> SameKindBody(g, onOpenPolicy)
        }
    }
}

@Composable
private fun SameKindBody(g: SameKindGroup, onOpen: (String) -> Unit) {
    var all by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(8.dp))
        g.picked?.let { p ->
            InfoCard(Modifier.fillMaxWidth(), onClick = { onOpen(p.policyId) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(p.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                    p.amountText?.let {
                        Spacer(Modifier.width(12.dp))
                        Text(it, style = OnRoadType.Body1.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(p.reason, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            Spacer(Modifier.height(32.dp))
        }

        Text("같은 종류 ${g.total}개", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(g.rule, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(14.dp))

        g.others.take(FIRST_ROWS).forEach { r -> KindRow(r, onOpen) }
        val rest = g.others.drop(FIRST_ROWS)
        if (rest.isNotEmpty()) {
            ExpandArea(all) { rest.forEach { r -> KindRow(r, onOpen) } }
            Text(
                if (all) "접기" else "나머지 ${rest.size}개 보기",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp).clip(OnRoadShapes.Chip).tap(onClick = { all = !all }).padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
        TabBarSpacer()
    }
}

/** 한 줄: 지역 · 금액 (금액을 모르면 '금액 확인 필요'). 내 조건으로도 받을 수 있는 것은 진하게 */
@Composable
private fun KindRow(r: SameKindRow, onOpen: (String) -> Unit) {
    val color = if (r.mine) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary
    Row(
        Modifier.fillMaxWidth().clip(OnRoadShapes.Chip).tap(pressed = 1f, onClickLabel = "${r.name} 보기", onClick = { onOpen(r.policyId) }).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.region, style = OnRoadType.Body2, color = color, modifier = Modifier.weight(1f))
        Text(r.amountText ?: "금액 확인 필요", style = OnRoadType.Body2, color = color)
    }
}
