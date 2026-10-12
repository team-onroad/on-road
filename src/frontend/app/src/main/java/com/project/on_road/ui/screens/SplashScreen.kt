package com.project.on_road.ui.screens

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.project.on_road.data.AppContainer
import com.project.on_road.ui.components.LogoColors
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 스플래시 (약 2.6초, 어두운 배경)
 * 1) 깜깜한 화면에 흰 길(A)이 크게 서 있다
 * 2) 아래에서 초록 동그라미가 길을 따라 부드럽게 올라와 제자리에 멈춘다
 * 3) 화면이 뒤로 빠지며(줌 아웃) 흰 둥근 사각형이 나타나고, 길은 남색으로 바뀌어 로고가 된다
 * 4) '온로드'와 슬로건이 올라온다 → 다음 화면
 * 애니메이션과 동시에 저장된 user_id를 서버에서 확인한다.
 */

// 로고 원본 좌표 (Group_207.svg, viewBox 367 x 374)
private const val VIEW_W = 367f
private const val VIEW_H = 374f
private const val CENTER_X = VIEW_W / 2f
private const val CENTER_Y = VIEW_H / 2f
private const val DOT_X = 183f
private const val DOT_Y = 235f
private const val DOT_R = 19f
/** 처음 확대 배율 (클수록 길이 화면을 꽉 채움) */
private const val ZOOM_START = 2.6f

private val SplashBg = Color(0xFF0B1120)

/** 부드럽게 시작해 천천히 멈추는 곡선 (튕김 없음) */
private val Smooth = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private fun roadPath() = Path().apply {
    moveTo(183.5f, 81f)
    lineTo(281f, 253.5f)
    lineTo(221f, 254f)
    lineTo(183.5f, 179f)
    lineTo(146.268f, 253.5f)
    lineTo(86f, 253.5f)
    close()
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val road = remember { Animatable(0f) }   // 길 나타남
    val dot = remember { Animatable(0f) }    // 0 = 화면 아래, 1 = 제자리
    val zoom = remember { Animatable(0f) }   // 0 = 확대, 1 = 원래 크기 (로고 완성)
    val text = remember { Animatable(0f) }
    val path = remember { roadPath() }

    // 어두운 화면이라 상태바 아이콘을 흰색으로, 나갈 때 원래대로
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = before ?: true
            controller?.isAppearanceLightNavigationBars = before ?: true
        }
    }

    LaunchedEffect(Unit) {
        val verify = launch { AppContainer.verifySession() }
        road.animateTo(1f, tween(500, easing = Smooth))
        // 동그라미가 아래에서 부드럽게 올라와 천천히 멈춤
        dot.animateTo(1f, tween(900, easing = Smooth))
        // 동그라미가 멈추는 것과 이어지게 바로 줌 아웃, 끝나갈 때 글자가 겹쳐서 올라옴
        launch { zoom.animateTo(1f, tween(1000, easing = Smooth)) }
        delay(650)
        text.animateTo(1f, tween(600, easing = Smooth))
        delay(450)
        verify.join()
        onFinished()
    }

    Box(Modifier.fillMaxSize().background(SplashBg), contentAlignment = Alignment.Center) {
        val logoSize = 120.dp
        Canvas(Modifier.fillMaxSize()) {
            val unit = logoSize.toPx() / VIEW_W                  // 로고 1단위 → 픽셀
            val z = ZOOM_START + (1f - ZOOM_START) * zoom.value  // 현재 배율
            val s = unit * z
            val center = Offset(size.width / 2f, size.height / 2f - 48.dp.toPx())

            translate(center.x - CENTER_X * s, center.y - CENTER_Y * s) {
                scale(s, s, pivot = Offset.Zero) {
                    // 흰 둥근 사각형: 줌 아웃되면서 나타남
                    val card = ((zoom.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    if (card > 0f) {
                        drawRoundRect(
                            Color.White.copy(alpha = card),
                            cornerRadius = CornerRadius(100f, 100f),
                            size = Size(VIEW_W, VIEW_H),
                        )
                    }
                    // 길: 흰색 → 남색
                    drawPath(path, lerp(Color.White, LogoColors.Mark, card).copy(alpha = road.value))

                    // 동그라미: 화면 아래 밖에서 제자리로
                    val startY = (size.height - center.y) / s + CENTER_Y + DOT_R * 2f
                    val y = startY + (DOT_Y - startY) * dot.value
                    if (dot.value > 0f) drawCircle(LogoColors.Dot, DOT_R, Offset(DOT_X, y))
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(y = 72.dp)
                .graphicsLayer {
                    alpha = text.value
                    translationY = (1f - text.value) * 30f
                },
        ) {
            Text("온로드", style = OnRoadType.Hero, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("너의 오늘도, 성장하는 길 위에", style = OnRoadType.Caption, color = OnRoadColors.TextQuaternary)
        }
    }
}