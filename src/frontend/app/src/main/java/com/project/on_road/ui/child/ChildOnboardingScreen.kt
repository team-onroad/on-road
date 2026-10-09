package com.project.on_road.ui.child

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.on_road.ui.components.SpeakOnce
import kotlinx.coroutines.delay
import java.time.LocalDate

/*
 * 아동 온보딩 — 시안 O0 ~ O③
 * 아동을 고른 직후: 테마 전환(스플래시) → 인사 → 나에 대해 알려 줘(체크리스트) → 길 소개 → 시작하기
 * 체크리스트 항목(이름 · 생일 · 사는 곳 · 전화번호)을 누르면 질문 화면이 열린다.
 * 다 끝나면 onDone(ChildProfile) 로 넘겨준다. 서버 가입(POST /users)은 부르는 쪽에서 한다.
 * 지금 지내는 상태는 묻지 않고 '시설 생활 중(in_care)'으로 넣는다.
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidOnboard {
    @DrawableRes val Logo: Int? = null          // 테마 전환 화면 로고
    @DrawableRes val Character: Int? = null     // 인사 · 이름 묻기 캐릭터
    const val SplashMillis = 1400                // 점선 길이 그려지는 시간
    const val SplashHoldMillis = 600L            // 다 그린 뒤 머무는 시간
    val Regions = listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주")
}
/* ====================================================== */

/** 아동 온보딩 결과 (서버 POST /users 에 쓰는 값) */
data class ChildProfile(
    val name: String,
    val birthDate: LocalDate,
    val region: String,      // 17개 약칭
    val phone: String?,      // 하이픈 없이, 없으면 null
    val status: String = "in_care",
)

private enum class KidObPage { SPLASH, HELLO, CHECKLIST, NAME, BIRTH, REGION, PHONE, ROAD }

@Composable
fun ChildOnboardingScreen(
    onDone: (ChildProfile) -> Unit,
    onBack: () -> Unit = {},
    loading: Boolean = false,      // 가입 중
    error: String? = null,         // 가입 실패 문구
) {
    var page by remember { mutableStateOf(KidObPage.SPLASH) }
    var name by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf<LocalDate?>(null) }
    var region by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }

    BackHandler(enabled = page != KidObPage.SPLASH) {
        page = when (page) {
            KidObPage.NAME, KidObPage.BIRTH, KidObPage.REGION, KidObPage.PHONE -> KidObPage.CHECKLIST
            KidObPage.ROAD -> KidObPage.CHECKLIST
            KidObPage.CHECKLIST -> KidObPage.HELLO
            else -> { onBack(); page }
        }
    }

    Box(Modifier.fillMaxSize().background(RoadKit.Background)) {
        when (page) {
            KidObPage.SPLASH -> ThemeSplash { page = KidObPage.HELLO }
            KidObPage.HELLO -> HelloPage { page = KidObPage.CHECKLIST }
            KidObPage.CHECKLIST -> ChecklistPage(
                name = name, birth = birth, region = region, phone = phone,
                onOpen = { page = it },
                onNext = { page = KidObPage.ROAD },
            )
            KidObPage.NAME -> NamePage(name, onChange = { name = it }) { page = KidObPage.CHECKLIST }
            KidObPage.BIRTH -> BirthPage(birth, onPick = { birth = it }, onBack = { page = KidObPage.CHECKLIST }) { page = KidObPage.CHECKLIST }
            KidObPage.REGION -> RegionPage(region, onPick = { region = it }, onBack = { page = KidObPage.CHECKLIST }) { page = KidObPage.CHECKLIST }
            KidObPage.PHONE -> PhonePage(phone, onChange = { phone = it.filter(Char::isDigit).take(11) }, onBack = { page = KidObPage.CHECKLIST }) { page = KidObPage.CHECKLIST }
            KidObPage.ROAD -> RoadIntroPage(loading = loading, error = error) {
                onDone(ChildProfile(name.trim(), birth!!, region!!, phone.takeIf { it.length in 10..11 }))
            }
        }
    }
}

/* ---------- 공통 조각 ---------- */

