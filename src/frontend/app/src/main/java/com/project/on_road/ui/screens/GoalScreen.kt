package com.project.on_road.ui.screens

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.GoalTask
import com.project.on_road.data.TimelineLink
import com.project.on_road.data.TimelineRules
import com.project.on_road.data.UserUpdate
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * 퇴소 후 '목표 D-day' (퇴소 전에는 TimelineScreen 의 퇴소 D-day 타임라인).
 *  - 목표: 이름(예: 첫 출근) + 날짜. 날짜는 서버 d_date 로 저장, 이름은 앱에 저장
 *  - 내 일정: 내가 직접 잡는 할 일 (제목 + 날짜, 체크)
 *  - 퇴소 후 꼭 챙길 일: 전입신고·자립수당처럼 날짜 없이 체크만
 */

class GoalViewModel(app: Application) : AndroidViewModel(app) {
    private val session = AppContainer.session

    var goalTitle by mutableStateOf(session.goalTitle.orEmpty())
        private set
    var goalDate by mutableStateOf(session.leaveDate)
        private set
    var tasks by mutableStateOf(session.goalTasks)
        private set
    val done = mutableStateListOf<String>().apply { addAll(session.timelineDone) }

    fun saveGoal(title: String, date: LocalDate?) {
        goalTitle = title.trim()
        session.goalTitle = goalTitle
        if (date != goalDate) {
            goalDate = date
            session.leaveDate = date
            saveDateToServer(date)
        }
    }

