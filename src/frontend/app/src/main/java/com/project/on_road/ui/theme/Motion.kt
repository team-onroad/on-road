package com.project.on_road.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 앱 전체 움직임 규칙. 화면 전환·내용 바뀜·태그 색 변화가 모두 같은 속도와 곡선으로 움직이게 한다.
 * (Material 3 emphasized 곡선: 시작은 빠르게, 끝은 천천히 내려앉는다)
 */
object OnRoadMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 화면이 들어올 때 */
    const val SCREEN_IN = 360
    /** 화면이 나갈 때 */
    const val SCREEN_OUT = 300
    /** 화면 안 내용이 바뀔 때 */
    const val CONTENT = 300
    /** 사라질 때 */
    const val FAST = 180

    fun <T> enter(ms: Int = CONTENT): FiniteAnimationSpec<T> = tween(ms, easing = EmphasizedDecelerate)
    fun <T> exit(ms: Int = FAST): FiniteAnimationSpec<T> = tween(ms, easing = EmphasizedAccelerate)
    fun <T> move(ms: Int = CONTENT): FiniteAnimationSpec<T> = tween(ms, easing = Emphasized)

    /* ---- 스프링: 위치·크기처럼 '움직이는' 것은 시간 대신 물리로 움직여 손에 붙는 느낌을 낸다 ---- */
    /** 튕김 없이 부드럽게 멈춘다 (화면 전환·펼치기·위치 이동) */
    const val SPATIAL_DAMPING = 1f
    const val SPATIAL_STIFFNESS = 300f
    /** 아주 살짝 튕긴다 (누름에서 돌아올 때·체크·하트) */
    const val BOUNCY_DAMPING = 0.72f
    const val BOUNCY_STIFFNESS = 480f

    fun <T> spatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(BOUNCY_DAMPING, BOUNCY_STIFFNESS)
    val spatialOffset: FiniteAnimationSpec<IntOffset> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntOffset.VisibilityThreshold)
    val spatialSize: FiniteAnimationSpec<IntSize> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntSize.VisibilityThreshold)
}
