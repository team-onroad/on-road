package com.project.on_road.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.CareerTag
import com.project.on_road.data.ExamRound
import com.project.on_road.data.JobDetail
import com.project.on_road.data.JobRecommendation
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.PlanData
import com.project.on_road.data.ProfileStore
import com.project.on_road.data.RoadmapItem
import com.project.on_road.data.TrainingCourse
import com.project.on_road.data.TrainingDetail
import com.project.on_road.ui.components.*
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 관심 분야 하나에 붙는 진로 정보: 직업 상세(추천 직업일 때) · 가장 가까운 시험 회차 · 가까운 훈련 과정 */
data class TagInfo(
    val tag: CareerTag,
    val detail: JobDetail? = null,
    val exam: ExamRound? = null,
    val trainings: List<TrainingCourse> = emptyList(),
)

class CareerRoadmapViewModel : ViewModel() {
    var tags by mutableStateOf<List<CareerTag>>(emptyList())
        private set
    var infos by mutableStateOf<Map<String, TagInfo>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var trainingDetails by mutableStateOf<Map<String, TrainingDetail?>>(emptyMap())
        private set

    fun loadTraining(c: TrainingCourse) {
        val k = c.id + c.round
        if (trainingDetails.containsKey(k)) return
        trainingDetails = trainingDetails + (k to null)
        viewModelScope.launch { trainingDetails = trainingDetails + (k to AppContainer.careerInfo.trainingDetail(c)) }
    }

    /** 서버에 저장된 관심 분야가 기준, 직업 id 는 휴대폰에 둔 것에서 찾는다 */
    private fun currentTags(): List<CareerTag> {
        val local = ActivityStore.careerTags()
        val server = ProfileStore.current?.interestTags.orEmpty()
        return if (server.isEmpty()) local else server.map { n -> local.firstOrNull { it.name == n } ?: CareerTag(n) }
    }

    fun load() {
        tags = currentTags()
        val userId = AppContainer.session.userId
        viewModelScope.launch {
            launch { PlanData.refresh(force = true) }
            ProfileStore.refresh()
            tags = currentTags()
            loading = false
            val today = LocalDate.now()
            val me = ProfileStore.current
            tags.forEach { t ->
                launch {
                    val detail = if (t.jobId != null && userId != null) runCatching {
                        AppContainer.coachApi.jobDetail(userId, JobRecommendation(t.jobId, t.name, "", "", t.types), AppState.stage)
                    }.getOrNull() else null
                    put(TagInfo(t, detail))
                    // 국가 자격만 큐넷 일정이 있다 (민간 자격은 빼고 찾는다)
                    val certs = detail?.certificates.orEmpty().filterNot { "민간" in it }
                    launch {
                        if (certs.isNotEmpty()) {
                            val ex = AppContainer.careerInfo.exams(certs)
                            val round = ex?.rounds?.firstOrNull { it.upcoming(today).isNotEmpty() }
                            update(t.name) { it.copy(exam = round) }
                        }
                    }
                    launch {
                        val list = AppContainer.careerInfo.trainings(detail?.keyword ?: t.name, me?.region, me?.district).take(1)
                        update(t.name) { it.copy(trainings = list) }
                    }
                }
            }
        }
    }

    private fun put(i: TagInfo) { infos = infos + (i.tag.name to (infos[i.tag.name]?.let { old -> i.copy(exam = old.exam, trainings = old.trainings) } ?: i)) }
    private fun update(name: String, f: (TagInfo) -> TagInfo) {
        val old = infos[name] ?: TagInfo(tags.firstOrNull { it.name == name } ?: CareerTag(name))
        infos = infos + (name to f(old))
    }
}

/* ───────────────────────── 시기별로 묶기 ───────────────────────── */

/** 로드맵 한 구간: 이름 · 날짜(예상이면 estimated) · 들어갈 것들 */
private class Period(val title: String, val date: LocalDate?, val estimated: Boolean, val policies: List<RoadmapItem>, val now: Boolean = false)

private val COLLEGE = Regex("대학|장학")

/** 일 찾을 때로 보내는 정책: 취업 분야 */
private fun RoadmapItem.forJob() = category == "employment"
private fun RoadmapItem.forCollege() = category == "education" && COLLEGE.containsMatchIn(name)

/**
 * 맞춤 신청 로드맵(3.32)을 시기로 다시 나눈다.
 * 지금 → 퇴소 전에 미리 → 퇴소할 때 → 일 찾을 때 → 대학 갈 때. 빈 구간은 뺀다.
 * 일 찾을 때 날짜는 관심 분야 시험의 마지막 일정(합격 발표) 다음 달로 잡는다 (시험이 없으면 날짜 없이)
 */
