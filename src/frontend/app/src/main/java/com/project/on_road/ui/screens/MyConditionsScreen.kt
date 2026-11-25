package com.project.on_road.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Districts
import com.project.on_road.data.FamilyMember
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.MyConditions
import com.project.on_road.data.REGIONS
import com.project.on_road.data.UserUpdate
import com.project.on_road.data.WorkStatus
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

/**
 * 나의 자립 조건: 한 화면에서 바로 누르고 고친다 (생년월일 · 사는 지역 · 지금 상황 · 퇴소일 · 지금 하는 일 · 가족 · 정책에서 확인한 조건).
 * 바꾼 게 있으면 아래 '저장하기'가 올라오고, 누르면 한 번에 저장해 홈 조건 · 추천, 정책 상세 '내 조건', 일정 로드맵에 반영한다.
 */
@Composable
fun MyConditionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = AppContainer.session
    var profile by remember { mutableStateOf(com.project.on_road.data.ProfileStore.current) }
    LaunchedEffect(Unit) { com.project.on_road.data.ProfileStore.refresh()?.let { profile = it } }

    // ── 고치는 중인 값 (저장 전) ──
    val p = profile
    var birth by remember(p) { mutableStateOf(p?.birthDate) }
    var region by remember(p) { mutableStateOf(p?.region) }
    var district by remember(p) { mutableStateOf(p?.district) }
    var status by remember(p) { mutableStateOf(p?.status) }
    var leave by remember(p) { mutableStateOf(p?.let { pp -> pp.dDate?.takeIf { pp.status != LivingStatus.LEFT } }) }
    var left by remember { mutableStateOf(MyConditions.leftDate) }
    var work by remember { mutableStateOf(session.workStatus) }
    var family by remember(p) { mutableStateOf(p?.family.orEmpty()) }
    val answersAtStart = remember { MyConditions.all() }
    var answers by remember { mutableStateOf(answersAtStart.toMap()) }

    var districtOpen by remember { mutableStateOf(false) }
    var pickingBirth by remember { mutableStateOf(false) }
    var pickingLeave by remember { mutableStateOf(false) }
    var addingFamily by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val changedProfile = p != null && (
        birth != p.birthDate || region != p.region || district != p.district || status != p.status ||
            (status != LivingStatus.LEFT && leave != p.dDate?.takeIf { p.status != LivingStatus.LEFT }) || family != p.family
        )
    val changedLocal = left != MyConditions.leftDate || work != session.workStatus || answers != answersAtStart.toMap()
    val dirty = changedProfile || changedLocal

    fun save() {
        val prof = p ?: return
        val userId = session.userId ?: return
        if (saving) return
        // 앱에만 두는 값: 퇴소한 날 · 지금 하는 일 · 확인한 조건
        if (left != MyConditions.leftDate) MyConditions.leftDate = left
        answersAtStart.forEach { (t, _) -> if (t !in answers) MyConditions.setAnswer(t, null) }
        answers.forEach { (t, v) -> if (MyConditions.answer(t) != v) MyConditions.setAnswer(t, v) }
        val workChanged = work != session.workStatus
        session.workStatus = work
        if (!changedProfile && !workChanged) { OnRoadToast.show("저장했어요"); return }
        val update = UserUpdate(
            birthDate = birth.takeIf { it != prof.birthDate },
            region = region.takeIf { it != prof.region || district != prof.district },
            district = district.takeIf { district != prof.district || region != prof.region },
            clearDistrict = district == null && prof.district != null,
            status = status.takeIf { it != prof.status },
            dDate = leave.takeIf { status != LivingStatus.LEFT && it != prof.dDate },
            family = family.takeIf { it != prof.family },
            work = work.takeIf { workChanged },
            clearWork = workChanged && work == null,
        )
        saving = true
        scope.launch {
            com.project.on_road.data.BackgroundWork.write { AppContainer.api.updateUser(userId, update) }.await()
                .onSuccess {
                    profile = it
                    AppContainer.applyProfile(it)
                    if (status == LivingStatus.LEFT && prof.status != LivingStatus.LEFT) com.project.on_road.notification.Notifications.applyAll(context)
                    com.project.on_road.data.PlanData.refreshRoadmap()
                    OnRoadToast.show("저장했어요")
                }
                .onFailure { OnRoadToast.show("저장하지 못했어요. 인터넷 연결을 확인해 주세요.") }
            saving = false
        }
    }

    val dot = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    // 바꾼 게 있을 때만 아래에서 올라온다
    val saveBar: (@Composable ColumnScope.() -> Unit)? = if (dirty) {
        { OnRoadButton(text = if (saving) "저장하는 중" else "저장하기", onClick = { save() }, modifier = Modifier.fillMaxWidth(), loading = saving) }
    } else null
    OnRoadScreen("나의 자립 조건", onBack, bottomBar = saveBar) {
        if (p == null) {
            SkeletonList(rows = 4)
            return@OnRoadScreen
        }

        // ── 생년월일 ──
        CondTitle("생년월일", top = false)
        CondField(birth?.format(dot).orEmpty(), birth?.let { "만 ${Period.between(it, LocalDate.now()).years}세" }) { pickingBirth = true }

        // ── 사는 지역: 시 · 도 칩 → 시 · 군 · 구 칩 (고르면 접혀 한 줄로) ──
        CondTitle("사는 지역")
        ChipGrid(REGIONS, columns = 4, selected = region) { r ->
            if (r != region) { region = r; district = null; districtOpen = true }
        }
        val districts = remember(region) { Districts.of(region) }
        val showGrid = districts.isNotEmpty() && (districtOpen || district == null)
        ExpandArea(districts.isNotEmpty() && !showGrid) {
            CondField(listOfNotNull(region, district).joinToString(" "), "바꾸기") { districtOpen = true }
        }
        ExpandArea(showGrid) {
            Spacer(Modifier.height(16.dp))
            Text("$region 시·군·구", style = OnRoadType.Body3.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextSlate)
            Spacer(Modifier.height(10.dp))
            ChipGrid(districts, columns = 3, selected = district) { d -> district = d; districtOpen = false }
        }

        // ── 지금 상황 + 퇴소일 ──
        CondTitle("지금 상황")
        Spacer(Modifier.height(CondSpace.TitleGap))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LivingStatus.values().forEach { s -> ChoiceChip(s.label, selected = s == status) { status = s } }
        }
        FadeSwitch(status == LivingStatus.LEFT, label = "leave-field") { isLeft ->
            if (isLeft) CondField("퇴소한 날", left?.format(dot) ?: "넣지 않았어요") { pickingLeave = true }
            else CondField("퇴소 예정일", leave?.format(dot) ?: "정하지 않았어요") { pickingLeave = true }
        }

        // ── 지금 하는 일 (다시 누르면 풀림) ──
        CondTitle("지금 하는 일")
        Spacer(Modifier.height(CondSpace.TitleGap))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkStatus.values().forEach { w -> ChoiceChip(w.label, selected = w == work) { work = if (work == w) null else w } }
        }

        // ── 가족 ──
        CondTitle("가족")
        Text("자녀나 동생이 있으면 넣어 주세요", style = OnRoadType.Body3, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Column(Modifier.animateContentSize(OnRoadMotion.move()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            family.forEachIndexed { i, m ->
                key(m.relation, m.birthDate, m.gender) {
                    CondCard(Modifier.reveal()) {
                        Column(Modifier.weight(1f)) {
                            Text(m.relationLabel(), style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                            Text(m.summary(), style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(
                            "삭제",
                            style = OnRoadType.Caption,
                            color = OnRoadColors.TextTertiary,
                            modifier = Modifier.tap(onClickLabel = "${m.relationLabel()} 삭제") { family = family.filterIndexed { k, _ -> k != i } }.padding(8.dp),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(OnRoadShapes.Item)
                    .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Item)
                    .tap(onClickLabel = "가족 추가") { addingFamily = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("가족 추가", style = OnRoadType.Body3.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextSecondary)
            }
        }

        // ── 정책에서 확인한 조건 (해당 / 해당 없음, 다시 누르면 지움) ──
        if (answersAtStart.isNotEmpty()) {
            CondTitle("정책에서 확인한 조건")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                answersAtStart.forEach { (text, _) ->
                    val cur = answers[text]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(OnRoadShapes.Item)
                            .background(OnRoadColors.SurfaceSubtle)
                            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Item)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(text, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("해당" to true, "해당 없음" to false).forEach { (label, v) ->
                                ChoiceChip(label, selected = cur == v) {
                                    answers = if (cur == v) answers - text else answers + (text to v)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickingBirth) {
        ProfileDateDialog(birth, past = true, onDismiss = { pickingBirth = false }) { d -> pickingBirth = false; birth = d }
    }
    if (pickingLeave) {
        if (status == LivingStatus.LEFT) {
            ProfileDateDialog(left, past = true, onDismiss = { pickingLeave = false }) { d -> pickingLeave = false; left = d }
        } else {
            ProfileDateDialog(leave, past = false, onDismiss = { pickingLeave = false }) { d -> pickingLeave = false; leave = d }
        }
    }
    if (addingFamily) {
        FamilyMemberDialog(onDismiss = { addingFamily = false }) { relation, gender, b ->
            addingFamily = false
            family = family + FamilyMember(relation, gender, b)
        }
    }
}

/* ---------------------------- 조각 ---------------------------- */

/** 간격: 섹션 사이 · 제목 → 내용 (모든 섹션 같게) */
private object CondSpace {
    val Section = 36.dp
    val TitleGap = 12.dp
}

@Composable
private fun CondTitle(text: String, top: Boolean = true) {
    if (top) Spacer(Modifier.height(CondSpace.Section))
    Text(text, style = OnRoadType.Headline, color = OnRoadColors.TextPrimary)
}

/** 값 한 칸 (날짜 · 고른 지역): 연회색 + 테두리, 오른쪽에 회색 보조 글 */
@Composable
private fun CondField(value: String, trailing: String?, onClick: () -> Unit) {
    CondCard(Modifier.padding(top = CondSpace.TitleGap).tap(onClickLabel = value, onClick = onClick)) {
        Text(value, style = OnRoadType.Body1, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = OnRoadType.Body3, color = OnRoadColors.TextTertiary) }
    }
}

@Composable
private fun CondCard(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Item)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 칩을 칸 수(columns)에 맞춰 같은 폭으로 줄 세운다 */
@Composable
private fun ChipGrid(items: List<String>, columns: Int, selected: String?, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = CondSpace.TitleGap), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t -> ChoiceChip(t, selected = t == selected, modifier = Modifier.weight(1f)) { onPick(t) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
