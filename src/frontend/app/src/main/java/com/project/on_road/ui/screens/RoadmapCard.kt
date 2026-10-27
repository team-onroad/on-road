package com.project.on_road.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.SavedRoadmap
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import java.time.Instant
import java.time.ZoneId

/** 밀리초 → "10월 15일" */
internal fun dayText(millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${d.monthValue}월 ${d.dayOfMonth}일"
}

/**
 * 저장한 진로 로드맵 + 단계 체크 (청소년 홈 · 성장 기록 공용).
 * compact = true 면 다음 단계 하나만 보여 준다.
 */
@Composable
fun RoadmapProgressCard(
    roadmap: SavedRoadmap,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onMore: (() -> Unit)? = null,
) {
    OnRoadCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag("로드맵", style = TagStyle.PrimarySoft)
            Spacer(Modifier.width(8.dp))
            Text(
                "${roadmap.jobName}까지",
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${roadmap.done.size}/${roadmap.steps.size}", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(roadmap.progress, height = 6.dp)
        Spacer(Modifier.height(14.dp))

        val indices = if (compact) listOfNotNull(roadmap.nextIndex) else roadmap.steps.indices.toList()
        if (indices.isEmpty()) {
            Text("모든 단계를 해냈어요. 새 목표를 찾아볼까요?", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            indices.forEach { i ->
                val s = roadmap.steps[i]
                val checked = i in roadmap.done
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { ActivityStore.toggleRoadmapStep(i) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    CheckBoxMark(checked, Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (compact) "이번에 할 일 · " else "") + s.period,
                            style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold),
                            color = OnRoadColors.Primary,
                        )
                        Text(
                            s.title,
                            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                            color = if (checked) OnRoadColors.TextQuaternary else OnRoadColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (compact) {
                            Text(s.detail, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (onMore != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "다른 직업 다시 찾아보기",
                style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextSecondary,
                modifier = Modifier.clickable(onClick = onMore),
            )
        }
    }
}
