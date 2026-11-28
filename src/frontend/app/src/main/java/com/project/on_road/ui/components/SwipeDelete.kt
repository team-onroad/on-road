package com.project.on_road.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadMotion
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 밀어서 지우기 크기 · 속도 (숫자만 바꿔서 조절) */
object SwipeSpace {
    /** 오른쪽 '지우기' 칸 폭 */
    val ActionWidth = 76.dp
    /** 열리고 닫히는 시간 (ms) */
    const val SETTLE = 420
    /** 이 속도보다 빠르게 밀면 덜 밀어도 열림 (px/s) */
    const val FLING = 900f
    /** 지운 카드가 접히며 사라지는 시간 (ms) */
    const val COLLAPSE = 320
}

/** 한 번에 카드 하나만 열리게: 다른 카드를 밀면 앞 카드는 닫힌다 */
object SwipeOpen {
    var key by mutableStateOf<Any?>(null)
}

/**
 * 왼쪽으로 밀면 오른쪽 흰 바탕에 빨간 '지우기'가 나온다.
 * 절반 넘게 밀거나 빠르게 밀면 열리고, 아니면 제자리. 열린 채로 카드를 누르면 닫힌다.
 * 실제로 지우기 전 확인은 [ConfirmDeleteDialog] 로 화면에서 한다.
 */
@Composable
fun SwipeToDelete(
    key: Any,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "지우기",
    content: @Composable () -> Unit,
) {
    val widthPx = with(LocalDensity.current) { SwipeSpace.ActionWidth.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spec = tween<Float>(SwipeSpace.SETTLE, easing = OnRoadMotion.Soft)
    fun settle(open: Boolean) {
        scope.launch { offset.animateTo(if (open) -widthPx else 0f, spec) }
        if (open) SwipeOpen.key = key else if (SwipeOpen.key == key) SwipeOpen.key = null
    }
    // 다른 카드가 열리면 닫는다
    LaunchedEffect(SwipeOpen.key) { if (SwipeOpen.key != key && offset.value != 0f) offset.animateTo(0f, spec) }

    Box(modifier.fillMaxWidth().clip(OnRoadShapes.Item)) {
        // 뒤: 흰 바탕 + 오른쪽 끝 '지우기'
        Box(Modifier.matchParentSize().background(Color.White), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .width(SwipeSpace.ActionWidth)
                    .fillMaxHeight()
                    .tap(onClickLabel = label, ripple = false) { settle(false); onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = OnRoadType.Body3.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.Danger)
            }
        }
        // 앞: 카드
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-widthPx * 1.25f, 0f)) }
                    },
                    onDragStarted = { SwipeOpen.key = key },
                    onDragStopped = { velocity -> settle(offset.value < -widthPx / 2 || velocity < -SwipeSpace.FLING) },
                ),
        ) {
            content()
            // 열려 있으면 카드를 눌러도 들어가지 않고 닫기만 한다
            if (offset.value != 0f) Box(Modifier.matchParentSize().tap(ripple = false, pressed = 1f) { settle(false) })
        }
    }
}

/** 지우기 전 확인 창. 본문 아래 detail 은 옅은 작은 글씨 (지워지는 것 목록 등) */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    text: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    detail: String? = null,
    confirmLabel: String = "지우기",
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text(title, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            Column {
                Text(text.keepWords(), style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                detail?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it.keepWords(), style = OnRoadType.Caption2, color = OnRoadColors.TextMuted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    )
}


/**
 * 지운 항목이 툭 사라지지 않고 위아래로 접히며 사라진다 (아래 카드가 부드럽게 올라온다).
 * 화면(ViewModel)은 지우기에 성공하면 removing 을 true 로 두고, [SwipeSpace.COLLAPSE] 뒤에 목록에서 뺀다.
 */
@Composable
fun Collapsing(removing: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = !removing,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.shrinkVertically(tween(SwipeSpace.COLLAPSE, easing = OnRoadMotion.Soft)) +
            androidx.compose.animation.fadeOut(tween(SwipeSpace.COLLAPSE / 2)),
    ) { content() }
}
