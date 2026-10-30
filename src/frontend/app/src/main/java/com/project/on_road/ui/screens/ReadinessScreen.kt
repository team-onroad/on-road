package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        Spacer(Modifier.width(10.dp))
        OnRoadTag(r.level, style = TagStyle.Neutral, modifier = Modifier.padding(bottom = 4.dp))
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

/** 성장 기록 맨 위 카드 (흰 바탕): 왼쪽 원형 차트(전체 점수) · 오른쪽 영역 6개 · 아래 가장 약한 곳 버튼 */
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
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreDonut(r.score, r.level, Modifier.size(124.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                r.areas.forEach { a ->
                    val weak = a.key == r.weakest
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 영역 점수를 작은 원 조각으로
                        AreaPie(a.score, Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            a.label,
                            style = OnRoadType.Caption.copy(fontWeight = if (weak) FontWeight.Bold else FontWeight.Normal),
                            color = if (weak) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${a.score}",
                            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                            color = if (weak) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary,
                        )
                    }
                }
            }
        }
        r.areas.firstOrNull { it.key == r.weakest }?.nextStep?.let { s ->
            Spacer(Modifier.height(18.dp))
            OnRoadButton(s.label, { onStep(s) }, Modifier.fillMaxWidth(), height = 44.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/** 전체 점수 원형 차트: 연회색 바퀴 위에 메인 색으로 점수만큼 */
@Composable
private fun ScoreDonut(score: Int, level: String, modifier: Modifier) {
    val track = OnRoadColors.SurfaceMuted
    val fill = OnRoadColors.Primary
    val sweep by androidx.compose.animation.core.animateFloatAsState(
        score.coerceIn(0, 100) * 3.6f,
        androidx.compose.animation.core.tween(700),
        label = "readiness-donut",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.11f
            val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = style)
            if (sweep > 0f) drawArc(fill, -90f, sweep, false, topLeft, arcSize, style = style)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$score", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
                Text("점", style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp, start = 1.dp))
            }
            Text(level, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
        }
    }
}

/** 영역 점수 작은 파이: 연회색 원 위에 메인 색 조각 */
@Composable
private fun AreaPie(score: Int, modifier: Modifier) {
    val track = OnRoadColors.SurfaceMuted
    val fill = OnRoadColors.Primary
    androidx.compose.foundation.Canvas(modifier) {
        drawCircle(track)
        if (score > 0) drawArc(fill, -90f, score.coerceIn(0, 100) * 3.6f, true)
    }
}
