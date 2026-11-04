package com.project.on_road.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

/* ======================= 목록 ======================= */

@Composable
fun RoleplayListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val stage = AppState.stage
    var list by remember { mutableStateOf<List<RoleplayScenario>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 분야 고르기 (전체 · 생활 · 금융 · 주거 · 일 · 면접 …). 상황이 많아져서 나눠 본다
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("전체") }
    LaunchedEffect(stage) {
        list = runCatching { AppContainer.coachApi.roleplayScenarios(stage) }
            .onFailure { error = it.userMessage("연습할 상황을 불러오지 못했어요.") }
            .getOrDefault(emptyList())
    }

    OnRoadScreen(if (stage == UserStage.TEEN) "면접 연습" else "실전 상황 연습", onBack) {
        val items = list
        if (items == null) {
            LoadingState("연습할 상황을 불러오고 있어요")
            return@OnRoadScreen
        }
        ScreenHeadline("AI가 상대역을 맡아요.", "실제처럼 말해 보세요.")
        Spacer(Modifier.height(6.dp))
        Text("전화하듯 말로 연습하고, 끝나면 잘한 점과 빠뜨린 점을 알려 드려요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        error?.let {
            Spacer(Modifier.height(16.dp))
            NoticeBox(it, title = "불러오지 못했어요", tone = NoticeTone.Warning)
        }

        val categories = listOf("전체") + items.map { it.category }.distinct()
        if (categories.size > 2) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            CategoryToggle(categories, category) { category = it }
        }
        Spacer(Modifier.height(if (categories.size > 2) 16.dp else OnRoadDimens.BlockGap))
        val shown = if (category == "전체") items else items.filter { it.category == category }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { s -> ScenarioRow(s) { onOpen(s.id) } }
        }
    }
}

