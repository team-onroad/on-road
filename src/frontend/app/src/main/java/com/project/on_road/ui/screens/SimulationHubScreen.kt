package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.on_road.ui.components.*
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.theme.*

/** 생활 가이드 화면 간격 · 크기 (숫자만 바꿔서 조절) */
private object GuideSpace {
    /** 화면 맨 위 → 제목 (신청 관리와 같은 값) */
    val TitleTop = 24.dp
    /** 제목 → 첫 카드 */
    val TitleBottom = 28.dp
    /** 묶음 사이 */
    val GroupGap = 36.dp
    /** 묶음 제목 → 카드 */
    val HeaderGap = 12.dp
    /** 한 달 살아보기 큰 카드 높이 */
    val HeroHeight = 96.dp
    /** 연습 카드: 제목 → 설명 사이 */
    val TileSubTop = 8.dp
    /** 연습 카드: 설명 행간 (12sp 글씨 기준) */
    val TileSubLineHeight = 16.sp
    /** 연습 카드(두 칸) 높이 */
    val TileHeight = 88.dp
}


/**
 * 생활 가이드: 신청 관리와 같은 머리(큰 제목) 아래 위계를 나눠 둔다.
 * 1. 한 달 살아보기: 대표 기능이라 메인 색 큰 카드 하나
 * 2. 미리 연습하기: 두 칸 카드 (상황 대처 · 실전 연습)
 * 3. 미리 계산해 보기: 연회색 한 줄 카드 (이름 왼쪽 · 무엇을 계산하는지 오른쪽)
 * 4. 진로: 흰 카드 하나 + 오른쪽 '시작 ›'
 */
@Composable
fun SimulationHubScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(OnRoadColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(GuideSpace.TitleTop))
        Text(
            "생활 가이드",
            style = OnRoadType.Display,
            color = OnRoadColors.TextPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(GuideSpace.TitleBottom))

        // 1. 대표 기능
        Box(Modifier.stagger(0)) {
            PrimaryHeroCard(
                title = "한 달 살아보기",
                sub = "매주 생기는 일에 대처하며 한 달 버티기",
                icon = Icons.Outlined.CalendarMonth,
                onClick = { onNavigate(Routes.SIM_MONTH) },
                height = GuideSpace.HeroHeight,
            )
        }

        // 2. 연습
        Spacer(Modifier.height(GuideSpace.GroupGap))
        GroupTitle("미리 연습하기")
        Spacer(Modifier.height(GuideSpace.HeaderGap))
        Row(Modifier.stagger(1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeTile("상황 대처 연습", "계약, 사기 문자 같은 순간을 미리 겪어 봐요", Modifier.weight(1f)) { onNavigate(Routes.PRACTICE) }
            PracticeTile("실전 연습", "은행, 주민센터, 면접을 AI와 말로 연습해요", Modifier.weight(1f)) { onNavigate(Routes.ROLEPLAY) }
        }

        // 3. 계산기
        Spacer(Modifier.height(GuideSpace.GroupGap))
        GroupTitle("미리 계산해 보기")
        Spacer(Modifier.height(GuideSpace.HeaderGap))
        Column(Modifier.stagger(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CalcRow("생활비 배분", "수입 나누기") { onNavigate(Routes.SIM_BUDGET) }
            CalcRow("주거비 비교", "월세, 전세, 공공임대") { onNavigate(Routes.SIM_HOUSING) }
            CalcRow("저축 목표", "언제 다 모일까") { onNavigate(Routes.SIM_SAVINGS) }
        }

        // 4. 진로
        Spacer(Modifier.height(GuideSpace.GroupGap))
        GroupTitle("진로")
        Spacer(Modifier.height(GuideSpace.HeaderGap))
        Box(Modifier.stagger(3)) {
            CareerCard { onNavigate(Routes.INTEREST) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/* 위계: 화면 제목(Display 24) → 대표 카드 제목(Title3 18) → 묶음 제목(GroupTitle, Headline 17)
 * → 카드 이름(Body2 15 굵게) → 설명(Caption2 12 연한 글씨). 큰 카드 · 묶음 제목은 components/HeroCard.kt 공통 */

/** 두 칸 연습 카드: 연회색 · 회색 테두리, 이름 → 설명(두 줄까지) */
@Composable
private fun PracticeTile(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    OnRoadCard(
        modifier.height(GuideSpace.TileHeight),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(GuideSpace.TileSubTop))
        Text(
            sub,
            style = OnRoadType.Caption2.copy(lineHeight = GuideSpace.TileSubLineHeight),
            color = OnRoadColors.TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 계산기 한 줄: 연회색 카드, 이름 왼쪽 · 무엇을 계산하는지 오른쪽 (검색 화면 정책 카드와 같은 배치) */
@Composable
private fun CalcRow(title: String, what: String, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 15.dp, bottom = 15.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            // 무엇을 계산하는지는 이름보다 한 단계 옅게, 끝에 › 로 누를 수 있음을 보인다
            Text(
                what,
                style = OnRoadType.Caption2,
                color = OnRoadColors.TextQuaternary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
        }
    }
}

/** 진로 카드: 흰 카드 · 칩 테두리, 오른쪽 가운데 '시작 ›' */
@Composable
private fun CareerCard(onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("진로 탐색", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text("흥미를 찾고 맞는 직업을 추천받아요", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시작", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
