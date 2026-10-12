package com.project.on_road.ui.child

import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.on_road.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * 아동 화면 공통 (시안 D · 길)
 * - 손글씨(hana), 색, 손으로 그린 듯한 테두리, 점선 그림 자리, 하위 화면 헤더
 * - 아동 화면 파일들이 모두 이걸 같이 쓴다.
 */

/* ==================== 여기서 바꾸기 ==================== */
internal object RoadKit {
    val Hana = FontFamily(Font(R.font.hana))

    val Background = Color(0xFFFFFEFB)   // 화면 배경 (거의 흰색)
    val Ink = Color(0xFF2E2A26)          // 기본 글씨 · 손그림 테두리
    val InkSub = Color(0xFF6B5F55)       // 작은 설명 글씨
    val InkMuted = Color(0xFF8A7C6E)     // 더 연한 글씨
    val Body = Color(0xFF4A4038)         // 카드 본문 글씨
    val Road = Color(0xFFCDBCA4)         // 점선 길 · 점선 자리
    val Chip = Color(0xFFF3EEE6)         // 헤더 동그란 버튼 배경
    val Accent = Color(0xFF0F172A)       // 대표색 (진한 남색) — 시작하기, 내 채팅, 눌러서 말하기, 다음 등
    val Star = Color(0xFF7A5410)         // 별 개수 글씨
}

/** 정거장 색 묶음: 배경 · 점선 자리 · 제목 · 설명 */
internal data class StopTone(val bg: Color, val slot: Color, val title: Color, val sub: Color)

internal object Tones {
    val Blue = StopTone(Color(0xFFE6EEFB), Color(0xFF8FA9D6), Color(0xFF2F4E86), Color(0xFF1C2941))   // 상담하기
    val Pink = StopTone(Color(0xFFFBE3E7), Color(0xFFE09AA8), Color(0xFFA33A50), Color(0xFF5C2E37))   // 좋아하는 거 찾기
    val Purple = StopTone(Color(0xFFECE6FA), Color(0xFFA796D9), Color(0xFF523F8C), Color(0xFF372D57)) // 내 스티커
    val Yellow = StopTone(Color(0xFFFDF0C7), Color(0xFFD2B160), Color(0xFF7A5410), Color(0xFF473513)) // 스티커 가게
    val Lime = StopTone(Color(0xFFF4FAE6), Color(0xFF8FB45E), Color(0xFF3D5A12), Color(0xFF4F6328))   // 도움이 필요해
}
/* ====================================================== */

/** 손글씨 글씨 스타일. 줄 높이를 넉넉히 줘서 글자 위아래가 잘리지 않게 한다. */
internal fun hanaText(size: TextUnit, weight: FontWeight = FontWeight.Bold, lineHeight: Float = 1.4f) =
    TextStyle(fontFamily = RoadKit.Hana, fontSize = size, fontWeight = weight, lineHeight = size * lineHeight)

/**
 * 손으로 그린 듯한 테두리 (살짝 울퉁불퉁한 선).
 * clip/background 보다 **앞에** 붙여야 선이 잘리지 않는다.
 *   Modifier.handOutline(18.dp).clip(shape).background(...)
 * seed 를 다르게 주면 선 모양이 달라진다.
 */
