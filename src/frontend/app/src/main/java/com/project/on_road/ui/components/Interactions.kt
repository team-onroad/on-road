package com.project.on_road.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadMotion
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.delay

/* ================================================================== */
/*  앱 전체 반응(인터랙션) 모음                                            */
/*  디자인(색·크기·배치)은 그대로 두고, 누르고·바뀌고·끝냈을 때의 반응만 더한다   */
/* ================================================================== */

/**
 * clickable 대신 쓰는 누르기. 누르는 동안 살짝 작아졌다가 돌아오고(pressScale), 물결 표시도 그대로 나온다.
 * [haptic] 을 켜면 체크·완료처럼 상태가 바뀌는 곳에서 짧게 진동한다.
 */
fun Modifier.tap(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    pressed: Float = 0.98f,
    haptic: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val feedback = LocalHapticFeedback.current
    this
        .pressScale(source, enabled = enabled, pressed = pressed)
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
        ) {
            if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** 상태가 바뀌는 순간(완료·체크·북마크) 짧은 진동. 컴포저블 안에서 받아 두고 콜백에서 부른다 */
@Composable
fun rememberTick(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { { feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/** 펼치기·접기 화살표: 열리면 부드럽게 뒤집힌다 */
fun Modifier.flipWhen(open: Boolean): Modifier = composed {
    val r by animateFloatAsState(if (open) 180f else 0f, OnRoadMotion.spatial(), label = "flip")
    this.graphicsLayer { rotationZ = r }
}

/** 목록 항목이 위에서부터 차례로 올라오며 나타난다 (처음 한 번만, 너무 길어지지 않게 8개까지만 늦춘다) */
@Composable
fun Modifier.stagger(index: Int): Modifier = this.reveal(delayMs = index.coerceAtMost(6) * 35)

/** 상태가 바뀌면 한 번 '톡' 커졌다 돌아온다 (북마크·체크 등) */
fun Modifier.popOn(key: Any?, enabled: Boolean = true): Modifier = composed {
    var first by remember { mutableStateOf(true) }
    var big by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        if (!enabled) return@LaunchedEffect
        big = true
        delay(110)
        big = false
    }
    val s by animateFloatAsState(
        if (big) 1.18f else 1f,
        if (big) tween(110, easing = OnRoadMotion.Emphasized) else OnRoadMotion.bouncy(),
        label = "pop",
    )
    this.graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * 숫자가 바뀔 때 툭 바뀌지 않고 올라가거나 내려가며 바뀐다 (금액·퍼센트·D-day).
 * 처음 보일 때는 [startFromZero] 면 0 에서부터 올라간다.
 */
@Composable
fun AnimatedNumber(
    value: Long,
    format: (Long) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = OnRoadType.Body1,
    color: Color = OnRoadColors.TextPrimary,
    startFromZero: Boolean = true,
    durationMs: Int = 600,
) {
    var shown by remember { mutableLongStateOf(if (startFromZero) 0L else value) }
    LaunchedEffect(value) {
        val from = shown
        if (from == value) return@LaunchedEffect
        animate(0f, 1f, animationSpec = tween(durationMs, easing = OnRoadMotion.Emphasized)) { f, _ ->
            shown = from + ((value - from) * f).toLong()
        }
        shown = value
    }
    Text(format(shown), modifier = modifier, style = style, color = color)
}

/** 답을 기다리는 동안 점 세 개가 차례로 떠오른다 */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = OnRoadColors.TextMuted) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(420, delayMillis = i * 140, easing = OnRoadMotion.Emphasized),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { alpha = a; translationY = (1f - a) * 3.dp.toPx() }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** 펼치기·접기 내용: 툭 나타나지 않고 아래로 펼쳐지며 나타나고, 접으면 말려 들어간다 */
@Composable
fun ExpandArea(visible: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        // 펼칠 때: 자리가 먼저 열리고 내용은 살짝 늦게 떠오른다 / 접을 때: 내용이 먼저 흐려지고 자리가 닫힌다
        enter = expandVertically(OnRoadMotion.spatialSize, expandFrom = Alignment.Top) +
            fadeIn(tween(OnRoadMotion.CONTENT, delayMillis = 60, easing = OnRoadMotion.EmphasizedDecelerate)),
        exit = shrinkVertically(OnRoadMotion.spatialSize, shrinkTowards = Alignment.Top) + fadeOut(tween(OnRoadMotion.FAST)),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/* ------------------------------------------------------------------ */
/*  짧은 알림 메시지 (저장했어요·복사했어요)                                */
/* ------------------------------------------------------------------ */

/** 화면 어디서든(뷰모델 포함) OnRoadToast.show("저장했어요") 로 아래쪽에 잠깐 띄운다 */
object OnRoadToast {
    internal var current by mutableStateOf<Pair<Long, String>?>(null)
    private var seq = 0L
    fun show(text: String) {
        seq += 1
        current = seq to text
    }
}

/** 전체 화면 맨 위에 한 번만 둔다 (OnRoadNavHost) */
@Composable
fun BoxScope.ToastHost() {
    val msg = OnRoadToast.current
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(msg) {
        if (msg == null) return@LaunchedEffect
        text = msg.second
        visible = true
        delay(if (msg.second.length > 28) 3200 else 2200)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 96.dp),
        enter = fadeIn(OnRoadMotion.enter()) + slideInVertically(OnRoadMotion.enter()) { h -> h / 2 },
        exit = fadeOut(OnRoadMotion.exit()) + slideOutVertically(OnRoadMotion.exit()) { h -> h / 3 },
    ) {
        Box(
            Modifier
                .clip(OnRoadShapes.Inner)
                .background(OnRoadColors.Primary)
                .tap(pressed = 0.98f) { visible = false }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(text, style = OnRoadType.Body3, color = Color.White)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  화면 사이 이어지는 움직임 (공유 요소)                                  */
/*  목록의 정책 이름을 누르면, 그 글자가 다음 화면 제목 자리로 날아가 커진다      */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** 같은 [key] 를 단 두 화면의 요소가 화면 전환 때 이어져 움직인다. 범위 밖에서는 아무 일도 하지 않는다 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedTitle(key: String): Modifier = composed {
    val shared = LocalSharedScope.current
    val anim = LocalNavAnimScope.current
    if (shared == null || anim == null) {
        this
    } else {
        with(shared) {
            this@composed.sharedBounds(
                rememberSharedContentState(key),
                animatedVisibilityScope = anim,
                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
            )
        }
    }
}

/** 전체 화면 묶음 (OnRoadNavHost 에서 NavHost 를 감싼다) */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedScreens(content: @Composable () -> Unit) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedScope provides this) { content() }
    }
}

/** 화면 하나의 전환 범위를 알려 준다 (OnRoadNavHost 의 screen) */
@Composable
fun ProvideNavAnimScope(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavAnimScope provides scope, content = content)
}

/** 정책 이름 공유 키 (정책 찾기 카드 · 체크리스트 목록 · 체크리스트 제목) */
fun policyTitleKey(policyId: String) = "policy-title-$policyId"

/* ------------------------------------------------------------------ */
/*  완료 표시: 동그라미가 그려지고 이어서 체크가 그려진다                     */
/* ------------------------------------------------------------------ */

@Composable
fun DrawnCheck(modifier: Modifier = Modifier, color: Color = OnRoadColors.Primary, size: androidx.compose.ui.unit.Dp = 22.dp) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val ring by animateFloatAsState(if (started) 1f else 0f, tween(420, easing = OnRoadMotion.EmphasizedDecelerate), label = "check-ring")
    val tick by animateFloatAsState(if (started) 1f else 0f, tween(300, delayMillis = 300, easing = OnRoadMotion.EmphasizedDecelerate), label = "check-tick")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(color, startAngle = -90f, sweepAngle = 360f * ring, useCenter = false, style = stroke,
            topLeft = Offset(stroke.width / 2, stroke.width / 2), size = Size(w - stroke.width, w - stroke.width))
        if (tick > 0f) {
            val path = Path().apply {
                moveTo(w * 0.30f, w * 0.52f)
                lineTo(w * 0.45f, w * 0.66f)
                lineTo(w * 0.71f, w * 0.38f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * tick, part, true)
            drawPath(part, color, style = stroke)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  슬라이더 눈금 진동: 값이 일정 칸을 넘을 때마다 아주 짧게 '틱'               */
/* ------------------------------------------------------------------ */

@Composable
fun rememberSliderTick(range: ClosedFloatingPointRange<Float>): (Float) -> Unit {
    val tick = rememberTick()
    val step = ((range.endInclusive - range.start) / 30f).coerceAtLeast(1f)
    var last by remember { mutableStateOf(Int.MIN_VALUE) }
    return { v ->
        val bucket = ((v - range.start) / step).toInt()
        if (last != Int.MIN_VALUE && bucket != last) tick()
        last = bucket
    }
}

/* ------------------------------------------------------------------ */
/*  신청 단계 막대: 끝난 칸은 메인 색, 지금 칸은 포인트 색, 남은 칸은 회색       */
/* ------------------------------------------------------------------ */

@Composable
fun StepRail(total: Int, done: Int, modifier: Modifier = Modifier, labels: List<String> = emptyList(), height: androidx.compose.ui.unit.Dp = 4.dp) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { k ->
                val target = when {
                    k < done -> OnRoadColors.Primary
                    k == done -> OnRoadColors.Point
                    else -> OnRoadColors.SurfaceSlate
                }
                val c by androidx.compose.animation.animateColorAsState(target, OnRoadMotion.move(), label = "step-$k")
                Box(Modifier.weight(1f).height(height).clip(OnRoadShapes.Badge).background(c))
            }
        }
        if (labels.size == total) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEachIndexed { k, t ->
                    Text(
                        t,
                        style = if (k == done) OnRoadType.Caption2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else OnRoadType.Caption2,
                        color = when {
                            k == done -> OnRoadColors.TextPrimary
                            k < done -> OnRoadColors.TextSecondary
                            else -> OnRoadColors.TextMuted
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  답변이 글자 단위로 흘러나오듯 보이기 (서버가 한 번에 보내도 앱에서 펼쳐 보인다)  */
/* ------------------------------------------------------------------ */

/**
 * [text] 를 앞에서부터 조금씩 늘려 [content] 에 넘긴다. 길어도 [maxMs] 안에 끝난다.
 * [animate] 가 false 면 처음부터 전부 보여 준다 (지난 대화·다시 그릴 때).
 */
@Composable
fun RevealText(text: String, animate: Boolean, maxMs: Int = 1400, onDone: () -> Unit = {}, content: @Composable (String) -> Unit) {
    var shown by remember(text, animate) { mutableStateOf(if (animate) 0 else text.length) }
    LaunchedEffect(text, animate) {
        if (!animate) return@LaunchedEffect
        val steps = (maxMs / 16).coerceAtLeast(1)
        val per = (text.length / steps).coerceAtLeast(1)
        while (shown < text.length) {
            shown = (shown + per).coerceAtMost(text.length)
            delay(16)
        }
        onDone()
    }
    content(if (shown >= text.length) text else text.take(shown))
}

/** 길게 누르면 글을 복사하고 짧게 진동 + "복사했어요" */
fun Modifier.copyOnLongPress(text: String): Modifier = composed {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val tick = rememberTick()
    this.pointerInput(text) {
        detectTapGestures(onLongPress = {
            clip.setText(androidx.compose.ui.text.AnnotatedString(text))
            tick()
            OnRoadToast.show("복사했어요")
        })
    }
}

/**
 * 값이 바뀌면(AI 가 채우거나 저장되면) 그 자리가 포인트 색으로 잠깐 빛났다가 사라진다.
 * 무엇이 바뀌었는지 눈으로 따라갈 수 있게. 처음 보일 때는 빛나지 않는다.
 */
fun Modifier.flashOn(key: Any?, corner: androidx.compose.ui.unit.Dp = 12.dp): Modifier = composed {
    var first by remember { mutableStateOf(true) }
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        a.snapTo(1f)
        a.animateTo(0f, tween(1100, easing = OnRoadMotion.EmphasizedDecelerate))
    }
    this.drawWithContent {
        drawContent()
        if (a.value > 0f) {
            drawRoundRect(
                OnRoadColors.Point.copy(alpha = 0.22f * a.value),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.toPx()),
            )
        }
    }
}
