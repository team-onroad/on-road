package com.project.on_road.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

import androidx.compose.foundation.layout.Box

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import com.project.on_road.data.PlanData
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
    // 할 일 · 끝낸 일은 앱 전체가 같이 보는 PlanData 에 둔다 (홈 · 알림 · 결과 화면과 같은 목록)
    val region: String? get() = PlanData.region
    val policies: List<PlanPolicy> get() = PlanData.policies
    val weak: PlanWeakArea? get() = PlanData.weak
    val done get() = PlanData.done
    val na get() = PlanData.na

    init {
        viewModelScope.launch { PlanData.refresh(force = true) }
    }

    fun plan(today: LocalDate): List<PlanTask> =
        TimelinePlanner.plan(PlanInput(leaveDate, today, region, work, policies, weak))

    /** 신청을 끝낸 정책 때문에 자동으로 끝난 할 일 (id → 까닭) */
    fun autoDone(): Map<String, String> = PlanData.autoDone()

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
                .onSuccess { AppContainer.applyProfile(it); com.project.on_road.ui.components.OnRoadToast.show("날짜를 저장했어요") }
                .onFailure {
                    android.util.Log.e("OnRoad", "퇴소일 서버 저장 실패", it)
                    com.project.on_road.ui.components.OnRoadToast.show("서버에 저장하지 못했어요. 인터넷 연결을 확인해 주세요.")
                }
        }
    }

    fun toggle(id: String) = PlanData.toggleDone(id)

    fun toggleNa(id: String) = PlanData.toggleNa(id)
}

private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val fullDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN)

private fun dLabel(days: Long): String = when {
    days > 0 -> "D-$days"
    days == 0L -> "D-DAY"
    else -> "D+${-days}"
}

/** 할 일에서 바로 갈 화면 (NextTask.kt 의 planRoute 와 같다) */
private fun routeOf(go: PlanGo): String =
    // 일정에서 연습하러 가면, 끝난 뒤 '일정으로 돌아가기'
    if (go is PlanGo.Practice) Routes.roleplay(go.scenarioId, from = "schedule") else planRoute(go)

/** 퇴소 D-day 타임라인 (내 상황에 맞춘 할 일). 알림·홈에서 바로 여는 화면 */
@Composable
fun TimelineScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    vm: TimelineViewModel = viewModel(),
) {
    OnRoadScreen("D-day 타임라인", onBack) { TimelineBody(onNavigate, vm) }
}

