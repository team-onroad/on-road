package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.animation.togetherWith
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.MonthLife
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlin.random.Random

class MonthLifeViewModel : ViewModel() {
    var incomeText by mutableStateOf("120")
        private set
    var home by mutableStateOf(MonthLife.Home.STUDIO)
    var emergency by mutableStateOf(10)
    var state by mutableStateOf<MonthLife.State?>(null)
        private set
    /** 방금 고른 것 (결과를 보여 주고 "계속"을 누르면 지운다) */
    var lastPick by mutableStateOf<Picked?>(null)
        private set
    private var seed = Random.nextInt()

    data class Picked(val weekNo: Int, val happening: MonthLife.Happening, val choice: MonthLife.Choice, val emergencyUsed: Int)

    val income: Int get() = incomeText.toIntOrNull() ?: 0
    val setup: MonthLife.Setup get() = MonthLife.Setup(income, home, emergency, seed)
    val canStart: Boolean get() = income > 0

    fun updateIncome(text: String) {
        incomeText = text.filter(Char::isDigit).take(4).trimStart('0')
    }

    fun setIncomePreset(v: Int) {
        incomeText = v.toString()
    }

    fun start() {
        if (!canStart) return
        state = MonthLife.start(setup)
        lastPick = null
    }

    fun pick(choice: MonthLife.Choice) {
        val st = state ?: return
        val h = st.current ?: return
        val next = MonthLife.pick(st, choice)
        lastPick = Picked(st.week.no, h, choice, st.emergency - next.emergency)
        state = next
    }

    /** 결과를 봤으면: 이번 주 남은 일 → 없으면 다음 주 */
    fun next() {
        lastPick = null
        state = state?.let { MonthLife.nextWeek(it) }
        logIfDone()
    }

    private var logged: MonthLife.State? = null

    /** 한 달이 끝나면 한 번 남긴다 → 홈 '지금 할 일' 추천과 성장 기록에 쓰인다 */
    private fun logIfDone() {
        val s = state ?: return
        if (!s.done || logged === s) return
        logged = s
        val r = MonthLife.report(s)
        com.project.on_road.data.ActivityStore.logMonthLife(
            com.project.on_road.data.MonthLifeRecord(
                at = System.currentTimeMillis(), left = r.left, shortage = r.shortage, usedEmergency = r.usedEmergency,
                home = s.setup.home.name, good = r.good.size, bad = r.missed.size, badEvents = r.badEvents,
            ),
        )
        // 서버에도 남겨 자립 준비도에 반영 (실패해도 그대로)
        val userId = com.project.on_road.data.AppContainer.session.userId ?: return
        viewModelScope.launch {
            runCatching {
                com.project.on_road.data.AppContainer.api.logActivity(
                    userId, "month_life", null,
                    mapOf(
                        "income" to s.setup.income.coerceIn(0, 10000), "left" to r.left.coerceIn(-10000, 10000),
                        "shortage" to r.shortage.coerceIn(0, 10000), "used_emergency" to r.usedEmergency.coerceIn(0, 10000),
                        "home" to s.setup.home.name, "good" to r.good.size.coerceAtMost(100), "bad" to r.missed.size.coerceAtMost(100),
                        "bad_events" to r.badEvents.distinct().take(40),
                    ),
                )
            }
        }
    }

    /** 같은 조건으로 다시 (같은 선택이면 같은 일이 생긴다) */
    fun retrySame() {
        start()
    }

    /** 다른 일이 생기는 새 한 달 */
    fun retryNew() {
        seed = Random.nextInt()
        start()
    }

    fun reset() {
        state = null
        lastPick = null
    }
}

private fun won(v: Int): String = when {
    v > 0 -> "+${v}만원"
    v < 0 -> "−${-v}만원"
    else -> "0원"
}