/** 분야 전환: 고른 것만 메인 색 바탕 · 흰 글씨 (서류 작성 '대화로 보기'와 같은 모양) */
@Composable
private fun CategoryToggle(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { c ->
            val on = c == selected
            Text(
                c,
                style = OnRoadType.Caption.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                color = if (on) Color.White else OnRoadColors.TextSecondary,
                modifier = Modifier
                    .clip(OnRoadShapes.Chip)
                    .background(if (on) OnRoadColors.Primary else Color.Transparent)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** 상황 한 줄: 연회색 카드, 제목 · 난이도 / 상황 / 상대 */
@Composable
private fun ScenarioRow(s: RoleplayScenario, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.title,
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            // 연회색 카드 위라 태그는 흰 바탕. 실전(어려움)만 메인 색 바탕 · 흰 글씨로 강조
            val hard = s.level == RoleplayLevel.HARD
            Text(
                s.level.label,
                style = OnRoadType.Micro.copy(fontWeight = if (hard) FontWeight.Bold else FontWeight.Medium),
                color = if (hard) Color.White else OnRoadColors.TextSecondary,
                modifier = Modifier
                    .clip(OnRoadShapes.Tag)
                    .background(if (hard) OnRoadColors.Primary else Color.White)
                    .border(1.dp, if (hard) OnRoadColors.Primary else OnRoadColors.BorderChip, OnRoadShapes.Tag)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(s.setting, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        // 상대: 앞에 얇은 세로 막대
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(2.dp).height(12.dp).clip(OnRoadShapes.Chip).background(OnRoadColors.Primary))
            Spacer(Modifier.width(8.dp))
            Text("상대 · ${s.partner}", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    /** 대화 중 오류 안내 (AI 연결 실패, 없는 상황 등) */
    var error by mutableStateOf<String?>(null)
        private set
    /** 기록에 남기는 중 / 남김 */
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    /** 이번 연습의 전개·돌발 상황 (서버가 첫 대사와 함께 정함) */
    var plan by mutableStateOf(RoleplayPlan.NONE)
        private set

    fun start(id: String, variantId: String? = null) {
        if (scenarioId == id) return
        scenarioId = id
        error = null
        viewModelScope.launch {
            if (scenario?.id != id) {
                scenario = runCatching { AppContainer.coachApi.roleplayScenarios(stage) }.getOrDefault(emptyList()).find { it.id == id }
            }
            waiting = true
            runCatching { AppContainer.coachApi.roleplayStart(id, stage, variantId) }
                .onSuccess {
                    plan = it.plan
                    turns.add(it.opening)
                    refreshChecks()
                }
                .onFailure { error = it.userMessage("첫 대사를 불러오지 못했어요.") }
            waiting = false
        }
    }

    /** 첫 대사를 받은 뒤에만 보낼 수 있다 (서버는 이전 대화가 1개 이상이어야 함) */
    val canSend: Boolean get() = !waiting && feedback == null && turns.isNotEmpty()

    /** 대화 중 힌트 ("뭐라고 하지?"). 서버 규칙으로 아직 안 챙긴 것 하나 */
    var hint by mutableStateOf<RoleplayHint?>(null)
        private set
    var hinting by mutableStateOf(false)
        private set
    /** 이번 연습에서 힌트를 본 횟수 */
    var hintCount by mutableStateOf(0)
        private set

    fun askHint() {
        if (hinting || turns.isEmpty()) return
        hinting = true
        viewModelScope.launch {
            hint = runCatching { AppContainer.coachApi.roleplayHint(scenarioId, turns.toList(), plan) }.getOrNull()
                ?: RoleplayHint(false, "힌트를 불러오지 못했어요", "잠시 후 다시 눌러 주세요.", "error", 0)
            if (hint?.kind != "error") hintCount++
            hinting = false
        }
    }

    fun clearHint() {
        hint = null
    }

    /** 대화 중 '챙길 것' 진행 (서버 규칙, LLM 안 씀). 상대가 말할 때마다 조용히 다시 받는다 */
    var checks by mutableStateOf<List<RoleplayCheck>>(emptyList())
        private set

    private fun refreshChecks() {
        if (turns.isEmpty()) return
        viewModelScope.launch {
            runCatching { AppContainer.coachApi.roleplayHint(scenarioId, turns.toList(), plan) }
                .getOrNull()?.let { checks = it.checks }
        }
    }

    fun send(text: String = input) {
        val t = text.trim().take(MESSAGE_LIMIT)
        if (t.isEmpty() || !canSend) return
        hint = null
        turns.add(RoleplayTurn(true, t))
        input = ""
        error = null
        viewModelScope.launch {
            waiting = true
            runCatching { AppContainer.coachApi.roleplayReplyWith(userId, scenarioId, stage, turns.toList(), plan) }
                .onSuccess { r ->
                    turns.add(RoleplayTurn(false, r.text))
                    refreshChecks()
                    // 상대역이 대화를 마무리하면 바로 피드백
                    if (r.finished) {
                        waiting = false
                        finish()
                    }
                }
                .onFailure {
                    // 보내지 못한 말은 입력창으로 돌려놓는다
                    if (turns.lastOrNull()?.fromUser == true) turns.removeAt(turns.lastIndex)
                    input = t
                    error = it.userMessage("대답을 받지 못했어요. 잠시 후 다시 보내 주세요.")
                }
            waiting = false
        }
    }

    fun finish() {
        if (finishing || turns.none { it.fromUser }) return
        finishing = true
        error = null
        viewModelScope.launch {
            runCatching { AppContainer.coachApi.roleplayFeedbackWith(userId, scenarioId, turns.toList(), false, plan) }
                .onSuccess {
                    feedback = it
                    // 연습할 때마다 휴대폰에 남겨서 지난번과 비교한다 (대화 내용은 남기지 않음)
                    com.project.on_road.data.ActivityStore.logPractice(
                        scenarioId, scenario?.title ?: "실전 연습", it.good.size, it.missed.size,
                    )
                    // 서버에도 남겨 자립 준비도에 반영 (대화 내용은 보내지 않음, 실패해도 그대로)
                    val fb = it
                    viewModelScope.launch {
                        runCatching {
                            AppContainer.api.logActivity(
                                userId, "practice", scenarioId,
                                mapOf("good" to fb.good.size.coerceAtMost(100), "missed" to fb.missed.size.coerceAtMost(100), "score" to fb.score),
                            )
                        }
                    }
                }
                .onFailure { error = it.userMessage("피드백을 만들지 못했어요. 잠시 후 다시 끝내기를 눌러 주세요.") }
            finishing = false
        }
    }

    /** "기록에 남기기": 같은 대화로 피드백을 저장한다 (대화 내용은 저장하지 않음) → 성장 기록에 표시 */
    fun save() {
        val fb = feedback ?: return
        if (saving || fb.roleplayId != null) return
        saving = true
        saveError = null
        viewModelScope.launch {
            runCatching { AppContainer.coachApi.roleplayFeedbackWith(userId, scenarioId, turns.toList(), true, plan) }
                .onSuccess { feedback = it }
                .onFailure { saveError = it.userMessage("기록에 남기지 못했어요. 잠시 후 다시 시도해 주세요.") }
            saving = false
        }
    }

    /** sameSituation: 같은 전개로 다시 (돌발 상황은 새로) / 아니면 다른 전개가 나올 수 있게 */
    fun retry(sameSituation: Boolean) {
        feedback = null
        hint = null
        hintCount = 0
        saveError = null
        checks = emptyList()
        turns.clear()
        val id = scenarioId
        val variant = plan.variantId.takeIf { sameSituation }
        plan = RoleplayPlan.NONE
        scenarioId = ""
        start(id, variant)
    }
}

/** 대사 길이 (3.18 message 1~500자) */
private const val MESSAGE_LIMIT = 500

/** 통화 상태: 상대가 생각 중 / 말하는 중 / 내가 말하는 중 / 내 차례 */
private enum class CallPhase { THINKING, SPEAKING, LISTENING, MY_TURN }

/**
 * 전화 통화형 상황 연습.
 * 상대 대사를 읽어 주고 → 다 읽으면 자동으로 마이크가 켜지고 → 말이 끝나면 바로 보낸다.
 * 자막(대화 기록)은 켜고 끌 수 있고, 말하기 어려우면 글로 쓸 수 있다.
 */
@Composable
fun RoleplayPlayScreen(
    scenarioId: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {},
    vm: RoleplayViewModel = viewModel(),
) {
    LaunchedEffect(scenarioId) { vm.start(scenarioId) }
    val speaker = LocalSpeaker.current
    // 들은 말은 바로 보내지 않고 잠깐 보여 준다 (3초 뒤 자동 전송, 누르면 멈춤)
    var draft by remember { mutableStateOf<String?>(null) }
    var others by remember { mutableStateOf(emptyList<String>()) }
    var appending by remember { mutableStateOf(false) }
    var autoSend by remember { mutableStateOf(true) }
    var countdown by remember { mutableIntStateOf(0) }
    fun heard(text: String, alts: List<String>) {
        val prev = draft
        draft = if (appending && prev != null) "$prev $text" else text
        others = if (appending) emptyList() else alts
        appending = false
        autoSend = true
    }
    val listener = rememberCallListener { t, o -> heard(t, o) }
    val dialogInput = rememberSpeechInput { heard(it, emptyList()) }
    fun sendDraft() {
        val t = draft ?: return
        draft = null
        others = emptyList()
        vm.send(t)
    }

    var speaking by remember { mutableStateOf(false) }
    var captions by remember { mutableStateOf(true) }
    var typing by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }

    val canTalk = vm.canSend && !vm.finishing
    fun listen() {
        if (!canTalk || typing) return
        if (!appending) draft = null
        speaker?.stop()
        speaking = false
        when {
            !listener.available -> dialogInput()
            listener.hasPermission() -> listener.start()
            else -> Unit // 권한은 아래 launcher 가 묻는다
        }
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) listen() else typing = true }
    fun talk() {
        if (listener.available && !listener.hasPermission()) permission.launch(android.Manifest.permission.RECORD_AUDIO)
        else listen()
    }

    // 상대 대사가 오면 읽어 주고, 다 읽으면 내 차례 (마이크 자동으로 켜짐)
    LaunchedEffect(vm.turns.size) {
        val last = vm.turns.lastOrNull() ?: return@LaunchedEffect
        if (last.fromUser || vm.feedback != null) return@LaunchedEffect
        listener.cancel()
        if (speaker != null) {
            speaking = true
            speaker.speak(last.text) {
                speaking = false
                if (vm.canSend && !vm.finishing && !typing) talk()
            }
        } else if (!typing) talk()
    }
    LaunchedEffect(draft, autoSend) {
        if (draft == null || !autoSend) return@LaunchedEffect
        for (i in 3 downTo 1) {
            countdown = i
            kotlinx.coroutines.delay(1000)
        }
        sendDraft()
    }
    // 통화 시간
    LaunchedEffect(vm.feedback == null, vm.turns.isEmpty()) {
        while (vm.feedback == null && vm.turns.isNotEmpty()) {
            kotlinx.coroutines.delay(1000)
            seconds++
        }
    }
    // 화면을 벗어나면 읽기·듣기 모두 멈춘다
    DisposableEffect(Unit) {
        onDispose {
            speaker?.stop()
            listener.cancel()
        }
    }
    LaunchedEffect(vm.finishing, vm.feedback) {
        if (vm.finishing || vm.feedback != null) {
            speaker?.stop()
            speaking = false
            listener.cancel()
        }
    }

    val phase = when {
        vm.waiting -> CallPhase.THINKING
        speaking -> CallPhase.SPEAKING
        listener.state == ListenState.LISTENING -> CallPhase.LISTENING
        else -> CallPhase.MY_TURN
    }
    val partner = vm.scenario?.partner ?: "상대역"

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
                            .clickable { vm.finish() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            } else null,
        )

        val fb = vm.feedback
        if (fb != null) {
            // 피드백은 아래에서 살짝 올라오며 나타난다 (통화 → 결과가 툭 바뀌지 않게)
            val appear = remember {
                androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
            }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = appear,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { it / 16 },
            ) {
                FeedbackPage(
                    fb,
                    saving = vm.saving,
                    saveError = vm.saveError,
                    onSave = vm::save,
                    onRetrySame = { seconds = 0; vm.retry(sameSituation = true) },
                    onRetryNew = { seconds = 0; vm.retry(sameSituation = false) },
                    onDone = onBack,
                    onStep = { onNavigate(com.project.on_road.ui.screens.navigation.stepRoute(it)) },
                )
            }
            return@Column
        }
        if (vm.finishing) {
            LoadingState("통화를 돌아보고 피드백을 만들고 있어요")
            return@Column
        }

        Spacer(Modifier.height(14.dp))
        // 상황 · 목표 (카드), 챙길 것은 카드 밖에
        vm.scenario?.let { s ->
            SubtleCard(
                Modifier.fillMaxWidth().padding(horizontal = OnRoadDimens.ScreenPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(s.setting, style = OnRoadType.Caption, color = OnRoadColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("목표 · ${s.goal}", style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // 챙길 것: 넘치면 다음 줄로. 말하면 바로 체크
        if (vm.checks.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = OnRoadDimens.ScreenPadding).padding(top = 10.dp)) {
                CheckChips(vm.checks)
            }
        }

        // 자막 (대화 기록): 위로 올라간 자막은 위쪽에서 흐려지며 사라진다
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (captions) {
                Captions(
                    vm.turns,
                    partner,
                    live = listener.partial.takeIf { phase == CallPhase.LISTENING }
                        ?.let { p -> if (appending && draft != null) "$draft $p".trim() else p },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(OnRoadColors.Background, OnRoadColors.Background.copy(alpha = 0f)),
                            ),
                        ),
                )
            } else {
                Text(
                    "자막을 껐어요",
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        (vm.error ?: listener.errorMessage)?.let { message ->
            Text(
                message,
                style = OnRoadType.Caption2,
                color = OnRoadColors.Danger,
                modifier = Modifier.fillMaxWidth().padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }

        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.BorderInput)
        // 뭐라고 하지?: 아직 안 챙긴 것 하나와 예시 문장
        val h = vm.hint
        if (h != null) {
            HintCard(
                h,
                onUse = {
                    if (typing) vm.input = h.example
                    else {
                        listener.cancel()
                        draft = h.example
                        others = emptyList()
                        autoSend = false
                    }
                    vm.clearHint()
                },
                onClose = vm::clearHint,
            )
        } else if (canTalk && draft == null) {
            Box(Modifier.fillMaxWidth().background(Color.White).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.hinting) "생각하는 중…" else "뭐라고 하지?",
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = OnRoadColors.TextSecondary,
                    modifier = Modifier
                        .clip(OnRoadShapes.Chip)
                        .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Chip)
                        .clickable(enabled = !vm.hinting) {
                            listener.cancel()
                            speaker?.stop()
                            speaking = false
                            vm.askHint()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        if (typing) {
            RoleplayInput(
                value = vm.input,
                onValueChange = { vm.input = it.take(MESSAGE_LIMIT) },
                enabled = vm.canSend,
                onSend = { vm.send() },
            )
            CallActions(
                captions = captions,
                onCaptions = { captions = !captions },
                onReplay = { vm.turns.lastOrNull { !it.fromUser }?.let { t -> speaking = true; speaker?.speak(t.text) { speaking = false } } },
                switchLabel = "말로 하기",
                onSwitch = { typing = false; listener.clearError(); talk() },
            )
        } else if (draft != null && phase != CallPhase.LISTENING) {
            HeardCard(
                text = draft.orEmpty(),
                others = others,
                countdown = if (autoSend) countdown else null,
                onPause = { autoSend = false },
                onPick = { draft = it; others = emptyList(); autoSend = false },
                onSend = { sendDraft() },
                onAgain = { draft = null; listener.clearError(); talk() },
                onMore = { appending = true; autoSend = false; listener.clearError(); talk() },
                onEdit = { vm.input = draft.orEmpty(); draft = null; typing = true },
            )
        } else {
            Column(
                Modifier.fillMaxWidth().background(Color.White).padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MicCircle(
                    phase = phase,
                    level = listener.level,
                    enabled = canTalk || phase == CallPhase.SPEAKING,
                    onClick = {
                        listener.clearError()
                        if (phase == CallPhase.LISTENING) listener.stop() else talk()
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when (phase) {
                        CallPhase.LISTENING -> "말을 마치면 눌러 주세요"
                        CallPhase.SPEAKING -> "눌러서 바로 대답하기"
                        else -> "눌러서 말하기"
                    },
                    style = OnRoadType.Caption2,
                    color = OnRoadColors.TextTertiary,
                )
            }
            CallActions(
                captions = captions,
                onCaptions = { captions = !captions },
                onReplay = { vm.turns.lastOrNull { !it.fromUser }?.let { t -> listener.cancel(); speaking = true; speaker?.speak(t.text) { speaking = false } } },
                switchLabel = "글로 쓰기",
                onSwitch = { listener.cancel(); speaker?.stop(); speaking = false; typing = true },
            )
        }
    }
}

/** 힌트 카드: 이번에 챙길 것 + 예시 문장. "이렇게 말하기"를 누르면 확인 카드(또는 입력칸)로 넣는다 */
@Composable
private fun HintCard(h: RoleplayHint, onUse: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = OnRoadDimens.ScreenPadding)
            .padding(top = 12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Inner)
                .background(OnRoadColors.SurfaceSubtle)
                .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Inner)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        h.kind == "error" -> "힌트"
                        h.done -> "다 챙겼어요"
                        h.kind == "surprise" -> "방금 그 말에는"
                        else -> "이번엔 이걸 챙겨 보세요"
                    },
                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold),
                    color = OnRoadColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (h.remaining > 0) OnRoadTag("남은 것 ${h.remaining}개", style = TagStyle.Neutral, textStyle = OnRoadType.Tiny)
            }
            Spacer(Modifier.height(4.dp))
            Text(h.label, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            BubbleText("“${h.example}”", color = OnRoadColors.TextSecondary, style = OnRoadType.Caption)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (h.kind != "error") {
                OnRoadButton("이렇게 말하기", onUse, Modifier.weight(1f), height = 36.dp, textStyle = OnRoadType.Caption)
            }
            OnRoadButton("닫기", onClose, Modifier.weight(1f), height = 36.dp, variant = ButtonVariant.Outline, textStyle = OnRoadType.Caption, borderColor = OnRoadColors.BorderChip)
        }
    }
}

