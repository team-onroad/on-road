package com.project.on_road.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadMotion
import com.project.on_road.ui.theme.OnRoadType

/** 하단 메뉴. route 는 OnRoadNavHost 의 Routes 값 (탭 첫 화면). 글자 없이 아이콘만, 고른 칸은 채운 아이콘 */
data class BottomTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val route: String)

/**
 * 앱 공통 탭 아이콘. 하단 메뉴와 상단 바의 '홈으로' 버튼이 같은 홈 아이콘을 쓴다
 * (여기만 바꾸면 둘 다 바뀐다). 상단 바 홈 버튼은 채운 홈(homeSelected).
 */
object NavIcons {
    val home: ImageVector get() = Icons.Outlined.Home
    val homeSelected: ImageVector get() = Icons.Filled.Home
}

fun bottomTabs(home: String, find: String, apply: String, guide: String, my: String) = listOf(
    BottomTab("홈", NavIcons.home, NavIcons.homeSelected, home),
    BottomTab("지원 찾기", Icons.Outlined.Search, Icons.Filled.Search, find),
    // 신청 관리: 체크 표시가 있는 클립보드
    BottomTab("신청 관리", Icons.Outlined.AssignmentTurnedIn, Icons.Filled.AssignmentTurnedIn, apply),
    BottomTab("생활 가이드", Icons.Outlined.Lightbulb, Icons.Filled.Lightbulb, guide),
    BottomTab("마이", Icons.Outlined.Person, Icons.Filled.Person, my),
)

private val BarShape = RoundedCornerShape(28.dp)

/**
 * 하단 메뉴 바로 위에 뜨는 둥근 버튼 (검색 탭의 '상담하러 가기').
 * 화면이 [route] 를 넣으면 OnRoadNavHost 가 메뉴와 그라데이션 '위'에 그린다 (흐려지지 않게, 메뉴 오른쪽 끝에 맞춰)
 */
object FloatingAction {
    var route by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** 버튼 크기 · 메뉴와의 틈 */
    val Size = 56.dp
    val Gap = 12.dp
}

/** 하단 메뉴 여백 · 그라데이션 (숫자만 바꿔서 조절) */
object BarSpace {
    /** 양옆 · 아래 여백 */
    val Margin = 16.dp
    /** 메뉴 위 여백 (이 위부터 바로 그라데이션) */
    val Top = 4.dp
    /** 그라데이션 높이 */
    val Fade = 24.dp
    /** 메뉴가 없는 화면(설정 등) 맨 아래 그라데이션 높이 */
    val EdgeFade = 20.dp
    /** 아래에 버튼이 붙은 화면(정책 상세 등): 버튼 영역 바로 위 그라데이션 높이 */
    val ActionFade = 16.dp
}

/**
 * 하단 메뉴가 지금 떠 있는지 (OnRoadNavHost 가 정한다).
 * 떠 있으면 화면 쪽 아래 그라데이션은 끈다 — 메뉴 그라데이션과 겹쳐 두 겹으로 보이지 않게.
 */
object BarShown {
    var value by androidx.compose.runtime.mutableStateOf(false)
}

/** 내용이 흰색으로 사라지는 그라데이션. 끝으로 갈수록 천천히 하얘져서 이음새가 보이지 않는다 (메뉴 · 아래 버튼 공통) */
fun softFadeBrush(color: Color = Color.White) = androidx.compose.ui.graphics.Brush.verticalGradient(
    // 한 번에 고르게 이어지는 S자 곡선 (예전 곡선은 위쪽이 갑자기 하얘져 두 겹처럼 보였다)
    0f to color.copy(alpha = 0f),
    0.2f to color.copy(alpha = 0.1f),
    0.4f to color.copy(alpha = 0.32f),
    0.6f to color.copy(alpha = 0.62f),
    0.8f to color.copy(alpha = 0.88f),
    1f to color,
)

/**
 * 메뉴 '위'에 따로 그리는 바닥 바 (검색 탭 'n개 담았어요').
 * 화면 안에 두면 메뉴 그라데이션에 덮이므로, 화면은 값만 넣고 OnRoadNavHost 가 그라데이션 위에 그린다.
 * count = 0 이면 숨김. 화면을 떠날 때 0 으로 돌려놓는다
 */
object CompareTray {
    var count by androidx.compose.runtime.mutableIntStateOf(0)
    var onCompare: () -> Unit = {}
}

/**
 * 떠 있는 둥근 하단 메뉴: 흰 바탕 · 얇은 테두리 · 큰 둥근 모서리, 화면 양옆과 아래에 여백을 둔다.
 * 글자는 없고 아이콘만: 고른 칸은 채운 아이콘 + 메인 색, 나머지는 선 아이콘 + 회색.
 * 글자 대신 화면 읽기 프로그램에는 탭 이름을 읽어 준다.
 */
@Composable
fun OnRoadBottomBar(
    tabs: List<BottomTab>,
    selectedRoute: String?,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
    /** 화면 바깥에서 이미 띄운 아래 여백 (그만큼 빼서 아래 여백이 양옆과 같아 보이게) */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Box(modifier.fillMaxWidth()) {
        // 메뉴 위 그라데이션: 위 여백(Top) 바로 위부터 내용이 빠르게 흰색으로 사라진다
        Box(
            Modifier
                .fillMaxWidth()
                .height(BarSpace.Fade)
                .offset(y = -BarSpace.Fade)
                // 끝으로 갈수록 천천히 하얘지는 곡선: 위 여백(Top)의 흰 띠와 이음새 없이 이어진다
                .background(softFadeBrush()),
        )
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(
                start = BarSpace.Margin,
                end = BarSpace.Margin,
                top = BarSpace.Top,
                bottom = (BarSpace.Margin - bottomInset).coerceAtLeast(0.dp),
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(BarShape)
                .background(Color.White)
                .border(1.dp, OnRoadColors.Border, BarShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = tab.route == selectedRoute
                val tint by animateColorAsState(
                    if (selected) OnRoadColors.Primary else OnRoadColors.TextTertiary,
                    OnRoadMotion.move(OnRoadMotion.FAST),
                    label = "tab-tint",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            this.selected = selected
                            role = Role.Tab
                            contentDescription = tab.label
                        }
                        .tap(pressed = 0.9f, haptic = !selected, ripple = false) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) tab.selectedIcon else tab.icon,
                        null,
                        tint = tint,
                        modifier = Modifier.size(26.dp).popOn(selected, enabled = selected),
                    )
                }
            }
        }
    }
    }
}
