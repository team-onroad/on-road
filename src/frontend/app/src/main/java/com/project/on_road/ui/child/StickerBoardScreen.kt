package com.project.on_road.ui.child

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.project.on_road.data.AppContainer
import com.project.on_road.data.PlacedSticker
import com.project.on_road.data.Sticker
import com.project.on_road.data.StickerStore
import com.project.on_road.ui.components.SpeakOnce
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * 내 스티커 — 시안 D (공책 탭: 내 기록 / 꾸미기)
 * 꾸미기: 모눈 종이에 산 스티커를 눌러 붙이고, 끌어서 옮기고, ✕로 뗀다.
 *        위치는 종이 크기 대비 비율로 저장해서 기기가 달라도 비슷한 자리에 보인다.
 * 내 기록: 지금까지 한 미션 수 + 점선 길 타임라인 (records 로 넘겨준 기록)
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidBoard {
    val Grid = Color(0xFFF0E9F8)          // 모눈 선
    val TabDash = Color(0xFFCDBCA4)       // 안 고른 탭 점선
    val StickerSize = 64.dp
    // 아래 서랍 동그라미 색 (차례로 돌아가며)
    val TrayTones = listOf(Tones.Purple, Tones.Yellow, Tones.Blue, Tones.Pink)
}
/* ====================================================== */

/**
 * 날짜별 꾸미기 페이지 저장 (휴대폰 안에 저장).
 * 나중에 '내 기록'에서 날짜별 페이지를 다시 보여 줄 때 load() 로 꺼내 쓰면 된다.
 */
object KidPages {
    private const val PREFS = "kid_pages"

    fun encode(list: List<PlacedSticker>): String =
        list.joinToString(";") { "${it.sticker.name},${it.x},${it.y}" }

    fun save(context: Context, date: LocalDate, list: List<PlacedSticker>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(date.toString(), encode(list)).apply()
    }

    fun loadRaw(context: Context, date: LocalDate): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(date.toString(), null)

    fun load(context: Context, date: LocalDate): List<PlacedSticker> =
        loadRaw(context, date).orEmpty().split(";").mapNotNull { part ->
            val f = part.split(",")
            if (f.size != 3) return@mapNotNull null
            val sticker = Sticker.of(f[0]) ?: return@mapNotNull null
            PlacedSticker(sticker, f[1].toFloatOrNull() ?: return@mapNotNull null, f[2].toFloatOrNull() ?: return@mapNotNull null)
        }

    /** 저장해 둔 날짜들 (최근 순) */
    fun dates(context: Context): List<LocalDate> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .sortedDescending()
}

/** 내 기록 한 줄 */
data class KidRecord(
    val date: LocalDate,
    val kind: KidRecordKind,
    val title: String,
    val sub: String? = null,
)

enum class KidRecordKind(val label: String) {
    MISSION("미션 성공 · 별 +1"),
    COUNSEL("상담하기"),
    FIND("좋아하는 거 찾기"),
    STICKER("새 스티커"),
    PAGE("꾸민 페이지"),
}

private fun KidRecordKind.tone(): StopTone = when (this) {
    KidRecordKind.MISSION -> Tones.Yellow
    KidRecordKind.COUNSEL -> Tones.Blue
    KidRecordKind.FIND -> Tones.Pink
    KidRecordKind.STICKER -> Tones.Purple
    KidRecordKind.PAGE -> Tones.Purple
}

