package com.project.on_road.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class SavingsViewModel : ViewModel() {
    var goal by mutableStateOf(500)
    var current by mutableStateOf(0)
    var own by mutableStateOf(20)
    var allowance by mutableStateOf(0)
    var didim by mutableStateOf(false)

    val plan: SavingsPlan get() = SavingsRules.plan(goal, current, own, allowance, didim)
}

/** 저축 목표 */
@Composable
fun SavingsScreen(
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    vm: SavingsViewModel = viewModel(),
) {
    val plan = vm.plan
    val months = plan.monthsToGoal

    OnRoadScreen("저축 목표", onBack) {
        OnRoadTag("목표 ${manwon(vm.goal)}", style = TagStyle.Neutral)
        Spacer(Modifier.height(12.dp))
        when {
            months == null -> ScreenHeadline("지금 속도로는", "10년 넘게 걸려요.")
            months == 0 -> ScreenHeadline("이미 목표에", "도달했어요.")
            else -> ScreenHeadline("${months}개월이면", "모을 수 있어요.")
        }
        if (months != null && months > 0) {
            Spacer(Modifier.height(6.dp))
            val date = LocalDate.now().plusMonths(months.toLong())
            Text(
                "${date.format(DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN))} 예상",
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
            )
        }

        Spacer(Modifier.height(24.dp))
        OnRoadCard(Modifier.fillMaxWidth()) {
            SavingsChart(plan.balances, vm.goal)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("매달 모이는 돈", manwon(plan.monthlyTotal))
                if (plan.matchMonthly > 0) Stat("정부 매칭", "+${manwon(plan.matchMonthly)}")
            }
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("목표와 저축 계획", trailingText = "단위: 만원")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("목표 금액", vm.goal, "만원", { vm.goal = it }, Modifier.weight(1f))
            NumberField("지금 모은 돈", vm.current, "만원", { vm.current = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(300, 500, 1000).forEach { g -> ChoiceChip(manwon(g), vm.goal == g) { vm.goal = g } }
        }

        Spacer(Modifier.height(20.dp))
        SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 8.dp)) {
            SliderRow("내 돈으로 매달", vm.own, 0..100) { vm.own = it }
            Spacer(Modifier.height(8.dp))
            SliderRow("자립수당 중 저축", vm.allowance, 0..50) { vm.allowance = it }
            Text("자립수당을 받고 있다면 일부를 떼어 모을 수 있어요.", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        OnRoadCard(
            Modifier.fillMaxWidth(),
            background = if (vm.didim) OnRoadColors.PrimarySoft else OnRoadColors.Surface,
            borderColor = if (vm.didim) OnRoadColors.Primary else OnRoadColors.BorderSlate,
            shape = OnRoadShapes.Inner,
            contentPadding = PaddingValues(start = 15.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            onClick = { vm.didim = !vm.didim },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("디딤씨앗통장 매칭 적용", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "내가 모은 돈의 2배를 정부가 함께 적립해요 (월 최대 ${SavingsRules.DIDIM_MATCH_CAP}만원, 예시 기준)",
                        style = OnRoadType.Tiny,
                        color = OnRoadColors.TextTertiary,
                    )
                }
                OnRoadSwitch(vm.didim, { vm.didim = it })
            }
        }

        if (months != null && months > 1) {
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("가는 길의 이정표")
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 75, 100).forEach { pct ->
                    val m = max(1, (months * pct / 100.0).roundToInt())
                    ListRow(
                        "$pct% 지점",
                        "${m}개월 뒤, 약 ${manwon(vm.goal * pct / 100)}",
                        trailing = { if (pct == 100) OnRoadTag("목표", style = TagStyle.PrimaryFilled) },
                    )
                }
            }
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("함께 보면 좋은 제도")
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("didim", "jarip-allowance").mapNotNull { SamplePolicies.find(it) }.forEach { p ->
                PolicyRow(p) { onOpenPolicy(p.id) }
            }
        }

        Spacer(Modifier.height(32.dp))
        Footnote("이자는 계산에 넣지 않았어요.\n매칭 조건과 한도는 실제 제도에서 확인해 주세요.")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        Text(label, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(manwon(value), style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
    }
    OnRoadSlider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

@Composable
private fun SavingsChart(balances: List<Int>, goal: Int) {
    val maxValue = max(balances.maxOrNull() ?: 0, goal).coerceAtLeast(1).toFloat()
    val lineColor = OnRoadColors.Primary
    val goalColor = OnRoadColors.ChartSand
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        val stepX = if (balances.size > 1) w / (balances.size - 1) else w
        fun y(v: Int) = h - (v / maxValue) * h

        val goalY = y(goal)
        drawLine(
            color = goalColor,
            start = Offset(0f, goalY),
            end = Offset(w, goalY),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )

        val line = Path()
        val area = Path()
        balances.forEachIndexed { i, v ->
            val x = i * stepX
            val yy = y(v)
            if (i == 0) {
                line.moveTo(x, yy)
                area.moveTo(x, h)
                area.lineTo(x, yy)
            } else {
                line.lineTo(x, yy)
                area.lineTo(x, yy)
            }
        }
        area.lineTo((balances.size - 1) * stepX, h)
        area.close()

        drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.12f), Color.Transparent)))
        drawPath(line, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lineColor, 5.dp.toPx(), Offset((balances.size - 1) * stepX, y(balances.last())))
    }
}
