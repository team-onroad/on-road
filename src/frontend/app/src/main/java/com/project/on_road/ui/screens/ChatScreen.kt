package com.project.on_road.ui.screens

import com.project.on_road.ui.theme.OnRoadMotion

import com.project.on_road.ui.components.copyOnLongPress

import com.project.on_road.ui.components.RevealText

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.animation.animateColorAsState


import com.project.on_road.ui.components.tap
import com.project.on_road.ui.components.dialogEnter
import com.project.on_road.ui.components.reveal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.History
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.ChatKind
import com.project.on_road.data.ChatMessage
import com.project.on_road.data.ChatRole
import com.project.on_road.data.DocCandidate
import com.project.on_road.data.HelpLine
import com.project.on_road.data.SignalSource
import com.project.on_road.data.UserStage
import com.project.on_road.data.Topic
import com.project.on_road.data.Links
import com.project.on_road.ui.components.BubbleText
import com.project.on_road.ui.components.ButtonVariant
import com.project.on_road.ui.components.ProfileChangeCard
import com.project.on_road.ui.components.LogoSlot
import com.project.on_road.ui.components.NumberedPoint
import com.project.on_road.ui.components.NextStepList
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.OnRoadTopBar
import com.project.on_road.ui.components.SpeakButton
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.components.rememberSpeechInput
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HelpContact(val name: String, val description: String, val number: String)

private val helpContacts = listOf(
    HelpContact("보건복지상담센터", "복지 제도, 긴급 지원 안내", "129"),
    HelpContact("청소년상담 1388", "고민 상담 (24시간)", "1388"),
    HelpContact("자살예방 상담전화", "마음이 많이 힘들 때 (24시간)", "109"),
    HelpContact("긴급 신고", "위험한 상황일 때", "112"),
)

/** 질문 칩을 만들 때 보는 내 조건 (가족 · 하는 일 · 받는 중 · 자격증) */
private fun suggestContext(stage: UserStage): com.project.on_road.data.ChatSuggestions.Context {
    val family = com.project.on_road.data.ProfileStore.current?.family.orEmpty()
    return com.project.on_road.data.ChatSuggestions.Context(
        youngAdult = stage == UserStage.YOUNG_ADULT,
        student = AppContainer.session.workStatus == com.project.on_road.data.WorkStatus.STUDENT,
        sibling = family.any { it.relation == "sibling" },
        child = family.any { it.relation == "child" },
        receiving = (com.project.on_road.data.PlanData.roadmap?.receivingCount ?: 0) > 0,
        certificates = com.project.on_road.data.CredentialStore.certificates.isNotEmpty(),
        district = com.project.on_road.data.ProfileStore.current?.district,
    )
}

/** 첫 질문 예시: 내 조건에 맞는 찾기 주제 */
private fun startersFor(stage: UserStage): List<String> =
    com.project.on_road.data.ChatSuggestions.starters(suggestContext(stage))

/** 서버가 지금 하는 일을 안 보낼 때 (예전 서버 · 가짜 모드) 시간에 따라 바꿔 보여 줄 말. (앞 말 다음 기다릴 ms, 말) */
private val LOCAL_STEPS = listOf(
    0L to "질문을 살펴보는 중",
    1200L to "관련 정보를 찾는 중",
    2500L to "답을 정리하는 중",
)

/** 질문 길이 (3.15 message 1~500자) */
private const val MESSAGE_LIMIT = 500

private fun nowLabel(): String = SimpleDateFormat("a h:mm", Locale.KOREAN).format(Date())

private fun dayLabel(millis: Long): String = SimpleDateFormat("M월 d일", Locale.KOREAN).format(Date(millis))

private fun greeting() = ChatMessage(
    0L, ChatRole.ASSISTANT,
    "안녕하세요, 온로드 AI 상담이에요.\n자립 준비, 진로, 지원 제도처럼 궁금한 것을 편하게 물어보세요.",
    kind = ChatKind.LOCAL,
)

class ChatViewModel : ViewModel() {
    private var nextId = 1L
    /** 이 대화의 저장 id (휴대폰 안 상담 기록) */
    private var chatId = System.currentTimeMillis()
    private var startedAt = chatId
    val messages = mutableStateListOf(greeting())
    /** 메시지 id → 보낸 시각 */
    val times = mutableStateMapOf(0L to nowLabel())
    var input by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    /** 지난 상담을 이어서 하는 중이면 그 대화를 시작한 날 */
    var resumedFrom by mutableStateOf<String?>(null)
        private set
    private var askedInitial = false
    private var resumedOnce = false

    fun askInitial(question: String?) {
        if (askedInitial || question.isNullOrBlank()) return
        askedInitial = true
        send(question)
    }

    /** 처음 열 때 한 번: 성장 기록에서 고른 지난 상담 불러오기 */
    fun resumeOnce(id: Long?) {
        if (resumedOnce || id == null) return
        resumedOnce = true
        load(id)
    }

    /** 지난 상담 불러오기 (이어서 질문하면 같은 대화에 쌓인다) */
    fun load(id: Long) {
        if (sending) return
        val saved = ActivityStore.chat(id) ?: return
        chatId = saved.id
        startedAt = saved.startedAt
        messages.clear()
        times.clear()
        nextId = 1L
        messages.add(greeting())
        times[0L] = dayLabel(saved.startedAt)
        saved.messages.forEach { m ->
            val copy = m.copy(id = nextId++)
            messages.add(copy)
            times[copy.id] = dayLabel(saved.updatedAt)
        }
        resumedFrom = dayLabel(saved.startedAt)
    }

