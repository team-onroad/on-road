package com.project.on_road.ui.screens

import com.project.on_road.ui.theme.OnRoadMotion

import com.project.on_road.ui.components.copyOnLongPress

import com.project.on_road.ui.components.RevealText

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.animation.animateColorAsState


import com.project.on_road.ui.components.tap
import com.project.on_road.ui.components.reveal
import com.project.on_road.ui.components.TypingDots

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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** 단계별 첫 질문 예시 */
private fun startersFor(stage: UserStage): List<String> = when (stage) {
    UserStage.YOUNG_ADULT -> listOf(
        "퇴소하면 매달 받을 수 있는 돈이 있어?",
        "혼자 살 집은 어떻게 구해?",
        "취업 준비는 뭐부터 해야 할까?",
        "요즘 앞으로가 너무 막막해",
    )
    else -> listOf(
        "받을 수 있는 장학금이 있어?",
        "진로를 어떻게 정해야 할지 모르겠어",
        "아르바이트할 때 알아야 할 게 있어?",
        "요즘 친구 관계가 힘들어",
    )
}

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
            var failed = false
            val reply = runCatching {
                AppContainer.chatApi.reply(AppContainer.session.userId.orEmpty(), messages.toList(), t)
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                failed = true
                ChatMessage(0L, ChatRole.ASSISTANT, "답변을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", needsCheck = true, kind = ChatKind.LOCAL)
            }
            val msg = reply.copy(id = nextId++)
            messages.add(msg)
            times[msg.id] = nowLabel()
            if (failed) retryOf[msg.id] = t else revealId = msg.id
            sending = false
            persist()
        }
    }

    /** 기다리는 중 멈추기 */
    fun stop() {
        if (!sending) return
        job?.cancel()
        sending = false
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
    LaunchedEffect(initialQuestion) { vm.askInitial(initialQuestion) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    var showHelp by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(vm.messages.size, vm.sending) {
        // 마지막 항목(안내 문구) 위치 = 메시지 수 + 입력 중 표시
        listState.animateScrollToItem(vm.messages.size + if (vm.sending) 1 else 0)
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
                        starters = if (msg.id == 0L && vm.messages.size == 1) startersFor(AppState.stage) else emptyList(),
                        onStarter = { vm.send(it) },
                        onOpenPolicy = onOpenPolicy,
                        onPrepare = onPrepare,
                        onAskMore = { runCatching { inputFocus.requestFocus() } },
                        onWriteDocument = { onWriteDocument(it, msg.handoffContext) },
                        reveal = msg.id == vm.revealId,
                        onRevealDone = { if (vm.revealId == msg.id) vm.revealId = null },
                        onRetry = if (msg.id in vm.retryOf) ({ vm.retry(msg.id) }) else null,
                    )
                }
                }
            }
            if (vm.sending) item(key = "typing") { Box(Modifier.animateItem().reveal()) { TypingBubble() } }
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
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ChatSpace.DialogSide)
                .clip(OnRoadShapes.Card)
                .background(Color.White)
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).tap(onClickLabel = "닫기", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "닫기", tint = OnRoadColors.TextTertiary, modifier = Modifier.size(18.dp)) }
            }
            sub?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            Spacer(Modifier.height(16.dp))
            content()
            bottom?.let {
                Spacer(Modifier.height(16.dp))
                it()
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
        bottom = { OnRoadButton("새 상담 시작", onNew, Modifier.fillMaxWidth()) },
    ) {
        if (chats.isNotEmpty()) {
            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chats, key = { it.id }) { c ->
                    DialogRow(
                        c.title,
                        "${dayLabel(c.updatedAt)} · 질문 ${c.questionCount}개" +
                            if (c.topics.isNotEmpty()) " · " + c.topics.joinToString(", ") { it.label } else "",
                    ) { onPick(c.id) }
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
    ChatDialog(title = "도움 연락처", sub = "누르면 전화 화면으로 연결돼요.", onDismiss = onDismiss) {
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
    onOpenPolicy: (String) -> Unit,
    onPrepare: (String) -> Unit,
    onAskMore: () -> Unit = {},
    onWriteDocument: (DocCandidate) -> Unit = {},
    reveal: Boolean = false,
    onRevealDone: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
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
            SpeakButton(msg.text, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(6.dp))
            Bubble {
                // 새 답변은 글자가 흘러나오듯 · 길게 누르면 복사
                RevealText(msg.text, animate = reveal, onDone = onRevealDone) { shown ->
                    BubbleText(
                        shown,
                        color = OnRoadColors.TextPrimary,
                        modifier = Modifier.copyOnLongPress(msg.text),
                        style = OnRoadType.Caption.copy(lineHeight = ChatSpace.AnswerLineHeight),
                    )
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
                    Spacer(Modifier.height(16.dp))
                    Text("답변 근거", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextTertiary)
                    Spacer(Modifier.height(8.dp))
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

                msg.policies.forEachIndexed { i, p ->
                    Spacer(Modifier.height(if (i == 0) 12.dp else 8.dp))
                    OnRoadButton(
                        text = "${p.name} 자세히 보기",
                        onClick = { onOpenPolicy(p.id) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = if (i == 0) ButtonVariant.Primary else ButtonVariant.Outline,
                        textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                        trailingIcon = Icons.Filled.NorthEast,
                    )
                }
            }

            // 대화에서 바뀐 것 같은 내 정보 → 확인 카드 (승인할 때만 반영)
            if (msg.profileChanges.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ProfileChangeCard(msg.profileChanges, key = "chat-${msg.id}")
            }


            if (msg.policies.isNotEmpty()) {
                val first = msg.policies.first()
                Spacer(Modifier.height(12.dp))
                com.project.on_road.ui.components.OnRoadCard(
                    Modifier.fillMaxWidth(),
                    background = OnRoadColors.SurfaceSubtle,
                    borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
                    shape = OnRoadShapes.Item,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        "‘${first.name}’ 신청에 필요한 단계와 서류를 체크리스트로 정리해 드릴까요?",
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
                    if (AppState.stage == UserStage.YOUNG_ADULT) "청년들이 자주 묻는 주제" else "청소년들이 자주 묻는 주제",
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
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TypingDots()
        Spacer(Modifier.width(10.dp))
        Text("답변을 준비하고 있어요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
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