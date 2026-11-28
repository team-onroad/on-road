package com.project.on_road.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color

private val OnRoadColorScheme = lightColorScheme(
    primary = OnRoadColors.Primary,
    onPrimary = Color.White,
    primaryContainer = OnRoadColors.PrimarySoft,
    onPrimaryContainer = OnRoadColors.Primary,
    secondary = OnRoadColors.TextSlate,
    background = OnRoadColors.Background,
    onBackground = OnRoadColors.TextPrimary,
    surface = OnRoadColors.Surface,
    onSurface = OnRoadColors.TextPrimary,
    surfaceVariant = OnRoadColors.SurfaceMuted,
    onSurfaceVariant = OnRoadColors.TextTertiary,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = OnRoadColors.Border,
    outlineVariant = OnRoadColors.Divider,
    error = OnRoadColors.Danger,
)

@Composable
fun OnRoadTheme(content: @Composable () -> Unit) {
    // 비율 맞춤: 디자인 기준 폭(390dp)에 맞춰 화면 전체를 같은 비율로 키우고 줄인다.
    // 갤럭시 S26(1080px, 약 384dp)처럼 폭이 조금씩 다른 휴대폰에서도 시안과 같은 배치가 나온다.
    // 글자 크기 설정은 따로 지키되, 아주 크게 키운 경우(1.3배 넘게)는 배치가 깨지지 않게 1.3배까지만.
    val config = LocalConfiguration.current
    val base = LocalDensity.current
    val widthPx = config.screenWidthDp * base.density
    val fitted = remember(widthPx, base.fontScale) {
        val widthDp = config.screenWidthDp
        // 휴대폰 폭(320~480dp)일 때만 맞춘다. 태블릿·가로 화면은 원래 크기 그대로
        val density = if (widthDp in 320..480) widthPx / DESIGN_WIDTH_DP else base.density
        Density(density, base.fontScale.coerceAtMost(MAX_FONT_SCALE))
    }
    CompositionLocalProvider(LocalDensity provides fitted) {
        MaterialTheme(
            colorScheme = OnRoadColorScheme,
            typography = OnRoadTypography,
            content = content,
        )
    }
}

/** 디자인 시안 기준 화면 폭 (캔버스 아트보드 390) */
private const val DESIGN_WIDTH_DP = 390f

/** 글자 크기 설정을 이보다 크게 키워도 이 배율까지만 (배치 깨짐 방지) */
private const val MAX_FONT_SCALE = 1.3f
