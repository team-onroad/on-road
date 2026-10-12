package com.project.on_road.ui.child

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.project.on_road.R
import com.project.on_road.data.AppContainer
import com.project.on_road.ui.components.SpeakOnce
import com.project.on_road.ui.navigation.Routes
import java.time.LocalDate

/*
 * 아동 홈 (만 10세 이하) — 시안 D · 길
 * 상단바(로고 · 아동 · ?) → 인사 → 점선 길을 따라
 * 오늘의 미션 → 상담하기 → 좋아하는 거 찾기 → 내 스티커 → 스티커 가게 → 도움이 필요해
 * - 공통 색·글씨·손그림 테두리는 ChildRoadKit.kt
 * - 그림 자리는 점선. 그림을 넣으면 KidHome 의 R.drawable 로 바꾸기
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidHome {
    // 그림: res/drawable 에 넣고 R.drawable.파일이름. null 이면 점선 자리
    @DrawableRes val ImgMission: Int? = null                     // 오늘의 미션 왼쪽 위 작은 스티커
    @DrawableRes val ImgCounsel: Int? = R.drawable.img_counsel
    @DrawableRes val ImgFind: Int? = R.drawable.img_find
    @DrawableRes val ImgSticker: Int? = R.drawable.img_sticker
    @DrawableRes val ImgShop: Int? = R.drawable.img_shop
    @DrawableRes val ImgHelp: Int? = R.drawable.img_help_call

    val MissionSlot = Color(0xFFB99A3E)   // 미션 스티커 자리 점선

    val SideMargin = 10.dp                // 화면 좌우 여백
    val StopSize = 68.dp                  // 길 위 동그라미 크기
    val StopImage = 48.dp                 // 동그라미 안 그림 크기
    val StopInset = 20.dp                 // 동그라미가 화면 끝에서 떨어진 거리
}
/* ====================================================== */

private val missions = listOf(
    "오늘 고마웠던 일 한 가지 떠올려 보기",
    "좋아하는 그림이나 사진 한 장 골라 보기",
    "선생님께 궁금한 직업 하나 물어보기",
    "내 방이나 책상 한 곳 정리하기",
    "친구에게 칭찬 한마디 해 보기",
    "오늘 배운 것 하나 말해 보기",
    "나중에 해 보고 싶은 일 그려 보기",
)

/** 이름 + 아/야 (받침 있으면 '아', 없으면 '야') */
private fun call(name: String): String {
    val last = name.lastOrNull() ?: return "친구야"
    if (last !in '가'..'힣') return name
    val hasBatchim = (last.code - 0xAC00) % 28 != 0
    return name + if (hasBatchim) "아" else "야"
}