/** 이름 + 아/야 */
private fun kidCall(name: String): String {
    val last = name.lastOrNull() ?: return "친구야"
    if (last !in '가'..'힣') return name
    return name + if ((last.code - 0xAC00) % 28 != 0) "아" else "야"
}

/** 위쪽 진행 표시: 작은 점선 길 위 점 3개 */
@Composable
private fun ProgressRoad(step: Int) {
    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 110.dp, height = 18.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    RoadKit.Road, androidx.compose.ui.geometry.Offset(9.dp.toPx(), size.height / 2),
                    androidx.compose.ui.geometry.Offset(size.width - 9.dp.toPx(), size.height / 2),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 9.dp.toPx())),
                )
            }
            for (i in 0..2) {
                val m = Modifier.offset(x = (46 * i).dp).size(18.dp)
                when {
                    i == step -> Box(m.clip(CircleShape).background(RoadKit.Ink))
                    i < step -> Box(m.clip(CircleShape).background(RoadKit.Road))
                    else -> Box(m.clip(CircleShape).background(RoadKit.Background).dashedOutline(RoadKit.Road, 9.dp, 1.6.dp))
                }
            }
        }
    }
}

@Composable
private fun NavyButton(label: String, enabled: Boolean = true, disabledLabel: String = label, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) RoadKit.Accent else RoadKit.Chip)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (enabled) label else disabledLabel,
            style = hanaText(if (enabled) 23.sp else 22.sp, lineHeight = 1.2f),
            color = if (enabled) Color.White else RoadKit.InkMuted,
        )
    }
}

@Composable
private fun PageTitle(big: String, small: String) {
    Column(Modifier.padding(start = 4.dp)) {
        Text(big, style = hanaText(31.sp, lineHeight = 1.25f), color = RoadKit.Ink)
        Spacer(Modifier.height(4.dp))
        Text(small, style = hanaText(20.sp, FontWeight.Normal, 1.35f), color = RoadKit.InkSub)
    }
}

/** 질문 화면 틀: 뒤로 · 제목 · 내용 · 아래 버튼 */
@Composable
private fun QuestionFrame(
    big: String,
    small: String,
    onBack: () -> Unit,
    button: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChipButton(onBack) { Chevron(left = true, color = RoadKit.Ink) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PageTitle(big, small)
            content()
        }
        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) { button() }
    }
}

/** 고르는 칸 (고르면 파스텔 + 손그림 테두리, 아니면 점선) */
@Composable
private fun PickCell(label: String, selected: Boolean, tone: StopTone, modifier: Modifier, height: Dp = 54.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(height)
            .then(if (selected) Modifier.handOutline(16.dp, seed = 7) else Modifier.dashedOutline(Color(0xFFD9CCBA), 16.dp, 1.6.dp))
            .clip(shape)
            .background(if (selected) tone.bg else RoadKit.Background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = hanaText(21.sp, if (selected) FontWeight.Bold else FontWeight.Normal, 1.2f),
            color = if (selected) RoadKit.Ink else RoadKit.Body,
            maxLines = 1,
        )
    }
}

