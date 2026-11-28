package com.project.on_road.ui.screens


import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Readiness
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.screens.navigation.stepRoute
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class ReadinessViewModel : ViewModel() {
    var readiness by mutableStateOf(com.project.on_road.data.ScreenCache.get<Readiness>("readiness"))
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        val id = AppContainer.session.userId ?: return
        // 다시 시도할 때는 오류를 지우고 불러오는 중으로 돌아간다
        if (readiness == null) error = null
        viewModelScope.launch {
            runCatching { AppContainer.api.getReadiness(id) }
                .onSuccess {
                    if (it != null) { readiness = it; com.project.on_road.data.ScreenCache.put("readiness", it) }
                    error = if (readiness == null) "자립 준비도를 계산하지 못했어요." else null
                }
                .onFailure { if (readiness == null) error = it.userMessage("자립 준비도를 불러오지 못했어요.") }
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
            FadeSwitch(vm.error != null, label = "readiness") { failed ->
                if (failed) ErrorState(vm.error, onRetry = vm::load, title = "자립 준비도를 불러오지 못했어요")
                else LoadingState("점수를 계산하고 있어요", steps = listOf("한 일을 영역별로 모으고 있어요"))
            }
            return@OnRoadScreen
        }
        // 점수가 오면 살짝 올라오며 나타난다 (툭 바뀌지 않게)
        Column(Modifier.reveal()) {
        // ── 1. 점수 · 단계 · 이번 주 변화 · 다음 단계까지 ──
        ScoreHeader(r)

        // ── 2. 영역 6개 한눈에 (육각형) ──
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            ReadinessRadar(r, Modifier.fillMaxWidth().height(240.dp))
        }

        // ── 요즘 내 준비: 행동 기록으로 본 판단 (다시 볼 것 · 잘하는 것) ──
        if (r.watch.isNotEmpty() || r.good.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            RecentPrep(r.watch, r.good)
        }

        // ── 3. 이번 주에 해낸 것 ──
        if (r.recent.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Text("이번 주에 해낸 것", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                r.recent.forEach { label ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckBoxMark(true, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = OnRoadType.Caption, color = OnRoadColors.TextPrimary)
                    }
                }
            }
        }

        // ── 4. 다음에 해 보면 좋은 것 (약한 영역부터) ──
        if (r.nextSteps.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            NextStepList(r.nextSteps, { onNavigate(stepRoute(it)) }, title = "다음에 해 보면 좋은 것")
        }

        Spacer(Modifier.height(24.dp))
        Footnote("점수는 준비 정도를 보여 주는 참고용이에요.")
        }
    }
}

@Composable
private fun ScoreHeader(r: Readiness) {
    // 점수가 0 에서 올라가며 보인다
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val shownScore by androidx.compose.animation.core.animateIntAsState(
        if (shown) r.score else 0,
        androidx.compose.animation.core.tween(700, easing = com.project.on_road.ui.theme.OnRoadMotion.EmphasizedDecelerate),
        label = "readiness-score",
    )
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$shownScore", style = OnRoadType.Display.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        Spacer(Modifier.width(4.dp))
        Text("점", style = OnRoadType.Headline, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
        Spacer(Modifier.width(10.dp))
        OnRoadTag(r.level, style = TagStyle.Neutral, modifier = Modifier.padding(bottom = 6.dp))
        if (r.weekDelta != 0) {
            Spacer(Modifier.width(6.dp))
            OnRoadTag(
                if (r.weekDelta > 0) "이번 주 +${r.weekDelta}점" else "이번 주 ${r.weekDelta}점",
                style = TagStyle.Neutral,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
    r.nextLevel?.let { (label, points) ->
        Spacer(Modifier.height(10.dp))
        ProgressBar(r.score / 100f)
        Spacer(Modifier.height(6.dp))
        Text("‘$label’까지 ${points}점 남았어요", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
    } ?: run {
        Spacer(Modifier.height(6.dp))
        Text("모든 영역을 잘 챙기고 있어요", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
    }
}

/** 요즘 내 준비 크기 · 간격 */
private object PrepSpace {
    val LabelWidth = 72.dp  // 왼쪽 이름 칸 폭
    val LineGap = 16.dp     // 다시 볼 것 ↔ 잘하는 것
    val TextGap = 6.dp      // 같은 줄 안 문장 사이
}

/** 숫자 조건만 굵게 ("25점", "14일", "2번") */
private val PREP_NUMBER = Regex("""\d[\d,.]*\s*(만\s*)?(점|일|번|개|곳|원|개월|주)""")

private fun prepText(text: String) = androidx.compose.ui.text.buildAnnotatedString {
    var last = 0
    for (m in PREP_NUMBER.findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = OnRoadColors.TextPrimary)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** 요즘 내 준비: '다시 볼 것' · '잘하는 것' 두 줄. 왼쪽 굵은 이름, 오른쪽 문장들 */
@Composable
private fun RecentPrep(watch: List<String>, good: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        SubHeader("요즘 내 준비")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(PrepSpace.LineGap)) {
            if (watch.isNotEmpty()) PrepLine("다시 볼 것", watch, OnRoadColors.Warning)
            if (good.isNotEmpty()) PrepLine("잘하는 것", good, OnRoadColors.TextPrimary)
        }
    }
}

@Composable
private fun PrepLine(label: String, texts: List<String>, labelColor: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = labelColor,
             modifier = Modifier.width(PrepSpace.LabelWidth).padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PrepSpace.TextGap)) {
            texts.forEach { Text(prepText(it), style = OnRoadType.Body3, color = OnRoadColors.TextSecondary) }
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자립 준비도", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("자세히", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(r.score.toLong(), { "$it" }, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
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