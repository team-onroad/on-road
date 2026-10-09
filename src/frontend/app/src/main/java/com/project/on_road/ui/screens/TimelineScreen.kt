package com.project.on_road.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.TimelineLink
import com.project.on_road.data.TimelineRules
import com.project.on_road.data.TimelineTask
import com.project.on_road.notification.DdayNotifier
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class TimelineViewModel(app: Application) : AndroidViewModel(app) {
    private val session = AppContainer.session

    var leaveDate by mutableStateOf(session.leaveDate ?: LocalDate.now().plusDays(120))
        private set
    var alarmOn by mutableStateOf(session.ddayAlarm)
        private set
    val done = mutableStateListOf<String>().apply { addAll(session.timelineDone) }

    fun updateDate(date: LocalDate) {
        leaveDate = date
        session.leaveDate = date
        if (alarmOn) DdayNotifier.schedule(getApplication(), date)
    }

    fun setAlarm(on: Boolean) {
        alarmOn = on
        session.ddayAlarm = on
        if (session.leaveDate == null) session.leaveDate = leaveDate
        if (on) DdayNotifier.schedule(getApplication(), leaveDate) else DdayNotifier.cancel(getApplication())
    }

    fun preview() = DdayNotifier.preview(getApplication(), leaveDate)

    fun toggle(id: String) {
        if (id in done) done.remove(id) else done.add(id)
        session.timelineDone = done.toSet()
    }
}

private enum class TaskStatus { DONE, PAST, TODAY, SOON, LATER }

private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN)

private fun dLabel(days: Long): String = when {
    days > 0 -> "D-$days"
    days == 0L -> "D-DAY"
    else -> "D+${-days}"
}

/** 퇴소 D-day 타임라인 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onBack: () -> Unit,
    onLink: (TimelineLink) -> Unit,
    vm: TimelineViewModel = viewModel(),
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingAction?.invoke()
        pendingAction = null
    }

    // 알림 권한이 필요한 동작은 권한 확인 후 실행 (Android 13+)
    fun withNotificationPermission(action: () -> Unit) {
        val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val today = LocalDate.now()
    val daysLeft = ChronoUnit.DAYS.between(today, vm.leaveDate)
    val tasks = TimelineRules.tasks
    val doneCount = tasks.count { it.id in vm.done }

    OnRoadScreen("퇴소 D-day 타임라인", onBack) {
        // 퇴소일
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag("퇴소 예정일", style = TagStyle.SlatePrimary, shape = OnRoadShapes.Field, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(OnRoadShapes.Field)
                    .border(1.dp, OnRoadColors.BorderInput, OnRoadShapes.Field)
                    .clickable { showPicker = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = OnRoadColors.TextSlate, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("날짜 바꾸기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlate)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(dLabel(daysLeft), style = OnRoadType.Hero, color = OnRoadColors.Primary)
        Text(vm.leaveDate.format(dateFormat), style = OnRoadType.Caption, color = OnRoadColors.TextMuted)

        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("준비한 일", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
            Text("$doneCount", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Text("/${tasks.size}개", style = OnRoadType.Tiny, color = OnRoadColors.TextSlateMid, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(doneCount / tasks.size.toFloat())

        // 알림
        Spacer(Modifier.height(24.dp))
        SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 15.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("D-day 알림 받기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    Text("할 일 날짜 아침 9시에 알려 드려요", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
                }
                OnRoadSwitch(vm.alarmOn, { on ->
                    if (on) withNotificationPermission { vm.setAlarm(true) } else vm.setAlarm(false)
                })
            }
            Text(
                "알림 미리 받아보기",
                style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.Primary,
                modifier = Modifier
                    .clickable { withNotificationPermission { vm.preview() } }
                    .padding(vertical = 4.dp),
            )
        }

        // 할 일 목록
        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("날짜별 할 일")
        Spacer(Modifier.height(16.dp))
        tasks.forEachIndexed { i, task ->
            val date = vm.leaveDate.plusDays(task.offsetDays.toLong())
            val diff = ChronoUnit.DAYS.between(today, date)
            val status = when {
                task.id in vm.done -> TaskStatus.DONE
                diff < 0 -> TaskStatus.PAST
                diff == 0L -> TaskStatus.TODAY
                diff <= 14 -> TaskStatus.SOON
                else -> TaskStatus.LATER
            }
            TimelineItem(
                task = task,
                date = date,
                status = status,
                isLast = i == tasks.lastIndex,
                onToggle = { vm.toggle(task.id) },
                onLink = { task.link?.let(onLink) },
            )
        }

        Spacer(Modifier.height(24.dp))
        Footnote("일정은 일반적인 예시예요.\n기관과 지역에 따라 신청 시기가 다를 수 있어요.")
    }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = vm.leaveDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        val pickerColors = DatePickerDefaults.colors(
            containerColor = Color.White,
            selectedDayContainerColor = OnRoadColors.Primary,
            todayDateBorderColor = OnRoadColors.Primary,
            todayContentColor = OnRoadColors.Primary,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            colors = pickerColors,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        vm.updateDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("확인", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            },
        ) {
            DatePicker(state = state, colors = pickerColors)
        }
    }
}

@Composable
private fun TimelineItem(
    task: TimelineTask,
    date: LocalDate,
    status: TaskStatus,
    isLast: Boolean,
    onToggle: () -> Unit,
    onLink: () -> Unit,
) {
    val isDday = task.offsetDays == 0
    val done = status == TaskStatus.DONE
    val urgent = status == TaskStatus.SOON || status == TaskStatus.TODAY
    val offsetLabel = when {
        task.offsetDays < 0 -> "D${task.offsetDays}"
        task.offsetDays == 0 -> "D-DAY"
        else -> "D+${task.offsetDays}"
    }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 세로 선 + 체크 원
        Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (done) OnRoadColors.Primary else Color.White)
                    .border(1.5.dp, if (done || isDday) OnRoadColors.Primary else OnRoadColors.BorderSage, CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, "완료 취소", tint = Color.White, modifier = Modifier.size(14.dp))
            }
            if (!isLast) {
                Box(Modifier.padding(top = 4.dp).width(1.dp).weight(1f).background(OnRoadColors.Border))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 8.dp)) {
            OnRoadCard(
                Modifier.fillMaxWidth(),
                background = if (done) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
                borderColor = when {
                    done || isDday -> OnRoadColors.Primary
                    urgent -> OnRoadColors.Danger.copy(alpha = 0.5f)
                    else -> OnRoadColors.BorderSage.copy(alpha = 0.7f)
                },
                shape = OnRoadShapes.Item,
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$offsetLabel  ${date.format(dateFormat)}",
                        style = OnRoadType.Micro,
                        color = if (isDday) OnRoadColors.Primary else OnRoadColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    when (status) {
                        TaskStatus.DONE -> OnRoadTag("완료", style = TagStyle.PrimaryFilled, shape = OnRoadShapes.Badge)
                        TaskStatus.TODAY -> OnRoadTag("오늘", style = TagStyle.Danger)
                        TaskStatus.SOON -> OnRoadTag("곧", style = TagStyle.Danger)
                        TaskStatus.PAST -> OnRoadTag("지난 일정", style = TagStyle.SlateFilled)
                        TaskStatus.LATER -> Unit
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    task.title,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = if (done) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(2.dp))
                Text(task.description, style = OnRoadType.Caption2, color = OnRoadColors.TextPrimary.copy(alpha = 0.6f))
                if (task.linkLabel != null) {
                    Spacer(Modifier.height(10.dp))
                    SmallBoxButton(task.linkLabel, onLink, trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight)
                }
            }
        }
    }
}
