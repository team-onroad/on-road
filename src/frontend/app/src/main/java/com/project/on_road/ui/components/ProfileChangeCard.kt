package com.project.on_road.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.ProfileChange
import com.project.on_road.data.ProfileChanges
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 대화에서 바뀐 것 같은 내 정보 확인 카드 (상담·문서 AI 공통).
 * [바꾸기]를 눌러야 회원정보에 반영한다. key: 같은 카드를 다시 그려도 결과를 기억하기 위한 값 (메시지 id 등)
 */
@Composable
fun ProfileChangeCard(changes: List<ProfileChange>, key: String, modifier: Modifier = Modifier) {
    if (changes.isEmpty()) return
    var state by rememberSaveable(key) { mutableStateOf("open") } // open / saving / applied / kept / failed
    val scope = rememberCoroutineScope()

    Column(
        modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(OnRoadColors.SurfaceCool)
            .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Inner)
            .padding(14.dp),
    ) {
        Text(
            when (state) {
                "applied" -> "내 정보를 바꿨어요"
                "kept" -> "내 정보는 그대로 둘게요"
                else -> "내 정보를 바꿀까요?"
            },
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            color = OnRoadColors.Primary,
        )
        Spacer(Modifier.height(8.dp))
        changes.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(c.label, style = OnRoadType.Caption2, color = OnRoadColors.TextSlateMid, modifier = Modifier.width(76.dp))
                Text(
                    "${c.current ?: "없음"} → ${c.proposedLabel}",
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextPrimary,
                )
            }
        }
        if (state == "failed") {
            Spacer(Modifier.height(6.dp))
            Text("바꾸지 못했어요. 다시 눌러 주세요.", style = OnRoadType.Micro, color = OnRoadColors.Danger)
        }
        if (state == "open" || state == "saving" || state == "failed") {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OnRoadButton(
                    "바꾸기",
                    {
                        state = "saving"
                        scope.launch { state = if (runCatching { ProfileChanges.apply(changes) }.isSuccess) "applied" else "failed" }
                    },
                    Modifier.weight(1f),
                    height = 36.dp,
                    textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    loading = state == "saving",
                )
                OnRoadButton(
                    "그대로 두기", { state = "kept" }, Modifier.weight(1f),
                    variant = ButtonVariant.Outline, height = 36.dp, textStyle = OnRoadType.Caption,
                    enabled = state != "saving",
                )
            }
        }
    }
}
