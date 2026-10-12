package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*

class HousingViewModel : ViewModel() {
    var months by mutableStateOf(24)

    var monthlyDeposit by mutableStateOf(500)
    var monthlyRent by mutableStateOf(45)
    var monthlyMaint by mutableStateOf(7)

    var jeonseDeposit by mutableStateOf(8000)
    var jeonseOwn by mutableStateOf(800)
    var jeonseRate by mutableStateOf(2.0)
    var jeonseMaint by mutableStateOf(7)

    var publicDeposit by mutableStateOf(300)
    var publicRent by mutableStateOf(20)
    var publicMaint by mutableStateOf(5)

    val results: List<HousingCost>
        get() = listOf(
            HousingRules.monthlyRent(monthlyDeposit, monthlyRent, monthlyMaint, months),
            HousingRules.jeonse(jeonseDeposit, jeonseOwn, jeonseRate, jeonseMaint, months),
            HousingRules.publicRental(publicDeposit, publicRent, publicMaint, months),
        )
}

private fun HousingType.color(): Color = when (this) {
    HousingType.MONTHLY -> OnRoadColors.ChartClay
    HousingType.JEONSE -> OnRoadColors.ChartSky
    HousingType.PUBLIC -> OnRoadColors.Primary
}

private fun HousingType.icon(): ImageVector = when (this) {
    HousingType.MONTHLY -> Icons.Outlined.Home
    HousingType.JEONSE -> Icons.Outlined.Key
    HousingType.PUBLIC -> Icons.Outlined.Apartment
}

/** 주거비 비교 */
@Composable
fun HousingScreen(
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    vm: HousingViewModel = viewModel(),
) {
    val results = vm.results
    val cheapest = results.minBy { it.monthly }
    val maxMonthly = results.maxOf { it.monthly }.coerceAtLeast(1)

    OnRoadScreen("주거비 비교", onBack) {
        ScreenHeadline("월세, 전세, 공공임대", "매달 얼마씩 나갈까요?")

        Spacer(Modifier.height(28.dp))
        FieldLabel("살 기간")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(12, 24, 36).forEach { m -> ChoiceChip("${m}개월", vm.months == m) { vm.months = m } }
        }

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        OnRoadCard(Modifier.fillMaxWidth()) {
            Text("매달 나가는 돈", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Text("월세·관리비·대출 이자를 합친 금액이에요.", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(18.dp))
            results.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.type.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, modifier = Modifier.width(60.dp))
                    ProgressBar(r.monthly / maxMonthly.toFloat(), Modifier.weight(1f), color = r.type.color(), height = 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(manwon(r.monthly), style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.width(64.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnRoadTag("가장 적게 나가요", style = TagStyle.PrimarySoft)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${cheapest.type.label}, ${vm.months}개월 ${manwon(cheapest.total)}",
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextPrimary,
                )
            }
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("조건을 바꿔 보세요", trailingText = "단위: 만원")
        Spacer(Modifier.height(16.dp))

        HousingCard(results[0], vm.months, cheapest.type == HousingType.MONTHLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("보증금", vm.monthlyDeposit, "만원", { vm.monthlyDeposit = it }, Modifier.weight(1f))
                NumberField("월세", vm.monthlyRent, "만원", { vm.monthlyRent = it }, Modifier.weight(1f), 3)
            }
            Spacer(Modifier.height(12.dp))
            NumberField("관리비", vm.monthlyMaint, "만원", { vm.monthlyMaint = it }, Modifier.fillMaxWidth(), 3)
        }
        Spacer(Modifier.height(12.dp))

        HousingCard(results[1], vm.months, cheapest.type == HousingType.JEONSE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("전세 보증금", vm.jeonseDeposit, "만원", { vm.jeonseDeposit = it }, Modifier.weight(1f))
                NumberField("내 돈", vm.jeonseOwn, "만원", { vm.jeonseOwn = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            NumberField("관리비", vm.jeonseMaint, "만원", { vm.jeonseMaint = it }, Modifier.fillMaxWidth(), 3)
            Spacer(Modifier.height(12.dp))
            FieldLabel("전세대출 금리", hint = "(연)")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.5, 2.0, 3.0, 4.0).forEach { rate ->
                    ChoiceChip("$rate%", vm.jeonseRate == rate) { vm.jeonseRate = rate }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        HousingCard(results[2], vm.months, cheapest.type == HousingType.PUBLIC) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("보증금", vm.publicDeposit, "만원", { vm.publicDeposit = it }, Modifier.weight(1f))
                NumberField("임대료", vm.publicRent, "만원", { vm.publicRent = it }, Modifier.weight(1f), 3)
            }
            Spacer(Modifier.height(12.dp))
            NumberField("관리비", vm.publicMaint, "만원", { vm.publicMaint = it }, Modifier.fillMaxWidth(), 3)
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("주거 지원 제도")
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("lh-housing", "jeonse").mapNotNull { SamplePolicies.find(it) }.forEach { p ->
                PolicyRow(p) { onOpenPolicy(p.id) }
            }
        }

        Spacer(Modifier.height(32.dp))
        Footnote("보증금은 계약이 끝나면 돌려받는 돈이라 ‘쓰는 돈’에서 뺐어요.\n금액은 예시이며 실제 조건은 공고에서 확인해 주세요.")
    }
}

@Composable
private fun HousingCard(
    cost: HousingCost,
    months: Int,
    isCheapest: Boolean,
    inputs: @Composable ColumnScope.() -> Unit,
) {
    val type = cost.type
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = if (isCheapest) OnRoadColors.Primary else OnRoadColors.Border,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(type.icon(), tint = type.color())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(type.label, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Text(type.description, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
            }
            if (isCheapest) OnRoadTag("최저", style = TagStyle.PrimaryFilled)
        }
        Spacer(Modifier.height(18.dp))
        inputs()
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
        Spacer(Modifier.height(12.dp))
        ValueLine("처음 필요한 내 돈", manwon(cost.upfront))
        if (type == HousingType.JEONSE) ValueLine("대출 이자 (매달)", manwon(cost.interest))
        ValueLine("매달 나가는 돈", manwon(cost.monthly), emphasize = true)
        ValueLine("${months}개월 동안 쓰는 돈", manwon(cost.total))
    }
}