/** 칸들을 n개씩 줄 맞춰 놓기 */
@Composable
private fun <T> PickGrid(items: List<T>, columns: Int, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/* ---------- O0 테마 전환 ---------- */

@Composable
private fun ThemeSplash(onNext: () -> Unit) {
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        draw.animateTo(1f, tween(KidOnboard.SplashMillis))
        delay(KidOnboard.SplashHoldMillis)
        onNext()
    }
    SpeakOnce("온로드. 너의 오늘도, 성장하는 길 위에")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        Column(
            Modifier.fillMaxSize().padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ImageSlot(Modifier.size(84.dp), Color(0xFFB9AD9E), radius = 22.dp, image = KidOnboard.Logo)
            Spacer(Modifier.height(18.dp))
            Text("온로드", style = hanaText(46.sp, lineHeight = 1.2f), color = RoadKit.Ink)
            Spacer(Modifier.height(10.dp))
            Text("너의 오늘도,\n성장하는 길 위에", style = hanaText(21.sp, FontWeight.Normal, 1.4f), color = RoadKit.InkSub, textAlign = TextAlign.Center)
        }

        // 아래: 점선 길이 왼쪽부터 그려지고, 길이 지나가면 정거장이 나타난다 (디자인 폭 390 기준)
        Box(Modifier.align(Alignment.BottomStart).padding(bottom = 40.dp).fillMaxWidth().height(150.dp)) {
            Canvas(
                Modifier.fillMaxSize().drawWithContent {
                    clipRect(right = size.width * draw.value) { this@drawWithContent.drawContent() }
                },
            ) {
                val sx = size.width / 390f
                val u = 1.dp.toPx()
                val p = Path().apply {
                    moveTo(-10f * sx, 110f * u)
                    cubicTo(60f * sx, 110f * u, 70f * sx, 40f * u, 130f * sx, 50f * u)
                    cubicTo(190f * sx, 60f * u, 190f * sx, 120f * u, 255f * sx, 105f * u)
                    cubicTo(320f * sx, 90f * u, 330f * sx, 40f * u, 400f * sx, 45f * u)
                }
                drawPath(
                    p, RoadKit.Road,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 10.dp.toPx()))),
                )
            }
            val stops = listOf(Triple(112f, 132f, Tones.Blue), Triple(238f, 68f, Tones.Pink), Triple(330f, 132f, Tones.Yellow))
            stops.forEachIndexed { i, (x, bottom, tone) ->
                val at = (x + 17f) / 390f
                val a = ((draw.value - at) / 0.12f).coerceIn(0f, 1f)
                Box(
                    Modifier
                        // 투명도 레이어가 자기 칸 밖을 잘라서, 손그림 선이 들어갈 여유(4dp)를 칸 안에 둔다
                        .offset(x = w * (x / 390f) - 4.dp, y = (150f - (bottom - 40f) - 34f - 4f).dp)
                        .size(42.dp)
                        .alpha(a)
                        .padding(4.dp)
                        .handOutline(17.dp, seed = if (i % 2 == 0) 7 else 21)
                        .clip(CircleShape)
                        .background(tone.bg),
                )
            }
        }
    }
}

/* ---------- O① 인사 ---------- */

@Composable
private fun HelloPage(onNext: () -> Unit) {
    SpeakOnce("안녕! 나는 온로드야. 너의 길을 같이 걸어 줄 친구야.")
    Column(Modifier.fillMaxSize()) {
        ProgressRoad(0)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ImageSlot(Modifier.size(200.dp), RoadKit.Road, radius = 48.dp, image = KidOnboard.Character)
            Spacer(Modifier.height(26.dp))
            Text("안녕!", style = hanaText(38.sp, lineHeight = 1.2f), color = RoadKit.Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "나는 온로드야.\n너의 길을 같이 걸어 줄 친구야.",
                style = hanaText(23.sp, FontWeight.Normal, 1.45f),
                color = RoadKit.InkSub,
                textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.padding(start = 26.dp, end = 26.dp, bottom = 32.dp)) { NavyButton("다음", onClick = onNext) }
    }
}

/* ---------- O② 체크리스트 ---------- */

