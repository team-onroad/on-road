package com.project.on_road.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.outlined.Call
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.ChatMessage
import com.project.on_road.data.ChatRole
import com.project.on_road.ui.components.ButtonVariant
import com.project.on_road.ui.components.LogoSlot
import com.project.on_road.ui.components.NumberedPoint
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

private val starters = listOf(
    "퇴소하면 매달 받을 수 있는 돈이 있어?",
    "혼자 살 집은 어떻게 구해?",
    "취업 준비는 뭐부터 해야 할까?",
    "요즘 앞으로가 너무 막막해",
)

private fun nowLabel(): String = SimpleDateFormat("a h:mm", Locale.KOREAN).format(Date())

class ChatViewModel : ViewModel() {
    private var nextId = 1L
    val messages = mutableStateListOf(
        ChatMessage(
            0L, ChatRole.ASSISTANT,
            "안녕하세요, 온로드 AI 상담이에요.\n자립 준비, 진로, 지원 제도처럼 궁금한 것을 편하게 물어보세요.",
        ),
    )
    /** 메시지 id → 보낸 시각 */
    val times = mutableStateMapOf(0L to nowLabel())
    var input by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    private var askedInitial = false

    fun askInitial(question: String?) {
        if (askedInitial || question.isNullOrBlank()) return
        askedInitial = true
        send(question)
    }

    fun send(text: String = input) {
        val t = text.trim()
        if (t.isEmpty() || sending) return
        val userMsg = ChatMessage(nextId++, ChatRole.USER, t)
        messages.add(userMsg)
        times[userMsg.id] = nowLabel()
        input = ""
        viewModelScope.launch {
            sending = true
            val reply = runCatching {
                AppContainer.chatApi.reply(AppContainer.session.userId.orEmpty(), messages.toList(), t)
            }.getOrElse {
                ChatMessage(0L, ChatRole.ASSISTANT, "답변을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", needsCheck = true)
            }
            val msg = reply.copy(id = nextId++)
            messages.add(msg)
            times[msg.id] = nowLabel()
            sending = false
        }
    }
}

/** AI 상담 (디자인: 상담) */
@Composable
fun ChatScreen(
    initialQuestion: String? = null,
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    onPrepare: (String) -> Unit = {},
    vm: ChatViewModel = viewModel(),
) {
    LaunchedEffect(initialQuestion) { vm.askInitial(initialQuestion) }
    val listState = rememberLazyListState()
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(vm.messages.size, vm.sending) {
        // 마지막 항목(안내 문구) 위치 = 메시지 수 + 입력 중 표시
        listState.animateScrollToItem(vm.messages.size + if (vm.sending) 1 else 0)
    }

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(title = "상담", onBack = onBack)
        HelpStrip { showHelp = true }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = OnRoadDimens.ScreenPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            items(vm.messages, key = { it.id }) { msg ->
                val time = vm.times[msg.id].orEmpty()
                if (msg.role == ChatRole.USER) {
                    UserMessage(msg.text, time)
                } else {
                    BotMessage(
                        msg = msg,
                        time = time,
                        starters = if (msg.id == 0L && vm.messages.size == 1) starters else emptyList(),
                        onStarter = { vm.send(it) },
                        onOpenPolicy = onOpenPolicy,
                        onPrepare = onPrepare,
                    )
                }
            }
            if (vm.sending) item(key = "typing") { TypingBubble() }
            item(key = "footer") {
                Text(
                    "AI 답변은 참고용이에요. 금액과 자격은 기관에서 확인해 주세요.",
                    style = OnRoadType.Tiny,
                    color = OnRoadColors.TextQuaternary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.BorderInput)
        ChatInputBar(
            value = vm.input,
            onValueChange = { vm.input = it },
            enabled = !vm.sending,
            onSend = { vm.send() },
            onVoice = { vm.input = (vm.input + " " + it).trim() },
        )
    }

    if (showHelp) HelpDialog { showHelp = false }
}

/* ---------------------------- 도움 연락처 ---------------------------- */

@Composable
private fun HelpStrip(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OnRoadColors.SurfaceGuide)
            .clickable(onClick = onClick)
            .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Call, null, tint = OnRoadColors.Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("혼자 고민하지 않아도 괜찮아요", style = OnRoadType.Micro, color = OnRoadColors.TextSlate, modifier = Modifier.weight(1f))
        Text("도움 연락처", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.Primary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text("도움 연락처", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("누르면 전화 화면으로 연결돼요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                Spacer(Modifier.height(4.dp))
                helpContacts.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(OnRoadShapes.Inner)
                            .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Inner)
                            .clickable { runCatching { uri.openUri("tel:${c.number}") } }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                            Text(c.description, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                        }
                        Text(c.number, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSlate) }
        },
    )
}

