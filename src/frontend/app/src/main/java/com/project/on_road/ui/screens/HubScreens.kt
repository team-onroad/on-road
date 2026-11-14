package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.components.OnRoadCard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import com.project.on_road.data.AppContainer
import com.project.on_road.data.DDate
import com.project.on_road.ui.components.ListRow
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.SubHeader
import com.project.on_road.ui.components.SectionHeader
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.project.on_road.ui.components.stagger
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/* ================================================================== */
/*  서류 모아 보기: 흩어져 있던 서류 기능 다섯 개를 한 곳에서 고른다          */
/*  (정책 신청서는 신청 관리 안의 체크리스트에서 이어서 쓴다)                */
/* ================================================================== */

@Composable
fun DocsHubScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    OnRoadScreen("서류", onBack) { DocsHubContent(onNavigate) }
}

/** 서류 고르기 목록 (서류 화면 · 신청 관리의 '서류' 탭이 같이 쓴다). 설정 화면과 같은 줄 모양 */
@Composable
fun ColumnScope.DocsHubContent(onNavigate: (String) -> Unit) {
    // 위계: 묶음 제목(신청 · 일정 칸과 같은 SubHeader) → 연회색 줄
    SubHeader("신청서 쓰기")
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow("가지고 있는 양식으로 쓰기", "양식 사진·파일을 올리면 칸을 나눠 줘요") { onNavigate(Routes.FORMS) }
        SettingRow("활동 기록 초안", "한 일을 말하면 제출용 기록으로 정리해요") { onNavigate(Routes.DOCUMENT) }
    }
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    SubHeader("서류 확인하기")
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow("계약서·등기부 위험 찾기", "놓치기 쉬운 조항을 미리 짚어 줘요") { onNavigate(Routes.RISK) }
    }
}

/* ================================================================== */
/*  마이: 설정 화면과 같은 모양 (글만 있는 이름 · 묶음 제목 · 연회색 줄)      */
/*  알림 · 계정 · 설정은 홈 위쪽 아이콘에서 연다                            */
/* ================================================================== */

/** 마이 탭 간격 (신청 관리 · 생활 가이드 머리와 같은 값) */
private object MySpace {
    val TitleTop = 24.dp
    val TitleBottom = 28.dp
}

/**
 * 마이: 큰 제목 '마이' → 내 정보 카드(누르면 계정 정보) → 자립 준비도 그래프 → 성장 기록.
 * 자립 준비도 · 성장 기록을 따로 들어가지 않고 한 화면에서 내려 본다
 */
@Composable
fun MyScreen(onNavigate: (String) -> Unit) {
    val session = AppContainer.session
    val date = session.leaveDate
    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        Spacer(Modifier.height(MySpace.TitleTop))
        Text(
            "내 정보",
            style = OnRoadType.Display,
            color = OnRoadColors.TextPrimary,
            modifier = Modifier.padding(horizontal = OnRoadDimens.ScreenPadding).semantics { heading() },
        )
        Spacer(Modifier.height(MySpace.TitleBottom))
        GrowthContent(
            Modifier.weight(1f),
            header = {
                // 내 정보 카드: 흰 카드 · 칩 테두리 (목록 화면 추천 카드와 같은 모양), 누르면 계정 정보
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    borderColor = OnRoadColors.BorderChip,
                    shape = OnRoadShapes.Item,
                    contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
                    onClick = { onNavigate(Routes.SETTINGS) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.nickname.ifBlank { "사용자" }, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                            if (date != null) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${DDate.untilText(session.status, session.goalTitle)} ${dDayLabel(ChronoUnit.DAYS.between(LocalDate.now(), date))}",
                                    style = OnRoadType.Caption2,
                                    color = OnRoadColors.TextTertiary,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("계정 정보", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            },
            onOpenPolicy = { onNavigate(Routes.policy(it)) },
            onOpenChecklist = { onNavigate(Routes.checklist(it)) },
            onOpenSimulation = { onNavigate(Routes.simResult(it)) },
            onOpenRoleplay = { onNavigate(Routes.roleplay(it)) },
            onOpenChat = { onNavigate(Routes.chat(chatId = it)) },
            onNavigate = onNavigate,
        )
    }
}

private fun dDayLabel(days: Long): String = when {
    days > 0 -> "D-$days"
    days == 0L -> "D-DAY"
    else -> "D+${-days}"
}