    /** 새 상담 시작 */
    fun startNew() {
        if (sending) return
        chatId = System.currentTimeMillis()
        startedAt = chatId
        messages.clear()
        times.clear()
        nextId = 1L
        messages.add(greeting())
        times[0L] = nowLabel()
        resumedFrom = null
    }

    private fun persist() {
        ActivityStore.saveChat(chatId, startedAt, messages.filter { it.id != 0L })
    }

    /** 방금 도착해 글자가 흘러나오는 중인 답변 id (끝나면 null) */
    var revealId by mutableStateOf<Long?>(null)
    /** 서버에서 문장이 오는 중인 답변 id (다 오면 null). 이때는 '입력 중' 점 대신 답이 바로 자란다 */
    var streamingId by mutableStateOf<Long?>(null)
        private set

    /** 흘러나오는 중에는 다 보였다는 신호를 무시한다 (다음 문장이 이어서 흘러나오게) */
    fun revealDone(id: Long) {
        if (revealId == id && streamingId != id) revealId = null
    }
    /** 지금 하는 일 ("관련 정책을 찾는 중"). 답 글이 보이기 시작하면 null */
    var status by mutableStateOf<String?>(null)
        private set

    /** 불러오지 못한 답변 id → 다시 보낼 질문 */
    val retryOf = mutableStateMapOf<Long, String>()
    private var job: kotlinx.coroutines.Job? = null

    fun send(text: String = input) {
        val t = text.trim().take(MESSAGE_LIMIT)
        if (t.isEmpty() || sending) return
        val userMsg = ChatMessage(nextId++, ChatRole.USER, t)
        messages.add(userMsg)
        times[userMsg.id] = nowLabel()
        input = ""
        // 관심 분야 신호 (주거·생활비·취업 …) → 관심사·다음 할 일 추천에 쓰인다
        ActivityStore.logQuestion(t, SignalSource.CHAT)
        ask(t)
    }

    private fun ask(t: String) {
        job = viewModelScope.launch {
            sending = true
            status = LOCAL_STEPS.first().second
            // 서버가 지금 하는 일(status)을 보내면 그걸 쓰고, 안 보내는 예전 서버면 시간에 따라 바꾼다
            var serverStatus = false
            val steps = launch {
                for ((wait, text) in LOCAL_STEPS.drop(1)) {
                    kotlinx.coroutines.delay(wait)
                    if (serverStatus || status == null) break
                    status = text
                }
            }
            var failed = false
            // 고민 상담 답은 문장이 오는 대로 말풍선을 먼저 만들고 이어 붙인다 (첫 문장이 1초 안에 보인다)
            var liveId: Long? = null
            val history = messages.toList()
            val reply = runCatching {
                AppContainer.chatApi.reply(
                    AppContainer.session.userId.orEmpty(), history, t,
                    onStatus = { text -> serverStatus = true; if (liveId == null) status = text },
                    onPartial = { partial ->
                    status = null
                    val id = liveId
                    if (id == null) {
                        val first = ChatMessage(nextId++, ChatRole.ASSISTANT, partial, kind = ChatKind.COUNSEL)
                        liveId = first.id
                        streamingId = first.id
                        revealId = first.id
                        times[first.id] = nowLabel()
                        messages.add(first)
                    } else {
                        val i = messages.indexOfFirst { it.id == id }
                        if (i >= 0) messages[i] = messages[i].copy(text = partial)
                    }
                    },
                )
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                failed = true
                ChatMessage(0L, ChatRole.ASSISTANT, "답변을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", needsCheck = true, kind = ChatKind.LOCAL)
            }
            val id = liveId
            val i = if (id != null) messages.indexOfFirst { it.id == id } else -1
            val msg: ChatMessage
            if (i >= 0) {
                // 흘러나온 말풍선을 최종 답으로 (같은 글이면 그대로 이어서, 바뀐 글이면 다시 흘러나온다)
                msg = reply.copy(id = id!!)
                messages[i] = msg
            } else {
                msg = reply.copy(id = nextId++)
                messages.add(msg)
                times[msg.id] = nowLabel()
                if (!failed) revealId = msg.id
            }
            steps.cancel()
            status = null
            streamingId = null
            if (failed) retryOf[msg.id] = t
            sending = false
            persist()
        }
    }

    /** 기다리는 중 멈추기 */
    fun stop() {
        if (!sending) return
        job?.cancel()
        sending = false
        status = null
        streamingId = null
        val msg = ChatMessage(nextId++, ChatRole.ASSISTANT, "답변을 멈췄어요. 궁금한 걸 다시 보내 주세요.", kind = ChatKind.LOCAL)
        messages.add(msg)
        times[msg.id] = nowLabel()
    }

    /** 불러오지 못한 답변 자리에서 같은 질문을 다시 보낸다 */
    fun retry(id: Long) {
        val q = retryOf.remove(id) ?: return
        if (sending) return
        messages.removeAll { it.id == id }
        times.remove(id)
        ask(q)
    }
}

