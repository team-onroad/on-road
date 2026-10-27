package com.project.on_road.ui.child

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.project.on_road.data.KidMood
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * 기분 얼굴 (시안 5) — 손그림 동그라미 + 살짝 흔들리는 선 표정. 이모지·블러셔·눈 반짝임 없음.
 * 좌표는 100 × 100 칸 기준으로 그리고, 실제 크기에 맞춰 늘린다.
 * 색은 아래 '여기서 바꾸기'.
 */

/* ==================== 여기서 바꾸기 ==================== */
private val Peach = StopTone(Color(0xFFFDE6D8), Color(0xFFE0A07C), Color(0xFF8A4A25), Color(0xFF4F2E1C))
private val Gray = StopTone(Color(0xFFEDEBE8), Color(0xFFB5ADA4), Color(0xFF5E5650), Color(0xFF3E3833))

internal fun KidMood.tone(): StopTone = when (this) {
    KidMood.EXCITED -> Tones.Yellow
    KidMood.GOOD -> Tones.Lime
    KidMood.CALM -> Tones.Mint
    KidMood.UNSURE -> Tones.Purple
    KidMood.TIRED -> Gray
    KidMood.WORRIED -> Peach
    KidMood.SAD -> Tones.Blue
    KidMood.ANGRY -> Tones.Pink
}
/* ====================================================== */

/** 기분 얼굴. 손그림 테두리는 RoadKit 의 handOutline 과 같은 선 */
@Composable
internal fun MoodFace(mood: KidMood, size: Dp, modifier: Modifier = Modifier, seed: Int = 7) {
    val tone = mood.tone()
    Box(
        modifier
            .size(size)   // 크기를 꼭 정해야 얼굴이 그려진다
            .handOutline(size / 2, seed, width = if (size < 40.dp) 1.4.dp else 2.dp)
            .clip(CircleShape)
            .background(tone.bg),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawFace(mood) }
    }
}

/* ---------- 손그림 선 도구 (100 칸 좌표) ---------- */

private class Pt(val x: Float, val y: Float)

private fun quad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, n: Int = 24): List<Pt> =
    (0..n).map { i ->
        val t = i / n.toFloat()
        val a = (1 - t) * (1 - t)
        val b = 2 * (1 - t) * t
        val c = t * t
        Pt(a * x0 + b * cx + c * x1, a * y0 + b * cy + c * y1)
    }

private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 12): List<Pt> =
    (0..n).map { i -> Pt(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n) }

private fun ring(cx: Float, cy: Float, r: Float, n: Int = 24): List<Pt> =
    (0 until n).map { i -> val a = 2 * PI * i / n; Pt(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat()) }

/** 선을 진행 방향의 옆으로 살짝 흔든다 (끝부분은 덜 흔든다) */
private fun wobble(pts: List<Pt>, amp: Float, seed: Int, closed: Boolean): List<Pt> {
    val rnd = Random(seed)
    val p1 = rnd.nextFloat() * 6.28f
    val p2 = rnd.nextFloat() * 6.28f
    val p3 = rnd.nextFloat() * 6.28f
    var len = 0f
    return pts.mapIndexed { i, p ->
        if (i > 0) len += hypot(p.x - pts[i - 1].x, p.y - pts[i - 1].y)
        val a = pts[(i - 1).coerceAtLeast(0)]
        val b = pts[(i + 1).coerceAtMost(pts.lastIndex)]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val d = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        var n = amp * (0.55f * sin(len * 0.13f + p1) + 0.3f * sin(len * 0.33f + p2) + 0.15f * sin(len * 0.065f + p3))
        if (!closed && pts.size > 6) n *= min(1f, min(i / 3f, (pts.lastIndex - i) / 3f))
        Pt(p.x - dy / d * n, p.y + dx / d * n)
    }
}

private fun DrawScope.pathOf(pts: List<Pt>, closed: Boolean): Path {
    val u = size.width / 100f
    return Path().apply {
        pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * u, p.y * u) else lineTo(p.x * u, p.y * u) }
        if (closed) close()
    }
}

