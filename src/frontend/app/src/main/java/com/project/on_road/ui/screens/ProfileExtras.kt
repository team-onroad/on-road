package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.Districts
import com.project.on_road.data.FamilyMember
import com.project.on_road.ui.components.ChoiceChip
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 가족 한 줄 설명: "여, 6세 (2020.05.01)" */
fun FamilyMember.summary(): String {
    val g = when (gender) { "male" -> "남, "; "female" -> "여, "; else -> "" }
    return "$g${age}세 (${birthDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))})"
}

fun FamilyMember.relationLabel(): String = if (relation == "child") "자녀" else "동생"

/** 시 · 군 · 구 고르기. 고르지 않음을 누르면 null */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DistrictDialog(region: String?, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val names = Districts.of(region)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text("시·군·구", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            FlowRow(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip("선택 안 함", selected = current == null) { onPick(null) }
                names.forEach { n -> ChoiceChip(n, selected = n == current) { onPick(n) } }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    )
}

/** 가족 추가: 관계 · 성별 · 생년월일 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FamilyMemberDialog(onDismiss: () -> Unit, onAdd: (relation: String, gender: String?, birthDate: LocalDate) -> Unit) {
    var relation by remember { mutableStateOf("child") }
    var gender by remember { mutableStateOf<String?>(null) }
    var birth by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text("가족 추가", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            Column {
                ExtraLabel("관계")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("자녀", selected = relation == "child") { relation = "child" }
                    ChoiceChip("동생", selected = relation == "sibling") { relation = "sibling" }
                }
                Spacer(Modifier.height(16.dp))
                ExtraLabel("성별")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("남", selected = gender == "male") { gender = "male" }
                    ChoiceChip("여", selected = gender == "female") { gender = "female" }
                    ChoiceChip("선택 안 함", selected = gender == null) { gender = null }
                }
                Spacer(Modifier.height(16.dp))
                ExtraLabel("생년월일")
                Row {
                    ChoiceChip(birth?.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) ?: "날짜 선택", selected = birth != null) { picking = true }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { birth?.let { onAdd(relation, gender, it) } }, enabled = birth != null) {
                Text("추가", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = if (birth != null) OnRoadColors.Primary else OnRoadColors.TextQuaternary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    )
    if (picking) {
        val today = LocalDate.now()
        val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = birth?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        val colors = DatePickerDefaults.colors(
            containerColor = Color.White,
            selectedDayContainerColor = OnRoadColors.Primary,
            todayDateBorderColor = OnRoadColors.Primary,
            todayContentColor = OnRoadColors.Primary,
        )
        com.project.on_road.ui.components.OnRoadDatePickerDialog(
            onDismissRequest = { picking = false },
            colors = colors,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { birth = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("확인", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
            },
        ) { DatePicker(state = state, colors = colors) }
    }
}

@Composable
private fun ExtraLabel(text: String) {
    Text(text, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
    Spacer(Modifier.height(8.dp))
}

/** 사는 지역(시 · 도) 고르기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegionDialog(current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text("사는 지역", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.project.on_road.data.REGIONS.forEach { r -> ChoiceChip(r, selected = r == current) { onPick(r) } }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    )
}

/** 지금 상황 고르기 (시설 생활 · 퇴소 예정 · 퇴소 후) */
@Composable
fun StatusDialog(current: com.project.on_road.data.LivingStatus?, onDismiss: () -> Unit, onPick: (com.project.on_road.data.LivingStatus) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text("지금 상황", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.project.on_road.data.LivingStatus.values().forEach { s ->
                    ChoiceChip(s.label, selected = s == current, modifier = Modifier.fillMaxWidth()) { onPick(s) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    )
}

/** 날짜 하나 고르기 (생년월일 · 퇴소일). past = true 면 오늘까지만, false 면 오늘부터만, null 이면 제한 없음 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDateDialog(initial: LocalDate?, past: Boolean?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = when (past) {
                true -> utcTimeMillis <= todayMillis
                false -> utcTimeMillis >= todayMillis
                null -> true
            }
            override fun isSelectableYear(year: Int): Boolean = when (past) {
                true -> year <= today.year
                false -> year >= today.year
                null -> true
            }
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
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("확인", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    ) { DatePicker(state = state, colors = colors) }
}