/** 방금 들은 말 확인: 3초 뒤 자동으로 보내고, 누르면 멈춘다. 비슷한 후보·다시 말하기·이어 말하기·고치기 */
@Composable
private fun HeardCard(
    text: String,
    others: List<String>,
    countdown: Int?,
    onPause: () -> Unit,
    onPick: (String) -> Unit,
    onSend: () -> Unit,
    onAgain: () -> Unit,
    onMore: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Inner)
                .background(OnRoadColors.SurfaceSubtle)
                .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Inner)
                .clickable(onClick = onPause)
                .padding(14.dp),
        ) {
            Text("이렇게 들었어요", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            BubbleText(text, color = OnRoadColors.TextPrimary, style = OnRoadType.Body2)
        }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("혹시 이 말이었나요?", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEach { o ->
                    Text(
                        o,
                        style = OnRoadType.Caption,
                        color = OnRoadColors.TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(OnRoadShapes.Field)
                            .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Field)
                            .clickable { onPick(o) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnRoadButton("다시 말하기", onAgain, Modifier.weight(1f), height = 36.dp, variant = ButtonVariant.Outline, textStyle = OnRoadType.Caption, borderColor = OnRoadColors.BorderChip)
            OnRoadButton("이어 말하기", onMore, Modifier.weight(1f), height = 36.dp, variant = ButtonVariant.Outline, textStyle = OnRoadType.Caption, borderColor = OnRoadColors.BorderChip)
            OnRoadButton("고치기", onEdit, Modifier.weight(1f), height = 36.dp, variant = ButtonVariant.Outline, textStyle = OnRoadType.Caption, borderColor = OnRoadColors.BorderChip)
        }
        Spacer(Modifier.height(8.dp))
        OnRoadButton(
            if (countdown != null && countdown > 0) "보내기 · ${countdown}초" else "보내기",
            onSend,
            Modifier.fillMaxWidth(),
            height = 44.dp,
        )
    }
}

/** 챙길 것 칩: 넘치면 다음 줄로. 챙기면 색이 부드럽게 바뀌고, 새로 생긴 칩(돌발 상황)은 서서히 나타난다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckChips(checks: List<RoleplayCheck>) {
    FlowRow(
        Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.tween(300)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        checks.forEach { c -> key(c.label) { CheckChip(c) } }
    }
}

@Composable
private fun CheckChip(c: RoleplayCheck) {
    val spec = androidx.compose.animation.core.tween<Color>(350)
    val bg by androidx.compose.animation.animateColorAsState(if (c.ok) OnRoadColors.Primary else OnRoadColors.Surface, spec, label = "chip-bg")
    val line by androidx.compose.animation.animateColorAsState(if (c.ok) OnRoadColors.Primary else OnRoadColors.BorderChip, spec, label = "chip-line")
    val fg by androidx.compose.animation.animateColorAsState(if (c.ok) Color.White else OnRoadColors.TextSecondary, spec, label = "chip-fg")
    // 처음 나타날 때 서서히
    val appear = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = appear,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(400)) +
            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(400), initialScale = 0.9f),
    ) {
        Row(
            Modifier
                .clip(OnRoadShapes.Chip)
                .background(bg)
                .border(1.dp, line, OnRoadShapes.Chip)
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .animateContentSize(androidx.compose.animation.core.tween(300)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = c.ok,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) +
                    androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(300)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(200)),
            ) {
                Row {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
            }
            Text(
                c.label,
                style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                color = fg,
                maxLines = 1,
            )
        }
    }
}

/** 자막 글씨: 상대 말 · 내 말 같은 크기 (14sp) */
private val CAPTION_TEXT = OnRoadType.Body3

/** 대화 기록을 자막처럼: 상대 말은 왼쪽 글자, 내 말은 오른쪽 연회색 말풍선 */
@Composable
private fun Captions(turns: List<RoleplayTurn>, partner: String, live: String?) {
    val list = rememberLazyListState()
    val count = turns.size + if (live != null) 1 else 0
    LaunchedEffect(count, live) { if (count > 0) list.animateScrollToItem(count - 1) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, top = 24.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        itemsIndexed(turns) { _, t -> CaptionLine(partner, t.text, mine = t.fromUser) }
        if (live != null) {
            item(key = "live") { CaptionLine(partner, live.ifBlank { "…" }, mine = true, live = true) }
        }
    }
}

@Composable
private fun CaptionLine(partner: String, text: String, mine: Boolean, live: Boolean = false) {
    if (mine) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text,
                style = CAPTION_TEXT,
                color = if (live) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(OnRoadShapes.Inner)
                    .background(OnRoadColors.SurfaceSubtle)
                    .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Inner)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Text(partner, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(text, style = CAPTION_TEXT, color = OnRoadColors.TextPrimary, modifier = Modifier.padding(end = 40.dp))
        }
    }
}