private fun DrawScope.stroke(pts: List<Pt>, seed: Int, width: Float = 3.8f, color: Color = RoadKit.Ink, amp: Float = 0.7f) {
    val u = size.width / 100f
    drawPath(
        pathOf(wobble(pts, amp, seed, false), false),
        color,
        style = Stroke(width = width * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.fill(pts: List<Pt>, seed: Int, color: Color, amp: Float = 0.7f) {
    drawPath(pathOf(wobble(pts, amp, seed, true), true), color)
}

/** 손으로 찍은 듯한 눈 (살짝 찌그러진 점) */
private fun DrawScope.blob(x: Float, y: Float, rx: Float, ry: Float, seed: Int, color: Color = RoadKit.Ink) {
    val pts = (0 until 20).map { i -> val a = 2 * PI * i / 20; Pt(x + (rx * cos(a)).toFloat(), y + (ry * sin(a)).toFloat()) }
    fill(pts, seed, color, 0.45f)
}

/* ---------- 표정 (시안 5: 얼굴 밖 표시 없음) ---------- */

private fun DrawScope.drawFace(mood: KidMood) {
    when (mood) {
        KidMood.EXCITED -> {
            stroke(quad(27f, 45f, 34f, 35f, 41f, 45f), 1)
            stroke(quad(59f, 45f, 66f, 35f, 73f, 44f), 2)
            fill(quad(30f, 56f, 50f, 84f, 70f, 57f), 3, RoadKit.Ink)
        }
        KidMood.GOOD -> {
            blob(35f, 42f, 4.2f, 4.8f, 1)
            blob(65f, 42.5f, 4.4f, 4.6f, 2)
            stroke(quad(36f, 60f, 50f, 73f, 64f, 59f), 3)
        }
        KidMood.CALM -> {
            stroke(quad(27f, 43f, 34f, 51f, 41f, 43f), 1)
            stroke(quad(59f, 43f, 66f, 50f, 73f, 44f), 2)
            stroke(quad(42f, 62f, 50f, 68f, 58f, 61f), 3)
        }
        KidMood.UNSURE -> {
            blob(35f, 43f, 3.6f, 4f, 1)
            blob(65f, 41f, 4.6f, 5f, 2)
            stroke(quad(33f, 64f, 41f, 56f, 50f, 64f) + quad(50f, 64f, 59f, 72f, 67f, 63f).drop(1), 3)
        }
        KidMood.TIRED -> {
            stroke(line(27f, 44f, 41f, 44f), 1)
            stroke(line(59f, 44f, 73f, 45f), 2)
            stroke(quad(29f, 45f, 34f, 50f, 39f, 45f), 3, 3f)
            stroke(quad(61f, 45f, 66f, 50f, 71f, 46f), 4, 3f)
            val o = ring(50f, 64f, 5f)
            stroke(o + o.first(), 5, 3.4f, amp = 0.5f)
        }
        KidMood.WORRIED -> {
            stroke(line(27f, 34f, 40f, 29f), 1)
            stroke(line(73f, 33f, 60f, 29f), 2)
            blob(35f, 44f, 4.2f, 4.6f, 3)
            blob(65f, 43.5f, 4.2f, 4.8f, 4)
            stroke(quad(40f, 66f, 45f, 60f, 50f, 65f) + quad(50f, 65f, 55f, 70f, 60f, 64f).drop(1), 5)
        }
        KidMood.SAD -> {
            blob(35f, 44f, 4.2f, 4.8f, 1)
            blob(65f, 44.5f, 4.4f, 4.6f, 2)
            stroke(quad(38f, 68f, 50f, 57f, 62f, 68f), 3)
        }
        KidMood.ANGRY -> {
            stroke(line(27f, 30f, 41f, 36f), 1)
            stroke(line(73f, 30f, 59f, 36f), 2)
            blob(35f, 45f, 4.2f, 4.6f, 3)
            blob(65f, 45f, 4.4f, 4.6f, 4)
            stroke(quad(39f, 67f, 50f, 59f, 61f, 66f), 5)
        }
    }
}