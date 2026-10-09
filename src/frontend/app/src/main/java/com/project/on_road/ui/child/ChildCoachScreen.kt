package com.project.on_road.ui.child

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.project.on_road.ui.components.SpeakOnce

/*
 * 아동 전용 상담하기 — 시안 D
 * 코치 말(흰 말풍선 + 손그림 테두리) / 내 말(대표색 말풍선)
 * → "더 쉽게 말해 줘" → 눌러서 대답하기(파스텔 2×2) → 눌러서 말하기(대표색)
 *
 * - 대화는 정해진 흐름(아래 CoachScript)으로 진행한다. AI 연결 전 단계.
 * - "눌러서 말하기"는 휴대폰 음성 인식을 연다. 말한 내용은 내 말풍선으로 들어간다.
 * - 위험한 말(때려, 무서워 등)이 들리면 일반 대화를 멈추고 도움 안내로 간다.
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidCoach {
    val BubbleMaxWidth = 270.dp
    // 이런 말이 들어 있으면 도움 안내로 바로 간다
    val DangerWords = listOf("때리", "때려", "맞았", "맞아서", "죽", "다쳤", "다치", "무서", "만졌", "만져", "혼자 있", "도와줘", "살려")
    // 대답 버튼 색 (차례로)
    val ChipTones = listOf(Tones.Blue, Tones.Pink, Tones.Purple, Tones.Yellow)
}
/* ====================================================== */

/** 대답 버튼을 누르면 할 일 */
private sealed interface CoachGo {
    data class Node(val id: String) : CoachGo
    object Help : CoachGo
    object Find : CoachGo
}

private data class CoachReply(val label: String, val go: CoachGo)

/** 코치 말 한 마디: 기본 말 + 더 쉬운 말 + 대답 버튼 */
private data class CoachNode(val text: String, val easy: String, val replies: List<CoachReply>)

private fun n(id: String) = CoachGo.Node(id)
private val home = CoachReply("처음으로", n("again"))
private val helpReply = CoachReply("도움이 필요해", CoachGo.Help)

