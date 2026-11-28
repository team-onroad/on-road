package com.project.on_road.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 신청 예정일 표시 (신청 관리 상세 칩 · 목록 카드) */
object DueDate {
    private val DAY = DateTimeFormatter.ofPattern("M월 d일")

    /** 3일 안이면 갈색으로 */
    const val SOON_DAYS = 3

    fun day(date: LocalDate): String = date.format(DAY)

    fun daysLeft(date: LocalDate, today: LocalDate = LocalDate.now()): Long = ChronoUnit.DAYS.between(today, date)

    /** D-8 · 오늘 · 날짜 지남 */
    fun dText(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val left = daysLeft(date, today)
        return when {
            left < 0 -> "날짜 지남"
            left == 0L -> "오늘"
            else -> "D-$left"
        }
    }

    fun color(date: LocalDate, today: LocalDate = LocalDate.now()): Color {
        val left = daysLeft(date, today)
        return when {
            left < 0 -> OnRoadColors.Danger
            left <= SOON_DAYS -> OnRoadColors.Warning
            else -> OnRoadColors.TextTertiary
        }
    }

    /** 3일 안 · 지남은 굵게 */
    fun urgent(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean = daysLeft(date, today) <= SOON_DAYS
}

/**
 * 정책 카드 아래 작은 칩. 정했으면 '신청 예정 11월 28일 · D-8', 안 정했으면 '신청할 날 정하기'
 */
@Composable
fun DueChip(date: LocalDate?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(OnRoadShapes.Field)
            .border(1.dp, OnRoadColors.BorderInput, OnRoadShapes.Field)
            .tap { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        if (date == null) {
            Text("신청할 날 정하기", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 1)
        } else {
            Text("신청 예정", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(DueDate.day(date), style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(
                "· ${DueDate.dText(date)}",
                style = if (DueDate.urgent(date)) OnRoadType.Caption.copy(fontWeight = FontWeight.Bold) else OnRoadType.Caption,
                color = DueDate.color(date),
                maxLines = 1,
            )
        }
    }
}

/**
 * 신청 예정일 고르기 (목표일 고르기와 같은 달력). 오늘 이전은 고를 수 없다.
 * 정해 둔 날짜가 있으면 '날짜 지우기' → onPick(null)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDatePicker(date: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate?) -> Unit) {
    val today = LocalDate.now()
    val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val start = date?.takeIf { !it.isBefore(today) } ?: today
    val state = rememberDatePickerState(
        initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayMillis
            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = Color.White,
        selectedDayContainerColor = OnRoadColors.Primary,
        todayDateBorderColor = OnRoadColors.Primary,
        todayContentColor = OnRoadColors.Primary,
    )
    com.project.on_road.ui.components.OnRoadDatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("정하기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary) }
        },
        dismissButton = {
            Row {
                if (date != null) {
                    TextButton(onClick = { onPick(null); onDismiss() }) {
                        Text("날짜 지우기", style = OnRoadType.Caption, color = OnRoadColors.Danger)
                    }
                }
                TextButton(onClick = onDismiss) { Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
            }
        },
    ) { DatePicker(state = state, colors = colors) }
}