private fun periods(infos: Collection<TagInfo>, today: LocalDate): List<Period> {
    val rm = PlanData.roadmap
    val items = rm?.stages.orEmpty().flatMap { s -> s.items.map { s.stage to it } }
    val jobItems = items.filter { it.second.forJob() }.map { it.second }
    val collegeItems = items.filter { it.second.forCollege() && !it.second.forJob() }.map { it.second }
    val rest = items.filterNot { it.second.forJob() || it.second.forCollege() }
    val examEnd = infos.mapNotNull { it.exam?.upcoming(today)?.maxOfOrNull { e -> e.end } }.maxOrNull()
    val leave = rm?.baseDate
    return listOfNotNull(
        Period("지금", today, false, rest.filter { it.first == "now" }.map { it.second }, now = true),
        rest.filter { it.first == "before_leave" }.map { it.second }.takeIf { it.isNotEmpty() }?.let { p ->
            Period("퇴소 전에 미리", p.mapNotNull { it.applyFrom }.minOrNull(), false, p)
        },
        rest.filter { it.first == "at_leave" }.map { it.second }.takeIf { it.isNotEmpty() }?.let { p -> Period("퇴소할 때", leave, false, p) },
        Period("일 찾을 때", examEnd?.plusMonths(1)?.withDayOfMonth(1), true, jobItems).takeIf { jobItems.isNotEmpty() },
        collegeItems.takeIf { it.isNotEmpty() }?.let { Period("대학 갈 때", null, false, it) },
    ).filter { it.now || it.policies.isNotEmpty() }
}

private fun dateText(d: LocalDate) = "${d.year}. ${d.monthValue}. ${d.dayOfMonth}"
private fun monthText(d: LocalDate) = "${d.year}. ${d.monthValue}"

/* ───────────────────────── 화면 ───────────────────────── */

/**
 * 나의 진로: 흥미 탐색 결과로 고른 관심 분야(최대 3개) + 내 조건(퇴소일 · 나이 · 지역)으로 만든 시기별 로드맵.
 * 관심 분야를 아직 안 골랐으면 흥미 탐색부터 한다.
 * 정책은 같은 종류(자립정착금 등) 중 받는 돈이 큰 것 하나만 보여 주고, 펼치면 나머지와 고른 까닭이 나온다 (서버 3.32).
 */
@Composable
fun CareerRoadmapScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    /** 관심 분야가 없을 때 흥미 탐색으로 (이 화면은 닫는다) */
    onStartInterest: () -> Unit = { onNavigate(Routes.INTEREST) },
    vm: CareerRoadmapViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    // 처음 들어오면 흥미 탐색부터
    var sent by remember { mutableStateOf(false) }
    LaunchedEffect(vm.loading, vm.tags) {
        if (!vm.loading && vm.tags.isEmpty() && !sent) {
            sent = true
            onStartInterest()
        }
    }
    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar("나의 진로", onBack, divider = false)
        when {
            vm.loading -> Column(Modifier.padding(OnRoadDimens.ScreenPadding)) { SkeletonList(rows = 4) }
            vm.tags.isEmpty() -> Column(Modifier.padding(OnRoadDimens.ScreenPadding)) {
                EmptyState("흥미 탐색으로 관심 분야를 고르면 로드맵을 만들어요.")
                Spacer(Modifier.height(16.dp))
                OnRoadButton("흥미 탐색 하기", onStartInterest, Modifier.fillMaxWidth())
            }
            else -> CareerBody(vm, onNavigate)
        }
    }
}

private object CareerSpace {
    val CardTop = 4.dp
    val CardBottom = 8.dp
    val TimelineTop = 20.dp
    val PeriodGap = 28.dp
    val CardGap = 6.dp
    val LineToText = 12.dp
    val Fade = 20.dp
}

@Composable
private fun ColumnScope.CareerBody(vm: CareerRoadmapViewModel, onNavigate: (String) -> Unit) {
    val today = remember { LocalDate.now() }
    val me = ProfileStore.current
    val list = periods(vm.infos.values, today)

    TopCard(
        tags = vm.tags,
        base = PlanData.roadmap?.baseDate ?: me?.let { m -> m.dDate?.takeIf { m.status != LivingStatus.LEFT && !it.isBefore(today) } },
        left = me?.status == LivingStatus.LEFT,
        onTags = { onNavigate(Routes.INTEREST) },
        modifier = Modifier.padding(horizontal = OnRoadDimens.ScreenPadding).padding(top = CareerSpace.CardTop),
    )

    Box(Modifier.weight(1f).padding(top = CareerSpace.CardBottom)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(CareerSpace.TimelineTop))
            list.forEachIndexed { i, p ->
                PeriodItem(p, first = i == 0, last = i == list.lastIndex) {
                    if (p.now) NowContent(vm, today)
                    p.policies.forEach { item -> PolicyPlanCard(item) { onNavigate(Routes.policyDetail(item.policyId)) } }
                }
            }
            Spacer(Modifier.height(32.dp))
            TabBarSpacer()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(CareerSpace.Fade)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(OnRoadColors.Background, OnRoadColors.Background.copy(alpha = 0f)))),
        )
    }
}

