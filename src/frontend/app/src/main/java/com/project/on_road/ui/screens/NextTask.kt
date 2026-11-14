package com.project.on_road.ui.screens

import com.project.on_road.ui.components.tap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.data.DDate
import com.project.on_road.data.PlanData
import com.project.on_road.data.PlanGo
import com.project.on_road.data.PlanTask
import com.project.on_road.ui.components.CheckBoxMark
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.PrimaryPill
import com.project.on_road.ui.screens.navigation.Routes
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 할 일에서 바로 갈 화면 (타임라인 · 홈 · 결과 화면이 같이 쓴다) */
fun planRoute(go: PlanGo): String = when (go) {
    is PlanGo.Practice -> Routes.roleplay(go.scenarioId)
    PlanGo.RiskCheck -> Routes.RISK
    is PlanGo.Checklist -> Routes.checklist(go.policyId)
    is PlanGo.PolicySearch -> Routes.policy(query = go.query)
    PlanGo.Budget -> Routes.SIM_BUDGET
    PlanGo.Housing -> Routes.SIM_HOUSING
    PlanGo.Savings -> Routes.SIM_SAVINGS
    PlanGo.MonthLife -> Routes.SIM_MONTH
    PlanGo.Forms -> Routes.DOCS
    is PlanGo.Chat -> Routes.chat(question = go.question)
}

/** 할 일을 누르면 갈 곳: 첫 번째 '바로 하기', 없으면 타임라인 */
fun PlanTask.route(): String = actions.firstOrNull()?.go?.let(::planRoute) ?: Routes.SIM_TIMELINE

/** 할 일 날짜를 오늘 기준으로: 놓친 일 · 오늘 · D-n (정책 할 일은 '진행 중인 신청' 같은 이름) */
fun PlanTask.whenText(today: LocalDate): String {
    label?.let { return it }
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> "놓친 일"
        days == 0L -> "오늘"
        else -> "D-$days"
    }
}

/**
 * 결과 화면 끝에 붙는 '다음 할 일'.
 * [finished] 를 주면 그 기능으로 하는 할 일 하나를 자동으로 끝내고 "끝냈어요"를 보여 준다.
 * 이어서 남은 할 일 중 가장 먼저 할 일을 바로 시작할 수 있게 한다 → 기능이 따로 놀지 않고 다음으로 이어진다.
 * 퇴소일이 없거나 퇴소 후 목표 화면을 쓰면 보이지 않는다.
 */
@Composable
fun NextTaskBlock(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    finished: PlanGo? = null,
    /** 체크리스트를 모두 끝낸 정책 (id, 이름) */
    finishedPolicy: Pair<String, String>? = null,
) {
    val session = AppContainer.session
    if (session.leaveDate == null || DDate.isGoal(session.status)) return
    // 화면에 다시 돌아와도 한 번만 끝내도록 저장해 둔다
    var ran by rememberSaveable { mutableStateOf(false) }
    var doneTitle by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (ran) return@LaunchedEffect
        ran = true
        finishedPolicy?.let { (id, name) -> PlanData.finishPolicy(id, name) }
        doneTitle = finished?.let { PlanData.completeBy(it) }?.title
    }
    val today = remember { LocalDate.now() }
    val next = PlanData.next(today)
    if (doneTitle == null && next == null) return

    OnRoadCard(
        modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        doneTitle?.let { title ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckBoxMark(true, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "‘$title’ 할 일을 끝냈어요",
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (next != null) Spacer(Modifier.height(14.dp))
        }
        if (next != null) {
            Text("다음 할 일 · ${next.whenText(today)}", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(2.dp))
            Text(
                next.title,
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryPill(next.actions.firstOrNull()?.label ?: "할 일 보기", { onNavigate(next.route()) })
                Spacer(Modifier.weight(1f))
                Text(
                    "전체 보기",
                    style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.tap { onNavigate(Routes.SIM_TIMELINE) }.padding(vertical = 4.dp, horizontal = 4.dp),
                )
            }
        }
    }
}
