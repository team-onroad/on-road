package com.project.on_road.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadColors
import kotlin.math.exp
import kotlin.math.min

enum class BuddyMood { IDLE, HAPPY }

/** 캐릭터 시트의 포즈 5종 */
enum class BuddyPose { STUDY, REST, JOURNEY, CARE, TOMORROW }

private val LeafColor = Color(0xFFBFE6C9)

private fun parse(d: String): Path =
    PathParser().parsePathString(d).toPath().apply { fillType = PathFillType.EvenOdd }

/** 경로는 한 번만 파싱해서 재사용 */
private object BuddyPaths {
    val silhouette by lazy { parse(BuddyArt.MAIN_SILHOUETTE) }
    val body by lazy { parse(BuddyArt.MAIN_BODY) }
    val sprout by lazy { parse(BuddyArt.MAIN_SPROUT) }
    val leaf by lazy { parse(BuddyArt.MAIN_LEAF) }
    val eyeL by lazy { parse(BuddyArt.MAIN_EYE_L) }
    val eyeR by lazy { parse(BuddyArt.MAIN_EYE_R) }
    val nose by lazy { parse(BuddyArt.MAIN_NOSE) }
    val star by lazy { parse(BuddyArt.MAIN_STAR) }

    private val poses = mutableMapOf<BuddyPose, PoseArt>()
    fun pose(p: BuddyPose): PoseArt = poses.getOrPut(p) {
        when (p) {
            BuddyPose.STUDY -> PoseArt(BuddyArt.STUDY_W, BuddyArt.STUDY_H, parse(BuddyArt.STUDY_SILHOUETTE), parse(BuddyArt.STUDY_LINE))
            BuddyPose.REST -> PoseArt(BuddyArt.REST_W, BuddyArt.REST_H, parse(BuddyArt.REST_SILHOUETTE), parse(BuddyArt.REST_LINE))
            BuddyPose.JOURNEY -> PoseArt(BuddyArt.JOURNEY_W, BuddyArt.JOURNEY_H, parse(BuddyArt.JOURNEY_SILHOUETTE), parse(BuddyArt.JOURNEY_LINE))
            BuddyPose.CARE -> PoseArt(BuddyArt.CARE_W, BuddyArt.CARE_H, parse(BuddyArt.CARE_SILHOUETTE), parse(BuddyArt.CARE_LINE))
            BuddyPose.TOMORROW -> PoseArt(BuddyArt.TOMORROW_W, BuddyArt.TOMORROW_H, parse(BuddyArt.TOMORROW_SILHOUETTE), parse(BuddyArt.TOMORROW_LINE))
        }
    }
}

private class PoseArt(val w: Float, val h: Float, val silhouette: Path, val line: Path)

/**
 * 온로드 메인 캐릭터 (원본 시트에서 추출한 벡터).
 * 움직임: 숨쉬기, 새싹 살랑임, 눈 깜빡임, 점선이 별을 향해 반짝이며 흐름, 별 반짝임.
 * HAPPY: 몸을 좌우로 신나게 흔들고 눈웃음을 짓는다.
 */