@Composable
fun StickerBoardScreen(
    onBack: () -> Unit,
    onShop: () -> Unit,
    records: List<KidRecord> = emptyList(),
) {
    val session = AppContainer.session
    var placed by remember { mutableStateOf(StickerStore.placed(session)) }
    val owned = remember { session.ownedStickers.mapNotNull(Sticker::of) }
    val tray = owned.filter { o -> placed.none { it.sticker == o } }
    var tab by remember { mutableIntStateOf(1) }   // 0 = 내 기록, 1 = 꾸미기
    var showHelp by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    // 오늘 저장해 둔 페이지 (지금 붙인 것과 같으면 '저장했어')
    var savedToday by remember { mutableStateOf(KidPages.loadRaw(context, today)) }
    var viewingPage by remember { mutableStateOf<LocalDate?>(null) }
    // 받은 기록 + 날짜별로 저장한 꾸미기 페이지
    val allRecords = remember(records, savedToday) {
        records + KidPages.dates(context).map { d ->
            KidRecord(d, KidRecordKind.PAGE, "스티커 ${KidPages.load(context, d).size}개를 붙였어")
        }
    }

    fun save(list: List<PlacedSticker>) {
        placed = list
        StickerStore.savePlaced(session, list)
    }

    SpeakOnce(if (tab == 1) "아래 스티커를 누르면 종이에 붙어. 끌어서 옮겨 봐!" else "지금까지 한 일을 볼 수 있어.")

    Column(Modifier.fillMaxSize().background(RoadKit.Background)) {
        RoadSubHeader("내 스티커", Tones.Purple.bg, onBack = onBack, onHelp = { showHelp = true })

        Column(Modifier.weight(1f).padding(start = 18.dp, end = 18.dp, bottom = 20.dp)) {
            // 공책: 탭과 종이를 한 선으로 이어서 그린다
            Notebook(
                tab = tab,
                onTab = { tab = it },
                star = session.starBalance,
                onShop = onShop,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                if (tab == 1) {
                    StickerPaper(
                        placed = placed,
                        emptyText = if (owned.isEmpty()) "아직 스티커가 없어\n가게에서 받아 볼까?" else "아래 스티커를 눌러서\n붙여 봐!",
                        modifier = Modifier.fillMaxSize(),
                        onMove = { sticker, x, y -> save(placed.map { if (it.sticker == sticker) it.copy(x = x, y = y) else it }) },
                        onRemove = { sticker -> save(placed.filterNot { it.sticker == sticker }) },
                    )
                } else {
                    RecordPaper(
                        missionCount = session.missionCount,
                        records = allRecords,
                        modifier = Modifier.fillMaxSize(),
                        onAttach = { tab = 1 },
                        onOpenPage = { viewingPage = it },
                    )
                }
            }

            if (tab == 1) {

                // 내 스티커 서랍
                Spacer(Modifier.height(16.dp))
                Text("눌러서 붙여 봐", style = hanaText(22.sp, lineHeight = 1.3f), color = RoadKit.Ink, modifier = Modifier.padding(start = 2.dp))
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    // 손그림 테두리가 잘리지 않게 위아래·양옆에 여유를 준다
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(tray) { i, s ->
                        val tone = KidBoard.TrayTones[i % KidBoard.TrayTones.size]
                        Box(
                            Modifier
                                .size(62.dp)
                                .handOutline(31.dp, seed = if (i % 2 == 0) 7 else 21)
                                .clip(CircleShape)
                                .background(tone.bg)
                                .clickable {
                                    save(placed + PlacedSticker(s, Random.nextFloat() * 0.7f + 0.15f, Random.nextFloat() * 0.7f + 0.15f))
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(s.emoji, fontSize = 32.sp) }
                    }
                    item {
                        Box(
                            Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .dashedOutline(RoadKit.Road, 31.dp, 1.6.dp)
                                .clickable(onClick = onShop),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("더\n받기", style = hanaText(15.sp, FontWeight.Normal, 1.1f), color = RoadKit.InkSub, textAlign = TextAlign.Center)
                        }
                    }
                }

                // 오늘 꾸민 거 저장
                val saved = savedToday == KidPages.encode(placed)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(27.dp))
                        .background(if (saved) RoadKit.Chip else RoadKit.Accent)
                        .clickable(enabled = !saved) {
                            save(placed)
                            KidPages.save(context, today, placed)
                            savedToday = KidPages.encode(placed)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (saved) "오늘 꾸민 거 저장했어!" else "오늘 꾸민 거 저장하기",
                        style = hanaText(22.sp, lineHeight = 1.2f),
                        color = if (saved) RoadKit.InkSub else Color.White,
                    )
                }
            }
        }
    }

    viewingPage?.let { d ->
        PageViewer(d, KidPages.load(context, d), onClose = { viewingPage = null })
    }

    if (showHelp) {
        Dialog(onDismissRequest = { showHelp = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ChildHelpScreen(onBack = { showHelp = false })
        }
    }
}

