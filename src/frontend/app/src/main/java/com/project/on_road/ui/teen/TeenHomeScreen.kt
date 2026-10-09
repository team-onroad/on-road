package com.project.on_road.ui.teen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import com.project.on_road.data.AppContainer
import com.project.on_road.data.UserStage
import com.project.on_road.ui.components.HomeFeature
import com.project.on_road.ui.components.HomeShortcutGrid
import com.project.on_road.ui.components.HomeShortcutSheet
import com.project.on_road.ui.components.LogoSlot
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.SectionHeader
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.navigation.Routes
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType

/*
 * 청소년 홈 (만 11~17세): 진로 탐색이 중심.
 * 상단바 → 인사 → 진로 탐색 카드 → 내 바로가기(편집 가능) → 고민 상담
 */
@Composable
fun TeenHomeScreen(onNavigate: (String) -> Unit) {
    val session = AppContainer.session
    val name = session.nickname.ifBlank { "친구" }
    val interest = session.interestTypes
    val explored = interest.isNotEmpty()

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
            Text("${name}님,", style = OnRoadType.DisplayRegular, color = OnRoadColors.TextPrimary)
            Text(
                if (explored) "${interest.joinToString("·") { it.label }} 성향이에요" else "나에게 맞는 길, 찾아볼까요?",
                style = OnRoadType.Display,
                color = OnRoadColors.TextPrimary,
            )

            // 진로 탐색 (메인 카드)
            Spacer(Modifier.height(24.dp))
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
                    if (explored) "흥미 유형에 맞는 직업과 준비 순서를 보여 줘요" else "정답은 없어요. 편하게 골라 주세요",
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