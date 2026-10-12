package com.project.on_road.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ChoiceTone
import com.project.on_road.data.PracticeScenario
import com.project.on_road.data.PracticeScenarios
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*

private fun ChoiceTone.tint(): Color = when (this) {
    ChoiceTone.GOOD -> OnRoadColors.Primary
    ChoiceTone.OKAY -> OnRoadColors.Warning
    ChoiceTone.RISKY -> OnRoadColors.Danger
}

private fun ChoiceTone.container(): Color = when (this) {
    ChoiceTone.GOOD -> OnRoadColors.PrimarySoft
    ChoiceTone.OKAY -> OnRoadColors.WarningSoft
    ChoiceTone.RISKY -> OnRoadColors.DangerTint
}

private fun ChoiceTone.tag(): TagStyle = when (this) {
    ChoiceTone.GOOD -> TagStyle.PrimarySoft
    ChoiceTone.OKAY -> TagStyle.Warning
    ChoiceTone.RISKY -> TagStyle.Danger
}

private fun ChoiceTone.icon(): ImageVector = when (this) {
    ChoiceTone.GOOD -> Icons.Outlined.CheckCircle
    ChoiceTone.OKAY -> Icons.Outlined.Info
    ChoiceTone.RISKY -> Icons.Outlined.WarningAmber
}

/* ======================= 목록 ======================= */

@Composable
fun PracticeListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    OnRoadScreen("상황 대처 연습", onBack) {
        ScreenHeadline("정답을 맞히는 시험이 아니에요.", "고르고, 이유를 읽어 봐요.")
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SectionHeader("연습할 상황", trailingText = "${PracticeScenarios.all.size}개")
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeScenarios.all.forEach { s ->
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    borderColor = OnRoadColors.BorderSlate,
                    shape = OnRoadShapes.Inner,
                    contentPadding = PaddingValues(16.dp),
                    onClick = { onOpen(s.id) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OnRoadTag(s.category, style = TagStyle.PrimaryOutline)
                        Spacer(Modifier.weight(1f))
                        Text("${s.steps.size}개의 상황", style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(s.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(s.summary, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                }
            }
        }
    }
}

/* ======================= 플레이 ======================= */

class PracticeViewModel : ViewModel() {
    var scenario by mutableStateOf<PracticeScenario?>(null)
        private set
    var stepIndex by mutableStateOf(0)
        private set
    var selected by mutableStateOf<Int?>(null)
        private set
    val picks = mutableStateListOf<ChoiceTone>()
    val finished: Boolean get() = scenario != null && stepIndex >= (scenario?.steps?.size ?: 0)

    fun load(id: String) {
        if (scenario?.id == id) return
        scenario = PracticeScenarios.find(id)
        restart()
    }

    fun choose(index: Int) {
        val s = scenario ?: return
        if (selected != null) return
        selected = index
        picks.add(s.steps[stepIndex].choices[index].tone)
    }

    fun next() {
        selected = null
        stepIndex += 1
    }

    fun restart() {
        stepIndex = 0
        selected = null
        picks.clear()
    }
}

@Composable
fun PracticePlayScreen(
    scenarioId: String,
    onBack: () -> Unit,
    onOpenBudget: () -> Unit,
    vm: PracticeViewModel = viewModel(),
) {
    LaunchedEffect(scenarioId) { vm.load(scenarioId) }
    val scenario = vm.scenario

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(scenario?.title ?: "상황 대처 연습", onBack)
        if (scenario == null) {
            LoadingState("시나리오를 불러오고 있어요")
            return@Column
        }
        AnimatedContent(
            targetState = if (vm.finished) -1 else vm.stepIndex,
            label = "practiceStep",
            modifier = Modifier.weight(1f),
        ) { index ->
            if (index == -1) {
                PracticeSummary(scenario, vm.picks, onRestart = vm::restart, onDone = onBack, onOpenBudget = onOpenBudget)
            } else {
                PracticeStepView(scenario, index, vm.selected, vm::choose, vm::next)
            }
        }
    }
}

@Composable
private fun PracticeStepView(
    scenario: PracticeScenario,
    index: Int,
    selected: Int?,
    onChoose: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val step = scenario.steps[index]
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("상황", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
            Text("${index + 1}", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Text("/${scenario.steps.size}", style = OnRoadType.Tiny, color = OnRoadColors.TextSlateMid, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar((index + if (selected != null) 1 else 0) / scenario.steps.size.toFloat())

        Spacer(Modifier.height(24.dp))
        // 상황 설명 (AI 말풍선 스타일)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Inner)
                .background(Color.White)
                .border(1.dp, OnRoadColors.PrimaryOutlineSoft, OnRoadShapes.Inner)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(OnRoadColors.Primary, 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(scenario.category, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(step.situation, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
        }

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubHeader("나라면 어떻게 할까요?")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            step.choices.forEachIndexed { i, choice ->
                val picked = selected == i
                val revealed = selected != null
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    background = if (picked) choice.tone.container() else OnRoadColors.Surface,
                    borderColor = if (picked) choice.tone.tint() else OnRoadColors.BorderInput,
                    shape = OnRoadShapes.Field,
                    contentPadding = PaddingValues(16.dp),
                    onClick = if (!revealed) ({ onChoose(i) }) else null,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioMark(picked)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            choice.text,
                            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                            color = if (revealed && !picked) OnRoadColors.TextQuaternary else OnRoadColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (revealed) {
                            Spacer(Modifier.width(8.dp))
                            Icon(choice.tone.icon(), null, tint = choice.tone.tint(), modifier = Modifier.size(18.dp))
                        }
                    }
                    AnimatedVisibility(visible = picked, enter = fadeIn() + slideInVertically { -it / 3 }) {
                        Column(Modifier.padding(start = 30.dp, top = 12.dp)) {
                            OnRoadTag(choice.tone.label, style = choice.tone.tag())
                            Spacer(Modifier.height(6.dp))
                            Text(choice.feedback, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextSlateDark)
                        }
                    }
                }
            }
        }

        if (selected != null) {
            Spacer(Modifier.height(12.dp))
            Text("다른 선택지의 아이콘도 확인해 보세요.", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(20.dp))
            OnRoadButton(
                text = if (index == scenario.steps.lastIndex) "정리 보기" else "다음 상황",
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PracticeSummary(
    scenario: PracticeScenario,
    picks: List<ChoiceTone>,
    onRestart: () -> Unit,
    onDone: () -> Unit,
    onOpenBudget: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(24.dp))
            OnRoadTag("연습 끝", style = TagStyle.PrimaryFilled)
            Spacer(Modifier.height(12.dp))
            ScreenHeadline("‘${scenario.title}’ 상황을", "미리 겪어 봤어요.")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceTone.entries.forEach { tone ->
                    val count = picks.count { it == tone }
                    if (count > 0) OnRoadTag("${tone.label} $count", style = tone.tag())
                }
            }
        }

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        GuideBlock("꼭 기억할 것", scenario.tips.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))

        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            if (scenario.id == "short-money") {
                Spacer(Modifier.height(24.dp))
                ListRow("생활비 배분으로 다음 달 계획 세우기", "부족한 달을 미리 대비해요", onClick = onOpenBudget)
            }
            Spacer(Modifier.height(32.dp))
            OnRoadButton("다른 상황 연습하기", onDone, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OnRoadButton("처음부터 다시 하기", onRestart, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            Spacer(Modifier.height(32.dp))
        }
    }
}