/* ── 위 카드: 관심 분야 · 기준일 ── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopCard(tags: List<CareerTag>, base: LocalDate?, left: Boolean, onTags: () -> Unit, modifier: Modifier = Modifier) {
    OnRoadCard(
        modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(16.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { OnRoadTag("#${it.name}") }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(OnRoadShapes.Chip).tap(pressed = 1f, onClickLabel = "관심 분야 바꾸기", onClick = onTags),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    base != null -> "퇴소 예정일 ${dateText(base)} 기준"
                    left -> "퇴소 후, 오늘 기준"
                    else -> "퇴소 예정일을 넣으면 날짜가 정해져요"
                },
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            Text("분야 바꾸기 ›", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
        }
    }
}

/* ── 구간 한 줄: 체크리스트와 같은 세로 선 · 점 ── */

@Composable
private fun PeriodItem(p: Period, first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (first) Spacer(Modifier.height(6.dp)) else CareerLine(Modifier.height(6.dp))
                Spacer(Modifier.height(10.dp))
                if (last) Spacer(Modifier.weight(1f)) else CareerLine(Modifier.weight(1f))
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (p.now) OnRoadColors.Primary else OnRoadColors.BorderChip))
            }
        }
        Spacer(Modifier.width(CareerSpace.LineToText))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else CareerSpace.PeriodGap)) {
            Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.title,
                    style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold),
                    color = if (p.now) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                p.date?.let { d ->
                    Text(
                        if (p.estimated) "${monthText(d)} 예상" else if (p.now) monthText(d) else dateText(d),
                        style = OnRoadType.Caption,
                        color = OnRoadColors.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(CareerSpace.CardGap), content = content)
        }
    }
}

@Composable
private fun CareerLine(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(OnRoadColors.BorderChip))
}

/* ── 지금: 내일배움카드 · 관심 분야 시험 · 가까운 훈련 과정 ── */

@Composable
private fun NowContent(vm: CareerRoadmapViewModel, today: LocalDate) {
    val uri = LocalUriHandler.current
    InfoCard(Modifier.fillMaxWidth(), onClick = { runCatching { uri.openUri("https://www.work24.go.kr/hr/h/a/1100/selectIssuGudn.do") } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("국민내일배움카드", style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(3.dp))
                Text("${vm.tags.joinToString(", ") { it.name }} 훈련비 지원", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            ExpandChevron(false)
        }
    }
    vm.tags.forEach { t ->
        val info = vm.infos[t.name] ?: return@forEach
        info.exam?.let { ExamRoundCard(it, today, showCert = true) }
        info.trainings.forEach { c -> TrainingCourseCard(c, vm.trainingDetails[c.id + c.round]) { vm.loadTraining(c) } }
    }
}

/* ── 정책 카드: 같은 종류 중 고른 것 하나 + 마감 · 펼치면 나머지 ── */

@Composable
private fun PolicyPlanCard(item: RoadmapItem, onOpen: () -> Unit) {
    var open by remember(item.policyId) { mutableStateOf(false) }
    InfoCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                item.kindTitle ?: item.name,
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold),
                color = OnRoadColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            item.amountText?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(item.name.takeIf { item.kindTitle != null }, deadline(item) ?: item.benefit?.takeIf { item.amountText == null }).joinToString("\n")
                .ifBlank { item.applyNote },
            style = OnRoadType.Caption,
            color = OnRoadColors.TextTertiary,
        )
        if (item.alternatives.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.clip(OnRoadShapes.Chip).tap(pressed = 1f, onClickLabel = if (open) "접기" else "같은 종류 보기", onClick = { open = !open }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("같은 종류 ${item.alternatives.size + 1}개", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
                Spacer(Modifier.width(2.dp))
                ExpandChevron(open)
            }
            ExpandArea(open) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.pickReason?.let { Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary) }
                    Spacer(Modifier.height(2.dp))
                    item.alternatives.forEach { a ->
                        Row {
                            Text(a.region, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, modifier = Modifier.width(44.dp))
                            Text(a.name, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(a.amountText ?: "금액 확인 필요", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                        }
                    }
                }
            }
        }
    }
}

/** 마감: 지원이 끝나는 날(퇴소 후 N년)과 나이 상한 중 빠른 것 */
private fun deadline(item: RoadmapItem): String? {
    val until = item.until
    val age = item.ageUntil
    return when {
        until != null && (age == null || !age.isBefore(until)) -> "${monthText(until)}까지"
        age != null && item.ageMax != null -> "만 ${item.ageMax}세까지, ${monthText(age)}까지"
        else -> null
    }
}
