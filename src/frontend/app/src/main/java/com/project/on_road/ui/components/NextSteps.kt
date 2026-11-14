package com.project.on_road.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.Links
import com.project.on_road.data.NextStep
import com.project.on_road.ui.theme.*

/**
 * "이어서 해 보기": 한 기능이 끝난 뒤 다른 기능으로 바로 넘어가는 줄.
 * 위계: 묶음 제목(SubHeader) → 할 일 이름(굵게) → 종류(작은 회색 글씨) / 오른쪽 › 하나.
 * 줄마다 연회색 카드 (검색 화면 · 신청 관리 카드와 같은 모양), 버튼 없이 줄 전체를 누른다
 */
@Composable
fun NextStepList(
    steps: List<NextStep>,
    onOpen: (NextStep) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "이어서 해 보기",
) {
    if (steps.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        SubHeader(title)
        Spacer(Modifier.height(NextStepSpace.TitleGap))
        Column(verticalArrangement = Arrangement.spacedBy(NextStepSpace.RowGap)) {
            steps.forEach { s -> NextStepRow(s) { onOpen(s) } }
        }
    }
}

/** 이어서 해 보기 간격 (숫자만 바꿔서 조절) */
object NextStepSpace {
    /** 제목 → 첫 줄 */
    val TitleGap = 12.dp
    /** 줄 사이 */
    val RowGap = 8.dp
    /** 카드 안 위아래 */
    val RowPadV = 14.dp
}

@Composable
fun NextStepRow(step: NextStep, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = NextStepSpace.RowPadV, bottom = NextStepSpace.RowPadV),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    step.label,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = OnRoadColors.TextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(Links.stepKindLabel(step.kind), style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = OnRoadColors.TextQuaternary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 작은 메인 색 버튼 (흰 글씨). 카드 안 '바로 가기' 같은 곳 */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Text(
        text,
        style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
        color = androidx.compose.ui.graphics.Color.White,
        maxLines = 1,
        modifier = modifier
            .pressScale(press, pressed = 0.94f)
            .clip(OnRoadShapes.Chip)
            .background(OnRoadColors.Primary)
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
