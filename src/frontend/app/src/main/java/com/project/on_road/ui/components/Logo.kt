package com.project.on_road.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** 로고 색 (Group_207.svg) */
object LogoColors {
    val Mark = Color(0xFF0F172A)
    val Dot = Color(0xFF34D399)
}

// 원본 SVG 좌표 (viewBox 367 x 374)
private const val VIEW_W = 367f
private const val VIEW_H = 374f
private const val MARK_LEFT = 86f
private const val MARK_TOP = 81f
private const val MARK_RIGHT = 281f
private const val MARK_BOTTOM = 254f
private const val DOT_X = 183f
private const val DOT_Y = 235f
private const val DOT_R = 19f

/** 길(A) 모양: M183.5 81 L281 253.5 L221 254 L183.5 179 L146.268 253.5 H86 Z */
private fun markPath() = Path().apply {
    moveTo(183.5f, 81f)
    lineTo(281f, 253.5f)
    lineTo(221f, 254f)
    lineTo(183.5f, 179f)
    lineTo(146.268f, 253.5f)
    lineTo(86f, 253.5f)
    close()
}

/**
 * 온로드 로고.
 * - withBackground = false: 마크만 (상단바, 작은 자리)
 * - withBackground = true : 흰 둥근 사각형 포함 (앱 아이콘 모양)
 * 애니메이션용 값
 * - reveal  : 0 → 1 길이 아래에서 위로 올라오며 나타남
 * - dotDrop : 0 → 1 점이 위에서 떨어져 제자리로 (1 넘으면 아래로 눌림, 스프링 값 그대로 넣으면 됨)
 * - dotScale: 점 크기 (톡 튀는 효과)
 */
@Composable
fun OnRoadLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    withBackground: Boolean = false,
    reveal: Float = 1f,
    dotDrop: Float = 1f,
    dotScale: Float = 1f,
    markColor: Color = LogoColors.Mark,
    dotColor: Color = LogoColors.Dot,
    backgroundColor: Color = Color.White,
) {
    val path = markPath()
    Canvas(modifier.size(size)) {
        // 보여줄 영역: 배경 포함이면 전체, 아니면 마크만 (점이 떨어지는 공간 포함)
        val left = if (withBackground) 0f else MARK_LEFT
        val top = if (withBackground) 0f else MARK_TOP
        val right = if (withBackground) VIEW_W else MARK_RIGHT
        val bottom = if (withBackground) VIEW_H else MARK_BOTTOM
        val bw = right - left
        val bh = bottom - top
        val s = min(this.size.width / bw, this.size.height / bh)
        val ox = (this.size.width - bw * s) / 2f - left * s
        val oy = (this.size.height - bh * s) / 2f - top * s

        translate(ox, oy) {
            scale(s, s, pivot = Offset.Zero) {
                if (withBackground) {
                    drawRoundRect(backgroundColor, cornerRadius = CornerRadius(100f, 100f), size = Size(VIEW_W, VIEW_H))
                }
                // 길: 아래에서 위로 드러남
                val r = reveal.coerceIn(0f, 1f)
                if (r > 0f) {
                    val clipTop = MARK_BOTTOM - (MARK_BOTTOM - MARK_TOP + 2f) * r
                    clipRect(left = MARK_LEFT - 2f, top = clipTop, right = MARK_RIGHT + 2f, bottom = MARK_BOTTOM + 2f) {
                        drawPath(path, markColor)
                    }
                }
                // 점: 위에서 떨어짐 (0 = 마크 위쪽 밖, 1 = 제자리)
                val startY = MARK_TOP - 70f
                val y = startY + (DOT_Y - startY) * dotDrop
                val alpha = (dotDrop * 3f).coerceIn(0f, 1f)
                if (alpha > 0f && dotScale > 0f) {
                    drawCircle(dotColor.copy(alpha = dotColor.alpha * alpha), DOT_R * dotScale, Offset(DOT_X, y))
                }
            }
        }
    }
}
