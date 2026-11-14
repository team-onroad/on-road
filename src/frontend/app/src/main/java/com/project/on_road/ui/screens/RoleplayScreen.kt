package com.project.on_road.ui.screens

import androidx.compose.animation.core.animateFloat

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue

import com.project.on_road.ui.theme.OnRoadMotion

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.layout.Box

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
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
    // 전에 본 목록이 있으면 바로 보여 주고 뒤에서 새로 받는다
    var list by remember { mutableStateOf(com.project.on_road.data.ScreenCache.get<List<RoleplayScenario>>("roleplays:$stage")) }
    var error by remember { mutableStateOf<String?>(null) }
    // '다시 시도'를 누를 때마다 1씩 올려서 다시 불러온다
    var reload by remember { mutableStateOf(0) }
    // 분야 고르기 (전체 · 생활 · 금융 · 주거 · 일 · 면접 …). 상황이 많아져서 나눠 본다
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("전체") }
    LaunchedEffect(stage, reload) {
        val fresh = runCatching { AppContainer.coachApi.roleplayScenarios(stage) }
            .onFailure { if (list.isNullOrEmpty()) error = it.userMessage("연습할 상황을 불러오지 못했어요.") }
            .getOrNull()
        list = fresh ?: list ?: emptyList()
        if (!fresh.isNullOrEmpty()) com.project.on_road.data.ScreenCache.put("roleplays:$stage", fresh)
    }

    OnRoadScreen(if (stage == UserStage.TEEN) "면접 연습" else "실전 상황 연습", onBack) {
        val items = list
        if (items == null) {
            SkeletonList(rows = 4)
            return@OnRoadScreen
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            ErrorState(it, onRetry = { error = null; list = null; reload++ }, title = "연습할 상황을 불러오지 못했어요")
        }

        val categories = listOf("전체", NOT_YET) + items.map { it.category }.distinct()
        // 내가 해 본 연습 (휴대폰 기록): 상황별 횟수 · 지난번 놓친 것
        val version = com.project.on_road.data.ActivityStore.version
        val mine = remember(version) {
            com.project.on_road.data.ActivityStore.practices().groupBy { it.scenarioId }
        }
        // 이번에 해 볼 연습: 자립 준비도에서 가장 약한 영역의 연습 → 없으면 아직 안 해 본 것
        val recommended = remember(items, version, com.project.on_road.data.PlanData.readiness) {
            val r = com.project.on_road.data.PlanData.readiness
            val fromReadiness = r?.nextSteps?.firstOrNull { it.kind == "practice" && items.any { s -> s.id == it.scenarioId } }
            val s = fromReadiness?.let { step -> items.first { it.id == step.scenarioId } }
                ?: items.firstOrNull { it.id !in mine }
            s?.let {
                val reason = if (fromReadiness != null) {
                    r?.areas?.firstOrNull { a -> a.nextStep?.scenarioId == it.id }?.let { a -> "‘${a.label}’ 영역을 채워요" } ?: "자립 준비도를 올려요"
                } else "아직 안 해 본 상황이에요"
                it to reason
            }
        }
        // 위계: 이번에 해 볼 연습(흰 카드 한 줄, 목록보다 살짝만 앞에) → '상황 고르기' 묶음 제목 → 분야 → 상황 카드
        recommended?.let { (s, reason) ->
            Spacer(Modifier.height(RoleplaySpace.HeroTop))
            Box(Modifier.stagger(0)) { RecommendRow(s, reason) { onOpen(s.id) } }
        }
        Spacer(Modifier.height(if (recommended != null) RoleplaySpace.GroupGap else RoleplaySpace.HeroTop))
        GroupTitle("상황 고르기")
        Spacer(Modifier.height(RoleplaySpace.HeaderGap))
        CategoryToggle(categories, category) { category = it }

        // 분야를 바꾸면 목록이 툭 바뀌지 않고 부드럽게, 처음엔 살짝 올라오며 나타난다
        Spacer(Modifier.height(RoleplaySpace.ListTop))
        FadeSwitch(category, Modifier.reveal(), label = "roleplay-category") { cat ->
            val shown = when (cat) {
                "전체" -> items
                NOT_YET -> items.filter { it.id !in mine }
                else -> items.filter { it.category == cat }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) EmptyState("이 분야는 모두 해 봤어요. 다른 분야도 연습해 보세요.")
                shown.forEach { s -> ScenarioRow(s, mine[s.id].orEmpty()) { onOpen(s.id) } }
            }
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
            val bg by androidx.compose.animation.animateColorAsState(
                if (on) OnRoadColors.Primary else OnRoadColors.Primary.copy(alpha = 0f), OnRoadMotion.move(), label = "cat-bg",
            )
            val fg by androidx.compose.animation.animateColorAsState(
                if (on) Color.White else OnRoadColors.TextTertiary, OnRoadMotion.move(), label = "cat-fg",
            )
            Text(
                c,
                style = OnRoadType.Caption.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                color = fg,
                modifier = Modifier
                    .clip(OnRoadShapes.Chip)
                    .background(bg)
                    .tap { onSelect(c) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 실전 연습 목록 간격 (숫자만 바꿔서 조절) */
private object RoleplaySpace {
    /** 위 바 → 큰 카드 */
    val HeroTop = 0.dp
    /** 큰 카드 → 상황 고르기 */
    val GroupGap = 28.dp
    /** 묶음 제목 → 분야 */
    val HeaderGap = 10.dp
    /** 분야 → 첫 카드 */
    val ListTop = 12.dp
}

/**
 * 이번에 해 볼 연습: 강조는 최소로. 흰 카드 · 칩 테두리 (생활 가이드 '진로' 카드와 같은 모양).
 * 왼쪽 위 작은 글씨 '추천 · 이유' → 상황 이름(굵게), 오른쪽 가운데 '시작 ›'
 */
@Composable
private fun RecommendRow(s: RoleplayScenario, reason: String, onStart: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        onClick = onStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("추천 · $reason", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(s.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시작", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * 상황 한 줄: 공통 목록 카드, 두 줄만.
 * 1줄: 이름(굵게) · 오른쪽 난이도 글씨 (상자 없이. 실전만 메인 색 굵게)
 * 2줄: 상황 설명(연한 글씨) · 해 본 적 있으면 오른쪽에 내 기록(더 연한 글씨)
 */
@Composable
private fun ScenarioRow(s: RoleplayScenario, tries: List<com.project.on_road.data.PracticeAttempt>, onClick: () -> Unit) {
    val hard = s.level == RoleplayLevel.HARD
    DataListCard(
        title = s.title,
        onClick = onClick,
        trailing = {
            Text(
                s.level.label,
                style = OnRoadType.Caption2.copy(fontWeight = if (hard) FontWeight.Bold else FontWeight.Medium),
                color = if (hard) OnRoadColors.Primary else OnRoadColors.TextTertiary,
                maxLines = 1,
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.setting,
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val last = tries.firstOrNull()
            if (last != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (last.missed == 0) "${tries.size}번 해 봄" else "지난번 ${last.missed}개 놓침",
                    style = OnRoadType.Caption2,
                    color = OnRoadColors.TextQuaternary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 이번에 해 볼 연습: 흰 카드 + 왜 이걸 추천하는지 + 작은 메인 색 버튼 */
@Composable
private fun RecommendCard(s: RoleplayScenario, reason: String, onStart: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        onClick = onStart,
    ) {
        Text("이번에 해 볼 연습 · $reason", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        Text(s.title, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(s.goal, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        PrimaryPill("연습 시작", onStart)
    }
}

/** 분야 고르기의 '아직 안 해 본 것' */
private const val NOT_YET = "안 해 본 것"

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
                .onFailure { error = it.userMessage("첫 대사를 불러오지 못했어요."); checksLoaded = true }
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
    /** 챙길 것을 한 번이라도 받아 봤는지 (못 받으면 준비 화면에서 빈 칸 대신 안내 문구) */
    var checksLoaded by mutableStateOf(false)
        private set

    private fun refreshChecks() {
        if (turns.isEmpty()) return
        viewModelScope.launch {
            // 받으면 바꾸고, 실패하거나 비어 오면 그 전 목록을 그대로 둔다 (깜빡이며 사라지지 않게)
            runCatching { AppContainer.coachApi.roleplayHint(scenarioId, turns.toList(), plan) }
                .getOrNull()?.checks?.takeIf { it.isNotEmpty() }?.let { checks = it }
            checksLoaded = true
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
                .onSuccess { feedback = it; com.project.on_road.ui.components.OnRoadToast.show("성장 기록에 남겼어요") }
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
        checksLoaded = false
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
    /** 연습이 끝난 뒤 돌아가는 버튼 이름 (체크리스트 · 일정에서 왔으면 그곳 이름) */
    doneLabel: String = "목록으로",
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
    // 통화 전 준비 화면을 보고 '통화 시작'을 눌렀는지. 그 전에는 상대 대사를 읽지 않는다
    var ready by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(vm.turns.size, ready) {
        if (!ready) return@LaunchedEffect
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
    LaunchedEffect(vm.feedback == null, vm.turns.isEmpty(), ready) {
        while (ready && vm.feedback == null && vm.turns.isNotEmpty()) {
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
            // 홈 버튼 대신 오른쪽에 '끝내기' (한 마디라도 해야 눌린다)
            onClose = null,
            divider = false,
            trailing = if (vm.feedback == null && ready) {
                {
                    val canFinish = vm.turns.any { it.fromUser } && !vm.finishing
                    Text(
                        "끝내기",
                        style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                        color = if (canFinish) OnRoadColors.TextPrimary else OnRoadColors.TextQuaternary,
                        modifier = Modifier
                            .clip(OnRoadShapes.Chip)
                            .tap(enabled = canFinish) { vm.finish() }
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
                    doneLabel = doneLabel,
                    onStep = { onNavigate(com.project.on_road.ui.screens.navigation.stepRoute(it)) },
                    next = { NextTaskBlock(onNavigate, finished = com.project.on_road.data.PlanGo.Practice(scenarioId)) },
                )
            }
            return@Column
        }
        if (vm.finishing) {
            LoadingState(
                "통화를 돌아보고 있어요",
                steps = listOf("잘한 점을 찾고 있어요", "빠뜨린 점과 다음에 할 말을 정리하고 있어요"),
            )
            return@Column
        }

        // ── 통화 전 준비: 상황 · 목표 · 챙길 것 · 지난번 기록을 보고 시작 ──
        if (!ready) {
            Briefing(
                scenario = vm.scenario,
                checks = vm.checks,
                checksLoaded = vm.checksLoaded,
                tries = remember(scenarioId) {
                    com.project.on_road.data.ActivityStore.practices().filter { it.scenarioId == scenarioId }
                },
                loading = vm.turns.isEmpty() && vm.error == null,
                error = vm.error,
                onStartTalk = { ready = true },
                onStartTyping = { typing = true; ready = true },
            )
            return@Column
        }

        // ── 위: 목표 한 줄 + 챙길 것 한 줄 (눌러야 상황 설명이 펼쳐진다) ──
        vm.scenario?.let { s -> SituationBar(s.setting, s.goal, vm.checks) }

        // ── 가운데: 자막 (위로 올라간 자막은 위쪽에서 흐려지며 사라진다) ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.animation.Crossfade(captions, label = "captions") { on ->
                if (on) {
                    Box(Modifier.fillMaxSize()) {
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
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("자막을 껐어요. 소리로 들어 보세요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    }
                }
            }
        }


        // ── 아래: 한 번에 하나만 (힌트 · 들은 말 확인 · 글 입력) + 통화 버튼 줄 (구분선 없이) ──
        val h = vm.hint
        val panel = when {
            h != null -> 1
            !typing && draft != null && phase != CallPhase.LISTENING -> 2
            typing -> 3
            else -> 0
        }
        androidx.compose.animation.AnimatedContent(
            targetState = panel,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(com.project.on_road.ui.theme.OnRoadMotion.enter()) +
                    androidx.compose.animation.slideInVertically(com.project.on_road.ui.theme.OnRoadMotion.enter()) { it / 6 }) togetherWith
                    androidx.compose.animation.fadeOut(com.project.on_road.ui.theme.OnRoadMotion.exit()) using
                    androidx.compose.animation.SizeTransform(clip = false)
            },
            label = "call-panel",
        ) { p ->
            when (p) {
                1 -> h?.let { hint ->
                    HintCard(
                        hint,
                        onUse = {
                            if (typing) vm.input = hint.example
                            else {
                                listener.cancel()
                                draft = hint.example
                                others = emptyList()
                                autoSend = false
                            }
                            vm.clearHint()
                        },
                        onClose = vm::clearHint,
                    )
                } ?: Spacer(Modifier.height(0.dp))
                2 -> HeardCard(
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
                3 -> RoleplayInput(
                    value = vm.input,
                    onValueChange = { vm.input = it.take(MESSAGE_LIMIT) },
                    enabled = vm.canSend,
                    onSend = { vm.send() },
                )
                else -> Spacer(Modifier.height(0.dp))
            }
        }
        // 들은 말 확인 카드가 떠 있는 동안은 아래 버튼 줄을 통째로 접는다 (마이크만 빠져 가운데가 비어 보이지 않게)
        ExpandArea(panel != 2) {
            CallControls(
                phase = phase,
                level = listener.level,
                showMic = !typing && panel != 2,
                micEnabled = canTalk || phase == CallPhase.SPEAKING,
                onMic = {
                    listener.clearError()
                    if (phase == CallPhase.LISTENING) listener.stop() else talk()
                },
                captions = captions,
                onCaptions = { captions = !captions },
                onReplay = {
                    vm.turns.lastOrNull { !it.fromUser }?.let { t ->
                        listener.cancel()
                        speaking = true
                        speaker?.speak(t.text) { speaking = false }
                    }
                },
                hintLabel = if (vm.hinting) "생각 중" else "뭐라고 하지?",
                hintEnabled = canTalk && !vm.hinting && h == null,
                onHint = {
                    listener.cancel()
                    speaker?.stop()
                    speaking = false
                    vm.askHint()
                },
                typing = typing,
                // 들은 말 확인 · 힌트 · 글 입력 카드가 떠 있을 땐 안내 · 상태 글씨를 숨긴다 (한 번에 하나만)
                notice = (vm.error ?: listener.errorMessage).takeIf { panel == 0 },
                showStatus = panel == 0,
                onSwitch = {
                    if (typing) {
                        typing = false
                        listener.clearError()
                        talk()
                    } else {
                        listener.cancel()
                        speaker?.stop()
                        speaking = false
                        typing = true
                    }
                },
            )
        }
    }
}

/**
 * 통화 전 준비 화면: 실전 상황 연습 '목록' 화면과 같은 부품 · 간격만 쓴다.
 * - 맨 위: 흰 카드 한 줄 (목록의 추천 카드와 같은 모양) = 상황(작은 연한 글씨) → 목표(굵게), 오른쪽 난이도
 * - 묶음 제목 '대화하면서 챙길 것' (목록의 '상황 고르기'와 같은 GroupTitle)
 * - 연회색 목록 카드 (목록의 상황 카드와 같은 DataListCard)
 * - 아래 고정: 통화 시작 / 글로 연습하기
 */
@Composable
private fun Briefing(
    scenario: RoleplayScenario?,
    checks: List<RoleplayCheck>,
    checksLoaded: Boolean,
    tries: List<com.project.on_road.data.PracticeAttempt>,
    loading: Boolean,
    error: String?,
    onStartTalk: () -> Unit,
    onStartTyping: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .reveal()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            // 목록 화면과 같은 첫 여백
            Spacer(Modifier.height(OnRoadDimens.ScreenTop + RoleplaySpace.HeroTop))
            if (scenario == null) {
                SkeletonList(rows = 1)
            } else {
                // 목록의 추천 카드와 같은 흰 카드 · 칩 테두리
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    borderColor = OnRoadColors.BorderChip,
                    shape = OnRoadShapes.Item,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(scenario.setting, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(scenario.goal, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        // 목록 상황 카드의 난이도 글씨와 같은 모양 (실전만 메인 색 굵게)
                        val hard = scenario.level == RoleplayLevel.HARD
                        Text(
                            scenario.level.label,
                            style = OnRoadType.Caption2.copy(fontWeight = if (hard) FontWeight.Bold else FontWeight.Medium),
                            color = if (hard) OnRoadColors.Primary else OnRoadColors.TextTertiary,
                        )
                    }
                }
            }

            // 목록의 '상황 고르기'와 같은 묶음 제목 · 간격
            Spacer(Modifier.height(RoleplaySpace.GroupGap))
            GroupTitle("대화하면서 챙길 것")
            Spacer(Modifier.height(RoleplaySpace.ListTop))
            FadeSwitch(
                when {
                    checks.isNotEmpty() -> 2
                    checksLoaded -> 1
                    else -> 0
                },
                label = "briefing-checks",
            ) { state ->
                when (state) {
                    0 -> SkeletonList(rows = 3)
                    1 -> Text("대화를 시작하면 챙길 것을 하나씩 알려 드려요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        checks.filter { it.kind != "surprise" }.forEach { c ->
                            // 목록의 상황 카드와 같은 연회색 카드 (누르는 동작은 없음)
                            DataListCard(title = c.label, onClick = null, titleBold = false)
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
            }
            Spacer(Modifier.height(24.dp))
        }

        // 아래 고정 버튼 (구분선 없이)
        Column(Modifier.background(Color.White).padding(horizontal = OnRoadDimens.ScreenPadding).padding(top = 8.dp, bottom = 12.dp)) {
            OnRoadButton(
                if (loading) "상대역 준비 중…" else "통화 시작",
                onStartTalk,
                Modifier.fillMaxWidth(),
                enabled = !loading && error == null,
                loading = loading,
                leadingIcon = if (loading) null else Icons.Outlined.Mic,
            )
            Text(
                "말하기 어렵다면 글로 연습하기",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Chip)
                    .tap(enabled = !loading && error == null, onClick = onStartTyping)
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/**
 * 통화 중 위 정보 카드 (준비 화면 · 체크리스트 정보 카드와 같은 연회색 카드).
 * 위계: 목표(굵게) + 오른쪽 '챙김 n/m' → 챙길 것 칩(흰 칩, 챙기면 메인 색). 카드를 누르면 상황 문장이 펼쳐진다.
 * 통화 화면의 주인공은 자막이라, 이 카드는 한 단계 뒤로 물러나 있다
 */
@Composable
private fun SituationBar(setting: String, goal: String, checks: List<RoleplayCheck>) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val shown = checks.filter { it.kind != "surprise" || it.ok }
    // 상자가 아니라 화면 끝에서 끝까지 깔린 연회색 띠 한 줄 (자막 위에 붙은 배경)
    Column(
        Modifier
            .fillMaxWidth()
            .background(OnRoadColors.SurfaceSubtle)
            .tap(pressed = 1f) { open = !open }
            .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = CallSpace.BandPadV)
            .animateContentSize(com.project.on_road.ui.theme.OnRoadMotion.move()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                goal,
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                maxLines = if (open) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (checks.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "챙김 ${checks.count { it.ok }}/${checks.size}",
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                )
            }
        }
        ExpandArea(open) {
            Text(
                setting,
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (shown.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            CheckChips(checks)
        }
    }
}

/**
 * 통화 버튼 줄: 자막 · 다시 듣기 | 마이크 | 뭐라고 하지? · 글로 쓰기.
 * (예전에는 '뭐라고 하지?' 줄 + 마이크 + 테두리 버튼 3개가 따로 쌓여 아래가 복잡했다)
 */
@Composable
private fun CallControls(
    phase: CallPhase,
    level: Float,
    showMic: Boolean,
    micEnabled: Boolean,
    onMic: () -> Unit,
    captions: Boolean,
    onCaptions: () -> Unit,
    onReplay: () -> Unit,
    hintLabel: String,
    hintEnabled: Boolean,
    onHint: () -> Unit,
    typing: Boolean,
    onSwitch: () -> Unit,
    /** 잘 못 들었어요 같은 안내: 있으면 상태 글씨 자리에 대신 보여 준다 */
    notice: String? = null,
    /** 아래 카드(들은 말 · 힌트 · 입력)가 떠 있으면 false */
    showStatus: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth().background(Color.White).padding(top = 10.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showStatus && (showMic || notice != null)) {
            // 상태 한 줄 (안내가 있으면 안내가 우선). 아래 버튼 이름보다 한 단계 진하게
            androidx.compose.animation.Crossfade(notice ?: phase, label = "call-status") { p ->
                Text(
                    when (p) {
                        is String -> p
                        CallPhase.THINKING -> "상대가 생각하고 있어요"
                        CallPhase.SPEAKING -> "상대가 말하고 있어요 · 눌러서 바로 대답"
                        CallPhase.LISTENING -> "듣고 있어요 · 다 말하면 눌러 주세요"
                        else -> "내 차례예요 · 눌러서 말하기"
                    },
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    color = if (p is String) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = OnRoadDimens.ScreenPadding),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlIcon(
                if (captions) Icons.Outlined.ClosedCaption else Icons.Outlined.ClosedCaptionDisabled,
                if (captions) "자막 끄기" else "자막 켜기",
                Modifier.weight(1f),
                onClick = onCaptions,
            )
            ControlIcon(Icons.Outlined.Replay, "다시 듣기", Modifier.weight(1f), onClick = onReplay)
            Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
                if (showMic) MicCircle(phase = phase, level = level, enabled = micEnabled, onClick = onMic)
            }
            ControlIcon(Icons.Outlined.Lightbulb, hintLabel, Modifier.weight(1f), enabled = hintEnabled, onClick = onHint)
            ControlIcon(
                if (typing) Icons.Outlined.Mic else Icons.Outlined.Keyboard,
                if (typing) "말로 하기" else "글로 쓰기",
                Modifier.weight(1f),
                onClick = onSwitch,
            )
        }
    }
}

/** 통화 화면 크기 (숫자만 바꿔서 조절) */
private object CallSpace {
    /** 자막 · 다시 듣기 · 힌트 · 글로 쓰기 버튼 크기 */
    val ToolSize = 48.dp
    /** 위 목표 띠 위아래 여백 */
    val BandPadV = 14.dp
}

/** 통화 버튼 하나: 둥근 네모 아이콘 + 아래 짧은 이름 */
@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier
            .pressScale(press, enabled = enabled, pressed = 0.92f)
            .clip(OnRoadShapes.Field)
            .clickable(
                interactionSource = press,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 앱 카드와 같은 모양: 연회색 바탕 · 회색 테두리 · 둥근 네모 (동그라미는 가운데 마이크 하나만)
        Box(
            Modifier
                .size(CallSpace.ToolSize)
                .clip(OnRoadShapes.Item)
                .background(OnRoadColors.SurfaceSubtle)
                .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Item),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (enabled) OnRoadColors.TextSecondary else OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp))
        }
        // 버튼 이름 글씨는 빼고 아이콘만. 이름은 화면 읽기(접근성)로만 읽어 준다
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
                .clip(OnRoadShapes.Item)
                .background(OnRoadColors.SurfaceSubtle)
                .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Item)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        h.kind == "error" -> "힌트"
                        h.done -> "다 챙겼어요"
                        h.kind == "surprise" -> "방금 그 말에는"
                        else -> "이번엔 이걸 챙겨 보세요"
                    },
                    style = OnRoadType.Caption2,
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.weight(1f).padding(top = 6.dp),
                )
                Box(
                    Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).tap(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "닫기", tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp)) }
            }
            Text(h.label, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(4.dp))
            BubbleText("“${h.example}”", color = OnRoadColors.TextSecondary, style = OnRoadType.Caption)
            if (h.kind != "error") {
                Spacer(Modifier.height(10.dp))
                PrimaryPill("이렇게 말하기", onUse)
            }
        }
    }
}

/** 방금 들은 말 확인: 3초 뒤 자동으로 보내고, 카드를 누르면 멈춘다. 다른 후보는 한 줄로, 나머지는 작은 글자 버튼으로 */
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
            .padding(horizontal = OnRoadDimens.ScreenPadding)
            .padding(top = 12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Item)
                .background(OnRoadColors.SurfaceSubtle)
                .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Item)
                .tap(pressed = 1f, onClick = onPause)
                .padding(16.dp),
        ) {
            Text(
                if (countdown != null && countdown > 0) "${countdown}초 뒤 보내요" else "이렇게 들었어요",
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextTertiary,
            )
            Spacer(Modifier.height(4.dp))
            BubbleText(text, color = OnRoadColors.TextPrimary, style = OnRoadType.Body2)
        }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                others.forEach { o -> ChoiceChip(o, selected = false) { onPick(o) } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("다시 말하기" to onAgain, "이어 말하기" to onMore, "고치기" to onEdit).forEach { (label, act) ->
                Text(
                    label,
                    style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextSecondary,
                    modifier = Modifier.clip(OnRoadShapes.Chip).tap(onClick = act).padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            OnRoadButton("보내기", onSend, height = 40.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/** 챙길 것 칩: 넘치면 다음 줄로. 챙기면 색이 부드럽게 바뀌고, 새로 생긴 칩(돌발 상황)은 서서히 나타난다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckChips(checks: List<RoleplayCheck>) {
    // 여러 줄로 쌓이면 자막 자리가 줄어서, 한 줄로 두고 옆으로 밀어 보게 한다
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                color = fg,
                maxLines = 1,
            )
        }
    }
}

/** 자막 글씨: 화면의 주인공이라 위 카드 · 아래 안내보다 크게 (15sp) */
private val CAPTION_TEXT = OnRoadType.Body2.copy(lineHeight = OnRoadType.Body3.lineHeight * 1.05f)

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
                // 내 말: 메인 색 말풍선 · 흰 글씨 (서류 작성 대화와 같게). 듣는 중인 말은 연한 말풍선
                color = if (live) OnRoadColors.TextTertiary else Color.White,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(OnRoadShapes.Inner)
                    .background(if (live) OnRoadColors.SurfaceSubtle else OnRoadColors.Primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Text(partner, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(text, style = CAPTION_TEXT, color = OnRoadColors.TextPrimary, modifier = Modifier.padding(end = 40.dp))
        }
    }
}

/**
 * 가운데 마이크 원: 지금 누구 차례인지 움직임으로 구분한다.
 *  - 상대가 생각 중: 원 안에서 점 세 개가 차례로 떠오른다
 *  - 상대가 말하는 중: 원 안에서 막대 다섯 개가 말소리처럼 오르내린다
 *  - 내 차례(마이크 대기): 원 둘레가 천천히 숨 쉬듯 커졌다 작아진다 + 차례가 오면 짧은 진동
 *  - 듣는 중: 목소리 크기만큼 테두리가 커지고, 물결이 바깥으로 퍼진다
 */
@Composable
private fun MicCircle(phase: CallPhase, level: Float, enabled: Boolean, onClick: () -> Unit) {
    val listening = phase == CallPhase.LISTENING
    val myTurn = phase == CallPhase.MY_TURN
    val tick = com.project.on_road.ui.components.rememberTick()
    // 내 차례가 되거나 듣기 시작하면 짧게 알려 준다
    LaunchedEffect(phase) { if (phase == CallPhase.MY_TURN || phase == CallPhase.LISTENING) tick() }

    val ring by androidx.compose.animation.core.animateDpAsState(
        if (listening) 66.dp + (level * 14).dp else 60.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "mic-ring",
    )
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "mic-orb")
    // 숨쉬기 (내 차례)
    val breathe by t.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400, easing = OnRoadMotion.Emphasized), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breathe",
    )
    // 바깥으로 퍼지는 물결 (듣는 중)
    val ripple by t.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "ripple",
    )
    val core by androidx.compose.animation.animateColorAsState(
        when {
            phase == CallPhase.SPEAKING || phase == CallPhase.THINKING -> OnRoadColors.SurfaceMuted
            enabled -> OnRoadColors.Primary
            else -> OnRoadColors.SurfaceMuted
        },
        OnRoadMotion.move(),
        label = "mic-core",
    )
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            // 물결: 커지면서 흐려진다
            Box(
                Modifier
                    .size(60.dp)
                    .graphicsLayer { val k = 1f + ripple * 0.4f; scaleX = k; scaleY = k; alpha = (1f - ripple) * 0.5f }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(OnRoadColors.Primary.copy(alpha = 0.18f)),
            )
            Box(
                Modifier
                    .size(ring)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(OnRoadColors.Primary.copy(alpha = 0.12f)),
            )
        } else if (myTurn && enabled) {
            Box(
                Modifier
                    .size(60.dp)
                    .graphicsLayer { val k = 1.06f + breathe * 0.14f; scaleX = k; scaleY = k; alpha = 0.6f - breathe * 0.3f }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(OnRoadColors.Primary.copy(alpha = 0.14f)),
            )
        }
        Box(
            Modifier
                .size(60.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(core)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(OnRoadMotion.enter(OnRoadMotion.FAST)) + androidx.compose.animation.scaleIn(OnRoadMotion.enter(OnRoadMotion.FAST), initialScale = 0.8f)) togetherWith
                        androidx.compose.animation.fadeOut(OnRoadMotion.exit())
                },
                label = "mic-face",
            ) { p ->
                when (p) {
                    CallPhase.THINKING -> com.project.on_road.ui.components.TypingDots(color = OnRoadColors.TextTertiary)
                    CallPhase.SPEAKING -> VoiceBars()
                    CallPhase.LISTENING -> Box(Modifier.size(18.dp).clip(OnRoadShapes.Chip).background(Color.White))
                    CallPhase.MY_TURN -> Icon(
                        Icons.Outlined.Mic,
                        contentDescription = "말하기",
                        tint = if (enabled) Color.White else OnRoadColors.TextQuaternary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/** 상대가 말하는 동안: 막대 다섯 개가 서로 다른 박자로 오르내린다 */
@Composable
private fun VoiceBars() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice-bars")
    val periods = listOf(420, 300, 520, 360, 460)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(22.dp)) {
        periods.forEachIndexed { i, ms ->
            val h by t.animateFloat(
                0.3f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(ms, delayMillis = i * 60, easing = OnRoadMotion.Emphasized),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(Modifier.width(3.dp).fillMaxHeight(h).clip(OnRoadShapes.Chip).background(OnRoadColors.Primary))
        }
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
            .padding(start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 앱 입력칸과 같은 모양: 흰 바탕 · 회색 테두리 · 8dp 모서리 · 48dp 높이
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = OnRoadDimens.FieldHeight)
                .clip(OnRoadShapes.Field)
                .background(Color.White)
                .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Field)
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                maxLines = 4,
                textStyle = OnRoadType.Body2.copy(color = OnRoadColors.TextPrimary),
                cursorBrush = SolidColor(OnRoadColors.Primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("실제처럼 대답해 보세요", style = OnRoadType.Body2, color = OnRoadColors.TextQuaternary)
                    inner()
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(OnRoadDimens.FieldHeight)
                .clip(OnRoadShapes.Field)
                .background(if (active) OnRoadColors.Primary else OnRoadColors.SurfaceMuted)
                .tap(enabled = active, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowUpward, "보내기", tint = if (active) Color.White else OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp))
        }
    }
}

/** 결과 화면 간격 (숫자만 바꿔서 조절. 실전 연습 목록 · 준비 화면과 같은 값) */
private object ResultSpace {
    /** 위 바 → 결과 카드 */
    val Top = 16.dp
    /** 묶음 사이 (배경 없는 글머리 목록 뒤에서도 묶음이 확실히 나뉘게 조금 넓게) */
    val GroupGap = 36.dp
    /** 묶음 제목 → 첫 카드 */
    val HeaderGap = 12.dp
    /** 카드 사이 */
    val CardGap = 8.dp
    /** 글머리 기호 줄 사이 */
    val BulletGap = 8.dp
    /** 글머리 점 앞 여백 (0이면 화면 여백 선 = 묶음 제목 시작선) */
    val BulletStart = 4.dp
    /** 글머리 점 위 여백 (15sp 첫 줄 가운데) */
    val BulletDotTop = 9.dp
    /** 글머리 기호 → 글 */
    val BulletIndent = 14.dp
}

/**
 * 연습 결과: 실전 연습 목록 · 준비 화면과 같은 부품만 쓴다.
 * 1. 결과 카드 (흰 카드 · 칩 테두리): 상황 → 점수(가장 큰 글씨) · 한 줄 평 → 챙긴 개수 / 성장 기록에 남기기
 * 2. 묶음 제목(GroupTitle) + 연회색 목록 카드: 다음엔 챙길 것 → 잘 챙긴 것 → 바꿔 말하기 → 챙겨 갈 서류
 * 3. 이어서 해 보기 · 다음 할 일 → 큰 버튼 하나 + 글자 버튼
 */
@Composable
private fun FeedbackPage(
    fb: RoleplayFeedback,
    saving: Boolean,
    saveError: String?,
    onSave: () -> Unit,
    onRetrySame: () -> Unit,
    onRetryNew: () -> Unit,
    onDone: () -> Unit,
    doneLabel: String = "목록으로",
    onStep: (NextStep) -> Unit = {},
    /** 타임라인 할 일 자동 완료 + 다음 할 일 */
    next: @Composable () -> Unit = {},
) {
    // 챙길 것 목록 (서버가 items 를 주면 그걸, 아니면 good · missed 글자 목록)
    val missedItems: List<Pair<String, String?>> =
        if (fb.items.isNotEmpty()) fb.items.filter { !it.ok }.map { (it.label + if (it.kind == "surprise") " (돌발)" else "") to it.example }
        else fb.missed.map { it to null }
    val goodItems: List<String> =
        if (fb.items.isNotEmpty()) fb.items.filter { it.ok }.map { it.label + if (it.kind == "surprise") " (돌발)" else "" }
        else fb.good
    val total = missedItems.size + goodItems.size

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(ResultSpace.Top))

        // 1. 결과 카드
        OnRoadCard(
            Modifier.fillMaxWidth(),
            borderColor = OnRoadColors.BorderChip,
            shape = OnRoadShapes.Item,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                fb.situation ?: "연습 결과",
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val score = fb.score
            Row(verticalAlignment = Alignment.Bottom) {
                if (score != null) {
                    com.project.on_road.ui.components.AnimatedNumber(score.toLong(), { "$it" }, style = OnRoadType.Display, color = OnRoadColors.TextPrimary, durationMs = 900)
                    Text("점", style = OnRoadType.Body2, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
                    fb.grade?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
                    }
                } else {
                    Text("수고했어요", style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (total > 0) "챙김 ${goodItems.size}/$total" else "",
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                // 성장 기록에 남기기: 오른쪽 작은 글자 버튼 (남기면 '남겼어요')
                val saved = fb.roleplayId != null
                Text(
                    when {
                        saved -> "성장 기록에 남겼어요"
                        saving -> "남기는 중…"
                        else -> "성장 기록에 남기기 ›"
                    },
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    color = if (saved) OnRoadColors.TextQuaternary else OnRoadColors.TextSecondary,
                    modifier = Modifier
                        .clip(OnRoadShapes.Chip)
                        .tap(enabled = !saved && !saving, onClick = onSave)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            saveError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }
        }

        // 2. 다음엔 챙길 것 (놓친 것 먼저)
        if (missedItems.isNotEmpty()) {
            ResultGroup("다음엔 이것도 챙겨요", gap = ResultSpace.BulletGap) {
                // 배경 없이 글머리 기호 목록
                missedItems.forEach { (label, example) -> BulletLine(label, example?.let { "“$it”" }) }
            }
        }
        if (goodItems.isNotEmpty()) {
            ResultGroup("잘 챙겼어요") {
                goodItems.forEach { label ->
                    DataListCard(
                        title = label,
                        onClick = null,
                        titleBold = false,
                        // 챙김 표시: 타임라인 '끝냄'과 같은 포인트 색
                        trailing = { Icon(Icons.Filled.Check, "챙김", tint = OnRoadColors.Point, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }

        // 내가 한 말 → 이렇게 바꿔 말하기
        if (fb.better.isNotEmpty()) {
            ResultGroup("이렇게 바꿔 말해 봐요") {
                fb.better.forEach { (before, after) ->
                    OnRoadCard(
                        Modifier.fillMaxWidth(),
                        background = OnRoadColors.SurfaceSubtle,
                        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
                        shape = OnRoadShapes.Item,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
                    ) {
                        Text("내가 한 말 · $before", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                        Spacer(Modifier.height(4.dp))
                        Text(after, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    }
                }
            }
        }

        if (fb.documents.isNotEmpty()) {
            ResultGroup("챙겨 갈 서류", gap = ResultSpace.BulletGap) {
                fb.documents.forEach { BulletLine(it) }
            }
        }

        // 3. 이어서
        if (fb.nextSteps.isNotEmpty()) {
            Spacer(Modifier.height(ResultSpace.GroupGap))
            NextStepList(fb.nextSteps, onStep)
        }
        Spacer(Modifier.height(ResultSpace.GroupGap))
        next()
        Spacer(Modifier.height(ResultSpace.GroupGap))
        // 큰 버튼은 하나만. 나머지는 아래 글자 버튼으로
        OnRoadButton("같은 상황 다시 하기", onRetrySame, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            listOf("다른 전개로 연습하기" to onRetryNew, doneLabel to onDone).forEach { (label, act) ->
                Text(
                    label,
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.clip(OnRoadShapes.Chip).tap(onClick = act).padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 결과 묶음: 목록 화면 '상황 고르기'와 같은 묶음 제목 + 카드(또는 글머리 기호) 목록 */
@Composable
private fun ResultGroup(title: String, gap: androidx.compose.ui.unit.Dp = ResultSpace.CardGap, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(ResultSpace.GroupGap))
    GroupTitle(title)
    Spacer(Modifier.height(ResultSpace.HeaderGap))
    Column(verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** 글머리 기호 한 줄: • + 글 (15 보통 굵기). 덧붙임(예시 문장)은 아래 연한 글씨 */
@Composable
private fun BulletLine(text: String, sub: String? = null) {
    Row(Modifier.padding(start = ResultSpace.BulletStart), verticalAlignment = Alignment.Top) {
        // 글머리 점: 첫 줄 글씨 가운데 높이에 맞춘 작은 점 (글자 '•'는 아래로 처져 보여서 직접 그린다)
        Box(Modifier.width(ResultSpace.BulletIndent).padding(top = ResultSpace.BulletDotTop)) {
            Box(Modifier.size(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(OnRoadColors.TextTertiary))
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = OnRoadType.Body2, color = OnRoadColors.TextPrimary)
            sub?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
        }
    }
}