/** 한 달 살아보기 */
@Composable
fun MonthLifeScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    /** 생활비 배분에서 넘어오면 그 수입으로 시작 */
    income: Int? = null,
    vm: MonthLifeViewModel = viewModel(),
) {
    LaunchedEffect(income) { income?.takeIf { it > 0 }?.let(vm::setIncomePreset) }
    val s = vm.state
    val back = { if (s != null) vm.reset() else onBack() }
    androidx.activity.compose.BackHandler(onBack = back)
    // 단계가 바뀌면 화면을 맨 위에서 다시 시작
    val step = when {
        s == null -> "setup"
        vm.lastPick != null -> "result-${s.week.no}-${s.week.happenings.count { it.picked != null }}"
        s.done -> "report"
        else -> "week-${s.week.no}-${s.week.happenings.count { it.picked != null }}"
    }
    // 단계마다 그때의 상태를 같이 넘겨, 넘어가는 동안 앞 화면이 바뀐 값으로 깜빡이지 않게
    val view = MonthView(step, s, vm.lastPick)
    androidx.compose.animation.AnimatedContent(
        targetState = view,
        contentKey = { it.step },
        transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { it / 12 })
                .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)))
        },
        label = "month-step",
    ) { v ->
        val st = v.state
        when {
            st == null -> SetupPage(vm, back)
            v.pick != null -> ResultPage(vm, st, v.pick, back)
            st.done -> ReportPage(vm, st, back, { onNavigate(com.project.on_road.ui.screens.navigation.stepRoute(it)) }, onBack)
            else -> WeekPage(vm, st, back)
        }
    }
}

private data class MonthView(val step: String, val state: MonthLife.State?, val pick: MonthLifeViewModel.Picked?)

/* ---------------------------- 시작 ---------------------------- */

