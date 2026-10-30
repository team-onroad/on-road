package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Readiness
import com.project.on_road.data.ReadinessArea
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.screens.navigation.stepRoute
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class ReadinessViewModel : ViewModel() {
    var readiness by mutableStateOf<Readiness?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        val id = AppContainer.session.userId ?: return
        viewModelScope.launch {
            runCatching { AppContainer.api.getReadiness(id) }
                .onSuccess { readiness = it; error = if (it == null) "자립 준비도를 계산하지 못했어요." else null }
                .onFailure { error = it.userMessage("자립 준비도를 불러오지 못했어요.") }
        }
    }
}

/** 자립 준비도: 6개 영역 점수 · 한 것 · 다음에 할 것 */
@Composable
fun ReadinessScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, vm: ReadinessViewModel = viewModel()) {
    // 다른 화면에서 연습하고 돌아오면 다시 계산
    LaunchedEffect(Unit) { vm.load() }
    val r = vm.readiness
    OnRoadScreen("자립 준비도", onBack) {
        if (r == null) {
            vm.error?.let { NoticeBox(it, title = "불러오지 못했어요", tone = NoticeTone.Warning) } ?: LoadingState("점수를 계산하고 있어요")
            return@OnRoadScreen
        }
        ScoreHeader(r)
        Spacer(Modifier.height(6.dp))
        Text(
            "앱에서 한 실전 연습 · 한 달 살아보기 · 생활비 계획 · 서류 · 신청 준비로 계산해요. 할수록 올라가요.",
            style = OnRoadType.Caption,
            color = OnRoadColors.TextTertiary,
        )

        if (r.nextSteps.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            NextStepList(r.nextSteps, { onNavigate(stepRoute(it)) }, title = "점수를 올리려면")
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("영역별", titleStyle = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.areas.forEach { a -> AreaCard(a, weakest = a.key == r.weakest) { s -> onNavigate(stepRoute(s)) } }
        }
        Spacer(Modifier.height(24.dp))
        Footnote("점수는 연습과 준비 정도를 보여 주는 참고용이에요.")
    }
}

@Composable
private fun ScoreHeader(r: Readiness) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${r.score}", style = OnRoadType.Display.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        Spacer(Modifier.width(4.dp))
        Text("점", style = OnRoadType.Headline, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun AreaCard(a: ReadinessArea, weakest: Boolean, onStep: (com.project.on_road.data.NextStep) -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = if (weakest) OnRoadColors.Primary else OnRoadColors.BorderChip,
        borderWidth = if (weakest) 1.5.dp else 1.dp,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.label, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            if (weakest) {
                Spacer(Modifier.width(8.dp))
                OnRoadTag("가장 약한 곳", style = TagStyle.Neutral, textStyle = OnRoadType.Tiny)
            }
            Spacer(Modifier.weight(1f))
            Text("${a.score}점", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        a.items.forEach { (label, done) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckBoxMark(done)
                Spacer(Modifier.width(10.dp))
                Text(label, style = OnRoadType.Caption, color = if (done) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary)
            }
        }
        a.nextStep?.let { s ->
            Spacer(Modifier.height(10.dp))
            NextStepRow(s) { onStep(s) }
        }
    }
}

/** 성장 기록 맨 위 카드 (흰 바탕): 점수 · 영역 6개 육각형 차트 · 가장 약한 곳 버튼 */
@Composable
fun ReadinessSummaryCard(r: Readiness, onOpen: () -> Unit, onStep: (com.project.on_road.data.NextStep) -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자립 준비도", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("자세히", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${r.score}", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
            Text("점", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        ReadinessRadar(r, Modifier.fillMaxWidth().height(250.dp))
        r.areas.firstOrNull { it.key == r.weakest }?.nextStep?.let { s ->
            Spacer(Modifier.height(12.dp))
            OnRoadButton(s.label, { onStep(s) }, Modifier.fillMaxWidth(), height = 44.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/**
 * 영역 6개 육각형(레이더) 차트.
 * 눈금: 안쪽 촘촘한 점선 → 바깥으로 갈수록 긴 점선 → 맨 바깥 실선 (색도 바깥 80% → 안쪽 연하게).
 * 내 점수: 포인트 색 (옅게 채움 + 선 + 꼭짓점 점).
 */
@Composable
private fun ReadinessRadar(r: Readiness, modifier: Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val ink = OnRoadColors.Primary
    val point = OnRoadColors.Point
    val labelStyle = OnRoadType.Caption2.copy(color = OnRoadColors.TextTertiary)
    val weakLabelStyle = OnRoadType.Caption2.copy(color = OnRoadColors.TextPrimary, fontWeight = FontWeight.Bold)
    val scoreStyle = OnRoadType.Caption2.copy(color = OnRoadColors.TextPrimary, fontWeight = FontWeight.Bold)
    // 처음 열 때 가운데서 바깥으로 퍼진다
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val t by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(700), label = "radar")

    androidx.compose.foundation.Canvas(modifier) {
        val n = r.areas.size.coerceAtLeast(3)
        val cx = size.width / 2
        val cy = size.height / 2
        val radius = minOf(size.width / 2 - 56.dp.toPx(), size.height / 2 - 26.dp.toPx())  // 이름 자리만 남기고 크게
        fun at(i: Int, ratio: Float): androidx.compose.ui.geometry.Offset {
            val a = Math.toRadians(-90.0 + 360.0 * i / n)
            return androidx.compose.ui.geometry.Offset(cx + (radius * ratio * Math.cos(a)).toFloat(), cy + (radius * ratio * Math.sin(a)).toFloat())
        }
        fun polygon(ratio: (Int) -> Float) = androidx.compose.ui.graphics.Path().apply {
            for (i in 0 until n) {
                val p = at(i, ratio(i))
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        val line = 1.dp.toPx()
        // 축 (가장 연하게)
        for (i in 0 until n) drawLine(ink.copy(alpha = 0.12f), at(i, 0f), at(i, 1f), line)
        // 눈금 4겹
        listOf(
            Triple(0.25f, 0.25f, floatArrayOf(1.dp.toPx(), 2.5.dp.toPx())),
            Triple(0.5f, 0.4f, floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            Triple(0.75f, 0.55f, floatArrayOf(7.dp.toPx(), 3.dp.toPx())),
            Triple(1f, 0.8f, null),
        ).forEach { (ratio, alpha, dash) ->
            drawPath(
                polygon { ratio }, ink.copy(alpha = alpha),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    line,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    pathEffect = dash?.let { androidx.compose.ui.graphics.PathEffect.dashPathEffect(it) },
                ),
            )
        }
        // 내 점수 (점수 그대로)
        fun ratioOf(i: Int) = (r.areas.getOrNull(i)?.score ?: 0) / 100f * t
        val shape = polygon(::ratioOf)
        drawPath(shape, point.copy(alpha = 0.22f))
        drawPath(shape, point, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        for (i in 0 until n) {
            val p = at(i, ratioOf(i))
            drawCircle(point, 3.5.dp.toPx(), p)
        }
        // 이름 · 점수: 한 줄 ("주거 75"), 꼭짓점 바로 바깥
        r.areas.forEachIndexed { i, a ->
            val weak = a.key == r.weakest
            val name = measurer.measure(a.label, if (weak) weakLabelStyle else labelStyle)
            val score = measurer.measure(" ${a.score}", scoreStyle)
            val w = (name.size.width + score.size.width).toFloat()
            val h = maxOf(name.size.height, score.size.height).toFloat()
            val p = at(i, 1f)
            val dx = p.x - cx
            val dy = p.y - cy
            val gap = 8.dp.toPx()
            val x = when {
                dx > 1f -> p.x + gap
                dx < -1f -> p.x - gap - w
                else -> p.x - w / 2
            }
            val y = when {
                kotlin.math.abs(dx) < 1f && dy < 0 -> p.y - gap - h
                kotlin.math.abs(dx) < 1f && dy > 0 -> p.y + gap
                else -> p.y - h / 2
            }
            drawText(name, topLeft = androidx.compose.ui.geometry.Offset(x, y + (h - name.size.height) / 2))
            drawText(score, topLeft = androidx.compose.ui.geometry.Offset(x + name.size.width, y + (h - score.size.height) / 2))
        }
    }
}
