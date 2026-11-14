package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Box

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ChoiceTone
import com.project.on_road.data.PracticeScenario
import com.project.on_road.data.PracticeScenarios
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*

/** 상태별 색: 네이비 톤에 맞춘 차분한 색 (숫자만 바꿔서 조절) */
private object ToneColor {
    /** 좋은 선택: 차분한 청록 */
    val Good = Color(0xFF3B8C7A)
    /** 나쁘지 않아요: 차분한 황토 */
    val Okay = Color(0xFFA9824A)
    /** 다시 생각해 봐요: 차분한 벽돌색 */
    val Risky = Color(0xFFB4585A)
    /** 테두리는 글씨 색보다 옅게 */
    const val LineAlpha = 0.6f
}

private fun ChoiceTone.tint(): Color = when (this) {
    ChoiceTone.GOOD -> ToneColor.Good
    ChoiceTone.OKAY -> ToneColor.Okay
    ChoiceTone.RISKY -> ToneColor.Risky
}

/** 풀이 글 행간 (13sp 글씨 기준) */
private val FeedbackLineHeight = 18.sp

/** 고른 카드 제목 → 풀이 사이 */
private val FeedbackTop = 10.dp

/** 고른 카드 테두리 색 (좋음은 포인트 색 그대로) */
private fun ChoiceTone.line(): Color = tint().copy(alpha = ToneColor.LineAlpha)

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
        Spacer(Modifier.height(8.dp))
        // 공통 목록 카드: 이름 왼쪽 · 분야 오른쪽(검색 화면 정책 카드와 같은 배치) / 그 아래 요약
        Column(Modifier.reveal(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeScenarios.all.forEachIndexed { si, s -> Box(Modifier.stagger(si)) {
                DataListCard(
                    title = s.title,
                    onClick = { onOpen(s.id) },
                    trailing = { Text(s.category, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1) },
                ) {
                    Text(s.summary, style = OnRoadType.Caption2.copy(lineHeight = OnRoadType.Caption.lineHeight), color = OnRoadColors.TextTertiary)
                }
            } }
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
        // 홈 버튼 자리에 '1/3' (몇 번째 상황인지)
        val total = scenario?.steps?.size ?: 0
        OnRoadTopBar(
            scenario?.title ?: "상황 대처 연습",
            onBack,
            onClose = null,
            divider = false,
            trailing = if (scenario != null && !vm.finished) {
                { Text("${vm.stepIndex + 1}/$total", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp)) }
            } else null,
        )
        if (scenario == null) {
            LoadingState("시나리오를 불러오고 있어요")
            return@Column
        }
        AnimatedContent(
            targetState = if (vm.finished) -1 else vm.stepIndex,
            label = "practiceStep",
            modifier = Modifier.weight(1f).reveal(),
        ) { index ->
            if (index == -1) {
                PracticeSummary(scenario, vm.picks, onRestart = vm::restart, onDone = onBack, onOpenBudget = onOpenBudget)
            } else {
                PracticeStepView(scenario, index, vm.selected, vm::choose, vm::next)
            }
        }
    }
}

/** 상황 대처 연습 간격 (숫자만 바꿔서 조절. 실전 연습 화면들과 같은 값) */
private object PracticeSpace {
    /** 위 바 → 상황 상자 */
    val Top = 8.dp
    /** 상황 상자 → '나라면 어떻게 할까요?' */
    val AskTop = 28.dp
    /** 묶음 사이 */
    val GroupGap = 28.dp
    /** 묶음 제목 → 첫 카드 */
    val HeaderGap = 12.dp
    /** 카드 사이 */
    val CardGap = 8.dp
    /** 글머리 점 앞 · 점과 글 사이 · 줄 사이 */
    val BulletStart = 4.dp
    val BulletIndent = 14.dp
    val BulletGap = 8.dp
}

/**
 * 상황 한 단계. 위계:
 * 1. 상황 상자 (연회색 정보 카드) = '지금 상황'(굵게) → 상황 문장(보통)
 * 2. 질문 '나라면 어떻게 할까요?' (묶음 제목, 가장 굵게) → 연회색 선택 카드
 * 3. 고르면: 고른 카드만 흰 바탕 · 1dp 메인 색 테두리 + 오른쪽 결과 · 아래 풀이, 나머지는 옅게 / 아래 고정 '다음 상황'
 */