private val CoachScript: Map<String, CoachNode> = mapOf(
    "start" to CoachNode(
        "오늘은 어떤 기분이야?", "지금 마음이 어때?",
        listOf(CoachReply("기분이 좋아!", n("good")), CoachReply("조금 속상해", n("sad")), CoachReply("잘 모르겠어", n("unsure")), CoachReply("다른 이야기 할래", n("other"))),
    ),
    "again" to CoachNode(
        "또 어떤 이야기를 해 볼까?", "무슨 이야기 할까?",
        listOf(CoachReply("기분이 좋아!", n("good")), CoachReply("조금 속상해", n("sad")), CoachReply("잘 모르겠어", n("unsure")), CoachReply("다른 이야기 할래", n("other"))),
    ),
    "sad" to CoachNode(
        "그랬구나. 속상한 날도 있어.\n무슨 일이 있었는지 말해 줄래?", "속상했구나.\n무슨 일이 있었어?",
        listOf(CoachReply("친구랑 다퉜어", n("friend")), CoachReply("숙제가 어려워", n("homework")), CoachReply("말하기 싫어", n("nottalk")), CoachReply("다른 이야기 할래", n("other"))),
    ),
    "friend" to CoachNode(
        "친구랑 다투면 마음이 무거워.\n어떻게 하고 싶어?", "친구랑 싸워서 속상하구나.\n어떻게 하고 싶어?",
        listOf(CoachReply("화해하고 싶어", n("makeup")), CoachReply("아직 화가 나", n("angry")), CoachReply("선생님께 말할래", n("teacher")), home),
    ),
    "makeup" to CoachNode(
        "좋은 생각이야!\n\"아까 미안해\" 한마디로 시작해 봐도 좋아.", "\"미안해\"라고\n먼저 말해 볼까?",
        listOf(CoachReply("해 볼게", n("cheer")), CoachReply("무서워", n("scared")), home),
    ),
    "angry" to CoachNode(
        "화가 나는 건 당연해.\n숨을 크게 세 번 쉬어 볼까?", "화가 나도 괜찮아.\n크게 숨 쉬어 보자.",
        listOf(CoachReply("했어", n("cheer")), CoachReply("그래도 화나", n("teacher")), home),
    ),
    "teacher" to CoachNode(
        "믿을 수 있는 어른에게 말하는 건\n아주 용기 있는 일이야.", "선생님께 말하는 건\n멋진 일이야.",
        listOf(helpReply, home),
    ),
    "homework" to CoachNode(
        "어려운 건 누구나 있어.\n어떤 게 제일 어려워?", "숙제 중에\n뭐가 어려워?",
        listOf(CoachReply("수학", n("hwtip")), CoachReply("글쓰기", n("hwtip")), CoachReply("너무 많아", n("hwtip")), home),
    ),
    "hwtip" to CoachNode(
        "조금씩 나눠서 해 보자.\n하나 끝내면 쉬어도 돼.\n모르는 건 선생님께 물어봐도 좋아.", "하나씩 해 보자.\n모르면 물어봐도 돼.",
        listOf(CoachReply("해 볼게", n("cheer")), home),
    ),
    "nottalk" to CoachNode(
        "괜찮아. 말하고 싶을 때 언제든 와 줘.\n나는 여기 있을게.", "괜찮아.\n나중에 말해도 돼.",
        listOf(home, helpReply),
    ),
    "good" to CoachNode(
        "와, 좋다!\n무슨 좋은 일이 있었어?", "기분 좋구나!\n무슨 일이야?",
        listOf(CoachReply("칭찬받았어", n("happy")), CoachReply("친구랑 놀았어", n("happy")), CoachReply("맛있는 거 먹었어", n("happy")), home),
    ),
    "happy" to CoachNode(
        "정말 좋았겠다!\n오늘 기분을 내 스티커에 남겨 봐도 좋아.", "좋았겠다!\n스티커로 남겨 볼까?",
        listOf(home),
    ),
    "unsure" to CoachNode(
        "그럴 수 있어.\n지금 몸은 어때?", "괜찮아.\n몸은 어때?",
        listOf(CoachReply("피곤해", n("tired")), CoachReply("괜찮아", n("again")), CoachReply("무서워", n("scared")), home),
    ),
    "tired" to CoachNode(
        "피곤한 날엔 조금 쉬어도 괜찮아.\n물 한 잔 마셔 볼까?", "쉬어도 돼.\n물 한 잔 마시자.",
        listOf(home),
    ),
    "scared" to CoachNode(
        "무서운 마음이 들 수 있어.\n믿을 수 있는 어른이랑 같이 이야기해도 괜찮아.", "무서우면\n어른에게 말해 줘.",
        listOf(helpReply, home),
    ),
    "cheer" to CoachNode("멋져!\n너는 잘할 수 있어.", "잘할 수 있어!", listOf(home)),
    "other" to CoachNode(
        "좋아! 무슨 이야기 하고 싶어?", "무슨 이야기 할까?",
        listOf(CoachReply("좋아하는 거", CoachGo.Find), CoachReply("꿈 이야기", n("dream")), home),
    ),
    "dream" to CoachNode(
        "나중에 해 보고 싶은 일이 있어?\n'좋아하는 거 찾기'에서 같이 찾아볼 수 있어.", "하고 싶은 일을\n같이 찾아볼까?",
        listOf(CoachReply("찾으러 가기", CoachGo.Find), home),
    ),
    // 말하기로 대답했을 때
    "heard" to CoachNode(
        "말해 줘서 고마워.\n조금 더 이야기해 줄래?", "고마워.\n더 말해 줄래?",
        listOf(CoachReply("기분이 좋아!", n("good")), CoachReply("조금 속상해", n("sad")), CoachReply("잘 모르겠어", n("unsure")), home),
    ),
    "danger" to CoachNode(
        "말해 줘서 정말 고마워.\n많이 힘들었겠다.\n지금 바로 믿을 수 있는 어른에게 알려 줘.", "말해 줘서 고마워.\n어른에게 꼭 알려 줘.",
        listOf(helpReply, home),
    ),
    "nomic" to CoachNode(
        "지금은 말하기를 쓸 수 없어.\n아래 버튼을 눌러서 대답해 줘.", "아래 버튼을 눌러 줘.",
        listOf(CoachReply("기분이 좋아!", n("good")), CoachReply("조금 속상해", n("sad")), CoachReply("잘 모르겠어", n("unsure")), CoachReply("다른 이야기 할래", n("other"))),
    ),
)

