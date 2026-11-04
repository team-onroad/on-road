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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.project.on_road.data.UserUpdate
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.PlanAction
import com.project.on_road.data.PlanGo
import com.project.on_road.data.PlanKind
import com.project.on_road.data.PlanPolicy
import com.project.on_road.data.PlanTask
import com.project.on_road.data.PlanInput
import com.project.on_road.data.PlanWeakArea
import com.project.on_road.data.TimelinePlanner
import com.project.on_road.data.WorkStatus
import com.project.on_road.ui.screens.navigation.Routes
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
    var work by mutableStateOf(session.workStatus)
        private set
    var region by mutableStateOf<String?>(null)
        private set
    var policies by mutableStateOf<List<PlanPolicy>>(emptyList())
        private set
    var weak by mutableStateOf<PlanWeakArea?>(null)
        private set
    val done = mutableStateListOf<String>().apply { addAll(session.timelineDone) }
    val na = mutableStateListOf<String>().apply { addAll(session.timelineNa) }

    init {
        loadPersonal()
    }

    /** 사는 지역 · 진행 중인 신청 · 저장한 정책. 못 불러와도 기본 할 일은 그대로 보인다 */
    private fun loadPersonal() {
        val userId = session.userId ?: return
        viewModelScope.launch {
            region = runCatching { AppContainer.api.getUser(userId)?.region }.getOrNull()
            val checklists = runCatching { AppContainer.api.getChecklists(userId) }.getOrDefault(emptyList())
            // 진행 중 + 끝낸 신청 (끝낸 신청은 관련 할 일을 자동으로 끝낸다)
            val mine = checklists.map { PlanPolicy(it.policyId, it.policyName, it.nextStep?.title, completed = it.completed) }
            val known = checklists.map { it.policyId }.toSet()
            val saved = session.savedPolicyIds.filter { it !in known }.take(3).mapNotNull { id ->
                runCatching { AppContainer.api.getPolicy(id) }.getOrNull()?.let { PlanPolicy(it.id, it.name, null) }
            }
            policies = mine + saved
            // 자립 준비도에서 가장 약한 영역
            weak = runCatching { AppContainer.api.getReadiness(userId) }.getOrNull()?.let { r ->
                r.areas.firstOrNull { it.key == r.weakest }?.let { a -> a.nextStep?.let { PlanWeakArea(a.label, it) } }
            }
        }
    }

    fun plan(today: LocalDate): List<PlanTask> =
        TimelinePlanner.plan(PlanInput(leaveDate, today, region, work, policies, weak))

    /** 신청을 끝낸 정책 때문에 자동으로 끝난 할 일 (id → 까닭) */
    fun autoDone(): Map<String, String> = TimelinePlanner.autoDone(policies).associate { it.taskId to it.reason }

    fun pickWork(w: WorkStatus) {
        work = if (work == w) null else w
        session.workStatus = work
    }

    fun updateDate(date: LocalDate) {
        leaveDate = date
        session.leaveDate = date
        if (alarmOn) DdayNotifier.schedule(getApplication(), date)
        saveToServer(date)
    }

    /** 3.12 회원정보 수정으로 서버 d_date 도 바꾼다. 실패해도 앱 안 날짜는 그대로 둔다 */
    private fun saveToServer(date: LocalDate) {
        val userId = session.userId ?: return
        viewModelScope.launch {
            runCatching { AppContainer.api.updateUser(userId, UserUpdate(dDate = date)) }
                .onSuccess { AppContainer.applyProfile(it) }
                .onFailure {
                    android.util.Log.e("OnRoad", "퇴소일 서버 저장 실패", it)
                    Toast.makeText(getApplication(), "서버에 저장하지 못했어요. 인터넷 연결을 확인해 주세요.", Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun toggle(id: String) {
        if (id in done) done.remove(id) else done.add(id)
        session.timelineDone = done.toSet()
    }

    fun toggleNa(id: String) {
        if (id in na) na.remove(id) else na.add(id)
        session.timelineNa = na.toSet()
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val fullDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN)

private fun dLabel(days: Long): String = when {
    days > 0 -> "D-$days"
    days == 0L -> "D-DAY"
    else -> "D+${-days}"
}

/** 할 일에서 바로 갈 화면 */
private fun routeOf(go: PlanGo): String = when (go) {
    is PlanGo.Practice -> Routes.roleplay(go.scenarioId)
    PlanGo.RiskCheck -> Routes.RISK
    is PlanGo.Checklist -> Routes.checklist(go.policyId)
    is PlanGo.PolicySearch -> Routes.policy(query = go.query)
    PlanGo.Budget -> Routes.SIM_BUDGET
    PlanGo.Housing -> Routes.SIM_HOUSING
    PlanGo.Savings -> Routes.SIM_SAVINGS
    PlanGo.MonthLife -> Routes.SIM_MONTH
    PlanGo.Forms -> Routes.FORMS
    is PlanGo.Chat -> Routes.chat(question = go.question)
}

/** 퇴소 D-day 타임라인 (내 상황에 맞춘 할 일) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    vm: TimelineViewModel = viewModel(),
) {
    var showPicker by remember { mutableStateOf(false) }
    var showClosed by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val daysLeft = ChronoUnit.DAYS.between(today, vm.leaveDate)
    val all = vm.plan(today)
    val auto = vm.autoDone()
    // 오래 지난 일은 놓친 일에 쌓지 않고 지난 일로 넘긴다
    val stale = all.filter { it.date.isBefore(today.minusDays(TimelinePlanner.MISSED_DAYS.toLong())) }
    val closed = all.filter { it.id in vm.done || it.id in vm.na || it.id in auto || it in stale }
    val open = all - closed.toSet()
    val missed = open.filter { it.date.isBefore(today) }
    val thisWeek = open.filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(7)) }
    val later = open.filter { it.date.isAfter(today.plusDays(7)) }

    OnRoadScreen("퇴소 D-day 타임라인", onBack) {
        // 퇴소일: 흰 카드 (왼쪽 D-day · 날짜, 오른쪽 날짜 바꾸기)
        OnRoadCard(
            Modifier.fillMaxWidth(),
            borderColor = OnRoadColors.BorderChip,
            shape = OnRoadShapes.Item,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("퇴소 예정일", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
                    Text(dLabel(daysLeft), style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
                    Text(vm.leaveDate.format(fullDateFormat), style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                }
                Spacer(Modifier.width(12.dp))
                PrimaryPill("날짜 바꾸기", { showPicker = true })
            }
        }

        // 지금 하는 일: 고르면 그에 맞는 할 일이 더해진다
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubHeader("지금 나는")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkStatus.entries.forEach { w -> ChoiceChip(w.label, selected = vm.work == w) { vm.pickWork(w) } }
        }

        if (missed.isNotEmpty()) {
            Group("놓친 일") { missed.forEach { TaskCard(it, today, vm, onNavigate) } }
        }
        Group("이번 주") {
            if (thisWeek.isEmpty()) {
                Text("이번 주에 할 일은 없어요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(start = 4.dp))
            } else {
                thisWeek.forEach { TaskCard(it, today, vm, onNavigate) }
            }
        }
        if (later.isNotEmpty()) {
            Group("다가오는 일") { later.forEach { TaskCard(it, today, vm, onNavigate) } }
        }
        if (closed.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            Row(
                Modifier.fillMaxWidth().clip(OnRoadShapes.Item).clickable { showClosed = !showClosed }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("끝낸 일·지난 일", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(if (showClosed) "접기" else "보기", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
            }
            if (showClosed) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    closed.forEach { TaskCard(it, today, vm, onNavigate, auto[it.id]) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    SubHeader(title)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** 연회색 카드: 체크 동그라미 · 날짜 / 제목 / 설명 / 바로 하기 버튼 · 해당 없음 */
@Composable
private fun TaskCard(
    t: PlanTask,
    today: LocalDate,
    vm: TimelineViewModel,
    onNavigate: (String) -> Unit,
    /** 체크리스트로 신청을 끝내서 자동으로 끝난 까닭 */
    autoReason: String? = null,
) {
    val done = t.id in vm.done || autoReason != null
    val na = t.id in vm.na
    val past = !done && !na && t.date.isBefore(today)
    val closed = done || na || (past && t.date.isBefore(today.minusDays(TimelinePlanner.MISSED_DAYS.toLong())))
    val offset = t.offsetDays
    val whenText = when {
        autoReason != null -> autoReason
        t.label != null -> t.label
        offset == null -> t.date.format(fullDateFormat)
        offset == 0 -> "D-DAY · ${t.date.format(dateFormat)}"
        offset < 0 -> "D$offset · ${t.date.format(dateFormat)}"
        else -> "D+$offset · ${t.date.format(dateFormat)}"
    }
    val todayMark = t.kind != PlanKind.POLICY && t.date == today
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 정책 할 일은 체크리스트에서 끝낸다 (여기서는 체크 없음)
            if (t.kind != PlanKind.POLICY) {
                Box(
                    Modifier
                        .padding(top = 1.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (done) OnRoadColors.Primary else Color.White)
                        .border(1.dp, if (done) OnRoadColors.Primary else OnRoadColors.BorderChip, CircleShape)
                        .clickable(enabled = autoReason == null) { vm.toggle(t.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Filled.Check, "완료 취소", tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(whenText, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
                    if (todayMark && !closed) OnRoadTag("오늘", style = TagStyle.PrimaryFilled)
                    if (na) Text("해당 없음", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    t.title,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = if (closed) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary,
                )
                if (!closed) {
                    Spacer(Modifier.height(2.dp))
                    Text(t.description, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
                    if (t.actions.isNotEmpty() || t.kind != PlanKind.POLICY) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                t.actions.forEach { a -> PrimaryPill(a.label, { onNavigate(routeOf(a.go)) }) }
                            }
                            if (t.kind != PlanKind.POLICY) {
                                Text(
                                    "해당 없음",
                                    style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                                    color = OnRoadColors.TextTertiary,
                                    modifier = Modifier.clickable { vm.toggleNa(t.id) }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                } else if (na) {
                    Text(
                        "되돌리기",
                        style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                        color = OnRoadColors.TextTertiary,
                        modifier = Modifier.clickable { vm.toggleNa(t.id) }.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