/* ---------- 탭 ---------- */

/**
 * 공책: 고른 탭과 종이를 **한 개의 손그림 선**으로 이어서 그린다.
 * 탭 아래가 끊기거나 겹쳐 보이지 않게, 고른 탭 위치를 재서 탭+종이 모양 전체를 하나의 선으로 만든다.
 */
@Composable
private fun Notebook(
    tab: Int,
    onTab: (Int) -> Unit,
    star: Int,
    onShop: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val tabHeight = 44.dp
    var rootX by remember { mutableFloatStateOf(0f) }
    var tabLeft by remember { mutableFloatStateOf(0f) }
    var tabWidth by remember { mutableFloatStateOf(0f) }

    Column(
        modifier
            .onGloballyPositioned { rootX = it.positionInWindow().x }
            .drawWithCache {
                val stroke = 2.dp.toPx()
                val h = stroke / 2 - WobbleAmp.toPx()   // 선이 흔들려도 흰 바탕이 선 밖으로 안 나오게
                val top = tabHeight.toPx()
                val r = 18.dp.toPx()       // 종이 모서리
                val r0 = 4.dp.toPx()       // 종이 왼쪽 위 (공책 느낌)
                val rt = 12.dp.toPx()      // 탭 모서리
                val right = size.width - h
                val bottom = size.height - h
                val x1 = tabLeft - rootX
                val x2 = x1 + tabWidth
                val base = android.graphics.Path().apply {
                    if (tabWidth > 0f) {
                        moveTo(x1, top)
                        lineTo(x1, h + rt)
                        quadTo(x1, h, x1 + rt, h)
                        lineTo(x2 - rt, h)
                        quadTo(x2, h, x2, h + rt)
                        lineTo(x2, top)
                    } else {
                        moveTo(h + r0, top)
                    }
                    lineTo(right - r, top)
                    quadTo(right, top, right, top + r)
                    lineTo(right, bottom - r)
                    quadTo(right, bottom, right - r, bottom)
                    lineTo(h + r, bottom)
                    quadTo(h, bottom, h, bottom - r)
                    lineTo(h, top + r0)
                    quadTo(h, top, h + r0, top)
                    close()
                }
                val line = wobble(base, seed = 21)
                onDrawWithContent {
                    drawPath(line, Color.White)   // 흔들린 선과 똑같은 모양으로 채운다
                    drawContent()
                    drawPath(line, RoadKit.Ink, style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
                }
            },
    ) {
        // 탭 + 별
        Row(
            Modifier.fillMaxWidth().height(tabHeight).padding(start = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("내 기록", "꾸미기").forEachIndexed { i, label ->
                if (i == tab) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .onGloballyPositioned {
                                tabLeft = it.positionInWindow().x
                                tabWidth = it.size.width.toFloat()
                            }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = hanaText(21.sp, lineHeight = 1.2f), color = RoadKit.Ink) }
                } else {
                    Box(
                        Modifier
                            .height(38.dp)
                            .tabDashed(KidBoard.TabDash, 12.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .clickable { onTab(i) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = hanaText(19.sp, FontWeight.Normal, 1.2f), color = RoadKit.InkMuted) }
                }
            }
            Spacer(Modifier.weight(1f))
            StarCount(star, Modifier.padding(bottom = 8.dp, end = 4.dp), onShop)
        }

        // 종이 안쪽
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)),
        ) { content() }
    }
}