internal fun Modifier.handOutline(
    radius: Dp,
    seed: Int = 7,
    color: Color = RoadKit.Ink,
    width: Dp = 2.dp,
    topStart: Dp = radius,
    topEnd: Dp = radius,
    bottomEnd: Dp = radius,
    bottomStart: Dp = radius,
): Modifier = drawWithCache {
    val w = width.toPx()
    val half = min(size.width, size.height) / 2f
    fun r(d: Dp) = min(d.toPx(), half)
    val ts = r(topStart)
    val te = r(topEnd)
    val be = r(bottomEnd)
    val bs = r(bottomStart)
    // 선이 안쪽으로 흔들려도 배경이 선 밖으로 비치지 않게, 기준선을 흔들림 폭만큼 바깥으로 둔다
    val inset = w / 2 - WobbleAmp.toPx()
    val base = android.graphics.Path().apply {
        addRoundRect(
            RectF(inset, inset, size.width - inset, size.height - inset),
            floatArrayOf(ts, ts, te, te, be, be, bs, bs),
            android.graphics.Path.Direction.CW,
        )
    }
    val path = wobble(base, seed)
    onDrawWithContent {
        drawContent()
        drawPath(path, color, style = Stroke(width = w, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

/** 닫힌 선(base)을 손으로 그린 듯 살짝 흔들어서 돌려준다. 탭+종이처럼 모양이 특이할 때도 쓴다. */
/** 손그림 선이 흔들리는 폭 */
internal val WobbleAmp = 1.1.dp

internal fun Density.wobble(base: android.graphics.Path, seed: Int, amplitude: Dp = WobbleAmp): Path {
    val measure = android.graphics.PathMeasure(base, true)
    val len = max(measure.length, 1f)
    val rnd = Random(seed)
    val p1 = rnd.nextFloat() * 6.28f
    val p2 = rnd.nextFloat() * 6.28f
    val p3 = rnd.nextFloat() * 6.28f
    // 한 바퀴에 정수 번 흔들리게 해서 이음새가 안 보이게 한다
    fun cycles(wave: Dp) = max(1, (len / wave.toPx()).roundToInt())
    val f1 = 2f * PI.toFloat() * cycles(46.dp) / len
    val f2 = 2f * PI.toFloat() * cycles(19.dp) / len
    val f3 = 2f * PI.toFloat() * cycles(97.dp) / len
    val amp = amplitude.toPx()
    val step = 2.dp.toPx()
    val pos = FloatArray(2)
    val tan = FloatArray(2)
    val path = Path()
    var d = 0f
    var first = true
    while (d < len) {
        measure.getPosTan(d, pos, tan)
        val n = amp * (0.55f * sin(d * f1 + p1) + 0.3f * sin(d * f2 + p2) + 0.15f * sin(d * f3 + p3))
        val x = pos[0] - tan[1] * n
        val y = pos[1] + tan[0] * n
        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        d += step
    }
    path.close()
    return path
}

/** 점선 테두리 (그림 자리, 고르지 않은 카드 등) */
internal fun Modifier.dashedOutline(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(max(radius.toPx() - w / 2, 0f)),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** 그림 자리. image 가 있으면 그림을, 없으면 점선 칸을 보여 준다. */
@Composable
internal fun ImageSlot(
    modifier: Modifier,
    slotColor: Color,
    radius: Dp,
    @DrawableRes image: Int? = null,
    fill: Color = Color.Transparent,
) {
    if (image != null) {
        Image(painterResource(image), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier.clip(RoundedCornerShape(radius)).background(fill).dashedOutline(slotColor, radius))
    }
}

/** 헤더의 동그란 버튼 (뒤로, ?) */
@Composable
internal fun ChipButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(RoadKit.Chip).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 꺾쇠 (뒤로 ‹ / 앞으로 ›) */
@Composable
internal fun Chevron(left: Boolean, color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val p = Path().apply {
            if (left) { moveTo(15 * s, 6 * s); lineTo(9 * s, 12 * s); lineTo(15 * s, 18 * s) }
            else { moveTo(9 * s, 6 * s); lineTo(15 * s, 12 * s); lineTo(9 * s, 18 * s) }
        }
        drawPath(p, color, style = Stroke(width = 2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 하위 화면 헤더: 뒤로 · 색 동그라미 · 제목 · (?) */
@Composable
internal fun RoadSubHeader(title: String, badge: Color, onBack: () -> Unit, onHelp: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipButton(onBack) { Chevron(left = true, color = RoadKit.Ink) }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(26.dp).handOutline(13.dp, seed = 7).clip(CircleShape).background(badge))
        Spacer(Modifier.width(8.dp))
        Text(title, style = hanaText(26.sp), color = RoadKit.Ink, modifier = Modifier.weight(1f), maxLines = 1)
        if (onHelp != null) {
            ChipButton(onHelp) { Text("?", style = hanaText(24.sp, lineHeight = 1.1f), color = RoadKit.Ink) }
        }
    }
}