/** AI 상담 (디자인: 상담) */
@Composable
fun ChatScreen(
    initialQuestion: String? = null,
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    onPrepare: (String) -> Unit = {},
    /** 상담 → 문서 AI: 고른 서류 후보와 상담 맥락 */
    onWriteDocument: (DocCandidate, String?) -> Unit = { _, _ -> },
    /** 성장 기록에서 고른 지난 상담 id. 있으면 그 대화를 불러와 이어서 한다 */
    chatId: Long? = null,
    onNavigate: (String) -> Unit = {},
    vm: ChatViewModel = viewModel(),
) {
    LaunchedEffect(chatId) { vm.resumeOnce(chatId) }
    // 질문 칩이 받는 중 · 자격증을 보려면 한 번 받아 둔다 (이미 있으면 다시 안 부름)
    LaunchedEffect(Unit) {
        com.project.on_road.data.CredentialStore.refresh()
        if (com.project.on_road.data.PlanData.roadmap == null) com.project.on_road.data.PlanData.refreshRoadmap()
    }
    LaunchedEffect(initialQuestion) { vm.askInitial(initialQuestion) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    var showHelp by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // 개인정보 안내: 들어올 때마다 맨 위에 작은 상자로, X 로 닫는다
    var privacyOpen by remember { mutableStateOf(true) }

    // 스크롤 규칙: 답의 '끝'이 아니라 '처음'에 맞춘다.
    // - 내가 보내면: 내 말과 입력 중 표시가 보이게 맨 아래로 한 번
    // - 답이 자라는 동안(문장이 오거나 글자가 나타나며 말풍선이 커질 때): 아래로 따라가되,
    //   답의 첫 줄이 화면 위쪽에 닿으면 멈춘다. 그 뒤로는 처음부터 읽고 직접 내려 본다
    // - 손으로 굴리면 따라가기를 멈춘다 (읽는 중에 끌려가지 않게)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val answerTop = with(density) { ChatSpace.AnswerTop.roundToPx() }
    var following by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(vm.messages.size) {
        val last = vm.messages.lastOrNull() ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        when {
            // 이전 상담을 불러왔을 때: 마지막 대화가 보이게 바로 (애니메이션 없이)
            !loaded -> { loaded = true; listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) }
            last.role == ChatRole.USER -> { following = true; listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
        }
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val i = vm.messages.lastIndex
            val bot = vm.messages.lastOrNull()?.role != ChatRole.USER
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
            Triple(bot, item?.offset, item?.size)
        }.collect { (bot, offset, size) ->
            if (!following || !bot || offset == null || size == null || dragged) return@collect
            val info = listState.layoutInfo
            val hidden = offset + size - info.viewportEndOffset // 답이 화면 아래로 가려진 만큼
            val room = offset - info.viewportStartOffset - answerTop // 답 첫 줄이 위쪽에 닿기까지 남은 거리
            val by = minOf(hidden, room)
            if (by > 0) listState.scrollBy(by.toFloat())
            if (room <= by) following = false // 답 첫 줄이 위쪽에 닿았다: 여기서 멈춘다
        }
    }

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(
            title = "상담",
            onBack = onBack,
            onClose = null,
            divider = false,
            // '지난 상담' 글씨 대신 기록 아이콘
            trailing = {
                Box(
                    Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).tap(onClickLabel = "지난 상담") { showHistory = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.History, "지난 상담", tint = OnRoadColors.TextSecondary, modifier = Modifier.size(22.dp))
                }
            },
        )
        HelpStrip { showHelp = true }
        androidx.compose.animation.AnimatedVisibility(
            visible = privacyOpen,
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            PrivacyNotice(onClose = { privacyOpen = false })
        }
        vm.resumedFrom?.let { day ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(OnRoadColors.SurfaceSubtle)
                    .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$day 상담을 이어서 하고 있어요", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
                Text(
                    "새 상담",
                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold),
                    color = OnRoadColors.Primary,
                    modifier = Modifier.tap { vm.startNew() },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = OnRoadDimens.ScreenPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(ChatSpace.MessageGap),
        ) {
            items(vm.messages, key = { it.id }) { msg ->
                val time = vm.times[msg.id].orEmpty()
                // 새 말풍선은 아래에서 살짝 올라오며 나타난다
                Box(Modifier.animateItem().reveal()) {
                if (msg.role == ChatRole.USER) {
                    UserMessage(msg.text, time)
                } else {
                    BotMessage(
                        msg = msg,
                        time = time,
                        starters = when {
                            msg.id == 0L && vm.messages.size == 1 -> startersFor(AppState.stage)
                            // 마지막 답변 아래: 방금 나온 정책으로 이어 찾기 + 아직 안 찾은 주제
                            msg.id != 0L && !vm.sending && msg.id == vm.messages.lastOrNull()?.id && msg.kind != ChatKind.LOCAL ->
                                com.project.on_road.data.ChatSuggestions.followUps(
                                    suggestContext(AppState.stage),
                                    msg.policies.map { it.name },
                                    vm.messages.filter { it.role == ChatRole.USER }.map { it.text },
                                )
                            else -> emptyList()
                        },
                        startersFirst = msg.id == 0L,
                        onStarter = { vm.send(it) },
                        onOpenPolicy = onOpenPolicy,
                        onPrepare = onPrepare,
                        onAskMore = { runCatching { inputFocus.requestFocus() } },
                        onWriteDocument = { onWriteDocument(it, msg.handoffContext) },
                        onOpenSchedule = {
                            // 신청 관리 '일정' 탭을 '정책 신청' 필터로
                            com.project.on_road.data.ScheduleJump.policyFilter = true
                            onNavigate(com.project.on_road.ui.screens.navigation.Routes.CHECKLISTS)
                        },
                        reveal = msg.id == vm.revealId,
                        onRevealDone = { vm.revealDone(msg.id) },
                        onRetry = if (msg.id in vm.retryOf) ({ vm.retry(msg.id) }) else null,
                    )
                }
                }
            }
            if (vm.sending && vm.streamingId == null) item(key = "typing") { Box(Modifier.animateItem().reveal()) { TypingBubble(vm.status) } }
            // 마지막 답 아래: 이야기한 분야로 이어서 해 볼 것 (위기 상담 중에는 띄우지 않는다)
            val lastBot = vm.messages.lastOrNull()?.takeIf { it.role != ChatRole.USER && it.kind != ChatKind.CRISIS }
            val lastQuestion = vm.messages.lastOrNull { it.role == ChatRole.USER }?.text
            val topic = lastQuestion?.let { Topic.classify(it).firstOrNull { t -> t != Topic.MIND } }
            if (!vm.sending && lastBot != null && topic != null) {
                item(key = "next-steps-${lastBot.id}") {
                    NextStepList(
                        Links.forTopic(topic),
                        { onNavigate(com.project.on_road.ui.screens.navigation.stepRoute(it)) },
                        title = "이 이야기와 이어서 해 보기",
                    )
                }
            }
            item(key = "footer") { Spacer(Modifier.height(4.dp)) }
        }

        ChatInputBar(
            value = vm.input,
            onValueChange = { vm.input = it.take(MESSAGE_LIMIT) },
            enabled = !vm.sending,
            sending = vm.sending,
            onStop = vm::stop,
            onSend = { vm.send() },
            onVoice = { vm.input = (vm.input + " " + it).trim().take(MESSAGE_LIMIT) },
            focusRequester = inputFocus,
        )
    }

    if (showHelp) HelpDialog { showHelp = false }
    if (showHistory) {
        HistoryDialog(
            onPick = { id -> showHistory = false; vm.load(id) },
            onNew = { showHistory = false; vm.startNew() },
            onDismiss = { showHistory = false },
        )
    }
}

