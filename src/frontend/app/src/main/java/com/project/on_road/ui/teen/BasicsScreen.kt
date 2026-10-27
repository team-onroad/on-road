package com.project.on_road.ui.teen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.BasicArea
import com.project.on_road.data.BasicLink
import com.project.on_road.data.BasicMission
import com.project.on_road.data.BasicMissions
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*

/*
 * 청소년 자립 기초 미션
 * 목록: 분야별(돈 관리 · 행정·서류 · 일·안전) 미션과 완료 표시
 * 미션: 왜 알아야 할까 → 알아 둘 것 → 확인 퀴즈(맞히면 완료) → 앱 기능으로 이어서 해 보기
 */

private fun BasicArea.icon(): ImageVector = when (this) {
    BasicArea.MONEY -> Icons.Outlined.AccountBalanceWallet
    BasicArea.PAPERS -> Icons.Outlined.Description
    BasicArea.WORK -> Icons.Outlined.WorkOutline
}

@Composable
fun BasicsListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val done = remember(ActivityStore.version) { ActivityStore.basicsDone() }
    val all = BasicMissions.all

    OnRoadScreen("자립 기초 미션", onBack) {
        ScreenHeadline("혼자 설 날을 위해", "기초부터 하나씩 익혀요.")
        Spacer(Modifier.height(6.dp))
        Text("읽고 퀴즈 한 문제를 맞히면 완료돼요. 순서는 자유예요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(20.dp))

        SubtleCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("완료한 미션", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
                Text("${done.size}/${all.size}", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(done.size / all.size.toFloat(), height = 6.dp)
        }

        BasicArea.entries.forEach { area ->
            val list = all.filter { it.area == area }
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SubHeader(area.label, trailingText = "${list.count { it.id in done }}/${list.size}")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { m ->
                    val ok = m.id in done
                    ListRow(
                        m.title,
                        if (ok) "완료했어요" else "읽고 퀴즈 풀기",
                        icon = if (ok) Icons.Outlined.TaskAlt else area.icon(),
                        trailing = { if (ok) OnRoadTag("완료", style = TagStyle.PrimaryFilled) },
                        onClick = { onOpen(m.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun BasicMissionScreen(missionId: String, onBack: () -> Unit, onLink: (BasicLink) -> Unit) {
    val mission = remember(missionId) { BasicMissions.of(missionId) }
    if (mission == null) {
        OnRoadScreen("자립 기초 미션", onBack) {
            EmptyState(Icons.Outlined.TaskAlt, "미션을 찾지 못했어요", "목록에서 다시 골라 주세요.")
        }
        return
    }
    BasicMissionContent(mission, onBack, onLink)
}

@Composable
private fun BasicMissionContent(m: BasicMission, onBack: () -> Unit, onLink: (BasicLink) -> Unit) {
    val alreadyDone = remember(m.id) { m.id in ActivityStore.basicsDone() }
    var picked by remember(m.id) { mutableStateOf<Int?>(null) }
    val correct = picked == m.quiz.answer
    val done = alreadyDone || correct

    SpeakOnce("${m.title}. ${m.why}", key = m.id)

    OnRoadScreen(m.area.label, onBack) {
        OnRoadTag(m.area.label, style = TagStyle.PrimarySoft)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.title, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            SpeakButton("${m.title}. ${m.why}")
        }
        Spacer(Modifier.height(16.dp))
        NoticeBox(m.why, title = "왜 알아야 할까요?")

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubHeader("알아 둘 것")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            m.points.forEachIndexed { i, p -> NumberedPoint(i + 1, p) }
        }

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubHeader("확인 퀴즈")
        Spacer(Modifier.height(12.dp))
        OnRoadCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Text(m.quiz.question, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                m.quiz.options.forEachIndexed { i, o ->
                    SelectCard(
                        title = o,
                        description = null,
                        selected = picked == i,
                        onClick = {
                            if (!correct) {
                                picked = i
                                if (i == m.quiz.answer) ActivityStore.completeBasic(m.id)
                            }
                        },
                    )
                }
            }
            picked?.let { p ->
                Spacer(Modifier.height(14.dp))
                if (p == m.quiz.answer) {
                    NoticeBox(m.quiz.explain, title = "정답이에요! 미션 완료")
                } else {
                    NoticeBox("다시 한 번 골라 볼까요? 위의 '알아 둘 것'에 힌트가 있어요.", title = "아쉬워요", tone = NoticeTone.Warning)
                }
            }
            if (alreadyDone && picked == null) {
                Spacer(Modifier.height(12.dp))
                Text("이미 완료한 미션이에요. 다시 풀어 봐도 좋아요.", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
            }
        }

        m.note?.let {
            Spacer(Modifier.height(16.dp))
            Footnote(it)
        }

        if (done) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            m.link?.let { link ->
                OnRoadButton(
                    link.label,
                    { onLink(link) },
                    Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline,
                    trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                )
                Spacer(Modifier.height(8.dp))
            }
            val next = BasicMissions.all.firstOrNull { it.id != m.id && it.id !in ActivityStore.basicsDone() }
            OnRoadButton(
                if (next != null) "목록으로" else "모든 미션 완료! 목록으로",
                onBack,
                Modifier.fillMaxWidth(),
            )
        }
    }
}