@Composable
private fun PracticeStepView(
    scenario: PracticeScenario,
    index: Int,
    selected: Int?,
    onChoose: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val step = scenario.steps[index]
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(PracticeSpace.Top))
            // 상황 상자 (연회색 정보 카드)
            OnRoadCard(
                Modifier.fillMaxWidth(),
                background = OnRoadColors.SurfaceSubtle,
                borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
                shape = OnRoadShapes.Item,
                contentPadding = PaddingValues(16.dp),
            ) {
                // 라벨만 굵게, 상황 문장은 보통 굵기 (강조는 아래 질문에)
                Text("지금 상황", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(step.situation, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextSecondary)
            }

            // 질문: 이 화면의 주인공 (다른 화면 묶음 제목과 같은 17 굵게)
            Spacer(Modifier.height(PracticeSpace.AskTop))
            GroupTitle("나라면 어떻게 할까요?")
            Spacer(Modifier.height(PracticeSpace.HeaderGap))
            Column(verticalArrangement = Arrangement.spacedBy(PracticeSpace.CardGap)) {
                step.choices.forEachIndexed { i, choice ->
                    val picked = selected == i
                    val revealed = selected != null
                    // 고른 뒤에는 '좋은 선택'도 함께 알려 준다 (내가 고르지 않았어도)
                    val showAnswer = revealed && !picked && choice.tone == ChoiceTone.GOOD
                    val line by androidx.compose.animation.animateColorAsState(
                        when {
                            picked -> choice.tone.line()
                            showAnswer -> ChoiceTone.GOOD.line()
                            else -> OnRoadColors.Border.copy(alpha = 0.7f)
                        },
                        OnRoadMotion.move(), label = "choice-line",
                    )
                    // 선택지: 다른 목록과 같은 연회색 카드. 고른 카드만 흰 바탕 · 1dp 메인 색 테두리 (서류 작성 칸과 같게)
                    OnRoadCard(
                        Modifier.fillMaxWidth().animateContentSize(OnRoadMotion.move()),
                        background = if (picked || showAnswer) OnRoadColors.Surface else OnRoadColors.SurfaceSubtle,
                        borderColor = line,
                        shape = OnRoadShapes.Item,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
                        onClick = if (!revealed) ({ onChoose(i) }) else null,
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                choice.text,
                                // 고른 카드 제목은 굵게
                                style = if (picked || showAnswer) OnRoadType.Body2.copy(fontWeight = FontWeight.Bold) else OnRoadType.Body2,
                                // 결과 글귀 없이, 고른 카드 · 좋은 선택 카드의 제목을 상태 색으로 (좋음 청록 · 보통 황토 · 위험 벽돌)
                                color = when {
                                    picked || showAnswer -> choice.tone.tint()
                                    revealed -> OnRoadColors.TextQuaternary
                                    else -> OnRoadColors.TextPrimary
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        AnimatedVisibility(visible = picked, enter = fadeIn(OnRoadMotion.enter())) {
                            // 풀이: 행간을 좁히고 한 단계 옅게 (선택지 글보다 뒤로)
                            Text(
                                choice.feedback,
                                style = OnRoadType.Caption.copy(lineHeight = FeedbackLineHeight),
                                color = OnRoadColors.TextTertiary,
                                modifier = Modifier.padding(top = FeedbackTop),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // 4. 아래 고정 버튼 (고른 뒤에만)
        AnimatedVisibility(visible = selected != null, enter = fadeIn(OnRoadMotion.enter())) {
            Box(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = OnRoadDimens.ScreenPadding).padding(top = 8.dp, bottom = 12.dp)) {
                OnRoadButton(
                    text = if (index == scenario.steps.lastIndex) "정리 보기" else "다음 상황",
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 연습 정리: 실전 연습 결과 화면과 같은 짜임.
 * 결과 카드(흰 카드) → '꼭 기억할 것' 글머리 목록 → (있으면) 이어서 할 것 → 큰 버튼 하나 + 글자 버튼
 */
@Composable
private fun PracticeSummary(
    scenario: PracticeScenario,
    picks: List<ChoiceTone>,
    onRestart: () -> Unit,
    onDone: () -> Unit,
    onOpenBudget: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(OnRoadDimens.ScreenTop))
        OnRoadCard(
            Modifier.fillMaxWidth(),
            borderColor = OnRoadColors.BorderChip,
            shape = OnRoadShapes.Item,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text("연습 끝 · ${scenario.title}", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text("미리 겪어 봤어요", style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            // 고른 결과 개수: 글씨만, 뜻 있는 색
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceTone.entries.forEach { tone ->
                    val count = picks.count { it == tone }
                    if (count > 0) Text("${tone.label} $count", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = tone.tint())
                }
            }
        }

        Spacer(Modifier.height(PracticeSpace.GroupGap))
        GroupTitle("꼭 기억할 것")
        Spacer(Modifier.height(PracticeSpace.HeaderGap))
        Column(verticalArrangement = Arrangement.spacedBy(PracticeSpace.BulletGap)) {
            scenario.tips.forEach { t ->
                Row(Modifier.padding(start = PracticeSpace.BulletStart), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(PracticeSpace.BulletIndent).padding(top = 9.dp)) {
                        Box(Modifier.size(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(OnRoadColors.TextTertiary))
                    }
                    Text(t, style = OnRoadType.Body2.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                }
            }
        }

        if (scenario.id == "short-money") {
            Spacer(Modifier.height(PracticeSpace.GroupGap))
            GroupTitle("이어서 해 보기")
            Spacer(Modifier.height(PracticeSpace.HeaderGap))
            DataListCard(
                title = "생활비 배분으로 다음 달 계획 세우기",
                onClick = onOpenBudget,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp)) },
            ) {
                Text("부족한 달을 미리 대비해요", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }
        }

        Spacer(Modifier.height(32.dp))
        // 큰 버튼은 하나만, 나머지는 글자 버튼 (실전 연습 결과와 같게)
        OnRoadButton("다른 상황 연습하기", onDone, Modifier.fillMaxWidth())
        Text(
            "처음부터 다시 하기",
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
            color = OnRoadColors.TextTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(OnRoadShapes.Chip).tap(onClick = onRestart).padding(vertical = 12.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}
