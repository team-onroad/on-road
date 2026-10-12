package com.project.on_road.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class SimulationViewModel : ViewModel() {
    var incomeText by mutableStateOf("")
        private set
    val allocation = mutableStateMapOf<BudgetCategory, Int>().apply {
        BudgetCategory.entries.forEach { put(it, 0) }
    }
    var loading by mutableStateOf(false)
        private set
    var result by mutableStateOf<SimulationResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val income: Int get() = incomeText.toIntOrNull() ?: 0
    val total: Int get() = allocation.values.sum()
    val remaining: Int get() = income - total
    /** 명세: 합계 ≤ 총소득이면 제출 가능 (남는 금액은 여유금) */
    val canSubmit: Boolean get() = income > 0 && total > 0 && remaining >= 0

    fun updateIncome(text: String) {
        incomeText = text.filter(Char::isDigit).take(4).trimStart('0')
    }

    fun setIncomePreset(value: Int) {
        incomeText = value.toString()
    }

    fun setAmount(category: BudgetCategory, value: Int) {
        allocation[category] = value.coerceIn(0, max(income, 0))
    }

    fun autoFill() {
        if (income <= 0) return
        BudgetRules.allocate(income, BudgetRules.recommended).forEach { (k, v) -> allocation[k] = v }
    }

    fun applyScenario(scenario: Scenario) {
        // 대안은 남는 금액이 있을 수 있으니 수입은 그대로 두고, 합계가 더 클 때만 늘린다
        val keep = result?.income ?: income
        incomeText = max(keep, scenario.allocation.values.sum()).toString()
        scenario.allocation.forEach { (k, v) -> allocation[k] = v }
        result = null
    }

    fun submit() {
        if (!canSubmit || loading) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                result = AppContainer.api.simulate(AppContainer.session.userId.orEmpty(), income, allocation.toMap())
            } catch (e: com.project.on_road.data.remote.ApiException) {
                error = when (e.code) {
                    "SUM_EXCEEDS_INCOME" -> "나눈 금액의 합계가 수입보다 커요. 금액을 줄여 주세요."
                    "USER_NOT_FOUND" -> "사용자 정보를 찾지 못했어요. 앱을 다시 시작해 주세요."
                    else -> "계산하지 못했어요. 잠시 후 다시 시도해 주세요."
                }
            } catch (e: Exception) {
                error = "계산하지 못했어요. 잠시 후 다시 시도해 주세요."
            } finally {
                loading = false
            }
        }
    }

    fun backToInput() {
        result = null
    }
}

/** 생활비 배분 시뮬레이션 */
@Composable
fun BudgetSimulationScreen(
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    vm: SimulationViewModel = viewModel(),
) {
    val result = vm.result
    BackHandler(enabled = result != null) { vm.backToInput() }

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(
            title = if (result == null) "생활비 배분" else "생활비 배분 결과",
            onBack = { if (vm.result != null) vm.backToInput() else onBack() },
        )
        AnimatedContent(targetState = result, label = "simulation", modifier = Modifier.weight(1f)) { r ->
            if (r == null) SimulationInput(vm)
            else SimulationResultView(r, onApply = vm::applyScenario, onOpenPolicy = onOpenPolicy, onRetry = vm::backToInput)
        }
    }
}

