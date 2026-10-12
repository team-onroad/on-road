package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.ui.components.*
import com.project.on_road.ui.navigation.Routes
import com.project.on_road.ui.theme.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private data class SimEntry(val route: String, val icon: ImageVector, val title: String, val description: String)

private val simEntries = listOf(
    SimEntry(Routes.SIM_BUDGET, Icons.Outlined.Calculate, "생활비 배분", "한 달 수입을 나눠 보고 부족한 곳을 찾아요"),
    SimEntry(Routes.SIM_HOUSING, Icons.Outlined.Apartment, "주거비 비교", "월세, 전세, 공공임대에 드는 돈을 비교해요"),
    SimEntry(Routes.SIM_SAVINGS, Icons.Outlined.Savings, "저축 목표", "매달 모으면 언제 목표에 닿는지 계산해요"),
    SimEntry(Routes.PRACTICE, Icons.Outlined.QuestionAnswer, "상황 대처 연습", "계약, 사기 문자 같은 순간을 미리 겪어 봐요"),
)

/** 생활 시뮬레이션 허브 */
@Composable
fun SimulationHubScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val leaveDate = AppContainer.session.leaveDate

    OnRoadScreen("생활 시뮬레이션", onBack) {
        ScreenHeadline("실제로 부딪히기 전에", "미리 연습해 봐요.")

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        // 퇴소 D-day: 모든 연습의 기준점이라 가장 크게
        OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary, onClick = { onNavigate(Routes.SIM_TIMELINE) }) {
            Row(verticalAlignment = Alignment.Top) {
                Text("퇴소 D-day 타임라인", style = OnRoadType.Title2, color = OnRoadColors.Primary, modifier = Modifier.weight(1f))
                if (leaveDate != null) {
                    val days = ChronoUnit.DAYS.between(LocalDate.now(), leaveDate)
                    OnRoadTag(
                        when {
                            days > 0 -> "D-$days"
                            days == 0L -> "D-DAY"
                            else -> "D+${-days}"
                        },
                        style = TagStyle.Danger,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "퇴소 날짜를 넣으면 언제 무엇을 해야 하는지 날짜별로 정리해 드려요.",
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
            )
            Spacer(Modifier.height(16.dp))
            OnRoadButton(
                text = if (leaveDate == null) "퇴소일 정하기" else "타임라인 보기",
                onClick = { onNavigate(Routes.SIM_TIMELINE) },
                modifier = Modifier.fillMaxWidth(),
                height = 40.dp,
                textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
            )
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("연습해 볼 것")
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            simEntries.forEach { e ->
                ListRow(e.title, e.description, icon = e.icon) { onNavigate(e.route) }
            }
        }

        Spacer(Modifier.height(32.dp))
        Footnote("계산 결과는 예시 기준이에요.\n실제 금액과 조건은 담당 기관에서 확인해 주세요.")
    }
}