/** 화면에 쌓이는 말 */
private data class CoachLine(val fromCoach: Boolean, val text: String, val nodeId: String? = null, val easy: Boolean = false)

@Composable
fun ChildCoachScreen(onBack: () -> Unit, onFind: () -> Unit) {
    val lines = remember { mutableStateListOf(CoachLine(true, CoachScript.getValue("start").text, "start")) }
    var current by remember { mutableStateOf("start") }
    var showHelp by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun coach(id: String) {
        current = id
        lines += CoachLine(true, CoachScript.getValue(id).text, id)
    }

    fun answer(r: CoachReply) {
        lines += CoachLine(false, r.label)
        when (val go = r.go) {
            is CoachGo.Node -> coach(go.id)
            CoachGo.Help -> { coach("teacher"); showHelp = true }
            CoachGo.Find -> onFind()
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) {
            lines += CoachLine(false, said)
            coach(if (KidCoach.DangerWords.any { it in said }) "danger" else "heard")
        }
    }

    // 새 말이 생기면 맨 아래로
    LaunchedEffect(lines.size) { listState.animateScrollToItem(lines.lastIndex) }

    // 코치의 마지막 말을 읽어 준다
    val lastCoach = lines.last { it.fromCoach }
    SpeakOnce(lastCoach.text.replace("\n", " "))

    Column(Modifier.fillMaxSize().background(RoadKit.Background)) {
        RoadSubHeader("상담하기", Tones.Blue.bg, onBack = onBack, onHelp = { showHelp = true })

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                if (line.fromCoach) {
                    val isLast = i == lines.lastIndex
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CoachBubble(line.text, seed = if (i % 2 == 0) 7 else 21)
                        // "더 쉽게 말해 줘"는 마지막 코치 말에만, 한 번만
                        if (isLast && !line.easy && line.nodeId != null) {
                            Box(
                                Modifier
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(19.dp))
                                    .background(RoadKit.Chip)
                                    .clickable {
                                        lines += CoachLine(true, CoachScript.getValue(line.nodeId).easy, line.nodeId, easy = true)
                                    }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("더 쉽게 말해 줘", style = hanaText(18.sp, lineHeight = 1.2f), color = RoadKit.Ink) }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(
                            Modifier
                                .widthIn(max = 240.dp)
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp))
                                .background(RoadKit.Accent)
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                        ) { Text(line.text, style = hanaText(22.sp, lineHeight = 1.35f), color = Color.White) }
                    }
                }
            }
        }

        // 대답 고르기
        val replies = CoachScript.getValue(current).replies
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
            Text("눌러서 대답해 봐", style = hanaText(20.sp, FontWeight.Normal, 1.3f), color = RoadKit.InkSub, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(8.dp))
            replies.chunked(2).forEachIndexed { row, pair ->
                if (row > 0) Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { col, r ->
                        val idx = row * 2 + col
                        val tone = KidCoach.ChipTones[idx % KidCoach.ChipTones.size]
                        Box(
                            Modifier
                                .weight(1f)
                                .height(60.dp)
                                .handOutline(18.dp, seed = if (idx % 3 == 0) 7 else 21)
                                .clip(RoundedCornerShape(18.dp))
                                .background(tone.bg)
                                .clickable { answer(r) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(r.label, style = hanaText(21.sp, lineHeight = 1.2f), color = RoadKit.Ink, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // 말로 대답
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(RoadKit.Accent)
                    .clickable {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "말해 봐")
                        }
                        runCatching { speech.launch(intent) }.onFailure { coach("nomic") }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("눌러서 말하기", style = hanaText(23.sp, lineHeight = 1.2f), color = Color.White) }
        }
    }

    if (showHelp) {
        Dialog(onDismissRequest = { showHelp = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ChildHelpScreen(onBack = { showHelp = false })
        }
    }
}

@Composable
private fun CoachBubble(text: String, seed: Int) {
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    Box(
        Modifier
            .widthIn(max = KidCoach.BubbleMaxWidth)
            .handOutline(20.dp, seed, topStart = 6.dp)
            .clip(shape)
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { Text(text, style = hanaText(22.sp, lineHeight = 1.4f), color = RoadKit.Ink) }
}