@Composable
private fun ChecklistPage(
    name: String,
    birth: LocalDate?,
    region: String?,
    phone: String,
    onOpen: (KidObPage) -> Unit,
    onNext: () -> Unit,
) {
    val doneName = name.isNotBlank()
    val doneBirth = birth != null
    val doneRegion = region != null
    val donePhone = phone.length in 10..11
    val doneCount = listOf(doneName, doneBirth, doneRegion, donePhone).count { it }
    val left = listOf(doneName, doneBirth, doneRegion).count { !it }   // 전화번호는 안 해도 됨

    SpeakOnce("나에 대해 알려 줘! 하나씩 눌러서 채워 봐.")

    Column(Modifier.fillMaxSize()) {
        ProgressRoad(1)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                Text("나에 대해 알려 줘!", style = hanaText(31.sp, lineHeight = 1.25f), color = RoadKit.Ink)
                Text(
                    buildAnnotatedString {
                        append("하나씩 눌러서 채워 봐 · ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Tones.Lime.title)) { append("${doneCount}개") }
                        append(" 했어")
                    },
                    style = hanaText(20.sp, FontWeight.Normal, 1.35f),
                    color = RoadKit.InkSub,
                )
            }
            CheckItem("이름", "뭐라고 부를까?", doneName, name, "쓰기", seed = 7) { onOpen(KidObPage.NAME) }
            CheckItem("생일", "언제 태어났어?", doneBirth, birth?.let { "${it.year}. ${it.monthValue}. ${it.dayOfMonth}." }.orEmpty(), "고르기", seed = 21) { onOpen(KidObPage.BIRTH) }
            CheckItem("사는 곳", "어느 지역에 살아?", doneRegion, region.orEmpty(), "고르기", seed = 7) { onOpen(KidObPage.REGION) }
            CheckItem("전화번호", "선생님 번호도 괜찮아", donePhone, phone, "쓰기", seed = 21, optional = true) { onOpen(KidObPage.PHONE) }

            Text(
                "모르면 선생님께 같이 해 달라고 해도 돼.",
                style = hanaText(18.sp, FontWeight.Normal, 1.4f),
                color = RoadKit.Body,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(RoadKit.Chip)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            NavyButton("다 했어", enabled = left == 0, disabledLabel = "${left}개만 더 하면 돼", onClick = onNext)
        }
    }
}

@Composable
private fun CheckItem(
    title: String,
    note: String,
    done: Boolean,
    value: String,
    action: String,
    seed: Int,
    optional: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .then(if (done) Modifier.handOutline(18.dp, seed) else Modifier.dashedOutline(Color(0xFFD9CCBA), 18.dp, 1.6.dp))
            .clip(shape)
            .background(RoadKit.Background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            Box(
                Modifier.size(34.dp).handOutline(17.dp, seed).clip(CircleShape).background(Tones.Lime.bg),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val s = size.width / 24f
                    val p = Path().apply { moveTo(5 * s, 12 * s); lineTo(10 * s, 17 * s); lineTo(19 * s, 7 * s) }
                    drawPath(p, Tones.Lime.title, style = Stroke(width = 3.4f * s, cap = StrokeCap.Round))
                }
            }
        } else {
            Box(Modifier.size(34.dp).dashedOutline(RoadKit.Road, 17.dp, 1.6.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = hanaText(21.sp, lineHeight = 1.25f), color = RoadKit.Ink)
                if (optional) {
                    Spacer(Modifier.width(6.dp))
                    Text("(안 해도 돼)", style = hanaText(15.sp, FontWeight.Normal, 1.2f), color = RoadKit.InkMuted)
                }
            }
            Text(note, style = hanaText(16.sp, FontWeight.Normal, 1.25f), color = RoadKit.InkSub)
        }
        if (done) {
            Text(value, style = hanaText(21.sp, lineHeight = 1.2f), color = RoadKit.Ink, maxLines = 1)
        } else {
            Text(
                action,
                style = hanaText(18.sp, lineHeight = 1.2f).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                color = Tones.Blue.title,
            )
        }
    }
}

/* ---------- 질문: 이름 ---------- */

@Composable
private fun NamePage(name: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    SpeakOnce("너를 뭐라고 부를까?")
    Column(Modifier.fillMaxSize()) {
        ProgressRoad(1)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 34.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // 캐릭터 + 말풍선
            Row(verticalAlignment = Alignment.Top) {
                ImageSlot(Modifier.size(92.dp), RoadKit.Road, radius = 26.dp, image = KidOnboard.Character)
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .handOutline(20.dp, seed = 7, topStart = 6.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp))
                        .background(Color.White)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) { Text("너를 뭐라고\n부를까?", style = hanaText(24.sp, lineHeight = 1.35f), color = RoadKit.Ink) }
            }

            // 이름 칸
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("내 이름", style = hanaText(20.sp, FontWeight.Normal, 1.3f), color = RoadKit.InkSub, modifier = Modifier.padding(start = 4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(66.dp)
                        .handOutline(18.dp, seed = 21)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("여기에 써 줘", style = hanaText(24.sp, FontWeight.Normal, 1.2f), color = RoadKit.InkMuted)
                    BasicTextField(
                        value = name,
                        onValueChange = { onChange(it.take(20)) },
                        singleLine = true,
                        textStyle = hanaText(26.sp, lineHeight = 1.2f).copy(color = RoadKit.Ink),
                        cursorBrush = SolidColor(RoadKit.Ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 미리 보기
            if (name.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dashedOutline(RoadKit.Road, 16.dp, 1.6.dp)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text("이렇게 불러 줄게!", style = hanaText(21.sp, FontWeight.Normal, 1.4f), color = RoadKit.Body)
                    Text("${kidCall(name.trim())}, 안녕!", style = hanaText(25.sp, lineHeight = 1.3f), color = RoadKit.Ink)
                }
            }
        }
        Box(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 32.dp)) {
            NavyButton("다 썼어", enabled = name.isNotBlank(), disabledLabel = "이름을 써 줘", onClick = onDone)
        }
    }
}

