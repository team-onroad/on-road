package com.project.on_road.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.project.on_road.ui.theme.OnRoadShapes
import kotlin.math.roundToInt

/**
 * 날짜 고르기 창. Material 날짜 창은 폭이 360dp 로 고정돼 있어서 폭이 좁은 휴대폰에서는 화면 양옆에 딱 붙는다.
 * 이 창은 화면 양옆에 여백(Margin)을 두고, 달력은 남은 폭에 맞게 살짝 줄여서 그린다. 쓰는 법은 DatePickerDialog 와 같다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnRoadDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") colors: DatePickerColors = DatePickerDefaults.colors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DateDialogSpace.Margin),
            shape = OnRoadShapes.Card,
            color = Color.White,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.fitWidth(DateDialogSpace.PickerWidth), content = content)
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

private object DateDialogSpace {
    /** 화면 양옆 여백 */
    val Margin = 20.dp
    /** Material 달력이 원래 그려지는 폭 */
    val PickerWidth = 360.dp
}

/** 정해진 폭(design)으로 그린 뒤, 남은 폭이 더 좁으면 비율대로 줄여서 놓는다 (글자 · 칸 비율 그대로) */
private fun Modifier.fitWidth(design: Dp): Modifier = layout { measurable, constraints ->
    val w = design.roundToPx()
    val placeable = measurable.measure(Constraints(minWidth = w, maxWidth = w))
    val scale = if (constraints.hasBoundedWidth && constraints.maxWidth < w) constraints.maxWidth / w.toFloat() else 1f
    val outW = (placeable.width * scale).roundToInt()
    val outH = (placeable.height * scale).roundToInt()
    layout(outW, outH) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}