/** 가운데 마이크. 듣는 동안 목소리 크기만큼 테두리가 커진다 */
@Composable
private fun MicCircle(phase: CallPhase, level: Float, enabled: Boolean, onClick: () -> Unit) {
    val listening = phase == CallPhase.LISTENING
    val ring by androidx.compose.animation.core.animateDpAsState(
        if (listening) 66.dp + (level * 12).dp else 60.dp,
        label = "mic-ring",
    )
    Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier
                    .size(ring)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(OnRoadColors.Primary.copy(alpha = 0.12f)),
            )
        }
        Box(
            Modifier
                .size(60.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (enabled) OnRoadColors.Primary else OnRoadColors.SurfaceMuted)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                Box(Modifier.size(18.dp).clip(OnRoadShapes.Chip).background(Color.White))
            } else {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = "말하기",
                    tint = if (enabled) Color.White else OnRoadColors.TextQuaternary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** 아래 작은 버튼: 자막 · 다시 듣기 · 말/글 바꾸기 */
@Composable
private fun CallActions(
    captions: Boolean,
    onCaptions: () -> Unit,
    onReplay: () -> Unit,
    switchLabel: String,
    onSwitch: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnRoadButton(if (captions) "자막 끄기" else "자막 켜기", onCaptions, Modifier.weight(1f), height = 34.dp, variant = ButtonVariant.Outline, textStyle = OnRoadType.Caption, borderColor = OnRoadColors.BorderChip)
        OnRoadButton("다시 듣기", onReplay, Modifier.weight(1f), height = 34.dp, variant = ButtonVariant.Outline, textStyle = OnRoadType.Caption, borderColor = OnRoadColors.BorderChip)
        OnRoadButton(switchLabel, onSwitch, Modifier.weight(1f), height = 34.dp, variant = ButtonVariant.Outline, textStyle = OnRoadType.Caption, borderColor = OnRoadColors.BorderChip)
    }
}

