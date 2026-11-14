package com.project.on_road.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Checklist
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
import androidx.compose.material.icons.outlined.Checklist
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

fun bottomTabs(home: String, find: String, apply: String, guide: String, my: String) = listOf(
    BottomTab("홈", Icons.Outlined.Home, Icons.Filled.Home, home),
    BottomTab("지원 찾기", Icons.Outlined.Search, Icons.Filled.Search, find),
    BottomTab("신청 관리", Icons.Outlined.Checklist, Icons.Filled.Checklist, apply),
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

/** 하단 메뉴 여백 (숫자만 바꿔서 조절) */
object BarSpace {
    /** 위 · 양옆 · 아래 여백 (모두 같은 값) */
    val Margin = 16.dp
    /** 메뉴 위로 흐려지는 띠 높이 */
    val Fade = 24.dp
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
        // 위쪽 그라데이션: 메뉴 바로 위 내용이 흰색으로 서서히 사라진다 (메뉴 위로 살짝 겹쳐 그린다)
        Box(
            Modifier
                .fillMaxWidth()
                .height(BarSpace.Fade)
                .offset(y = -BarSpace.Fade)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White))),
        )
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(
                start = BarSpace.Margin,
                end = BarSpace.Margin,
                top = BarSpace.Margin,
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
                        .tap(pressed = 0.9f, haptic = !selected) { onSelect(tab) },
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