/** 탭 모양 점선 (왼쪽 · 위 · 오른쪽만, 아래는 열림) */
private fun Modifier.tabDashed(color: Color, radius: Dp) = drawWithContent {
    drawContent()
    val w = 1.6.dp.toPx()
    val r = radius.toPx()
    val h = size.height
    val x1 = w / 2
    val x2 = size.width - w / 2
    val p = Path().apply {
        moveTo(x1, h)
        lineTo(x1, r)
        quadraticBezierTo(x1, w / 2, r, w / 2)
        lineTo(x2 - r, w / 2)
        quadraticBezierTo(x2, w / 2, x2, r)
        lineTo(x2, h)
    }
    drawPath(p, color, style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
}

@Composable
private fun StarCount(count: Int, modifier: Modifier, onClick: () -> Unit) {
    Text(
        buildAnnotatedString {
            append("별 ")
            withStyle(SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RoadKit.Star)) { append("${count}개") }
        },
        style = hanaText(17.sp, FontWeight.Normal, 1.3f),
        color = RoadKit.InkMuted,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/* ---------- 꾸미기 ---------- */

@Composable
private fun StickerPaper(
    placed: List<PlacedSticker>,
    emptyText: String,
    modifier: Modifier,
    onMove: (Sticker, Float, Float) -> Unit,
    onRemove: (Sticker) -> Unit,
) {
    val today = LocalDate.now()
    val dateLabel = "${today.monthValue}월 ${today.dayOfMonth}일 ${today.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.KOREAN)}"

    BoxWithConstraints(
        modifier
            .drawBehind {
                // 모눈
                val step = 22.dp.toPx()
                val line = 1.dp.toPx()
                var y = step
                while (y < size.height) { drawRect(KidBoard.Grid, Offset(0f, y), Size(size.width, line)); y += step }
                var x = step
                while (x < size.width) { drawRect(KidBoard.Grid, Offset(x, 0f), Size(line, size.height)); x += step }
            },
    ) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val halfPx = with(density) { (KidBoard.StickerSize / 2).toPx() }

        Text(
            dateLabel,
            style = hanaText(18.sp, FontWeight.Normal, 1.3f),
            color = RoadKit.InkMuted,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        )

        if (placed.isEmpty()) {
            Text(
                emptyText,
                style = hanaText(22.sp, lineHeight = 1.35f),
                color = RoadKit.InkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        placed.forEach { p ->
            key(p.sticker) {
                var pos by remember(p.sticker) { mutableStateOf(Offset(p.x * wPx, p.y * hPx)) }
                Box(
                    Modifier
                        .offset { IntOffset((pos.x - halfPx).roundToInt(), (pos.y - halfPx).roundToInt()) }
                        .size(KidBoard.StickerSize)
                        .pointerInput(p.sticker) {
                            detectDragGestures(
                                onDragEnd = { onMove(p.sticker, pos.x / wPx, pos.y / hPx) },
                            ) { change, drag ->
                                change.consume()
                                pos = Offset(
                                    (pos.x + drag.x).coerceIn(halfPx, wPx - halfPx),
                                    (pos.y + drag.y).coerceIn(halfPx, hPx - halfPx),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p.sticker.emoji, fontSize = 44.sp)
                    // 떼기 (대표색)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(RoadKit.Accent)
                            .clickable { onRemove(p.sticker) },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }
                }
            }
        }
    }
}

/* ---------- 내 기록 ---------- */

@Composable
private fun RecordPaper(
    missionCount: Int,
    records: List<KidRecord>,
    modifier: Modifier,
    onAttach: () -> Unit,
    onOpenPage: (LocalDate) -> Unit,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Text(
            buildAnnotatedString {
                append("지금까지 미션 ")
                withStyle(SpanStyle(color = RoadKit.Star)) { append("${missionCount}번") }
                append("을 했어!")
            },
            style = hanaText(24.sp, lineHeight = 1.3f),
            color = RoadKit.Ink,
        )
        Spacer(Modifier.height(16.dp))

        if (records.isEmpty()) {
            Text(
                "아직 기록이 없어.\n꾸미기에서 오늘 페이지를 저장하면\n여기에 하나씩 쌓여!",
                style = hanaText(20.sp, FontWeight.Normal, 1.4f),
                color = RoadKit.InkMuted,
            )
            return@Column
        }

        val today = LocalDate.now()
        val byDate = records.sortedByDescending { it.date }.groupBy { it.date }

        // 점선 길 (왼쪽 세로줄)
        Column(
            Modifier.drawBehind {
                val x = 18.dp.toPx()
                drawLine(
                    RoadKit.Road,
                    Offset(x, 4.dp.toPx()),
                    Offset(x, size.height - 4.dp.toPx()),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 10.dp.toPx())),
                )
            },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            byDate.entries.forEachIndexed { gi, (date, list) ->
                val isToday = date == today
                val label = (if (isToday) "오늘 · " else "") +
                        "${date.monthValue}월 ${date.dayOfMonth}일" +
                        (if (isToday) "" else " ${date.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.KOREAN)}")
                Text(
                    label,
                    style = hanaText(19.sp, if (isToday) FontWeight.Bold else FontWeight.Normal, 1.3f),
                    color = if (isToday) RoadKit.Ink else RoadKit.InkMuted,
                    modifier = Modifier.padding(start = 50.dp, top = if (gi == 0) 0.dp else 8.dp),
                )
                list.forEachIndexed { i, r -> RecordRow(r, seed = if (i % 2 == 0) 7 else 21, onAttach = onAttach, onOpenPage = onOpenPage) }
            }
        }
    }
}

@Composable
private fun RecordRow(r: KidRecord, seed: Int, onAttach: () -> Unit, onOpenPage: (LocalDate) -> Unit) {
    val tone = r.kind.tone()
    val other = if (seed == 7) 21 else 7
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).handOutline(18.dp, seed).clip(CircleShape).background(tone.bg))
        Spacer(Modifier.width(14.dp))
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .handOutline(16.dp, other)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .then(if (r.kind == KidRecordKind.PAGE) Modifier.clickable { onOpenPage(r.date) } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.kind.label, style = hanaText(16.sp, FontWeight.Normal, 1.2f), color = tone.title)
                Text(r.title, style = hanaText(21.sp, lineHeight = 1.25f), color = RoadKit.Ink)
                if (r.sub != null) Text(r.sub, style = hanaText(16.sp, FontWeight.Normal, 1.2f), color = tone.sub)
            }
            when (r.kind) {
                KidRecordKind.STICKER -> Unit
                KidRecordKind.PAGE -> {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "보기",
                        style = hanaText(17.sp, lineHeight = 1.2f).copy(textDecoration = TextDecoration.Underline),
                        color = tone.sub,
                    )
                }
                else -> {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "붙이기",
                        style = hanaText(17.sp, lineHeight = 1.2f).copy(textDecoration = TextDecoration.Underline),
                        color = tone.sub,
                        modifier = Modifier.clickable(onClick = onAttach),
                    )
                }
            }
        }
    }
}

