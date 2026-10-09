package com.project.on_road.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
    MaterialTheme(
        colorScheme = OnRoadColorScheme,
        typography = OnRoadTypography,
        content = content,
    )
}