/** 타임라인 내용. 타임라인 화면과 신청 관리의 '일정' 탭이 같이 쓴다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.TimelineBody(
    onNavigate: (String) -> Unit,
    vm: TimelineViewModel = viewModel(),
    /** false 면 위 '퇴소까지' 카드를 빼고 그린다 (신청 관리 '일정' 칸은 카드를 스크롤 밖에 고정해 둔다) */
    showHeader: Boolean = true,
) {
    var showClosed by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val all = vm.plan(today)
    val auto = vm.autoDone()
    // 오래 지난 일은 놓친 일에 쌓지 않고 지난 일로 넘긴다
    val stale = all.filter { it.date.isBefore(today.minusDays(TimelinePlanner.MISSED_DAYS.toLong())) }
    val closed = all.filter { it.id in vm.done || it.id in vm.na || it.id in auto || it in stale }
    val open = all - closed.toSet()
    val missed = open.filter { it.date.isBefore(today) }
    val thisWeek = open.filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(7)) }
    val later = open.filter { it.date.isAfter(today.plusDays(7)) }

    run {
        if (showHeader) TimelineInfoCard(vm)
        // 타임라인: 놓친 일 → 오늘 → 다가오는 일을 날짜순으로 한 줄에
        Spacer(Modifier.height(if (showHeader) 28.dp else 8.dp))
        val line = missed + thisWeek + later
        if (line.isEmpty()) {
            Text("남은 할 일이 없어요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(start = 4.dp))
        }
        // 처음엔 놓친 일 + 앞으로 한 달 안의 일만 보이고, 그 뒤는 맨 아래 '이후 일정 보기' 하나로 펼친다
        var showLater by remember { mutableStateOf(false) }
        val soon = line.filter { !it.date.isAfter(today.plusDays(NEAR_DAYS)) }
        val rest = line - soon.toSet()
        val shown = if (showLater) line else soon
        var lastMonth: java.time.YearMonth? = null
        shown.forEachIndexed { i, t ->
            val month = java.time.YearMonth.from(t.date)
            if (month != lastMonth) {
                lastMonth = month
                MonthHeader(month, first = i == 0)
            }
            Box(Modifier.stagger(i)) {
                TimelineRow(t, today, vm, onNavigate, left = i % 2 == 0, last = i == shown.lastIndex && rest.isEmpty())
            }
        }
        if (rest.isNotEmpty()) {
            // 가운데 선 끝에 붙은 글씨 버튼 하나
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier
                        .clip(OnRoadShapes.Chip)
                        .tap { showLater = !showLater }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showLater) "이후 일정 접기" else "이후 일정 보기",
                        style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                        color = OnRoadColors.TextTertiary,
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp).flipWhen(showLater))
                }
            }
        }

        if (closed.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            Row(
                Modifier.fillMaxWidth().clip(OnRoadShapes.Item).tap { showClosed = !showClosed }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("끝낸 일·지난 일", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(if (showClosed) "접기" else "보기", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
            }
            ExpandArea(showClosed) {
                Column(Modifier.padding(top = 12.dp)) {
                    closed.forEachIndexed { i, t ->
                        TimelineRow(t, today, vm, onNavigate, autoReason = auto[t.id], left = i % 2 == 0, last = i == closed.lastIndex)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

}

/**
 * '퇴소까지' 정보 카드: 연회색 바탕 · 회색 테두리 (앱 데이터 카드 모양).
 * 퇴소까지 D-n · 날짜 · 지금 하는 일, 오른쪽 '정보 수정'에서 날짜와 지금 하는 일을 바꾼다
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TimelineInfoCard(vm: TimelineViewModel = viewModel(), modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), vm.leaveDate)
    OnRoadCard(
        modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("퇴소까지 ${dLabel(daysLeft)}", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(vm.leaveDate.format(fullDateFormat), vm.work?.label).joinToString(" · "),
                    style = OnRoadType.Caption2,
                    color = OnRoadColors.TextTertiary,
                    maxLines = 1,
                )
            }
            Row(
                Modifier.clip(OnRoadShapes.Chip).tap(onClickLabel = "퇴소일 · 지금 하는 일 수정") { editing = true }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("정보 수정", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }

    // 정보 수정: 퇴소 예정일 · 지금 하는 일 (바꾸면 바로 저장되고 할 일이 다시 짜인다)
    if (editing) InfoEditDialog(vm) { editing = false }
}

/**
 * 정보 수정 창. 화면 양옆 20dp 여백을 둔 흰 카드 한 장.
 * 위계: 창 제목 → 항목 이름(퇴소 예정일 · 지금 하는 일) → 입력칸 / 칩 → 아래 확인 버튼 하나.
 * 날짜는 년 · 월 · 일 휠로 고른다 (달력은 무거워서 창이 버벅였다). 확인을 누를 때 저장한다.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InfoEditDialog(vm: TimelineViewModel, onDismiss: () -> Unit) {
    // 고르는 중인 날짜 (확인을 눌러야 저장)
    var draft by remember { mutableStateOf(vm.leaveDate) }
    val confirm = {
        if (draft != vm.leaveDate) vm.updateDate(draft)
        onDismiss()
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(OnRoadShapes.Card)
                .background(Color.White)
                .padding(24.dp),
        ) {
            Text("정보 수정", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)

            // 퇴소 예정일: 년 · 월 · 일 휠 (달력보다 가볍다). '확인'을 누를 때 저장한다
            Spacer(Modifier.height(24.dp))
            Text("퇴소 예정일", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            val thisYear = LocalDate.now().year
            val years = remember { (thisYear - 1..thisYear + 6).toList() }
            val dayCount = java.time.YearMonth.of(draft.year, draft.monthValue).lengthOfMonth()
            // 년 · 월 · 일 휠 셋에 가운데 선택 띠 하나만 가로로 길게 (휠마다 따로 그리지 않는다)
            Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(OnRoadShapes.Field)
                    .background(OnRoadColors.SurfaceMuted),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelPicker(
                    items = years.map { "${it}년" },
                    selectedIndex = years.indexOf(draft.year).coerceAtLeast(0),
                    onSelect = { i -> draft = clampDate(years[i], draft.monthValue, draft.dayOfMonth) },
                    modifier = Modifier.weight(1.3f),
                    itemHeight = 40.dp,
                    visibleCount = 3,
                    textStyle = OnRoadType.Body2,
                    showBand = false,
                )
                WheelPicker(
                    items = (1..12).map { "${it}월" },
                    selectedIndex = draft.monthValue - 1,
                    onSelect = { i -> draft = clampDate(draft.year, i + 1, draft.dayOfMonth) },
                    modifier = Modifier.weight(1f),
                    itemHeight = 40.dp,
                    visibleCount = 3,
                    textStyle = OnRoadType.Body2,
                    showBand = false,
                )
                WheelPicker(
                    items = (1..dayCount).map { "${it}일" },
                    selectedIndex = draft.dayOfMonth - 1,
                    onSelect = { i -> draft = clampDate(draft.year, draft.monthValue, i + 1) },
                    modifier = Modifier.weight(1f),
                    itemHeight = 40.dp,
                    visibleCount = 3,
                    textStyle = OnRoadType.Body2,
                    showBand = false,
                )
            }
            }

            // 지금 하는 일: 고르기 칩 (같은 걸 다시 누르면 풀린다)
            Spacer(Modifier.height(24.dp))
            Text("지금 하는 일", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkStatus.entries.forEach { w -> ChoiceChip(w.label, selected = vm.work == w) { vm.pickWork(w) } }
            }

            Spacer(Modifier.height(28.dp))
            OnRoadButton("확인", confirm, Modifier.fillMaxWidth(), textStyle = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold))
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

/** 가운데 세로 선 */
@Composable
private fun CenterLine(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(OnRoadColors.BorderChip))
}

/** 달 제목: 가운데에 '2026년 11월'과 그 달의 기간 */
@Composable
private fun MonthHeader(month: java.time.YearMonth, first: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!first) CenterLine(Modifier.height(12.dp))
        Text("${month.year}년 ${month.monthValue}월", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(
            "${month.atDay(1).format(rangeFormat)}~${month.atEndOfMonth().format(rangeFormat)}",
            style = OnRoadType.Caption2,
            color = OnRoadColors.TextTertiary,
        )
        CenterLine(Modifier.height(16.dp))
    }
}

/** 그 달에 없는 날(2월 30일 등)은 마지막 날로 맞춘다 */
private fun clampDate(year: Int, month: Int, day: Int): LocalDate {
    val last = java.time.YearMonth.of(year, month).lengthOfMonth()
    return LocalDate.of(year, month, day.coerceAtMost(last))
}

/** 처음에 보여 줄 기간: 오늘부터 이 날짜 수 안의 일 (그 뒤는 '이후 일정 보기') */
private const val NEAR_DAYS = 30L

private val rangeFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.KOREAN)

/**
 * 타임라인 한 줄: 가운데 세로 선과 점, 할 일은 선 왼쪽 · 오른쪽에 번갈아.
 * 점 색: 끝냄 = 포인트 색, 오늘 = 메인 색, 놓친 일 = 빨강, 다가오는 일 = 회색. 점을 누르면 끝냄 표시.
 * 할 일을 누르면 설명 · 바로 하기 · 해당 없음이 펼쳐진다 (제목 옆 +).
 */
@Composable
private fun TimelineRow(
    t: PlanTask,
    today: LocalDate,
    vm: TimelineViewModel,
    onNavigate: (String) -> Unit,
    autoReason: String? = null,
    left: Boolean,
    last: Boolean,
) {
    val done = t.id in vm.done || autoReason != null
    val na = t.id in vm.na
    val closed = done || na || t.date.isBefore(today.minusDays(TimelinePlanner.MISSED_DAYS.toLong()))
    val missed = !closed && t.date.isBefore(today)
    val isToday = t.date == today && !closed
    val upcoming = !closed && !missed && !isToday
    var open by remember(t.id) { mutableStateOf(false) }
    val checkable = t.kind != PlanKind.POLICY && autoReason == null

    val dot by animateColorAsState(
        when {
            done -> OnRoadColors.Point
            isToday -> OnRoadColors.Primary
            missed -> OnRoadColors.Danger
            else -> OnRoadColors.BorderChip
        },
        OnRoadMotion.move(OnRoadMotion.FAST),
        label = "dot",
    )
    // 다가오는 일 · 끝낸 일은 연한 글씨, 오늘 · 놓친 일은 진한 글씨
    val titleColor = if (upcoming || closed) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary
    val offset = t.offsetDays
    val dText = when {
        autoReason != null -> autoReason
        na -> "해당 없음"
        t.label != null -> t.label
        offset == null -> null
        offset == 0 -> "D-DAY"
        offset < 0 -> "D$offset"
        else -> "D+$offset"
    }
    val chip = listOfNotNull(if (t.date == today) "오늘" else t.date.format(shortDateFormat), dText).joinToString(" · ")
    val align = if (left) Alignment.End else Alignment.Start
    val textAlign = if (left) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start

    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Chip)
                .tap(enabled = !closed) { open = !open }
                .padding(horizontal = 4.dp),
            horizontalAlignment = align,
        ) {
            Text(
                t.title,
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                color = titleColor,
                textAlign = textAlign,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            Spacer(Modifier.height(6.dp))
            // 날짜 태그 + 펼치기(+). 놓친 일만 빨간 태그 (뜻이 있는 곳에만 색)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnRoadTag(chip, style = if (missed) TagStyle.Danger else TagStyle.Neutral)
                if (!closed) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(OnRoadColors.SurfaceSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (open) "−" else "+", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                    }
                }
            }
            ExpandArea(open && !closed) {
                Column(Modifier.padding(top = 8.dp), horizontalAlignment = align) {
                    Text(t.description, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, textAlign = textAlign)
                    Spacer(Modifier.height(6.dp))
                    t.actions.forEach { a ->
                        Row(
                            Modifier.clip(OnRoadShapes.Chip).tap { onNavigate(routeOf(a.go)) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.Primary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (t.kind != PlanKind.POLICY) {
                        Text(
                            "해당 없음",
                            style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                            color = OnRoadColors.TextTertiary,
                            modifier = Modifier.tap(haptic = true) { vm.toggleNa(t.id) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            if (na) {
                Text(
                    "되돌리기",
                    style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.tap(haptic = true) { vm.toggleNa(t.id) }.padding(top = 4.dp),
                )
            }
        }
    }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.weight(1f).padding(bottom = 18.dp), contentAlignment = Alignment.TopEnd) { if (left) content() }
        // 가운데 선 · 점 (점을 누르면 끝냄)
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                CenterLine(Modifier.height(6.dp))
                Spacer(Modifier.height(10.dp))
                if (last) Spacer(Modifier.weight(1f)) else CenterLine(Modifier.weight(1f))
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .tap(haptic = true, enabled = checkable, onClickLabel = if (done) "끝냄 취소" else "끝냄") { vm.toggle(t.id) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            }
        }
        Box(Modifier.weight(1f).padding(bottom = 18.dp), contentAlignment = Alignment.TopStart) { if (!left) content() }
    }
}

private val shortDateFormat = DateTimeFormatter.ofPattern("M.d", Locale.KOREAN)

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
    val checkBg by animateColorAsState(if (done) OnRoadColors.Primary else Color.White, OnRoadMotion.move(OnRoadMotion.FAST), label = "task-check-bg")
    val checkScale by animateFloatAsState(if (done) 1f else 0.4f, OnRoadMotion.move(OnRoadMotion.FAST), label = "task-check-scale")
    val checkAlpha by animateFloatAsState(if (done) 1f else 0f, OnRoadMotion.move(OnRoadMotion.FAST), label = "task-check-alpha")
    val checkLine by animateColorAsState(if (done) OnRoadColors.Primary else OnRoadColors.BorderChip, OnRoadMotion.move(OnRoadMotion.FAST), label = "task-check-line")
    // 한눈에 보이게: 처음엔 체크 · 제목 · 날짜 한 줄씩만. 누르면 설명 · 바로 하기 · 해당 없음이 펼쳐진다
    var open by remember(t.id) { mutableStateOf(false) }
    val missedCard = past && !closed
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        // 놓친 일은 테두리로 표시 (목표 D-day 화면의 '곧 다가오는 일'과 같은 표시)
        borderColor = if (missedCard) OnRoadColors.Danger.copy(alpha = 0.5f) else OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = if (closed) null else ({ open = !open }),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 정책 할 일은 체크리스트에서 끝낸다 (여기서는 체크 없음)
            if (t.kind != PlanKind.POLICY) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(checkBg)
                        .border(1.dp, checkLine, CircleShape)
                        .tap(haptic = true, enabled = autoReason == null) { vm.toggle(t.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    // 체크 표시가 톡 튀어나오고, 풀면 오므라들며 사라진다
                    Icon(
                        Icons.Filled.Check,
                        if (done) "완료 취소" else null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp).graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkAlpha },
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    t.title,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = if (closed) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(if (na) "$whenText · 해당 없음" else whenText, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
            }
            if (todayMark && !closed) {
                Spacer(Modifier.width(8.dp))
                OnRoadTag("오늘", style = TagStyle.Neutral)
            }
            if (!closed) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.KeyboardArrowDown, if (open) "접기" else "펼치기", tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp).flipWhen(open))
            } else if (na) {
                Text(
                    "되돌리기",
                    style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.tap(haptic = true) { vm.toggleNa(t.id) }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
        ExpandArea(open && !closed) {
            // 체크 동그라미 오른쪽 글자 줄에 맞춘다
            Column(Modifier.padding(start = if (t.kind != PlanKind.POLICY) 34.dp else 0.dp, top = 8.dp)) {
                Text(t.description, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        // 바로 할 일: 메인 색 글씨 + › (정책 카드의 '자세히 보기'와 같은 모양)
                        t.actions.forEach { a ->
                            Row(
                                Modifier.clip(OnRoadShapes.Chip).tap { onNavigate(routeOf(a.go)) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(a.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.Primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (t.kind != PlanKind.POLICY) {
                        Text(
                            "해당 없음",
                            style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                            color = OnRoadColors.TextTertiary,
                            modifier = Modifier.tap(haptic = true) { vm.toggleNa(t.id) }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