/** 말하기 어려울 때 글로 대답 */
@Composable
private fun RoleplayInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    val active = enabled && value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(OnRoadShapes.Inner)
                .background(OnRoadColors.SurfaceSubtle)
                .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Inner)
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
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(OnRoadShapes.Field)
                .background(if (active) OnRoadColors.Primary else OnRoadColors.SurfaceMuted)
                .clickable(enabled = active, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowUpward, "보내기", tint = if (active) Color.White else OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FeedbackPage(
    fb: RoleplayFeedback,
    saving: Boolean,
    saveError: String?,
    onSave: () -> Unit,
    onRetrySame: () -> Unit,
    onRetryNew: () -> Unit,
    onDone: () -> Unit,
    onStep: (NextStep) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "연습 결과" + (fb.situation?.let { " · $it" } ?: ""),
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
            color = OnRoadColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        val score = fb.score
        if (score != null) ScoreHeader(score, fb.grade)
        else Text("수고했어요", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)

        if (fb.items.isNotEmpty()) {
            // 놓친 것 먼저 (다시 할 때 바로 보이게), 잘한 것은 아래
            val missed = fb.items.filter { !it.ok }
            val good = fb.items.filter { it.ok }
            if (missed.isNotEmpty()) {
                FbSection("다음엔 이것도 챙겨요") {
                    missed.forEach { MissedCard(it) }
                }
            }
            if (good.isNotEmpty()) {
                FbSection("잘 챙겼어요") {
                    FbCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            good.forEach { c -> CheckLine(c.label + if (c.kind == "surprise") " (돌발)" else "", true) }
                        }
                    }
                }
            }
        } else {
            if (fb.missed.isNotEmpty()) FbSection("다음엔 이것도 챙겨요") { FbCard { fb.missed.forEach { CheckLine(it, false) } } }
            if (fb.good.isNotEmpty()) FbSection("잘 챙겼어요") { FbCard { fb.good.forEach { CheckLine(it, true) } } }
        }

        // 내가 실제로 한 말 → 이렇게 바꿔 보기 (서버가 내 말과 비슷한 것만 준다)
        if (fb.better.isNotEmpty()) {
            FbSection("이렇게 바꿔 말해 봐요") {
                fb.better.forEach { (before, after) ->
                    FbCard {
                        Text("내가 한 말", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                        Text(before, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text("이렇게 말하면", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                        Text(after, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    }
                }
            }
        }

        if (fb.documents.isNotEmpty()) {
            FbSection("챙겨 갈 서류") {
                FbCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { fb.documents.forEach { Text("· $it", style = OnRoadType.Caption, color = OnRoadColors.TextPrimary) } } }
            }
        }

        if (fb.nextSteps.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            NextStepList(fb.nextSteps, onStep)
        }

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        OnRoadButton("같은 상황 다시 하기", onRetrySame, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OnRoadButton("다른 전개로 연습하기", onRetryNew, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, borderColor = OnRoadColors.BorderChip)
        Spacer(Modifier.height(8.dp))
        // 피드백 결과만 성장 기록에 남긴다 (대화 내용은 남기지 않음)
        val saved = fb.roleplayId != null
        OnRoadButton(
            if (saved) "기록에 남겼어요" else "기록에 남기기",
            onSave,
            Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline,
            borderColor = OnRoadColors.BorderChip,
            enabled = !saved,
            loading = saving,
        )
        saveError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "목록으로",
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
            color = OnRoadColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDone).padding(vertical = 12.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

/** 점수 (0~100)와 한 줄 평 */
@Composable
private fun ScoreHeader(score: Int, grade: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$score", style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
        Spacer(Modifier.width(2.dp))
        Text("점", style = OnRoadType.Body2, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(bottom = 5.dp))
        Spacer(Modifier.width(10.dp))
        grade?.let { Text(it, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary, modifier = Modifier.padding(bottom = 5.dp)) }
    }
    Spacer(Modifier.height(10.dp))
    ProgressBar(score.coerceIn(0, 100) / 100f, Modifier.fillMaxWidth(), height = 6.dp)
}

@Composable
private fun FbSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    SubHeader(title)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** 연회색 카드 */
@Composable
private fun FbCard(content: @Composable ColumnScope.() -> Unit) = OnRoadCard(
    Modifier.fillMaxWidth(),
    background = OnRoadColors.SurfaceSubtle,
    borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
    shape = OnRoadShapes.Item,
    contentPadding = PaddingValues(16.dp),
    content = content,
)

/** 놓친 것 하나: 무엇을 챙길지 + 이렇게 말해 보세요 */
@Composable
private fun MissedCard(c: RoleplayCheck) = FbCard {
    CheckLine(c.label + if (c.kind == "surprise") " (돌발)" else "", false)
    c.example?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            "“$it”",
            style = OnRoadType.Caption,
            color = OnRoadColors.TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Inner)
                .background(OnRoadColors.Surface)
                .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Inner)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** 동그라미 체크 + 글 (챙김: 메인 색 체크 / 놓침: 빈 동그라미) */
@Composable
private fun CheckLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(18.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (ok) OnRoadColors.Primary else OnRoadColors.Surface)
                .border(1.dp, if (ok) OnRoadColors.Primary else OnRoadColors.BorderChip, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ok) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
    }
}