@Composable
fun ChildHomeScreen(onNavigate: (String) -> Unit, onLogout: () -> Unit = {}) {
    val session = AppContainer.session
    val today = LocalDate.now().toEpochDay()
    val mission = missions[(today % missions.size).toInt()]
    var doneToday by remember { mutableStateOf(session.missionDoneDay == today) }
    var balance by remember { mutableIntStateOf(session.starBalance) }
    var showHelp by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    val name = session.nickname.ifBlank { "친구" }

    SpeakOnce(if (doneToday) "${call(name)}, 오늘 미션 성공!" else "${call(name)}, 안녕! 오늘의 미션은 $mission")

    Column(
        Modifier
            .fillMaxSize()
            .background(RoadKit.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        // 점선 길 (디자인 기준 높이 800). 좌우 여백은 KidHome.SideMargin
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(start = KidHome.SideMargin, end = KidHome.SideMargin, top = 10.dp, bottom = 28.dp)
                .height(800.dp),
        ) {
            val w = maxWidth
            val density = LocalDensity.current
            // 오늘의 미션 카드 아래 끝(dp). 카드 높이에 맞춰 길이 바로 이어지게 한다
            var missionBottom by remember { mutableStateOf(316f) }
            RoadLine(Modifier.fillMaxSize(), missionBottom)

            // 도움말 (?)
            Box(Modifier.align(Alignment.TopEnd)) {
                ChipButton({ showHelp = true }) { Text("?", style = hanaText(24.sp, lineHeight = 1.1f), color = RoadKit.Ink) }
            }

            // 출발: 인사
            Column(Modifier.offset(x = 14.dp, y = 10.dp)) {
                Text("${call(name)}, 안녕!", style = hanaText(31.sp, lineHeight = 1.3f), color = RoadKit.Ink)
                Text("오늘은 어디로 가 볼까?", style = hanaText(21.sp, FontWeight.Normal, 1.35f), color = RoadKit.InkSub)
            }

            // 별 개수 → 내 기록
            Text(
                buildAnnotatedString {
                    append("지금까지 별 ")
                    withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RoadKit.Star)) { append("${balance}개") }
                    append(" 모았어!")
                },
                style = hanaText(18.sp, FontWeight.Normal, 1.35f),
                color = RoadKit.InkMuted,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = 112.dp)
                    .clickable { onNavigate(Routes.STICKER_BOARD) },
            )

            // 첫 번째 정거장: 오늘의 미션
            MissionCard(
                mission = mission,
                done = doneToday,
                modifier = Modifier
                    .offset(x = 70.dp, y = 150.dp)
                    .width(w - 80.dp)   // 오른쪽 여백 10
                    .onSizeChanged { missionBottom = 150f + with(density) { it.height.toDp().value } },
                onStart = {
                    doneToday = true
                    session.missionDoneDay = today
                    session.missionCount = session.missionCount + 1
                    balance = session.starBalance
                },
            )

            // 정거장들
            Stop("상담하기", "마음 이야기하기", Tones.Blue, KidHome.ImgCounsel, end = false, y = 360.dp, seed = 21,
                modifier = Modifier.align(Alignment.TopStart)) { onNavigate(Routes.CHILD_COACH) }
            Stop("좋아하는 거 찾기", "내 취향 발견하기", Tones.Pink, KidHome.ImgFind, end = true, y = 446.dp, seed = 7,
                modifier = Modifier.align(Alignment.TopEnd)) { onNavigate(Routes.CHILD_FIND) }
            Stop("내 스티커", "모은 별과 스티커", Tones.Purple, KidHome.ImgSticker, end = false, y = 520.dp, seed = 21,
                modifier = Modifier.align(Alignment.TopStart)) { onNavigate(Routes.STICKER_BOARD) }
            Stop("스티커 가게", "새로운 스티커 구경", Tones.Yellow, KidHome.ImgShop, end = true, y = 600.dp, seed = 7,
                modifier = Modifier.align(Alignment.TopEnd)) { onNavigate(Routes.STICKER_SHOP) }

            // 길 끝: 도움이 필요해
            HelpPill(Modifier.offset(y = 722.dp)) { showHelp = true }
        }

        // 처음으로 돌아가기 (로그아웃)
        Text(
            "처음으로 돌아가기",
            style = hanaText(17.sp, FontWeight.Normal, 1.3f).copy(textDecoration = TextDecoration.Underline),
            color = RoadKit.InkMuted,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { showLogout = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(28.dp))
    }

    if (showLogout) LogoutDialog(onCancel = { showLogout = false }, onConfirm = { showLogout = false; onLogout() })

    if (showHelp) {
        Dialog(
            onDismissRequest = { showHelp = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ChildHelpScreen(onBack = { showHelp = false })
        }
    }
}

/**
 * 정거장을 잇는 점선 길.
 * 디자인 좌표(폭 354)를 화면 폭에 맞춘다. 왼쪽 정거장 중심(66)과 오른쪽 정거장 중심(288)이
 * 실제 동그라미 중심에 오도록 늘린다.
 */
@Composable
private fun RoadLine(modifier: Modifier, missionBottom: Float) {
    Canvas(modifier) {
        val unit = 1.dp.toPx()
        val designW = size.width / unit
        // 디자인 x 66 → 왼쪽 동그라미 중심, 288 → 오른쪽 동그라미 중심
        val c = KidHome.StopInset.value + KidHome.StopSize.value / 2f
        fun x(v: Float) = (c + (v - 66f) * (designW - 2f * c) / 222f) * unit
        fun y(v: Float) = v * unit
        val segs = listOf(
            floatArrayOf(58f, 92f, 58f, 140f, 110f, 150f, 130f, 160f),
            floatArrayOf(190f, missionBottom - 2f, 190f, missionBottom + 24f, 66f, maxOf(missionBottom + 8f, 330f), 66f, 360f),
            floatArrayOf(66f, 428f, 66f, 463f, 288f, 412f, 288f, 446f),
            floatArrayOf(288f, 514f, 288f, 544f, 66f, 494f, 66f, 520f),
            floatArrayOf(66f, 588f, 66f, 623f, 288f, 570f, 288f, 600f),
            floatArrayOf(288f, 668f, 288f, 698f, 200f, 700f, 190f, 722f),
        )
        val path = Path()
        segs.forEach { s ->
            path.moveTo(x(s[0]), y(s[1]))
            path.cubicTo(x(s[2]), y(s[3]), x(s[4]), y(s[5]), x(s[6]), y(s[7]))
        }
        drawPath(
            path,
            RoadKit.Road,
            style = Stroke(
                width = 3.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 10.dp.toPx())),
            ),
        )
    }
}

