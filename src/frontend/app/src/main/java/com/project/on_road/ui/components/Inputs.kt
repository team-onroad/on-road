package com.project.on_road.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 라벨 + 숫자 입력 (단위 표시) */
@Composable
fun NumberField(
    label: String,
    value: Int,
    suffix: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxDigits: Int = 5,
) {
    Column(modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(6.dp))
        OnRoadTextField(
            value = if (value == 0) "" else value.toString(),
            onValueChange = { text -> onChange(text.filter(Char::isDigit).take(maxDigits).toIntOrNull() ?: 0) },
            placeholder = "0",
            suffix = suffix,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun manwon(value: Int): String = "%,d만원".format(value)