@Composable
private fun SimulationInput(vm: SimulationViewModel) {
    val income = vm.income
    val remaining = vm.remaining
    val total = vm.total

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            ScreenHeadline("한 달 수입을 적고", "어디에 쓸지 나눠 보세요.")

            Spacer(Modifier.height(28.dp))
            FieldLabel("한 달 수입", required = true)
            Spacer(Modifier.height(8.dp))
            OnRoadTextField(
                value = vm.incomeText,
                onValueChange = vm::updateIncome,
                placeholder = "0",
                suffix = "만원",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(80, 120, 150, 200).forEach { preset ->
                    ChoiceChip("${preset}만원", income == preset) { vm.setIncomePreset(preset) }
                }
            }

            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            BalanceCard(income, vm.total, remaining, vm.allocation)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .align(Alignment.End)
                    .clickable(enabled = income > 0, onClick = vm::autoFill)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = OnRoadColors.Primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("권장 비율로 채우기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
            }

            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BudgetCategory.entries.forEach { c ->
                    CategorySlider(c, vm.allocation[c] ?: 0, income) { vm.setAmount(c, it) }
                }
            }

            if (remaining < 0) {
                Spacer(Modifier.height(12.dp))
                NoticeBox("슬라이더를 줄여서 합계를 수입과 맞춰 주세요.", title = "수입보다 ${-remaining}만원 더 나눴어요", tone = NoticeTone.Warning)
            }
            vm.error?.let {
                Spacer(Modifier.height(12.dp))
                NoticeBox(it, title = "계산하지 못했어요", tone = NoticeTone.Warning)
            }
            Spacer(Modifier.height(24.dp))
        }

        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
        Column(Modifier.background(Color.White).padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 12.dp)) {
            OnRoadButton(
                text = "결과 보기",
                onClick = vm::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.canSubmit,
                loading = vm.loading,
            )
            val hint = when {
                income <= 0 -> "한 달 수입을 먼저 입력해 주세요."
                total <= 0 -> "항목별로 금액을 나눠 주세요."
                remaining > 0 -> "남은 ${remaining}만원은 여유금으로 계산돼요."
                remaining < 0 -> "합계가 수입을 넘으면 결과를 볼 수 없어요."
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = OnRoadType.Caption2,
                    color = if (remaining < 0) OnRoadColors.Danger else OnRoadColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BalanceCard(income: Int, total: Int, remaining: Int, allocation: Map<BudgetCategory, Int>) {
    val (statusText, statusStyle) = when {
        income <= 0 -> "수입을 입력해 주세요" to TagStyle.Neutral
        remaining < 0 -> "${-remaining}만원 초과" to TagStyle.Danger
        remaining > 0 -> "${remaining}만원 여유" to TagStyle.Slate
        else -> "딱 맞아요" to TagStyle.PrimarySoft
    }
    val segments = BudgetCategory.entries.map { (allocation[it] ?: 0).toFloat() to it.color }

    OnRoadCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("나눈 금액", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$total", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
                    Text(" / ${income}만원", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            OnRoadTag(statusText, style = statusStyle)
        }
        Spacer(Modifier.height(14.dp))
        StackedBar(segments, height = 10.dp)
        Spacer(Modifier.height(12.dp))
        CategoryLegend()
    }
}

@Composable
private fun CategoryLegend() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BudgetCategory.entries.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(c.color, 7.dp)
                Spacer(Modifier.width(5.dp))
                Text(c.label, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun CategorySlider(c: BudgetCategory, value: Int, income: Int, onChange: (Int) -> Unit) {
    val maxValue = max(income, 1).toFloat()
    val short = income > 0 && value < c.minimum
    SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(c.icon, tint = c.color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Text(c.hint, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
            }
            Text("${value}만원", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        }
        OnRoadSlider(
            value = value.toFloat().coerceAtMost(maxValue),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..maxValue,
            color = c.color,
            enabled = income > 0,
        )
        Row {
            Text(
                "권장 최소 ${c.minimum}만원",
                style = OnRoadType.Tiny,
                color = if (short) OnRoadColors.Danger else OnRoadColors.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(if (income > 0) "${value * 100 / income}%" else "0%", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
        }
    }
}

@Composable
private fun SimulationResultView(
    result: SimulationResult,
    onApply: (Scenario) -> Unit,
    onOpenPolicy: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val shortageMap = result.shortages.associateBy { it.category }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(24.dp))
            OnRoadTag("한 달 수입 ${result.income}만원", style = TagStyle.Neutral)
            Spacer(Modifier.height(12.dp))
            if (result.shortages.isEmpty()) {
                ScreenHeadline("균형 잡힌", "계획이에요.")
            } else {
                ScreenHeadline("${result.shortages.size}개 항목이", "권장 금액보다 적어요.")
            }

            Spacer(Modifier.height(24.dp))
            OnRoadCard(Modifier.fillMaxWidth()) {
                StackedBar(BudgetCategory.entries.map { (result.allocation[it] ?: 0).toFloat() to it.color }, height = 12.dp)
                Spacer(Modifier.height(12.dp))
                CategoryLegend()
            }

            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("항목별 점검")
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BudgetCategory.entries.forEach { c -> ResultItemRow(c, result.allocation[c] ?: 0, shortageMap[c]) }
            }

            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("이렇게 바꿔 볼 수도 있어요")
            Spacer(Modifier.height(16.dp))
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = OnRoadDimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(result.scenarios) { s -> ScenarioCard(s) { onApply(s) } }
        }

        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            if (result.relatedPolicies.isNotEmpty()) {
                Spacer(Modifier.height(OnRoadDimens.SectionGap))
                SectionHeader("도움이 될 수 있는 정책")
                Spacer(Modifier.height(4.dp))
                Text(
                    if (result.shortages.isEmpty()) "여유를 더 만들고 싶다면 살펴보세요." else "부족한 항목과 관련 있는 제도예요.",
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextTertiary,
                )
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.relatedPolicies.forEach { p -> PolicyRow(p) { onOpenPolicy(p.id) } }
                }
            }
            Spacer(Modifier.height(32.dp))
            OnRoadButton("처음부터 다시 계산하기", onRetry, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            Spacer(Modifier.height(20.dp))
            Footnote("권장 금액은 1인 가구를 가정한 예시 기준이에요.")
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ResultItemRow(c: BudgetCategory, amount: Int, shortage: Shortage?) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = if (shortage != null) OnRoadColors.DangerTint else OnRoadColors.Surface,
        borderColor = if (shortage != null) OnRoadColors.Danger.copy(alpha = 0.35f) else OnRoadColors.BorderSlate,
        shape = OnRoadShapes.Inner,
        contentPadding = PaddingValues(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(c.icon, tint = c.color)
            Spacer(Modifier.width(12.dp))
            Text(c.label, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${amount}만원", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                if (shortage != null) {
                    Text("권장보다 ${shortage.recommended - amount}만원 부족", style = OnRoadType.Micro, color = OnRoadColors.Danger)
                } else {
                    Text("충분해요", style = OnRoadType.Micro, color = OnRoadColors.Primary)
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(scenario: Scenario, onApply: () -> Unit) {
    SubtleCard(Modifier.width(264.dp), contentPadding = PaddingValues(18.dp)) {
        Text(scenario.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(scenario.description, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, minLines = 2)
        Spacer(Modifier.height(14.dp))
        StackedBar(BudgetCategory.entries.map { (scenario.allocation[it] ?: 0).toFloat() to it.color })
        Spacer(Modifier.height(12.dp))
        BudgetCategory.entries.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                ColorDot(c.color, 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(c.label, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
                Text("${scenario.allocation[c] ?: 0}만원", style = OnRoadType.Micro, color = OnRoadColors.TextPrimary)
            }
        }
        Spacer(Modifier.height(14.dp))
        OnRoadButton(
            "이 배분으로 조정하기", onApply, Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline, height = 38.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** 관련 정책 한 줄 (시뮬레이션 화면들에서 공통 사용) */
@Composable
internal fun PolicyRow(p: Policy, onClick: () -> Unit) {
    ListRow(p.name, "${p.agency} · ${p.amount}", icon = categoryIcon(p.category), onClick = onClick)
}