/* ---------------------------- 말풍선 ---------------------------- */

private val BubbleBorder = OnRoadColors.BorderSage.copy(alpha = 0.4f)

@Composable
private fun BotAvatar() {
    // 캐릭터·로고 이미지 자리
    Box(
        Modifier
            .size(32.dp)
            .clip(OnRoadShapes.Field)
            .background(OnRoadColors.SurfaceMuted)
            .border(1.dp, BubbleBorder, OnRoadShapes.Field),
        contentAlignment = Alignment.Center,
    ) { LogoSlot(size = 24.dp) }
}

@Composable
private fun Bubble(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(Color.White)
            .border(1.dp, BubbleBorder, OnRoadShapes.Inner)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun BotMessage(
    msg: ChatMessage,
    time: String,
    starters: List<String>,
    onStarter: (String) -> Unit,
    onOpenPolicy: (String) -> Unit,
    onPrepare: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        BotAvatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On-road", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.width(4.dp))
                Text(time, style = OnRoadType.Tiny, color = OnRoadColors.TextSlate)
                if (msg.needsCheck) {
                    Spacer(Modifier.width(8.dp))
                    OnRoadTag("확인이 필요해요", style = TagStyle.Danger, textStyle = OnRoadType.Tiny)
                }
                Spacer(Modifier.weight(1f))
                SpeakButton(msg.text, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(8.dp))
            Bubble {
                Text(msg.text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextPrimary)

                if (msg.evidence.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(thickness = 1.dp, color = OnRoadColors.BorderInput)
                    Spacer(Modifier.height(16.dp))
                    Text("[답변 근거]", style = OnRoadType.Caption, color = OnRoadColors.TextPrimary)
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(OnRoadShapes.Chip)
                            .background(OnRoadColors.SurfaceMuted)
                            .border(1.dp, BubbleBorder, OnRoadShapes.Chip)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        msg.evidence.forEachIndexed { i, ev ->
                            Column {
                                NumberedPoint(i + 1, "“${ev.snippet}”")
                                Text(ev.source, style = OnRoadType.Tiny, color = OnRoadColors.TextSlateMid, modifier = Modifier.padding(start = 34.dp, top = 2.dp))
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

            if (msg.policies.isNotEmpty()) {
                val first = msg.policies.first()
                Spacer(Modifier.height(8.dp))
                Bubble {
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
                            "다른 것도 물어볼래요", { onStarter("다른 지원 제도도 알려줘") }, Modifier.weight(1f),
                            variant = ButtonVariant.Gray, height = 34.dp, textStyle = OnRoadType.Caption, shape = OnRoadShapes.Chip,
                        )
                    }
                }
            }

            if (starters.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("* 청년들이 자주 묻는 주제", style = OnRoadType.Caption, color = OnRoadColors.TextSlate)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    starters.forEach { SuggestionChip(it) { onStarter(it) } }
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth()) {
        BotAvatar()
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .clip(OnRoadShapes.Inner)
                .background(Color.White)
                .border(1.dp, BubbleBorder, OnRoadShapes.Inner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("답변을 준비하고 있어요…", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
    }
}

@Composable
private fun UserMessage(text: String, time: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = OnRoadType.Tiny, color = OnRoadColors.TextSlate)
            Spacer(Modifier.width(6.dp))
            Text("나", style = OnRoadType.Body2, color = OnRoadColors.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .widthIn(max = 260.dp)
                .clip(OnRoadShapes.Inner)
                .background(Color.White)
                .border(1.dp, OnRoadColors.Primary, OnRoadShapes.Inner)
                .padding(horizontal = 15.dp, vertical = 12.dp),
        ) {
            Text(text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.Primary)
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
) {
    val listen = rememberSpeechInput(onVoice)
    val active = enabled && value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
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
                    if (value.isEmpty()) Text("입력하세요..", style = OnRoadType.Caption, color = OnRoadColors.TextSlate)
                    inner()
                },
            )
            if (AppState.voiceOn) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Mic, "말로 입력", tint = OnRoadColors.TextSlate,
                    modifier = Modifier.size(20.dp).clickable(onClick = listen),
                )
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