/* ---------- 질문: 생일 ---------- */

@Composable
private fun BirthPage(birth: LocalDate?, onPick: (LocalDate) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    SpeakOnce("언제 태어났어? 해, 달, 날을 차례로 골라 줘.")
    val thisYear = LocalDate.now().year
    var year by remember { mutableIntStateOf(birth?.year ?: 0) }
    var month by remember { mutableIntStateOf(birth?.monthValue ?: 0) }
    var day by remember { mutableIntStateOf(birth?.dayOfMonth ?: 0) }
    val maxDay = if (year > 0 && month > 0) LocalDate.of(year, month, 1).lengthOfMonth() else 31
    if (day > maxDay) day = 0
    val ready = year > 0 && month > 0 && day > 0

    QuestionFrame(
        big = "언제 태어났어?",
        small = "해 → 달 → 날 차례로 골라 줘",
        onBack = onBack,
        button = {
            NavyButton("골랐어", enabled = ready, disabledLabel = "다 골라 줘") {
                onPick(LocalDate.of(year, month, day)); onDone()
            }
        },
    ) {
        Text("태어난 해", style = hanaText(20.sp, lineHeight = 1.3f), color = RoadKit.Ink)
        PickGrid((thisYear - 11..thisYear - 5).toList().reversed(), columns = 3) { y, m ->
            PickCell("${y}년", y == year, Tones.Yellow, m) { year = y }
        }
        Text("태어난 달", style = hanaText(20.sp, lineHeight = 1.3f), color = RoadKit.Ink)
        PickGrid((1..12).toList(), columns = 4) { mo, m ->
            PickCell("${mo}월", mo == month, Tones.Pink, m) { month = mo }
        }
        Text("태어난 날", style = hanaText(20.sp, lineHeight = 1.3f), color = RoadKit.Ink)
        PickGrid((1..maxDay).toList(), columns = 6) { d, m ->
            PickCell("$d", d == day, Tones.Blue, m, height = 48.dp) { day = d }
        }
    }
}

/* ---------- 질문: 사는 곳 ---------- */

@Composable
private fun RegionPage(region: String?, onPick: (String) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    SpeakOnce("어느 지역에 살아? 잘 모르면 선생님께 물어봐!")
    var picked by remember { mutableStateOf(region) }
    QuestionFrame(
        big = "어느 지역에 살아?",
        small = "잘 모르면 선생님께 물어봐!",
        onBack = onBack,
        button = {
            NavyButton("골랐어", enabled = picked != null, disabledLabel = "하나 골라 줘") {
                onPick(picked!!); onDone()
            }
        },
    ) {
        PickGrid(KidOnboard.Regions, columns = 3) { r, m ->
            PickCell(r, r == picked, Tones.Blue, m) { picked = r }
        }
    }
}

/* ---------- 질문: 전화번호 ---------- */

