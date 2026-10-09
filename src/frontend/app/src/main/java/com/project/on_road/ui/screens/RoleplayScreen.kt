package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

private fun RoleplayLevel.tag(): TagStyle = when (this) {
    RoleplayLevel.EASY -> TagStyle.PrimarySoft
    RoleplayLevel.NORMAL -> TagStyle.Slate
    RoleplayLevel.HARD -> TagStyle.Danger
}

/* ======================= 목록 ======================= */

@Composable
fun RoleplayListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val stage = AppState.stage
    var list by remember { mutableStateOf<List<RoleplayScenario>?>(null) }
    LaunchedEffect(stage) {
        list = runCatching { AppContainer.coachApi.roleplayScenarios(stage) }.getOrDefault(emptyList())
    }

    OnRoadScreen(if (stage == UserStage.TEEN) "면접 연습" else "실전 상황 연습", onBack) {
        val items = list
        if (items == null) {
            LoadingState("연습할 상황을 불러오고 있어요")
            return@OnRoadScreen
        }
        ScreenHeadline("AI가 상대역을 맡아요.", "실제처럼 대답해 보세요.")
        Spacer(Modifier.height(6.dp))
        Text("대화가 끝나면 잘한 점과 빠뜨린 점을 정리해 드려요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEachIndexed { i, s ->
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    borderColor = if (i == 0) OnRoadColors.Primary else OnRoadColors.Border,
                    onClick = { onOpen(s.id) },
                    contentPadding = PaddingValues(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            s.title,
                            style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold),
                            color = if (i == 0) OnRoadColors.Primary else OnRoadColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        OnRoadTag(s.level.label, style = s.level.tag())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(s.setting, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(OnRoadColors.Primary, 6.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("AI ${s.partner}", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                        Spacer(Modifier.weight(1f))
                        Text("목표 · ${s.goal}", style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary, maxLines = 1)
                    }
                }
            }
        }
    }
}

/* ======================= 대화 ======================= */

class RoleplayViewModel : ViewModel() {
    private val stage = AppState.stage
    private val userId get() = AppContainer.session.userId.orEmpty()
    private var scenarioId = ""

    var scenario by mutableStateOf<RoleplayScenario?>(null)
        private set
    val turns = mutableStateListOf<RoleplayTurn>()
    var input by mutableStateOf("")
    var waiting by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<RoleplayFeedback?>(null)
        private set
    var finishing by mutableStateOf(false)
        private set

    fun start(id: String) {
        if (scenarioId == id) return
        scenarioId = id
        viewModelScope.launch {
            scenario = runCatching { AppContainer.coachApi.roleplayScenarios(stage) }.getOrDefault(emptyList()).find { it.id == id }
            waiting = true
            runCatching { AppContainer.coachApi.roleplayOpening(id, stage) }.onSuccess { turns.add(it) }
            waiting = false
        }
    }

    fun send(text: String = input) {
        val t = text.trim()
        if (t.isEmpty() || waiting || feedback != null) return
        turns.add(RoleplayTurn(true, t))
        input = ""
        viewModelScope.launch {
            waiting = true
            runCatching { AppContainer.coachApi.roleplayReply(userId, scenarioId, stage, turns.toList()) }
                .onSuccess { turns.add(it) }
            waiting = false
        }
    }

    fun finish() {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            feedback = runCatching { AppContainer.coachApi.roleplayFeedback(userId, scenarioId, turns.toList()) }.getOrNull()
            finishing = false
        }
    }

    fun retry() {
        feedback = null
        turns.clear()
        val id = scenarioId
        scenarioId = ""
        start(id)
    }
}

private val BubbleBorder = OnRoadColors.BorderSage.copy(alpha = 0.4f)