/** 저장해 둔 날짜의 꾸미기 페이지 보기 (보기만 가능) */
@Composable
private fun PageViewer(date: LocalDate, stickers: List<PlacedSticker>, onClose: () -> Unit) {
    val title = "${date.monthValue}월 ${date.dayOfMonth}일 ${date.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.KOREAN)}"
    SpeakOnce("${title}에 꾸민 페이지야.")
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(4.dp)   // 손그림 선이 창 끝에 잘리지 않게
                .handOutline(24.dp, seed = 21)
                .clip(RoundedCornerShape(24.dp))
                .background(RoadKit.Background)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = hanaText(23.sp, lineHeight = 1.3f), color = RoadKit.Ink)
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .padding(2.dp)
                    .handOutline(16.dp, seed = 7)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .drawBehind {
                        val step = 18.dp.toPx()
                        val line = 1.dp.toPx()
                        var y = step
                        while (y < size.height) { drawRect(KidBoard.Grid, Offset(0f, y), Size(size.width, line)); y += step }
                        var x = step
                        while (x < size.width) { drawRect(KidBoard.Grid, Offset(x, 0f), Size(line, size.height)); x += step }
                    },
            ) {
                val half = 26.dp
                if (stickers.isEmpty()) {
                    Text(
                        "붙인 스티커가 없어",
                        style = hanaText(20.sp, FontWeight.Normal, 1.3f),
                        color = RoadKit.InkMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                stickers.forEach { p ->
                    Box(
                        Modifier
                            .offset(x = maxWidth * p.x - half, y = maxHeight * p.y - half)
                            .size(half * 2),
                        contentAlignment = Alignment.Center,
                    ) { Text(p.sticker.emoji, fontSize = 36.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(RoadKit.Chip)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("닫기", style = hanaText(20.sp, lineHeight = 1.2f), color = RoadKit.InkSub) }
        }
    }
}