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
 * 나중에 받은 데이터로 채워지는 묶음: keys 가 바뀌면(비어 있다가 채워지면) 그 묶음만 살짝 올라오며 서서히 나타난다.
 * 높이 변화는 감싼 화면의 animateContentSize 가 부드럽게 맞춘다.
 */
@Composable
fun Appear(vararg keys: Any?, content: @Composable ColumnScope.() -> Unit) {
    // key(*keys) 처럼 펼쳐 넘기면 Compose 컴파일러가 멈춘다 (IR lowering 오류) → 목록 하나로 묶어서 넘긴다
    androidx.compose.runtime.key(keys.toList()) {
        Column(Modifier.fillMaxWidth().reveal(), content = content)
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
        androidx.compose.animation.core.tween(RevealMotion.Duration, delayMs, OnRoadMotion.Soft),
        label = "reveal",
    )
    return this.graphicsLayer {
        alpha = p
        translationY = (1f - p) * RevealMotion.Rise.toPx()
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

/**
 * AnimatedContent 기본 전환 대신 쓰는 앱 공통 전환 (FadeSwitch 와 같은 움직임).
 * 기본값은 옛 내용이 0.09초 만에 사라지고 새 내용이 작게 시작해 커져서 '뚝' 바뀌어 보였다.
 * 다 바뀐 뒤 모양은 같고, 바뀌는 과정만 부드럽다.
 */
fun <S> androidx.compose.animation.AnimatedContentTransitionScope<S>.smoothSwap(clip: Boolean = true): androidx.compose.animation.ContentTransform =
    (fadeIn(OnRoadMotion.enter()) +
        slideInVertically(OnRoadMotion.spatialOffset) { h -> (h / 20).coerceAtMost(48) }) togetherWith
        androidx.compose.animation.fadeOut(OnRoadMotion.exit()) using
        SizeTransform(clip = clip) { _, _ -> OnRoadMotion.spatialSize }

/** 화면 내용이 떠오르는 움직임 (reveal · stagger 공통) */
object RevealMotion {
    /** 떠오르는 시간 (1초는 화면을 연 뒤에도 한참 움직여 보였다) */
    const val Duration = 320
    /** 아래에서 올라오는 거리 */
    val Rise = 8.dp
    /** stagger 한 칸 사이 시간 (줄줄이 늦게 나타나지 않게 짧게) */
    const val Step = 40
}