@Composable
fun RoleplayPlayScreen(scenarioId: String, onBack: () -> Unit, vm: RoleplayViewModel = viewModel()) {
    LaunchedEffect(scenarioId) { vm.start(scenarioId) }
    val speaker = LocalSpeaker.current
    val listState = rememberLazyListState()

    // 상대역의 새 발화는 음성이 켜져 있으면 읽어 준다
    LaunchedEffect(vm.turns.size) {
        val last = vm.turns.lastOrNull()
        if (last != null && !last.fromUser && AppState.voiceOn) speaker?.speak(last.text)
        if (vm.turns.isNotEmpty()) listState.animateScrollToItem(vm.turns.size)
    }

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(
            title = vm.scenario?.title ?: "상황 연습",
            onBack = onBack,
            trailing = if (vm.feedback == null && vm.turns.count { it.fromUser } >= 1) {
                {
                    Text(
                        "끝내기",
                        style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                        color = OnRoadColors.Danger,
                        modifier = Modifier
                            .clip(OnRoadShapes.Field)
                            .clickable(onClick = vm::finish)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            } else null,
        )

        val fb = vm.feedback
        if (fb != null) {
            FeedbackPage(fb, onRetry = vm::retry, onDone = onBack)
            return@Column
        }
        if (vm.finishing) {
            LoadingState("대화를 돌아보고 피드백을 만들고 있어요")
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = OnRoadDimens.ScreenPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "intro") {
                vm.scenario?.let { s ->
                    NoticeBox("${s.setting}\n목표 · ${s.goal}", title = "상황")
                }
            }
            itemsIndexed(vm.turns) { _, t ->
                if (t.fromUser) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        Text("나", style = OnRoadType.Caption, color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .widthIn(max = 260.dp)
                                .clip(OnRoadShapes.Inner)
                                .background(Color.White)
                                .border(1.dp, OnRoadColors.Primary, OnRoadShapes.Inner)
                                .padding(horizontal = 15.dp, vertical = 12.dp),
                        ) {
                            Text(t.text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.Primary)
                        }
                    }
                } else {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(OnRoadColors.Primary, 6.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("AI ${vm.scenario?.partner ?: "상대역"}", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                            Spacer(Modifier.weight(1f))
                            SpeakButton(t.text, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .widthIn(max = 280.dp)
                                .clip(OnRoadShapes.Inner)
                                .background(Color.White)
                                .border(1.dp, BubbleBorder, OnRoadShapes.Inner)
                                .padding(horizontal = 15.dp, vertical = 12.dp),
                        ) {
                            Text(t.text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextPrimary)
                        }
                    }
                }
            }
            if (vm.waiting) {
                item(key = "typing") {
                    Text("상대역이 말하는 중…", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.BorderInput)
        RoleplayInput(
            value = vm.input,
            onValueChange = { vm.input = it },
            enabled = !vm.waiting,
            onSend = { vm.send() },
            onVoice = { vm.send(it) },
        )
    }
}

@Composable
private fun RoleplayInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onVoice: (String) -> Unit,
) {
    val listen = rememberSpeechInput(onVoice)
    val active = enabled && value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(OnRoadShapes.Inner)
                .background(OnRoadColors.SurfaceMuted)
                .border(1.dp, OnRoadColors.BorderInput, OnRoadShapes.Inner)
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                maxLines = 4,
                textStyle = OnRoadType.Caption.copy(color = OnRoadColors.TextPrimary),
                cursorBrush = SolidColor(OnRoadColors.Primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("실제처럼 대답해 보세요", style = OnRoadType.Caption, color = OnRoadColors.TextSlate)
                    inner()
                },
            )
            if (AppState.voiceOn) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.Mic, "말로 대답", tint = OnRoadColors.TextSlate, modifier = Modifier.size(20.dp).clickable(onClick = listen))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(OnRoadShapes.Field)
                .background(if (active) OnRoadColors.PrimaryDeep else OnRoadColors.SurfaceMuted)
                .clickable(enabled = active, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowUpward, "보내기", tint = if (active) Color.White else OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FeedbackPage(fb: RoleplayFeedback, onRetry: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(24.dp))
            OnRoadTag("연습 끝", style = TagStyle.PrimaryFilled)
            Spacer(Modifier.height(12.dp))
            ScreenHeadline("수고했어요!", "대화를 돌아볼까요?")
            Spacer(Modifier.height(OnRoadDimens.BlockGap))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fb.good.isNotEmpty()) FeedbackBlock("잘 챙긴 것", fb.good, done = true)
                if (fb.missed.isNotEmpty()) FeedbackBlock("빠뜨린 것", fb.missed, done = false, danger = true)
                if (fb.documents.isNotEmpty()) FeedbackBlock("준비할 서류", fb.documents, done = false)
            }
        }

        if (fb.better.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            GuideBlock(
                "이렇게 말해 보면 더 좋아요",
                fb.better.joinToString("\n\n") { (before, after) -> "“$before”\n→ “$after”" },
            )
        }

        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(24.dp))
            Footnote("서류와 절차는 기관마다 다를 수 있어요.\n방문 전에 담당 기관에 확인해 주세요.")
            Spacer(Modifier.height(24.dp))
            OnRoadButton("다시 연습하기", onRetry, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OnRoadButton("목록으로", onDone, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 피드백 카드 (체크리스트 항목 카드 스타일) */
@Composable
private fun FeedbackBlock(title: String, items: List<String>, done: Boolean, danger: Boolean = false) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = if (done) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
        borderColor = when {
            done -> OnRoadColors.Primary
            danger -> OnRoadColors.Danger.copy(alpha = 0.5f)
            else -> OnRoadColors.BorderSage.copy(alpha = 0.7f)
        },
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
    ) {
        Text(title, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = if (danger) OnRoadColors.Danger else OnRoadColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        items.forEach { BulletText(it, OnRoadColors.TextPrimary.copy(alpha = 0.75f), Modifier.padding(vertical = 2.dp)) }
    }
}
