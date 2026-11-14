package com.project.on_road.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.project.on_road.ui.theme.OnRoadMotion

/**
 * 불러오는 중 → 내용, 단계 → 다음 단계처럼 화면 안 내용이 통째로 바뀔 때 툭 바뀌지 않고
 * 이전 내용은 빠르게 사라지고 새 내용은 살짝 올라오며 나타나게 한다.
 * target 이 바뀔 때만 움직이므로, 같은 상태에서 값만 바뀌는 건 그대로 둔다.
 */
@Composable
fun <S> FadeSwitch(
    target: S,
    modifier: Modifier = Modifier,
    label: String = "fade-switch",
    content: @Composable ColumnScope.(S) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(OnRoadMotion.enter()) +
                slideInVertically(OnRoadMotion.spatialOffset) { h -> (h / 20).coerceAtMost(48) }) togetherWith
                fadeOut(OnRoadMotion.exit()) using SizeTransform(clip = false)
        },
        label = label,
    ) { s ->
        Column(Modifier.fillMaxWidth()) { content(s) }
    }
}

/**
 * 처음 나타날 때 살짝 올라오며 서서히 보인다.
 * 불러오는 중 표시가 사라지고 내용이 '띡' 하고 바뀌던 곳(내용이 준비되면 그때 그리는 화면)에 붙인다.
 */
@Composable
fun Modifier.reveal(delayMs: Int = 0): Modifier {
    var shown by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val p by androidx.compose.animation.core.animateFloatAsState(
        if (shown) 1f else 0f,
        androidx.compose.animation.core.tween(OnRoadMotion.CONTENT, delayMs, OnRoadMotion.EmphasizedDecelerate),
        label = "reveal",
    )
    return this.graphicsLayer {
        alpha = p
        translationY = (1f - p) * 10.dp.toPx()
    }
}

/**
 * 탭처럼 순서가 있는 내용 바꾸기: 오른쪽 탭으로 가면 오른쪽에서, 왼쪽으로 가면 왼쪽에서 살짝 밀려 들어온다.
 */
@Composable
fun TabSwitch(
    index: Int,
    modifier: Modifier = Modifier,
    label: String = "tab-switch",
    content: @Composable ColumnScope.(Int) -> Unit,
) {
    AnimatedContent(
        targetState = index,
        modifier = modifier,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (fadeIn(OnRoadMotion.enter()) + slideInHorizontally(OnRoadMotion.spatialOffset) { w -> dir * w / 12 }) togetherWith
                (fadeOut(OnRoadMotion.exit()) + slideOutHorizontally(OnRoadMotion.exit()) { w -> -dir * w / 24 }) using
                SizeTransform(clip = false) { _, _ -> OnRoadMotion.spatialSize }
        },
        label = label,
    ) { i -> Column(Modifier.fillMaxWidth()) { content(i) } }
}
