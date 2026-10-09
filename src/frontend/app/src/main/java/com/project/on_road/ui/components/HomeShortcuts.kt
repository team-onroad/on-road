package com.project.on_road.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.navigation.Routes
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType

/* ================================================================== */
/*  홈 바로가기: 사용자가 원하는 기능을 골라 홈에 추가·삭제                    */
/*  고른 목록은 SessionStore.homeShortcuts 에 key 순서대로 저장한다.         */
/* ================================================================== */

/** 홈에 추가할 수 있는 기능 목록. 새 기능이 생기면 여기에 한 줄 추가하면 된다. */
enum class HomeFeature(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: () -> String,
) {
    CHAT("AI 상담", "고민 나누기", Icons.Outlined.Forum, { Routes.chat() }),
    POLICY("지원 찾기", "장학금·지원 제도", Icons.Outlined.Search, { Routes.policy() }),
    FORMS("서류 도우미", "신청서 쓰기", Icons.Outlined.DocumentScanner, { Routes.FORMS }),
    INTEREST("흥미 탐색", "진로·직업 추천", Icons.Outlined.Explore, { Routes.INTEREST }),
    ROLEPLAY("면접 연습", "AI와 연습하기", Icons.Outlined.RecordVoiceOver, { Routes.ROLEPLAY }),
    DOCUMENT("활동 기록", "기록 초안 만들기", Icons.Outlined.Description, { Routes.DOCUMENT }),
    CHECKLIST("신청 준비", "체크리스트", Icons.Outlined.Checklist, { Routes.CHECKLISTS }),
    COMPARE("정책 비교", "조건 한눈에 보기", Icons.Outlined.Balance, { Routes.compare() }),
    NEXT("다음 할 일", "추천 할 일", Icons.Outlined.TaskAlt, { Routes.NEXT_ACTIONS }),
    SIMULATION("자립 연습", "시뮬레이션 모음", Icons.Outlined.Calculate, { Routes.SIMULATION }),
    BUDGET("생활비 배분", "한 달 예산 짜기", Icons.Outlined.AccountBalanceWallet, { Routes.SIM_BUDGET }),
    SAVINGS("저축 목표", "모으는 계획", Icons.Outlined.Savings, { Routes.SIM_SAVINGS }),
    HOUSING("주거비 비교", "월세·전세·공공임대", Icons.Outlined.Apartment, { Routes.SIM_HOUSING }),
    TIMELINE("자립 타임라인", "D-day 할 일", Icons.Outlined.Event, { Routes.SIM_TIMELINE }),
    PRACTICE("상황 연습", "이럴 땐 어떻게?", Icons.Outlined.Psychology, { Routes.PRACTICE }),
    GROWTH("성장 기록", "지금까지 한 일", Icons.Outlined.ShowChart, { Routes.GROWTH });

    companion object {
        /** 홈에 둘 수 있는 최대 개수 */
        const val MAX = 9

        /** 청소년 기본 바로가기 (처음 한 번) */
        val TEEN_DEFAULT = listOf(CHAT, POLICY, FORMS, ROLEPLAY, DOCUMENT, GROWTH)

        fun fromKeys(keys: List<String>): List<HomeFeature> =
            keys.mapNotNull { k -> entries.firstOrNull { it.name == k } }
    }
}

/** 바로가기 3칸 그리드 + 마지막 칸 '추가' */
@Composable
fun HomeShortcutGrid(
    items: List<HomeFeature>,
    onOpen: (HomeFeature) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    // null 칸 = '추가' 타일 (최대 개수가 아니면 마지막에 하나)
    val cells: List<HomeFeature?> = buildList {
        addAll(items)
        if (items.size < HomeFeature.MAX) add(null)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { f ->
                    val cellMod = Modifier.weight(1f).fillMaxHeight()
                    if (f == null) AddTile(cellMod, onEdit) else ShortcutTile(f, cellMod) { onOpen(f) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ShortcutTile(f: HomeFeature, modifier: Modifier, onClick: () -> Unit) {
    SubtleCard(modifier, onClick = onClick, contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)) {
        IconTile(f.icon, tint = OnRoadColors.Primary)
        Spacer(Modifier.height(14.dp))
        Text(f.title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(f.subtitle, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddTile(modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 104.dp)
            .clip(OnRoadShapes.Inner)
            .background(Color.White)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Inner)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, "기능 추가", tint = OnRoadColors.TextTertiary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text("추가", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextTertiary)
    }
}

/** 바로가기 편집 시트: 체크한 순서대로 홈에 놓인다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeShortcutSheet(
    current: List<HomeFeature>,
    onDismiss: () -> Unit,
    onSave: (List<HomeFeature>) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picked = remember { mutableStateListOf<HomeFeature>().apply { addAll(current) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("홈 바로가기 편집", style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "자주 쓰는 기능을 골라 주세요. 고른 순서대로 홈에 보여요. (${picked.size}/${HomeFeature.MAX})",
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HomeFeature.entries) { f ->
                    val on = f in picked
                    val order = picked.indexOf(f) + 1
                    val full = !on && picked.size >= HomeFeature.MAX
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(OnRoadShapes.Inner)
                            .background(Color.White)
                            .border(1.dp, if (on) OnRoadColors.Primary else OnRoadColors.BorderSlate, OnRoadShapes.Inner)
                            .clickable(enabled = !full) { if (on) picked.remove(f) else picked.add(f) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(f.icon, tint = if (full) OnRoadColors.TextQuaternary else OnRoadColors.Primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = if (full) OnRoadColors.TextQuaternary else OnRoadColors.TextPrimary)
                            Text(f.subtitle, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                        }
                        if (on) {
                            Box(
                                Modifier.size(22.dp).clip(OnRoadShapes.Chip).background(OnRoadColors.Primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$order", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = Color.White)
                            }
                        } else {
                            CheckBoxMark(false, size = 22.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OnRoadButton(
                    "기본으로",
                    onClick = { picked.clear(); picked.addAll(HomeFeature.TEEN_DEFAULT) },
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Outline,
                    height = 52.dp,
                )
                OnRoadButton(
                    "저장",
                    onClick = { onSave(picked.toList()) },
                    modifier = Modifier.weight(2f),
                    height = 52.dp,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