@Composable
private fun SetupPage(vm: MonthLifeViewModel, onBack: () -> Unit) {
    val cash = MonthLife.startingCash(vm.setup)
    OnRoadScreen(
        "한 달 살아보기",
        onBack,
        bottomBar = {
            OnRoadButton("한 달 시작하기", vm::start, Modifier.fillMaxWidth(), enabled = vm.canStart)
        },
    ) {
        ScreenHeadline("혼자 사는 한 달,", "미리 살아 봐요.")
        Spacer(Modifier.height(6.dp))
        Text("매주 살다 보면 생길 법한 일이 확률대로 랜덤으로 일어나요. 직접 골라 대처하고, 한 달이 끝나면 남은 돈과 잘한 점을 알려 드려요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("한 달 수입", titleStyle = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonthLife.INCOME_PRESETS.forEach { v -> ChoiceChip("${v}만원", selected = vm.income == v) { vm.setIncomePreset(v) } }
        }
        Spacer(Modifier.height(10.dp))
        OnRoadTextField(
            value = vm.incomeText,
            onValueChange = vm::updateIncome,
            placeholder = "직접 입력",
            keyboardType = KeyboardType.Number,
            suffix = "만원",
            background = OnRoadColors.SurfaceSubtle,
            borderColor = OnRoadColors.BorderChip,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text("자립수당·알바·월급 등을 합친 금액이에요.", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("사는 곳", titleStyle = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonthLife.Home.entries.forEach { h ->
                PickRow(h.label, h.hint, "월 ${h.cost}만원", selected = vm.home == h) { vm.home = h }
            }
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("비상금 떼어 두기", titleStyle = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonthLife.EMERGENCY_PRESETS.forEach { v -> ChoiceChip(if (v == 0) "안 함" else "${v}만원", selected = vm.emergency == v) { vm.emergency = v } }
        }
        Spacer(Modifier.height(6.dp))
        Text("생활비가 모자랄 때만 꺼내 써요.", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SubtleCard(Modifier.fillMaxWidth()) {
            MoneyLine("수입", "${vm.income}만원")
            MoneyLine("집·공과금", won(-vm.home.cost))
            MoneyLine("휴대폰", won(-MonthLife.PHONE))
            MoneyLine("비상금", won(-vm.emergency))
            Spacer(Modifier.height(6.dp))
            MoneyLine("한 달 생활비", "${cash}만원", strong = true, danger = cash < 0)
            Spacer(Modifier.height(8.dp))
            Text(
                "매주 식비·교통비로 ${MonthLife.weekCost()}만원 정도가 나가요.",
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextSecondary,
            )
        }
    }
}

/* ---------------------------- 한 주 ---------------------------- */

@Composable
private fun WeekPage(vm: MonthLifeViewModel, s: MonthLife.State, onBack: () -> Unit) {
    val w = s.week
    val h = w.current ?: return
    val idx = w.happenings.indexOf(h)
    OnRoadScreen("한 달 살아보기", onBack) {
        WeekHeader(w.no, s)
        if (idx == 0) {
            Spacer(Modifier.height(8.dp))
            SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Text("이번 주 시작", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                MoneyLine("식비·교통비", won(-w.base))
                if (w.carried != 0) MoneyLine(w.carriedText ?: "지난주 선택", won(w.carried), danger = w.carried < 0)
            }
        }

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OnRoadTag("이번 주 일 ${idx + 1} / ${w.happenings.size}", style = TagStyle.Neutral)
            if (h.event.percent > 0) OnRoadTag("확률 ${h.event.percent}%", style = TagStyle.Neutral)
        }
        Spacer(Modifier.height(10.dp))
        Text(h.event.title, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        BubbleText(h.event.body, color = OnRoadColors.TextSecondary, style = OnRoadType.Body2)

        Spacer(Modifier.height(20.dp))
        Text("어떻게 할까요?", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            h.event.choices.forEach { c ->
                PickRow(c.label, null, won(c.money), selected = false) { vm.pick(c) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Footnote("일마다 한 주에 일어날 확률이 있고,\n매주 랜덤으로 생겨요. 몇 개가 생길지는 몰라요.")
    }
}

@Composable
private fun WeekHeader(no: Int, s: MonthLife.State) {
    OnRoadTag("${no}주차 / ${MonthLife.WEEKS}주", style = TagStyle.Neutral)
    Spacer(Modifier.height(12.dp))
    SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row {
            Stat("생활비", "${s.cash}만원", Modifier.weight(1f))
            Stat("비상금", "${s.emergency}만원", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, small: Boolean = false) {
    Column(modifier) {
        Text(label, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
        Text(value, style = if (small) OnRoadType.Body2.copy(fontWeight = FontWeight.Bold) else OnRoadType.Title2, color = OnRoadColors.TextPrimary)
    }
}

/* ---------------------------- 고른 결과 ---------------------------- */

@Composable
private fun ResultPage(vm: MonthLifeViewModel, s: MonthLife.State, p: MonthLifeViewModel.Picked, onBack: () -> Unit) {
    val c = p.choice
    val moreThisWeek = !s.week.done
    OnRoadScreen(
        "한 달 살아보기",
        onBack,
        bottomBar = {
            OnRoadButton(
                when {
                    moreThisWeek -> "계속"
                    s.done -> "한 달 결과 보기"
                    else -> "다음 주로"
                },
                vm::next,
                Modifier.fillMaxWidth(),
            )
        },
    ) {
        OnRoadTag("${p.weekNo}주차 · ${p.happening.event.title}", style = TagStyle.Neutral)
        Spacer(Modifier.height(12.dp))
        Text(c.label, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        BubbleText(c.result, color = OnRoadColors.TextSecondary, style = OnRoadType.Body2)

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubtleCard(Modifier.fillMaxWidth()) {
            MoneyLine("생활비", won(c.money - c.save), strong = true, danger = c.money - c.save < 0)
            if (c.save != 0) MoneyLine("비상금", won(c.save))
            if (c.later != 0 && !s.done) MoneyLine("다음 주", won(c.later), danger = c.later < 0)
        }
        if (p.emergencyUsed > 0) {
            Spacer(Modifier.height(8.dp))
            NoticeBox("생활비가 모자라 비상금에서 ${p.emergencyUsed}만원을 꺼냈어요.", tone = NoticeTone.Warning)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Text("남은 생활비 ${s.cash}만원", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
            Text("비상금 ${s.emergency}만원", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
        }
    }
}

/* ---------------------------- 한 달 결과 ---------------------------- */

@Composable
private fun ReportPage(vm: MonthLifeViewModel, s: MonthLife.State, onBack: () -> Unit, onStep: (com.project.on_road.data.NextStep) -> Unit, onDone: () -> Unit) {
    val r = remember(s) { MonthLife.report(s) }
    OnRoadScreen("한 달 살아보기", onBack) {
        OnRoadTag("한 달 끝", style = TagStyle.PrimaryFilled)
        Spacer(Modifier.height(12.dp))
        Text(r.title, style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        BubbleText(r.summary, color = OnRoadColors.TextSecondary, style = OnRoadType.Body2)

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubtleCard(Modifier.fillMaxWidth()) {
            MoneyLine("다음 달로 넘길 돈", "${r.left}만원", strong = true)
            Spacer(Modifier.height(4.dp))
            MoneyLine("비상금에서 쓴 돈", "${r.usedEmergency}만원")
            if (r.shortage > 0) MoneyLine("모자란 돈", "${r.shortage}만원", danger = true)
        }

        if (r.good.isNotEmpty() || r.missed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.BorderChip, shape = OnRoadShapes.Item, contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp)) {
                Text("이번 달 선택", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                r.good.forEach { ReviewLine(it, ok = true) }
                r.missed.forEach { ReviewLine(it, ok = false) }
            }
        }

        if (r.tips.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.BorderChip, shape = OnRoadShapes.Item, contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp)) {
                Text("다음 달엔 이렇게", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                r.tips.forEach { BulletText(it, OnRoadColors.TextPrimary.copy(alpha = 0.8f), Modifier.padding(vertical = 3.dp)) }
            }
        }

        if (r.nextSteps.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            NextStepList(r.nextSteps, onStep)
        }

        Spacer(Modifier.height(24.dp))
        Footnote("금액은 예시 기준이에요.\n실제 지원 조건은 담당 기관에서 확인해 주세요.")
        Spacer(Modifier.height(24.dp))
        OnRoadButton("같은 한 달 다시 하기", vm::retrySame, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OnRoadButton("다른 일이 생기는 한 달", vm::retryNew, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, borderColor = OnRoadColors.BorderChip)
        Spacer(Modifier.height(8.dp))
        OnRoadButton("목록으로", onDone, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, borderColor = OnRoadColors.BorderChip)
    }
}

/* ---------------------------- 작은 조각 ---------------------------- */

/** 고르는 줄: 흰 카드, 고르면 메인 컬러 테두리 */
@Composable
private fun PickRow(title: String, hint: String?, trailing: String, selected: Boolean, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = if (selected) OnRoadColors.Primary else OnRoadColors.BorderChip,
        borderWidth = if (selected) 1.5.dp else 1.dp,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                hint?.let { Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary) }
            }
            Spacer(Modifier.width(12.dp))
            Text(trailing, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
        }
    }
}

@Composable
private fun MoneyLine(label: String, value: String, strong: Boolean = false, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (strong) OnRoadType.Body2.copy(fontWeight = FontWeight.Bold) else OnRoadType.Caption,
            color = if (strong) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (strong) OnRoadType.Body2.copy(fontWeight = FontWeight.Bold) else OnRoadType.Caption,
            color = if (danger) OnRoadColors.Danger else OnRoadColors.TextPrimary,
        )
    }
}

@Composable
private fun ReviewLine(text: String, ok: Boolean) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Top) {
        Text(
            if (ok) "✓" else "✗",
            style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
            color = if (ok) OnRoadColors.Primary else OnRoadColors.Danger,
            modifier = Modifier.width(20.dp),
        )
        Text(text, style = OnRoadType.Caption, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
    }
}
