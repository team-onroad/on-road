package com.project.on_road.ui.components

import androidx.compose.ui.draw.drawBehind

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadMotion
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType

/* ================================================================== */
/*  마이크로 인터랙션 · 빈 상태 · 오류 · 불러오는 중                        */
/* ================================================================== */

/**
 * 누르는 동안 살짝 작아졌다가 손을 떼면 돌아온다.
 * 같은 [source] 를 clickable(interactionSource = source, ...) 에 넘겨야 눌림을 안다.
 * 모양을 자르기(clip) 전에, 수식어 맨 앞에 붙인다.
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, enabled: Boolean = true, pressed: Float = 0.98f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (enabled && isPressed) pressed else 1f,
        // 누를 때는 바로 들어가고, 손을 떼면 아주 살짝 튕기며 돌아온다
        if (enabled && isPressed) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        else spring(dampingRatio = OnRoadMotion.BOUNCY_DAMPING, stiffness = OnRoadMotion.BOUNCY_STIFFNESS),
        label = "press-scale",
    )
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/** 처음엔 숨겼다가 [delayMs] 뒤에 서서히 보이게. 금방 끝나는 요청에 깜빡임을 막는다 */
@Composable
internal fun rememberDelayedAlpha(delayMs: Long = 280L): Float {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs)
        shown = true
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, OnRoadMotion.enter(), label = "delayed-alpha")
    return a
}

/* ------------------------------------------------------------------ */
/*  빈 상태                                                              */
/* ------------------------------------------------------------------ */

/**
 * 빈 상태: 흰 카드에 안내 한 줄 + 작은 메인 색 버튼.
 * 큰 아이콘·제목은 쓰지 않는다. 다음에 무엇을 하면 되는지만 알려 준다.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(OnRoadColors.Surface)
            .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Inner)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = OnRoadType.Caption,
            color = OnRoadColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(12.dp))
            PrimaryPill(actionLabel, onAction)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  오류                                                                */
/* ------------------------------------------------------------------ */

/**
 * 불러오지 못했을 때: 무엇이 안 됐는지 한 줄 + 이유 + '다시 시도'.
 * 붉은 경고 대신 차분하게 보여 주고, 다시 해 볼 수 있는 길을 바로 준다.
 */
@Composable
fun ErrorState(
    message: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String = "불러오지 못했어요",
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(OnRoadColors.Surface)
            .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Inner)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            message ?: "인터넷 연결을 확인하고 잠시 후 다시 시도해 주세요.",
            style = OnRoadType.Caption,
            color = OnRoadColors.TextTertiary,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            PrimaryPill("다시 시도", onRetry)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  불러오는 중 (자리 표시)                                               */
/* ------------------------------------------------------------------ */

/** 회색 막대 하나. [SkeletonList] 안에서 같이 숨 쉬듯 깜빡인다 */
@Composable
fun SkeletonBar(modifier: Modifier = Modifier, height: Dp = 12.dp, widthFraction: Float = 1f, alpha: Float = 1f) {
    val sweep = LocalShimmer.current
    val base = OnRoadColors.SurfaceBadge.copy(alpha = 0.6f)
    val light = OnRoadColors.SurfaceSubtle
    Spacer(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .graphicsLayer { this.alpha = alpha }
            .clip(OnRoadShapes.Badge)
            .background(base)
            // 왼쪽에서 오른쪽으로 밝은 빛이 천천히 지나간다
            .then(
                if (sweep == null) Modifier
                else Modifier.drawBehind {
                    val band = size.width * 0.6f
                    val x = -band + (size.width + band * 2) * sweep.value
                    drawRect(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(base, light, base),
                            startX = x - band / 2,
                            endX = x + band / 2,
                        ),
                    )
                },
            ),
    )
}

/** SkeletonList 안의 막대들이 같은 빛줄기를 공유한다 */
private val LocalShimmer = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Float>?> { null }

/**
 * 목록 화면을 불러오는 동안 내용이 들어올 자리를 미리 그려 둔다.
 * 동그라미보다 '곧 이런 게 나온다'가 보여서 기다림이 짧게 느껴진다.
 */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 4, header: Boolean = true) {
    val appear = rememberDelayedAlpha(180L)
    val pulse = 1f
    val sweep = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
        label = "skeleton-sweep",
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalShimmer provides sweep) {
    Column(
        modifier.fillMaxWidth().graphicsLayer { alpha = appear },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header) {
            SkeletonBar(height = 22.dp, widthFraction = 0.55f, alpha = pulse)
            SkeletonBar(height = 12.dp, widthFraction = 0.75f, alpha = pulse)
            Spacer(Modifier.height(16.dp))
        }
        repeat(rows) { i ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Inner)
                    .background(OnRoadColors.SurfaceSubtle)
                    .border(1.dp, OnRoadColors.Border.copy(alpha = 0.7f), OnRoadShapes.Inner)
                    .padding(16.dp),
            ) {
                SkeletonBar(height = 14.dp, widthFraction = if (i % 2 == 0) 0.6f else 0.45f, alpha = pulse)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(height = 10.dp, widthFraction = 0.85f, alpha = pulse)
            }
        }
    }
    }
}
