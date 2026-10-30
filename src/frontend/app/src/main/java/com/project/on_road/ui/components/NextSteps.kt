package com.project.on_road.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.Links
import com.project.on_road.data.NextStep
import com.project.on_road.ui.theme.*

/** "이어서 해 보기": 한 기능이 끝난 뒤 다른 기능으로 바로 넘어가는 줄 */
@Composable
fun NextStepList(
    steps: List<NextStep>,
    onOpen: (NextStep) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "이어서 해 보기",
) {
    if (steps.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text(title, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEach { s -> NextStepRow(s) { onOpen(s) } }
        }
    }
}

@Composable
fun NextStepRow(step: NextStep, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                OnRoadTag(Links.stepKindLabel(step.kind), style = TagStyle.Neutral, textStyle = OnRoadType.Tiny)
                Spacer(Modifier.height(6.dp))
                Text(step.label, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Text("바로 가기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
        }
    }
}
