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
    const val SCREEN_IN = 420
    /** 화면이 나갈 때 (뒤로 가기) */
    const val SCREEN_OUT = 380
    /** 화면 안 내용이 바뀔 때 */
    const val CONTENT = 360
    /** 사라질 때 */
    const val FAST = 240
    /** 접히며 들어갈 때 (펼친 내용 · 패널 닫기) */
    const val COLLAPSE = 320
    /** 뒤로 갈 때 아래 화면이 물러나 있던 정도 · 옅어진 정도 (작을수록 덜 움직인다) */
    const val BACK_SHIFT = 5
    const val BACK_ALPHA = 0.85f

    fun <T> enter(ms: Int = CONTENT): FiniteAnimationSpec<T> = tween(ms, easing = EmphasizedDecelerate)
    /** 사라질 때: 처음부터 부드럽게 줄어들고 끝에서 툭 끊기지 않는 곡선 (예전 가속 곡선은 머뭇거리다 갑자기 사라졌다) */
    fun <T> exit(ms: Int = FAST): FiniteAnimationSpec<T> = tween(ms, easing = ExitEasing)
    val ExitEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    /** 접히며 들어갈 때: 스프링 꼬리 없이 정해진 시간에 깔끔하게 닫힌다 */
    fun <T> collapse(ms: Int = COLLAPSE): FiniteAnimationSpec<T> = tween(ms, easing = Emphasized)
    fun <T> move(ms: Int = CONTENT): FiniteAnimationSpec<T> = tween(ms, easing = Emphasized)

    /* ---- 스프링: 위치·크기처럼 '움직이는' 것은 시간 대신 물리로 움직여 손에 붙는 느낌을 낸다 ---- */
    /** 튕김 없이 부드럽게 멈춘다 (화면 전환·펼치기·위치 이동) */
    const val SPATIAL_DAMPING = 1f
    const val SPATIAL_STIFFNESS = 200f
    /** 아주 살짝 튕긴다 (누름에서 돌아올 때·체크·하트) */
    const val BOUNCY_DAMPING = 0.8f
    const val BOUNCY_STIFFNESS = 380f

    fun <T> spatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(BOUNCY_DAMPING, BOUNCY_STIFFNESS)
    val spatialOffset: FiniteAnimationSpec<IntOffset> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntOffset.VisibilityThreshold)
    val spatialSize: FiniteAnimationSpec<IntSize> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntSize.VisibilityThreshold)
}
