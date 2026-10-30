package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.data.DDate
import com.project.on_road.ui.components.*
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private data class SimEntry(val route: String, val title: String, val description: String, val kind: String)

/** 미리 살아 보기(체험) · 미리 계산해 보기(계산기) 두 묶음 */
private val simEntries = listOf(
    SimEntry(Routes.SIM_MONTH, "한 달 살아보기", "매주 생기는 일에 대처하며 한 달 생활비를 버텨 봐요", "체험"),
    SimEntry(Routes.PRACTICE, "상황 대처 연습", "계약, 사기 문자 같은 순간을 미리 겪어 봐요", "체험"),
    SimEntry(Routes.SIM_BUDGET, "생활비 배분", "한 달 수입을 나눠 보고 부족한 곳을 찾아요", "계산"),
    SimEntry(Routes.SIM_HOUSING, "주거비 비교", "월세, 전세, 공공임대에 드는 돈을 비교해요", "계산"),
    SimEntry(Routes.SIM_SAVINGS, "저축 목표", "매달 모으면 언제 목표에 닿는지 계산해요", "계산"),
)

private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN)

/** 생활 시뮬레이션 허브: D-day 카드 → 미리 살아 보기 → 미리 계산해 보기 */
@Composable
fun SimulationHubScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val leaveDate = AppContainer.session.leaveDate
    val goal = DDate.isGoal(AppContainer.session.status)

    OnRoadScreen("생활 시뮬레이션", onBack) {
        ScreenHeadline("실제로 부딪히기 전에", "미리 연습해 봐요.")

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        DdayCard(leaveDate, goal) { onNavigate(Routes.SIM_TIMELINE) }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        GroupTitle("미리 살아 보기", "직접 골라 대처해요")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            simEntries.filter { it.kind == "체험" }.forEach { e -> SimRow(e) { onNavigate(e.route) } }
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        GroupTitle("미리 계산해 보기", "숫자를 넣으면 바로 계산해요")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            simEntries.filter { it.kind == "계산" }.forEach { e -> SimRow(e) { onNavigate(e.route) } }
        }
    }
}

/** D-day: 흰 카드. 왼쪽 D-day · 날짜, 오른쪽 '날짜별 할 일' 버튼 */
@Composable
private fun DdayCard(date: LocalDate?, goal: Boolean, onOpen: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (goal) "목표 D-day" else "퇴소 D-day",
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = OnRoadColors.TextSecondary,
                )
                if (date != null) {
                    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
                    Text(
                        when {
                            days > 0 -> "D-$days"
                            days == 0L -> "D-DAY"
                            else -> "D+${-days}"
                        },
                        style = OnRoadType.Display,
                        color = OnRoadColors.TextPrimary,
                    )
                    Text(date.format(dateFormat), style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (goal) "목표일을 정하면\n날짜별 할 일을 챙겨 드려요." else "퇴소일을 넣으면\n날짜별로 정리해 드려요.",
                        style = OnRoadType.Caption,
                        color = OnRoadColors.TextPrimary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            PrimaryPill(
                when {
                    date != null -> if (goal) "목표 일정" else "날짜별 할 일"
                    goal -> "목표일 정하기"
                    else -> "퇴소일 정하기"
                },
                onOpen,
            )
        }
    }
}

@Composable
private fun GroupTitle(title: String, sub: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(sub, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
    }
    Spacer(Modifier.height(12.dp))
}

/** 기능 한 줄: 연회색 카드, 아이콘 없이 제목 · 설명 */
@Composable
private fun SimRow(e: SimEntry, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        onClick = onClick,
    ) {
        Text(e.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(e.description, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
