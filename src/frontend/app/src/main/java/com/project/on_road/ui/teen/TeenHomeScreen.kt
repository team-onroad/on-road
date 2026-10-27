package com.project.on_road.ui.teen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.AppContainer
import com.project.on_road.data.BasicMissions
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.UserStage
import com.project.on_road.ui.components.HomeFeature
import com.project.on_road.ui.components.HomeShortcutGrid
import com.project.on_road.ui.components.HomeShortcutSheet
import com.project.on_road.ui.components.LogoSlot
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.ProgressBar
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.SectionHeader
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.navigation.Routes
import com.project.on_road.ui.screens.RoadmapProgressCard
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType

/*
 * 청소년 홈 (만 11~17세): 진로 탐색이 중심.
 * 상단바 → 인사 → 진로 탐색(로드맵이 있으면 이번에 할 일) → 자립 기초 미션 → 내 바로가기(편집 가능) → 고민 상담
 */
@Composable
fun TeenHomeScreen(onNavigate: (String) -> Unit) {
    val session = AppContainer.session
    val name = session.nickname.ifBlank { "친구" }
    val interest = session.interestTypes
    val explored = interest.isNotEmpty()
    val version = ActivityStore.version
    val roadmap = remember(version) { ActivityStore.roadmap() }
    val basicsDone = remember(version) { ActivityStore.basicsDone() }
    val kidLiked = remember(version) { ActivityStore.likedCounts().take(2).map { it.first } }

    // 홈 바로가기: 저장된 게 없으면 기본값
    var shortcuts by remember {
        mutableStateOf(session.homeShortcuts?.let(HomeFeature::fromKeys) ?: HomeFeature.TEEN_DEFAULT)
    }
    var editing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(OnRoadColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        // 상단바
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoSlot(size = 36.dp)
            Spacer(Modifier.width(8.dp))
            Text(UserStage.TEEN.label, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onNavigate(Routes.SETTINGS) }) {
                Icon(Icons.Outlined.Settings, "설정", tint = OnRoadColors.TextTertiary)
            }
        }

        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
            // 인사
            Spacer(Modifier.height(20.dp))
            // 퇴소 예정일: 청소년은 날짜만 (D-day 는 자립준비청년 홈에서). 누르면 퇴소 D-day 타임라인
            session.leaveDate?.takeIf { session.status != LivingStatus.LEFT }?.let { d ->
                OnRoadTag(
                    "퇴소 예정일 ${d.year}.${"%02d".format(d.monthValue)}.${"%02d".format(d.dayOfMonth)}",
                    style = TagStyle.Neutral,
                    modifier = Modifier.clickable { onNavigate(Routes.SIM_TIMELINE) },
                )
                Spacer(Modifier.height(14.dp))
            }
            Text("${name}님,", style = OnRoadType.DisplayRegular, color = OnRoadColors.TextPrimary)
            Text(
                if (explored) "${interest.joinToString("·") { it.label }} 성향이에요" else "나에게 맞는 길, 찾아볼까요?",
                style = OnRoadType.Display,
                color = OnRoadColors.TextPrimary,
            )

            // 진로 탐색: 로드맵이 있으면 이번에 할 일, 없으면 탐색 시작 카드
            Spacer(Modifier.height(24.dp))
            if (roadmap != null) {
                RoadmapProgressCard(roadmap, compact = true, onMore = { onNavigate(Routes.INTEREST) })
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(OnRoadShapes.Card)
                        .background(OnRoadColors.Primary.copy(alpha = 0.05f))
                        .border(1.dp, OnRoadColors.Primary, OnRoadShapes.Card)
                        .padding(20.dp),
                ) {
                    OnRoadTag("진로 탐색", style = TagStyle.PrimaryFilled)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (explored) "추천 직업과\n로드맵을 확인해 봐요" else "몇 가지 대화로\n흥미 유형을 알아봐요",
                        style = OnRoadType.Title2,
                        color = OnRoadColors.Primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            explored -> "흥미 유형에 맞는 직업과 준비 순서를 보여 줘요"
                            kidLiked.isNotEmpty() -> "어릴 때 좋아한 ${kidLiked.joinToString("·")}도 함께 반영해요"
                            else -> "정답은 없어요. 편하게 골라 주세요"
                        },
                        style = OnRoadType.Caption,
                        color = OnRoadColors.TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    OnRoadButton(
                        text = if (explored) "직업 추천·로드맵 보기" else "흥미 탐색 시작하기",
                        onClick = { onNavigate(Routes.INTEREST) },
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                        textStyle = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                        trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                    )
                }
            }

            // 자립 기초 미션
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SectionHeader("자립 기초 미션", actionText = "전체보기", onAction = { onNavigate(Routes.BASICS) })
            Spacer(Modifier.height(14.dp))
            BasicsCard(basicsDone) { id -> onNavigate(if (id != null) Routes.basic(id) else Routes.BASICS) }

            // 내 바로가기 (편집 가능)
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SectionHeader("내 바로가기", actionText = "편집", onAction = { editing = true })
            Spacer(Modifier.height(14.dp))
            HomeShortcutGrid(
                items = shortcuts,
                onOpen = { onNavigate(it.route()) },
                onEdit = { editing = true },
            )

            // 고민 상담 (항상 보이게)
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            HelpRow { onNavigate(Routes.chat()) }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (editing) {
        HomeShortcutSheet(
            current = shortcuts,
            onDismiss = { editing = false },
            onSave = { picked ->
                shortcuts = picked
                session.homeShortcuts = picked.map { it.name }
                editing = false
            },
        )
    }
}

/** 고민이 있을 때: AI 상담으로 연결 + 1388 안내 */
@Composable
private fun HelpRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Inner)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SupportAgent, null, tint = OnRoadColors.Primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("고민이 있나요?", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Text("저희와 함께 청소년 상담 1388에 연락할 수 있어요", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary)
    }
}

/** 자립 기초 미션 진행: 완료 수 + 다음 미션 */
@Composable
private fun BasicsCard(done: Set<String>, onOpen: (String?) -> Unit) {
    val all = BasicMissions.all
    val next = all.firstOrNull { it.id !in done }
    OnRoadCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), onClick = { onOpen(next?.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (next == null) "모든 미션을 마쳤어요" else "${done.size + 1}번째 미션",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text("${done.size}/${all.size}", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(done.size / all.size.toFloat(), height = 6.dp)
        Spacer(Modifier.height(14.dp))
        if (next != null) {
            OnRoadTag(next.area.label, style = TagStyle.Slate)
            Spacer(Modifier.height(8.dp))
            Text(next.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text("읽고 퀴즈 한 문제 풀기", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
        } else {
            Text("기초를 모두 익혔어요!", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
    }
}