    /** 3.12 회원정보 수정으로 서버 d_date(목표일)도 맞춘다 */
    private fun saveDateToServer(date: LocalDate?) {
        val userId = session.userId ?: return
        viewModelScope.launch {
            val update = if (date == null) UserUpdate(clearDDate = true) else UserUpdate(dDate = date)
            runCatching { AppContainer.api.updateUser(userId, update) }
                .onSuccess { AppContainer.applyProfile(it) }
                .onFailure { Toast.makeText(getApplication(), "서버에 저장하지 못했어요. 인터넷 연결을 확인해 주세요.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun saveTasks(list: List<GoalTask>) {
        tasks = list
        session.goalTasks = list
    }

    fun addTask(title: String, date: LocalDate) =
        saveTasks(tasks + GoalTask(System.currentTimeMillis().toString(), title.trim(), date, false))

    fun toggleTask(id: String) = saveTasks(tasks.map { if (it.id == id) it.copy(done = !it.done) else it })

    fun removeTask(id: String) = saveTasks(tasks.filterNot { it.id == id })

    fun toggleBasic(id: String) {
        if (id in done) done.remove(id) else done.add(id)
        session.timelineDone = done.toSet()
    }
}

private val goalDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN)

private fun dText(days: Long): String = when {
    days > 0 -> "D-$days"
    days == 0L -> "D-DAY"
    else -> "D+${-days}"
}

@Composable
fun GoalTimelineScreen(
    onBack: () -> Unit,
    onLink: (TimelineLink) -> Unit,
    vm: GoalViewModel = viewModel(),
) {
    var editGoal by remember { mutableStateOf(false) }
    var addTask by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val title = vm.goalTitle.ifBlank { "목표" }

    OnRoadScreen("목표 D-day", onBack) {
        // ── 목표 ──
        val date = vm.goalDate
        if (date == null) {
            OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary) {
                IconTile(Icons.Outlined.Flag, tint = OnRoadColors.Primary)
                Spacer(Modifier.height(12.dp))
                Text("목표를 정해 볼까요?", style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("첫 출근, 이사, 자격증 시험처럼 준비 중인 날", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                OnRoadButton("목표 정하기", { editGoal = true }, Modifier.fillMaxWidth(), height = 44.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnRoadTag("목표일", style = TagStyle.SlatePrimary, shape = OnRoadShapes.Field, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(OnRoadShapes.Field)
                        .border(1.dp, OnRoadColors.BorderInput, OnRoadShapes.Field)
                        .clickable { editGoal = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Edit, null, tint = OnRoadColors.TextSlate, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("목표 바꾸기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlate)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("${title}까지", style = OnRoadType.Title2, color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dText(ChronoUnit.DAYS.between(today, date)), style = OnRoadType.Hero, color = OnRoadColors.Primary)
            Text(date.format(goalDateFormat), style = OnRoadType.Caption, color = OnRoadColors.TextMuted)
        }

        // ── 내 일정 ──
        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("내 일정", actionText = "추가", onAction = { addTask = true })
        Spacer(Modifier.height(12.dp))
        val sorted = vm.tasks.sortedWith(compareBy<GoalTask> { it.done }.thenBy { it.date })
        if (sorted.isEmpty()) {
            SubtleCard(Modifier.fillMaxWidth(), onClick = { addTask = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Outlined.Add)
                    Spacer(Modifier.width(12.dp))
                    Text("목표까지 할 일을 날짜와 함께 넣어 봐요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sorted.forEach { t ->
                    GoalTaskRow(t, ChronoUnit.DAYS.between(today, t.date), onToggle = { vm.toggleTask(t.id) }, onRemove = { vm.removeTask(t.id) })
                }
            }
        }

        // ── 퇴소 후 꼭 챙길 일 (날짜 없이 체크) ──
        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        val basics = TimelineRules.tasks.filter { it.offsetDays > 0 }
        SectionHeader("퇴소 후 꼭 챙길 일", trailingText = "${basics.count { it.id in vm.done }}/${basics.size}")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            basics.forEach { task ->
                val checked = task.id in vm.done
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    background = if (checked) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
                    borderColor = if (checked) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
                    shape = OnRoadShapes.Item,
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheckCircle(checked) { vm.toggleBasic(task.id) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title,
                                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                                color = if (checked) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary,
                                textDecoration = if (checked) TextDecoration.LineThrough else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(task.description, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        task.link?.let { link ->
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight, task.linkLabel,
                                tint = OnRoadColors.TextQuaternary,
                                modifier = Modifier.clip(CircleShape).clickable { onLink(link) }.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Footnote("목표일은 내가 정한 날이에요.\n퇴소일과는 따로 관리돼요.")
    }

    if (editGoal) {
        GoalEditDialog(
            title = vm.goalTitle,
            date = vm.goalDate,
            onDismiss = { editGoal = false },
            onSave = { t, d -> vm.saveGoal(t, d); editGoal = false },
        )
    }
    if (addTask) {
        TaskAddDialog(
            onDismiss = { addTask = false },
            onSave = { t, d -> vm.addTask(t, d); addTask = false },
        )
    }
}

@Composable
private fun CheckCircle(checked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) OnRoadColors.Primary else Color.White)
            .border(1.5.dp, if (checked) OnRoadColors.Primary else OnRoadColors.BorderSage, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, "완료 취소", tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun GoalTaskRow(t: GoalTask, days: Long, onToggle: () -> Unit, onRemove: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = if (t.done) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
        borderColor = when {
            t.done -> OnRoadColors.Primary
            days in 0..3 -> OnRoadColors.Danger.copy(alpha = 0.5f)
            else -> OnRoadColors.BorderSage.copy(alpha = 0.7f)
        },
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckCircle(t.done, onToggle)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = if (t.done) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${dText(days)} · ${t.date.format(goalDateFormat)}", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, maxLines = 1)
            }
            Text(
                "지우기",
                style = OnRoadType.Micro,
                color = OnRoadColors.TextQuaternary,
                modifier = Modifier.clip(OnRoadShapes.Chip).clickable(onClick = onRemove).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** 날짜 고르기 칸 (누르면 달력) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate?, placeholder: String, onPick: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(OnRoadShapes.Field)
            .border(1.dp, OnRoadColors.BorderInput, OnRoadShapes.Field)
            .clickable { picking = true }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            date?.format(goalDateFormat) ?: placeholder,
            style = OnRoadType.Caption,
            color = if (date == null) OnRoadColors.TextQuaternary else OnRoadColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.Edit, null, tint = OnRoadColors.TextSlate, modifier = Modifier.size(14.dp))
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        val colors = DatePickerDefaults.colors(
            containerColor = Color.White,
            selectedDayContainerColor = OnRoadColors.Primary,
            todayDateBorderColor = OnRoadColors.Primary,
            todayContentColor = OnRoadColors.Primary,
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            colors = colors,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
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
private fun GoalEditDialog(title: String, date: LocalDate?, onDismiss: () -> Unit, onSave: (String, LocalDate?) -> Unit) {
    var t by remember { mutableStateOf(title) }
    var d by remember { mutableStateOf(date) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text("목표 정하기", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            Column {
                FieldLabel("목표 이름")
                Spacer(Modifier.height(6.dp))
                OnRoadTextField(t, { t = it.take(20) }, Modifier.fillMaxWidth(), placeholder = "예: 첫 출근")
                Spacer(Modifier.height(14.dp))
                FieldLabel("목표일")
                Spacer(Modifier.height(6.dp))
                DateField(d, "날짜 고르기") { d = it }
                if (date != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "목표일 지우기",
                        style = OnRoadType.Micro,
                        color = OnRoadColors.TextTertiary,
                        modifier = Modifier.clickable { d = null }.padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(t, d) }) {
                Text("저장", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    )
}

@Composable
private fun TaskAddDialog(onDismiss: () -> Unit, onSave: (String, LocalDate) -> Unit) {
    var t by remember { mutableStateOf("") }
    var d by remember { mutableStateOf<LocalDate?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text("일정 추가", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            Column {
                FieldLabel("할 일")
                Spacer(Modifier.height(6.dp))
                OnRoadTextField(t, { t = it.take(30) }, Modifier.fillMaxWidth(), placeholder = "예: 이력서 보내기")
                Spacer(Modifier.height(14.dp))
                FieldLabel("날짜")
                Spacer(Modifier.height(6.dp))
                DateField(d, "날짜 고르기") { d = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { d?.let { onSave(t, it) } },
                enabled = t.isNotBlank() && d != null,
            ) {
                Text(
                    "추가",
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = if (t.isNotBlank() && d != null) OnRoadColors.Primary else OnRoadColors.TextQuaternary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
        },
    )
}