/* ---------------------------- 지난 상담 ---------------------------- */

/** 상담 화면 간격 (숫자만 바꿔서 조절) */
private object ChatSpace {
    /** 말 사이 */
    val MessageGap = 28.dp
    /** 답을 따라 내려가다 멈추는 자리: 답 첫 줄이 화면 위에서 이만큼 아래 (바로 위 내 말 끝이 조금 보이게) */
    val AnswerTop = 56.dp
    /** AI 답변 행간 (13sp 글씨 기준, 기본 21.7) */
    val AnswerLineHeight = 20.sp
    /** 첫 인사 → 자주 묻는 주제 */
    val StarterTop = 20.dp
    /** 모달 양옆 여백 */
    val DialogSide = 20.dp
}

/**
 * 상담 모달 공통 모양 (일정 '정보 수정' 창과 같은 흰 카드).
 * 위계: 제목(Title3) → 짧은 안내(연한 글씨) → 연회색 목록 카드 → 아래 버튼
 */
@Composable
private fun ChatDialog(
    title: String,
    sub: String?,
    onDismiss: () -> Unit,
    bottom: (@Composable (closeThen: (() -> Unit) -> Unit) -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(closeThen: (() -> Unit) -> Unit) -> Unit,
) {
    // 닫기: 바로 없애지 않고 작아지며 흐려지는 움직임을 먼저 보여 준 뒤 실제로 닫는다 (고른 상담 열기 · 새 상담도 같게)
    var closingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val closeThen: (() -> Unit) -> Unit = { action -> if (closingAction == null) closingAction = action }
    LaunchedEffect(closingAction) {
        val action = closingAction ?: return@LaunchedEffect
        kotlinx.coroutines.delay(com.project.on_road.ui.components.DIALOG_CLOSE_MS)
        action()
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { closeThen(onDismiss) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ChatSpace.DialogSide)
                .then(Modifier.dialogEnter(closing = closingAction != null))
                .clip(OnRoadShapes.Card)
                .background(Color.White)
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).tap(onClickLabel = "닫기") { closeThen(onDismiss) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "닫기", tint = OnRoadColors.TextTertiary, modifier = Modifier.size(18.dp)) }
            }
            sub?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            Spacer(Modifier.height(16.dp))
            content(closeThen)
            bottom?.let {
                Spacer(Modifier.height(16.dp))
                it(closeThen)
            }
        }
    }
}

/** 모달 안 목록 한 줄: 연회색 카드 (앱 목록 카드와 같은 모양) */
@Composable
private fun DialogRow(title: String, sub: String?, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    com.project.on_road.ui.components.OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1)
                sub?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
                }
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
    }
}

@Composable
private fun HistoryDialog(onPick: (Long) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    val chats = remember { ActivityStore.chats() }
    ChatDialog(
        title = "지난 상담",
        sub = if (chats.isEmpty()) "질문하면 이 휴대폰에 대화가 남아요." else "이어서 할 상담을 골라요",
        onDismiss = onDismiss,
        bottom = { closeThen -> OnRoadButton("새 상담 시작", { closeThen(onNew) }, Modifier.fillMaxWidth()) },
    ) { closeThen ->
        if (chats.isNotEmpty()) {
            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chats, key = { it.id }) { c ->
                    DialogRow(
                        c.title,
                        "${dayLabel(c.updatedAt)} · 질문 ${c.questionCount}개" +
                            if (c.topics.isNotEmpty()) " · " + c.topics.joinToString(", ") { it.label } else "",
                    ) { closeThen { onPick(c.id) } }
                }
            }
        }
    }
}

/* ---------------------------- 도움 연락처 ---------------------------- */

@Composable
private fun HelpStrip(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OnRoadColors.SurfaceGuide)
            .tap(onClick = onClick)
            .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Call, null, tint = OnRoadColors.Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("혼자 고민하지 않아도 괜찮아요", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
        Text("도움 연락처", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.Primary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    ChatDialog(title = "도움 연락처", sub = "누르면 전화 화면으로 연결돼요.", onDismiss = onDismiss) { _ ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            helpContacts.forEach { c ->
                DialogRow(
                    c.name,
                    c.description,
                    trailing = { Text(c.number, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary) },
                ) { runCatching { uri.openUri("tel:${c.number}") } }
            }
        }
    }
}

