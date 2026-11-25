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

    // 짧고 차분하게: 누른 뒤 움직임이 오래 남아 다음 손동작과 겹치지 않도록 0.3초 안팎에서 끝낸다
    /** 화면이 들어올 때 */
    const val SCREEN_IN = 320
    /** 화면이 나갈 때 (뒤로 가기) */
    const val SCREEN_OUT = 280
    /** 화면 안 내용이 바뀔 때 */
    const val CONTENT = 260
    /** 사라질 때 */
    const val FAST = 160
    /** 접히며 들어갈 때 (펼친 내용 · 패널 닫기) */
    const val COLLAPSE = 240
    /** 화면 전환 때 밀려 들어오는 거리 (화면 폭의 1/n). 폭 전체를 밀면 눈이 따라가느라 어지럽다 */
    const val SCREEN_SHIFT = 8
    /** 뒤로 갈 때 아래 화면이 물러나 있던 정도 · 옅어진 정도 (작을수록 덜 움직인다) */
    const val BACK_SHIFT = 12
    const val BACK_ALPHA = 0.9f

    /** 아주 부드럽게 내려앉는 곡선: 처음엔 또렷이 움직이고 끝은 길게 미끄러지듯 멈춘다 */
    val Soft = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun <T> enter(ms: Int = CONTENT): FiniteAnimationSpec<T> = tween(ms, easing = Soft)
    /** 사라질 때: 처음부터 부드럽게 줄어들고 끝에서 툭 끊기지 않는 곡선 (예전 가속 곡선은 머뭇거리다 갑자기 사라졌다) */
    fun <T> exit(ms: Int = FAST): FiniteAnimationSpec<T> = tween(ms, easing = ExitEasing)
    val ExitEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    /** 접히며 들어갈 때: 스프링 꼬리 없이 정해진 시간에 깔끔하게 닫힌다 */
    fun <T> collapse(ms: Int = COLLAPSE): FiniteAnimationSpec<T> = tween(ms, easing = Emphasized)
    fun <T> move(ms: Int = CONTENT): FiniteAnimationSpec<T> = tween(ms, easing = Soft)

    /* ---- 스프링: 위치·크기처럼 '움직이는' 것은 시간 대신 물리로 움직여 손에 붙는 느낌을 낸다 ---- */
    /** 튕김 없이 부드럽게 멈춘다 (화면 전환·펼치기·위치 이동) */
    const val SPATIAL_DAMPING = 1f
    const val SPATIAL_STIFFNESS = 500f // 0.3초 안팎에 멈춘다 (120 은 1초 가까이 미끄러졌다)
    /** 거의 튕기지 않고 빠르게 돌아온다 (누름에서 돌아올 때·체크) */
    const val BOUNCY_DAMPING = 0.9f
    const val BOUNCY_STIFFNESS = 700f

    fun <T> spatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(BOUNCY_DAMPING, BOUNCY_STIFFNESS)
    val spatialOffset: FiniteAnimationSpec<IntOffset> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntOffset.VisibilityThreshold)
    val spatialSize: FiniteAnimationSpec<IntSize> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntSize.VisibilityThreshold)
}