@Composable
private fun PhonePage(phone: String, onChange: (String) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    SpeakOnce("전화번호를 써 줘. 선생님 번호도 괜찮아. 안 해도 돼.")
    val valid = phone.length in 10..11 && phone.startsWith("01")
    QuestionFrame(
        big = "전화번호를 써 줘",
        small = "선생님 번호도 괜찮아 · 안 해도 돼",
        onBack = onBack,
        button = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavyButton("다 썼어", enabled = valid, disabledLabel = "숫자만 써 줘", onClick = onDone)
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).clickable { onChange(""); onDone() },
                    contentAlignment = Alignment.Center,
                ) { Text("안 할래", style = hanaText(20.sp, FontWeight.Normal, 1.2f), color = RoadKit.InkSub) }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .handOutline(18.dp, seed = 21)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (phone.isEmpty()) Text("01012345678", style = hanaText(24.sp, FontWeight.Normal, 1.2f), color = RoadKit.InkMuted)
            BasicTextField(
                value = phone,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                textStyle = hanaText(26.sp, lineHeight = 1.2f).copy(color = RoadKit.Ink),
                cursorBrush = SolidColor(RoadKit.Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ---------- O③ 길 소개 ---------- */

@Composable
private fun RoadIntroPage(loading: Boolean, error: String?, onStart: () -> Unit) {
    SpeakOnce("이 길을 따라 같이 가 보자! 정거장을 누르면 갈 수 있어.")
    Column(Modifier.fillMaxSize()) {
        ProgressRoad(2)
        Column(Modifier.weight(1f).padding(start = 22.dp, end = 22.dp, top = 34.dp)) {
            Text("이 길을 따라\n같이 가 보자!", style = hanaText(33.sp, lineHeight = 1.25f), color = RoadKit.Ink, modifier = Modifier.padding(start = 4.dp))
            Text("정거장을 누르면 갈 수 있어", style = hanaText(21.sp, FontWeight.Normal, 1.35f), color = RoadKit.InkSub, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            Spacer(Modifier.height(26.dp))

            // 미니 길 (디자인 폭 346 × 높이 380)
            BoxWithConstraints(Modifier.fillMaxWidth().height(380.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val u = 1.dp.toPx()
                    val dw = size.width / u
                    fun x(v: Float) = (52f + (v - 52f) * (dw - 104f) / 242f) * u
                    fun y(v: Float) = v * u
                    val p = Path().apply {
                        moveTo(x(52f), y(64f)); cubicTo(x(52f), y(104f), x(294f), y(70f), x(294f), y(110f))
                        moveTo(x(294f), y(174f)); cubicTo(x(294f), y(214f), x(52f), y(180f), x(52f), y(220f))
                        moveTo(x(52f), y(284f)); cubicTo(x(52f), y(320f), x(173f), y(330f), x(173f), y(372f))
                    }
                    drawPath(p, RoadKit.Road, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 10.dp.toPx()))))
                }
                MiniStop("상담하기", "마음 이야기하기", Tones.Blue, end = false, y = 0.dp, seed = 7, modifier = Modifier.align(Alignment.TopStart))
                MiniStop("좋아하는 거 찾기", "내 취향 발견하기", Tones.Pink, end = true, y = 110.dp, seed = 21, modifier = Modifier.align(Alignment.TopEnd))
                MiniStop("내 스티커", "모은 별과 스티커", Tones.Purple, end = false, y = 220.dp, seed = 7, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (error != null) {
                Text(
                    error,
                    style = hanaText(18.sp, FontWeight.Normal, 1.35f),
                    color = Color(0xFF8A2E0E),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFCE3D6))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            NavyButton("시작하기", enabled = !loading, disabledLabel = "잠깐만 기다려 줘…", onClick = onStart)
        }
    }
}

@Composable
private fun MiniStop(title: String, sub: String, tone: StopTone, end: Boolean, y: Dp, seed: Int, modifier: Modifier) {
    Row(modifier.offset(x = if (end) (-20).dp else 20.dp, y = y), verticalAlignment = Alignment.CenterVertically) {
        val circle = @Composable {
            Box(Modifier.size(64.dp).handOutline(32.dp, seed).clip(CircleShape).background(tone.bg), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).dashedOutline(tone.slot, 20.dp))
            }
        }
        if (!end) { circle(); Spacer(Modifier.width(12.dp)) }
        Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
            Text(title, style = hanaText(22.sp, lineHeight = 1.25f), color = tone.title)
            Text(sub, style = hanaText(17.sp, FontWeight.Normal, 1.25f), color = tone.sub)
        }
        if (end) { Spacer(Modifier.width(12.dp)); circle() }
    }
}