/** 오늘의 미션: 흰 카드 + 손그림 테두리 + 왼쪽 위 작은 스티커 자리 + 대표색 버튼 */
@Composable
private fun MissionCard(mission: String, done: Boolean, modifier: Modifier, onStart: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .handOutline(18.dp, seed = 7)
                .clip(shape)
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("오늘의 미션", style = hanaText(24.sp, lineHeight = 1.25f), color = RoadKit.Ink, modifier = Modifier.weight(1f))
                Text("별 +1", style = hanaText(17.sp), color = RoadKit.Star)
            }
            Text(
                mission,
                style = hanaText(20.sp, lineHeight = 1.3f),
                color = RoadKit.Body,
                maxLines = 3,
                modifier = Modifier.padding(start = 10.dp),
            )
            if (done) {
                Box(
                    Modifier
                        .align(Alignment.End)
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(RoadKit.Chip)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("오늘 미션 끝! 내일 또 만나", style = hanaText(18.sp, lineHeight = 1.2f), color = RoadKit.InkSub)
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.End)
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(RoadKit.Accent)
                        .clickable(onClick = onStart)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("시작하기", style = hanaText(20.sp, lineHeight = 1.2f), color = Color.White)
                }
            }
        }
        // 왼쪽 위 작은 스티커 자리
        ImageSlot(
            Modifier.offset(x = (-16).dp, y = (-14).dp).size(36.dp),
            KidHome.MissionSlot,
            radius = 8.dp,
            image = KidHome.ImgMission,
            fill = Tones.Yellow.bg,
        )
    }
}

/** 정거장: 동그라미(손그림 테두리 + 그림) + 제목·설명. end=true 면 오른쪽 정렬 */
@Composable
private fun Stop(
    title: String,
    sub: String,
    tone: StopTone,
    @DrawableRes image: Int?,
    end: Boolean,
    y: Dp,
    seed: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .offset(x = if (end) -KidHome.StopInset else KidHome.StopInset, y = y)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!end) {
            StopCircle(tone, image, seed)
            Spacer(Modifier.width(12.dp))
        }
        Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
            Text(title, style = hanaText(23.sp, lineHeight = 1.25f), color = tone.title, textAlign = if (end) TextAlign.End else TextAlign.Start)
            Text(sub, style = hanaText(17.sp, FontWeight.Normal, 1.25f), color = tone.sub, textAlign = if (end) TextAlign.End else TextAlign.Start)
        }
        if (end) {
            Spacer(Modifier.width(12.dp))
            StopCircle(tone, image, seed)
        }
    }
}

@Composable
private fun StopCircle(tone: StopTone, @DrawableRes image: Int?, seed: Int) {
    Box(
        Modifier.size(KidHome.StopSize).handOutline(KidHome.StopSize / 2, seed).clip(CircleShape).background(tone.bg),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(painterResource(image), null, Modifier.size(KidHome.StopImage), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.size(KidHome.StopImage - 6.dp).dashedOutline(tone.slot, (KidHome.StopImage - 6.dp) / 2))
        }
    }
}

/** 길 끝: 도움이 필요해 (연둣빛 알약) */
@Composable
private fun HelpPill(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .handOutline(36.dp, seed = 21)
            .clip(shape)
            .background(Tones.Lime.bg)
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImageSlot(Modifier.size(48.dp), Tones.Lime.slot, radius = 24.dp, image = KidHome.ImgHelp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("도움이 필요해", style = hanaText(23.sp, lineHeight = 1.25f), color = Tones.Lime.title)
            Text("어려울 땐 언제든지 연락해줘.", style = hanaText(17.sp, FontWeight.Normal, 1.25f), color = Tones.Lime.sub)
        }
    }
}

/** 처음으로 돌아가기 확인 */
@Composable
private fun LogoutDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    SpeakOnce("처음 화면으로 갈까? 다시 들어오려면 이름이랑 생일을 다시 써야 해.")
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(4.dp)   // 손그림 선이 창 끝에 잘리지 않게
                .handOutline(24.dp, seed = 21)
                .clip(RoundedCornerShape(24.dp))
                .background(RoadKit.Background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("처음 화면으로 갈까?", style = hanaText(25.sp, lineHeight = 1.3f), color = RoadKit.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "다시 들어오려면\n이름이랑 생일을 다시 써야 해.",
                style = hanaText(19.sp, FontWeight.Normal, 1.4f),
                color = RoadKit.InkSub,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(RoadKit.Accent).clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) { Text("응, 갈래", style = hanaText(22.sp, lineHeight = 1.2f), color = Color.White) }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(RoadKit.Chip).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("아니, 여기 있을래", style = hanaText(20.sp, lineHeight = 1.2f), color = RoadKit.InkSub) }
        }
    }
}