@Composable
fun OnRoadBuddy(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    mood: BuddyMood = BuddyMood.IDLE,
    showTrail: Boolean = true,
    animated: Boolean = true,
    lineColor: Color = OnRoadColors.TextPrimary,
    bodyColor: Color = Color.White,
    trailColor: Color = OnRoadColors.TextPrimary,
    starColor: Color = OnRoadColors.Star,
    trailReveal: Float = 1f,
    sproutGrow: Float = 1f
) {
    val happy = mood == BuddyMood.HAPPY
    val t = rememberInfiniteTransition(label = "buddy")

    val breath by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (happy) 450 else 1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath"
    )
    val sway by t.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(if (happy) 380 else 1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway"
    )
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 3800
                1f at 0
                1f at 3300
                0.08f at 3400
                1f at 3530
            }
        ),
        label = "blink"
    )
    val flow by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "flow"
    )
    val twinkle by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "twinkle"
    )
    val a = if (animated) 1f else 0f

    Canvas(modifier.size(size)) {
        // 보여줄 영역: 점선 포함 전체 또는 캐릭터만
        val box = if (showTrail) floatArrayOf(0f, 0f, BuddyArt.MAIN_W, BuddyArt.MAIN_H) else BuddyArt.CHAR_BOX
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]
        val s = min(this.size.width / bw, this.size.height / bh)
        val ox = (this.size.width - bw * s) / 2f - box[0] * s
        val oy = (this.size.height - bh * s) / 2f - box[1] * s

        withTransform({
            translate(ox, oy)
            scale(s, s, pivot = Offset.Zero)
        }) {
            if (showTrail) drawTrail(flow * a, twinkle * a, trailColor, starColor, trailReveal)

            val ground = Offset(BuddyArt.GROUND_X, BuddyArt.GROUND_Y)
            val wiggle = if (happy) sway * 5f * a else 0f
            val breathe = 1f + breath * a * (if (happy) 0.05f else 0.018f)

            rotate(wiggle, pivot = ground) {
                scale(1f - (breathe - 1f) * 0.5f, breathe, pivot = ground) {
                    drawPath(BuddyPaths.silhouette, bodyColor)
                    drawPath(BuddyPaths.body, lineColor)

                    // 눈: 깜빡임 / 기쁠 때는 눈웃음
                    val eyeScale = if (happy) 0.35f else 1f - (1f - blink) * a
                    drawEye(BuddyPaths.eyeL, BuddyArt.EYE_L_C, eyeScale, lineColor)
                    drawEye(BuddyPaths.eyeR, BuddyArt.EYE_R_C, eyeScale, lineColor)
                    drawPath(BuddyPaths.nose, lineColor)

                    // 새싹
                    rotate(
                        degrees = sway * a * (if (happy) 14f else 7f),
                        pivot = Offset(BuddyArt.SPROUT_PIVOT_X, BuddyArt.SPROUT_PIVOT_Y)
                    ) {
                        val g = sproutGrow.coerceIn(0f, 1.2f)
                        scale(g, g, pivot = Offset(BuddyArt.SPROUT_PIVOT_X, BuddyArt.SPROUT_PIVOT_Y)) {
                            drawPath(BuddyPaths.leaf, LeafColor)
                            drawPath(BuddyPaths.sprout, lineColor)
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawEye(path: Path, c: FloatArray, scaleY: Float, color: Color) {
    scale(1f, scaleY.coerceAtLeast(0.05f), pivot = Offset(c[0], c[1])) {
        drawPath(path, color)
    }
}

/** 점선이 별 쪽으로 반짝이며 흐르고, 별이 반짝인다. */
private fun DrawScope.drawTrail(flow: Float, twinkle: Float, dotColor: Color, starColor: Color, reveal: Float = 1f) {
    val dots = BuddyArt.DOTS
    val count = dots.size / 3
    for (i in 0 until count) {
        val x = dots[i * 3]
        val y = dots[i * 3 + 1]
        val r = dots[i * 3 + 2]
        // 흐르는 빛: flow 위치 근처의 점이 커지고 밝아짐
        val pos = i / (count - 1f)
        val d = pos - flow
        val pulse = exp(-(d * d) / 0.012f)
        // reveal: 아래 왼쪽부터 한 점씩 톡톡 나타남
        val appear = ((reveal * count) - i).coerceIn(0f, 1f)
        if (appear <= 0f) continue
        drawCircle(dotColor.copy(alpha = (0.45f + 0.55f * pulse) * appear), r * (1f + 0.6f * pulse) * appear, Offset(x, y))
    }
    val starAppear = ((reveal - 0.92f) / 0.08f).coerceIn(0f, 1f)
    if (starAppear <= 0f) return
    val c = Offset(BuddyArt.STAR_C[0], BuddyArt.STAR_C[1])
    val starScale = (0.88f + 0.22f * twinkle) * starAppear
    rotate(twinkle * 12f, pivot = c) {
        scale(starScale, starScale, pivot = c) {
            drawPath(BuddyPaths.star, starColor)
        }
    }
}

/** 캐릭터 시트의 포즈 일러스트. 숨쉬듯 살짝 움직인다. */
@Composable
fun OnRoadBuddyPose(
    pose: BuddyPose,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    animated: Boolean = true,
    lineColor: Color = OnRoadColors.TextPrimary,
    bodyColor: Color = Color.White
) {
    val t = rememberInfiniteTransition(label = "pose")
    val breath by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "poseBreath"
    )
    val a = if (animated) 1f else 0f

    Canvas(modifier.size(size)) {
        val art = BuddyPaths.pose(pose)
        val s = min(this.size.width / art.w, this.size.height / art.h)
        val ox = (this.size.width - art.w * s) / 2f
        val oy = (this.size.height - art.h * s) / 2f
        withTransform({
            translate(ox, oy)
            scale(s, s, pivot = Offset.Zero)
        }) {
            val bottom = Offset(art.w / 2f, art.h)
            val k = 1f + breath * a * 0.025f
            scale(1f, k, pivot = bottom) {
                drawPath(art.silhouette, bodyColor)
                drawPath(art.line, lineColor)
            }
        }
    }
}