/* ---------------------------- 말풍선 ---------------------------- */

private val BubbleBorder = OnRoadColors.BorderSage.copy(alpha = 0.4f)

@Composable
private fun BotAvatar() {
    // 캐릭터·로고 이미지 자리
    Box(
        Modifier
            .size(24.dp)
            .clip(OnRoadShapes.Chip)
            .background(OnRoadColors.SurfaceMuted),
        contentAlignment = Alignment.Center,
    ) { LogoSlot(size = 18.dp) }
}

private fun planWon(won: Long): String =
    if (won % 10_000 == 0L) "${"%,d".format(won / 10_000)}만 원" else "${"%,d".format(won)}원"

/**
 * 상담 답 아래 '내 조건 기준' 카드. 위계: 라벨(12 회색) → 현금 합계(22 굵게) → 보조(13 회색) → 정책 줄 → 버튼.
 * 정책 줄은 이름(14) 아래에 언제 신청 · 받는 것(12 회색). 돈이 아닌 정책은 받는 내용을 보여 준다
 */
@Composable
private fun PlanCard(
    plan: com.project.on_road.data.ChatPlan,
    onOpenPolicy: (String) -> Unit,
    onWriteDocument: (DocCandidate) -> Unit,
    onOpenSchedule: () -> Unit,
) {
    val goal = com.project.on_road.data.DDate.isGoal(AppContainer.session.status)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Item)
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Text(
            listOfNotNull("내 조건 기준", plan.dDay?.takeIf { it >= 0 }?.let { "퇴소 D-$it" }).joinToString(" · "),
            style = OnRoadType.Caption2,
            color = OnRoadColors.TextTertiary,
        )
        Spacer(Modifier.height(2.dp))
        val big = when {
            // 같은 종류(자립수당 · 정착금)는 하나만 센 최대 금액
            plan.monthlyWon > 0 -> "최대 월 ${planWon(plan.monthlyWon)}"
            plan.onceWon > 0 -> "최대 ${planWon(plan.onceWon)}"
            else -> null
        }
        if (big != null) {
            Text(big, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
            if (plan.monthlyWon > 0 && plan.onceWon > 0) {
                Spacer(Modifier.height(4.dp))
                Text("한 번에 받는 돈 최대 ${planWon(plan.onceWon)}", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
        } else {
            Text("조건이 맞는 정책 ${plan.matchCount}개", style = OnRoadType.Headline, color = OnRoadColors.TextPrimary)
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            plan.items.take(3).forEach { item ->
                val whenText = item.applyFrom?.let { d ->
                    "${d.monthValue}월 ${d.dayOfMonth}일부터" + if ("미리" in item.applyNote) " 미리 신청" else " 신청"
                } ?: "지금 신청"
                Column(Modifier.fillMaxWidth().clip(OnRoadShapes.Chip).tap(onClickLabel = "${item.name} 정책 보기") { onOpenPolicy(item.policyId) }) {
                    Text(item.name, style = OnRoadType.Body3.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
                    Text(
                        listOfNotNull(whenText, item.amountText ?: item.benefit).joinToString(" · "),
                        style = OnRoadType.Caption2,
                        color = OnRoadColors.TextTertiary,
                    )
                }
            }
        }
        val form = plan.items.firstOrNull { it.hasForm }
        if (form != null || !goal) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                form?.let { f ->
                    OnRoadButton(
                        "신청서 쓰기",
                        { onWriteDocument(DocCandidate("${f.name} 신청서", f.policyId.toLongOrNull(), null)) },
                        Modifier.weight(1f),
                        variant = ButtonVariant.Outline,
                        height = 40.dp,
                        textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                        borderColor = OnRoadColors.BorderChip,
                    )
                }
                // 퇴소 후(목표일) 사용자는 일정이 퇴소일 기준이 아니라서 버튼을 두지 않는다
                if (!goal) {
                    OnRoadButton(
                        "일정 보기",
                        onOpenSchedule,
                        Modifier.weight(1f),
                        height = 40.dp,
                        textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(content: @Composable () -> Unit) {
    // AI 말은 상자 없이 글만 (버튼 · 근거는 그 아래에 이어서)
    Column(Modifier.fillMaxWidth()) { content() }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BotMessage(
    msg: ChatMessage,
    time: String,
    starters: List<String>,
    onStarter: (String) -> Unit,
    /** true: 첫 인사 아래 칩 (자주 묻는 주제 + 개인정보 안내), false: 답변 아래 '이어서 찾기' */
    startersFirst: Boolean = true,
    onOpenPolicy: (String) -> Unit,
    onPrepare: (String) -> Unit,
    onAskMore: () -> Unit = {},
    onWriteDocument: (DocCandidate) -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    reveal: Boolean = false,
    onRevealDone: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    // 정책 답변: 정책마다 한 문단 (이름은 짧게 · 굵게) + 출처 한 줄 + '내 조건으로 보면' 행
    val parts = remember(msg.text, msg.policies) {
        if (msg.kind == ChatKind.POLICY && !msg.needsCheck) {
            com.project.on_road.data.remote.AnswerFormat.parts(msg.text, msg.policies.map { it.name })
                .takeIf { it.body.isNotEmpty() }
        } else null
    }
    // 듣기 · 복사는 화면에 보이는 글 그대로 ("[내 조건으로 보면]" 같은 기호 없이)
    val readable = remember(parts, msg.text) { parts?.let(com.project.on_road.data.remote.AnswerFormat::plain) ?: msg.text }
    // 넓은 질문의 '내 조건 요약' 카드가 있으면 같은 내용인 '내 조건으로 보면' 행은 빼고 카드만
    val showMyCheck = parts != null && parts.myCheck.isNotEmpty() && msg.plan == null
    // 위: 이름 · 시간 한 줄 / 아래: 답 (로고 · 상자 · 들여쓰기 없이 화면 여백 선에서 바로)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("On-road", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.width(6.dp))
            Text(time, style = OnRoadType.Micro, color = OnRoadColors.TextQuaternary)
                if (msg.needsCheck) {
                    Spacer(Modifier.width(8.dp))
                    OnRoadTag("확인이 필요해요", style = TagStyle.Danger, textStyle = OnRoadType.Tiny)
                }
            Spacer(Modifier.weight(1f))
            SpeakButton(readable, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(6.dp))
            Bubble {
                if (parts != null) {
                    PolicyAnswer(
                        parts, fullText = readable, reveal = reveal, onRevealDone = onRevealDone,
                        policies = msg.policies, onOpenPolicy = onOpenPolicy, showMyCheck = showMyCheck,
                    )
                } else {
                // 새 답변은 글자가 흘러나오듯 · 길게 누르면 복사
                RevealText(msg.text, animate = reveal, onDone = onRevealDone) { shown ->
                    BubbleText(
                        shown,
                        color = OnRoadColors.TextPrimary,
                        modifier = Modifier.copyOnLongPress(msg.text),
                        style = OnRoadType.Caption.copy(lineHeight = ChatSpace.AnswerLineHeight),
                    )
                }
                }
                if (onRetry != null) {
                    Spacer(Modifier.height(12.dp))
                    OnRoadButton(
                        "다시 보내기",
                        onRetry,
                        height = 36.dp,
                        variant = ButtonVariant.Outline,
                        textStyle = OnRoadType.Caption,
                        borderColor = OnRoadColors.BorderChip,
                    )
                }

                // 넓은 정책 질문: 내 조건 기준 요약 (금액 · 언제 신청)
                msg.plan?.let { plan ->
                    Spacer(Modifier.height(14.dp))
                    PlanCard(plan, onOpenPolicy = onOpenPolicy, onWriteDocument = onWriteDocument, onOpenSchedule = onOpenSchedule)
                }

                // 문서 AI 로 넘기기: 쓸 서류 고르기
                if (msg.kind == ChatKind.DOCUMENT && msg.candidates.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        msg.candidates.forEachIndexed { i, c ->
                            OnRoadButton(
                                text = "${c.title} 쓰기",
                                onClick = { onWriteDocument(c) },
                                modifier = Modifier.fillMaxWidth(),
                                variant = if (i == 0) ButtonVariant.Primary else ButtonVariant.Outline,
                                textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                            )
                        }
                    }
                }

                // 위기 안내: 바로 전화할 수 있는 연락처
                if (msg.kind == ChatKind.CRISIS && msg.contacts.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        msg.contacts.forEach { ContactRow(it) }
                    }
                }

                if (msg.evidence.isNotEmpty()) {
                    // 근거 원문은 접어 두고, 눌러야 펼친다 (글이 너무 길어지지 않게)
                    var openEvidence by remember(msg.id) { mutableStateOf(false) }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.clip(OnRoadShapes.Chip).tap(onClickLabel = "답변 근거 보기") { openEvidence = !openEvidence }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "답변 근거 ${msg.evidence.size}개",
                            style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold),
                            color = OnRoadColors.TextTertiary,
                        )
                        Icon(
                            if (openEvidence) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                            tint = OnRoadColors.TextTertiary, modifier = Modifier.size(18.dp),
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(openEvidence) {
                    Column(Modifier.padding(top = 8.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(OnRoadShapes.Item)
                            .background(OnRoadColors.SurfaceSubtle)
                            .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Item)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        msg.evidence.forEachIndexed { i, ev ->
                            Column {
                                NumberedPoint(i + 1, "“${ev.snippet}”")
                                Text(ev.source, style = OnRoadType.Tiny, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(start = 34.dp, top = 2.dp))
                            }
                        }
                    }
                    }
                    }
                }

                // 답변 근거 정책 → 바로가기 버튼. 비슷한 정책(근거 아님)은 아래에 제목을 따로 달아 묶는다
                val (answeredAll, similar) = msg.policies.partition { !it.related }
                // '내 조건으로 보면' 행이 있는 정책은 행을 누르면 상세로 가므로 버튼을 또 두지 않는다
                // 행과 이어진 정책 (행에서 찾은 정책 번호)
                val inRows = if (parts != null && showMyCheck) {
                    parts.myCheck.mapNotNull { r ->
                        com.project.on_road.data.remote.AnswerFormat.matchPolicy(r.name, msg.policies.map { it.name })
                            .takeIf { it >= 0 }?.let { msg.policies[it].id }
                    }.toSet()
                } else emptySet()
                val answered = answeredAll.filter { it.id !in inRows }
                answered.forEachIndexed { i, p ->
                    Spacer(Modifier.height(if (i == 0) 14.dp else 8.dp))
                    OnRoadButton(
                        text = "${com.project.on_road.data.remote.AnswerFormat.shortName(p.name)} 자세히 보기",
                        onClick = { onOpenPolicy(p.id) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = if (i == 0) ButtonVariant.Primary else ButtonVariant.Outline,
                        textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                        trailingIcon = Icons.Filled.NorthEast,
                    )
                }
                if (similar.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("비슷한 정책", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextTertiary)
                    similar.forEach { p ->
                        Spacer(Modifier.height(8.dp))
                        OnRoadButton(
                            text = "${com.project.on_road.data.remote.AnswerFormat.shortName(p.name)} 자세히 보기",
                            onClick = { onOpenPolicy(p.id) },
                            modifier = Modifier.fillMaxWidth(),
                            variant = ButtonVariant.Outline,
                            textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                            trailingIcon = Icons.Filled.NorthEast,
                        )
                    }
                }
            }

            // 대화에서 바뀐 것 같은 내 정보 → 확인 카드 (승인할 때만 반영)
            if (msg.profileChanges.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ProfileChangeCard(msg.profileChanges, key = "chat-${msg.id}")
            }


            // 체크리스트 제안은 답변 근거 정책으로만 (비슷한 정책뿐이면 묻지 않는다)
            val firstAnswered = msg.policies.firstOrNull { !it.related }
            if (firstAnswered != null) {
                val first = firstAnswered
                Spacer(Modifier.height(12.dp))
                com.project.on_road.ui.components.OnRoadCard(
                    Modifier.fillMaxWidth(),
                    background = OnRoadColors.SurfaceSubtle,
                    borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
                    shape = OnRoadShapes.Item,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        "‘${com.project.on_road.data.remote.AnswerFormat.shortName(first.name)}’ 신청 단계와 서류를 체크리스트로 정리해 드릴까요?",
                        style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight),
                        color = OnRoadColors.TextPrimary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OnRoadButton(
                            "네, 정리해 줘요", { onPrepare(first.id) }, Modifier.weight(1f),
                            height = 34.dp, textStyle = OnRoadType.Caption, shape = OnRoadShapes.Chip,
                        )
                        OnRoadButton(
                            "다른 것도 물어볼래요", onAskMore, Modifier.weight(1f),
                            variant = ButtonVariant.Gray, height = 34.dp, textStyle = OnRoadType.Caption, shape = OnRoadShapes.Chip,
                        )
                    }
                }
            }

            if (starters.isNotEmpty()) {
                Spacer(Modifier.height(ChatSpace.StarterTop))
                // 자주 묻는 주제: 작은 라벨 + 글 길이만큼만 차지하는 둥근 칩 (여러 개가 줄 바꿔 흐른다)
                Text(
                    when {
                        !startersFirst -> "이어서 많이 묻는 질문"
                        AppState.stage == UserStage.YOUNG_ADULT -> "자립준비청년이 많이 헷갈리는 질문"
                        else -> "퇴소 전에 많이 헷갈리는 질문"
                    },
                    style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    starters.forEach { q ->
                        Text(
                            q,
                            style = OnRoadType.Caption,
                            color = OnRoadColors.TextSecondary,
                            modifier = Modifier
                                .clip(OnRoadShapes.Chip)
                                .background(OnRoadColors.SurfaceSubtle)
                                .border(1.dp, OnRoadColors.Border, OnRoadShapes.Chip)
                                .tap { onStarter(q) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 정책 답변 본문.
 *   문단: 정책마다 한 덩어리 ('서리풀 생활보조수당 지원'은 굵게), 문단 사이 띄움
 *   출처: "출처: 정부24" 연한 한 줄
 *   내 조건으로 보면: 회색 카드, 정책마다 굵은 이름 + 짧은 안내 줄 (꼬리표 없이)
 * 글이 흘러나오는 동안은 문단만, 다 나오면 아래 조각이 나타난다
 */
@Composable
private fun PolicyAnswer(
    parts: com.project.on_road.data.remote.AnswerFormat.Parts,
    fullText: String,
    reveal: Boolean,
    onRevealDone: () -> Unit,
    policies: List<com.project.on_road.data.Policy>,
    onOpenPolicy: (String) -> Unit,
    showMyCheck: Boolean = true,
) {
    val body = remember(parts) {
        parts.body.joinToString("\n\n") { p ->
            // 문단 첫머리의 정책 이름은 굵게 (BubbleText 는 '…' 로 감싼 말을 굵게 쓴다)
            val name = parts.names.firstOrNull { p.startsWith(it) }
            if (name != null) "'$name'" + p.removePrefix(name) else p
        }
    }
    RevealText(body, animate = reveal, onDone = onRevealDone) { shown ->
        Column(Modifier.copyOnLongPress(fullText), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.split("\n\n").filter { it.isNotBlank() }.forEach { p ->
                BubbleText(
                    p,
                    color = OnRoadColors.TextPrimary,
                    style = OnRoadType.Caption.copy(lineHeight = ChatSpace.AnswerLineHeight),
                    boldQuotes = true,
                )
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = !reveal,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
    ) {
        Column {
            Spacer(Modifier.height(10.dp))
            if (parts.sources.isNotEmpty()) {
                Text("출처: " + parts.sources.joinToString(", "), style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
            }
            parts.notice?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = OnRoadType.Tiny, color = OnRoadColors.TextQuaternary)
            }
            if (showMyCheck) {
                Spacer(Modifier.height(14.dp))
                MyCheckList(parts.myCheck, policies, onOpenPolicy)
            }
        }
    }
}

/**
 * '내 조건으로 보면': 작은 회색 제목 + 정책마다 회색 행 (굵은 이름, 한 줄 요약, 오른쪽 ›).
 * 행을 누르면 그 정책 상세로 간다 ('자세히 보기' 버튼 대신)
 */
@Composable
private fun MyCheckList(
    rows: List<com.project.on_road.data.remote.AnswerFormat.MyCheckRow>,
    policies: List<com.project.on_road.data.Policy>,
    onOpenPolicy: (String) -> Unit,
) {
    Text("내 조건으로 보면", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextTertiary)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { r ->
            val policy = com.project.on_road.data.remote.AnswerFormat.matchPolicy(r.name, policies.map { it.name })
                .takeIf { it >= 0 }?.let { policies[it] }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Item)
                    .background(OnRoadColors.SurfaceSubtle)
                    .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Item)
                    .tap(enabled = policy != null, onClickLabel = "${r.name} 자세히 보기") { policy?.let { onOpenPolicy(it.id) } }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    // 해당: … / 확인 필요: … (값이 여럿이면 머리말 뒤 같은 자리에 한 줄씩 맞춰서)
                    com.project.on_road.data.remote.AnswerFormat.summary(r).forEachIndexed { i, line ->
                        if (i == 0) Spacer(Modifier.height(4.dp)) // 해당 · 확인 필요 줄 사이는 띄우지 않는다
                        val color = if (line.label == "확인 필요") OnRoadColors.TextTertiary else OnRoadColors.TextSecondary
                        Row {
                            line.label?.let {
                                Text("$it:", style = OnRoadType.Caption2, color = color)
                                Spacer(Modifier.width(6.dp))
                            }
                            Column {
                                line.values.forEach { v -> Text(v, style = OnRoadType.Caption2, color = color) }
                            }
                        }
                    }
                }
                if (policy != null) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * 개인정보 안내 상자 (대화 내용은 서버를 거쳐 외부 AI 로 간다. 서버가 번호 · 주소를 가리지만 다 잡지는 못한다).
 * 한 줄 요약 + 작은 설명, 오른쪽 X 로 닫기
 */
@Composable
private fun PrivacyNotice(onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, top = 12.dp)
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Item)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "전화번호 · 주소 · 계좌번호는 적지 말아 주세요",
                style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextSecondary,
            )
            Text("질문은 답을 만들기 위해 AI 서비스로 전달돼요", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
        }
        Box(
            Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).tap(onClickLabel = "안내 닫기", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, "안내 닫기", tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

/** 위기 안내 연락처 한 줄. 누르면 전화 화면으로 */
@Composable
private fun ContactRow(line: HelpLine) {
    val uri = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Inner)
            .tap { runCatching { uri.openUri("tel:${line.phone}") } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.name, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(line.phone, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
    }
}

@Composable
private fun TypingBubble(status: String?) {
    // 지금 하는 일 (점 없이 글만): 글이 천천히 숨 쉬듯 옅어졌다 진해지고,
    // 단계가 바뀌면 위로 살짝 밀려 올라가며 바뀐다 ("관련 정책을 찾는 중" → "정책 원문을 읽는 중" …)
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "chat-status-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween<Float>(900),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "chat-status-alpha",
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.animation.AnimatedContent(
            targetState = status ?: "답을 정리하는 중",
            transitionSpec = {
                val enter = androidx.compose.animation.slideInVertically { it / 2 } + androidx.compose.animation.fadeIn()
                val exit = androidx.compose.animation.slideOutVertically { -it / 2 } + androidx.compose.animation.fadeOut()
                enter togetherWith exit
            },
            label = "chat-status",
        ) { text ->
            Text(text, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary.copy(alpha = alpha))
        }
    }
}

@Composable
private fun UserMessage(text: String, time: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        // 내 말: 메인 색 말풍선 · 흰 글씨 (서류 작성 · 실전 연습과 같게), 시간은 아래 작게
        Box(
            Modifier
                .widthIn(max = 280.dp)
                .clip(OnRoadShapes.Inner)
                .background(OnRoadColors.Primary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BubbleText(text, color = Color.White, modifier = Modifier.copyOnLongPress(text))
        }
        if (time.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(time, style = OnRoadType.Micro, color = OnRoadColors.TextQuaternary)
        }
    }
}

/* ---------------------------- 입력창 ---------------------------- */

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onVoice: (String) -> Unit,
    sending: Boolean = false,
    onStop: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val listen = rememberSpeechInput(onVoice)
    val active = enabled && value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, top = 8.dp, bottom = 12.dp),
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
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                maxLines = 4,
                textStyle = OnRoadType.Body2.copy(color = OnRoadColors.TextPrimary),
                cursorBrush = SolidColor(OnRoadColors.Primary),
                modifier = Modifier.weight(1f).let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m },
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("궁금한 걸 물어보세요", style = OnRoadType.Body2, color = OnRoadColors.TextQuaternary)
                    inner()
                },
            )
            if (AppState.voiceOn) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Mic, "말로 입력", tint = OnRoadColors.TextTertiary,
                    modifier = Modifier.size(20.dp).tap(onClick = listen),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 보내기 ↔ 멈추기: 기다리는 동안은 같은 자리가 멈추기 버튼이 된다
        val on = active || sending
        val bg by animateColorAsState(if (on) OnRoadColors.PrimaryDeep else OnRoadColors.SurfaceMuted, OnRoadMotion.move(OnRoadMotion.FAST), label = "send-bg")
        Box(
            Modifier
                .size(OnRoadDimens.FieldHeight)
                .clip(OnRoadShapes.Field)
                .background(bg)
                .tap(enabled = on, haptic = sending, onClick = if (sending) onStop else onSend),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.Crossfade(sending, label = "send-icon") { s ->
                if (s) Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Color.White).semantics { contentDescription = "답변 멈추기" })
                else Icon(Icons.Filled.ArrowUpward, "보내기", tint = if (active) Color.White else OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp))
            }
        }
    }
}