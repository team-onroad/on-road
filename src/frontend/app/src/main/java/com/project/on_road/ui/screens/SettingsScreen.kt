package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.UserStage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*

/** 설정 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onReset: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    val isChild = AppState.stage == UserStage.CHILD

    OnRoadScreen("설정", onBack) {
        ListRow(
            AppContainer.session.nickname.ifBlank { "사용자" },
            "${AppState.stage.label} 화면으로 보고 있어요",
            icon = Icons.Outlined.Person,
        )

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("음성")
        Spacer(Modifier.height(16.dp))
        SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 15.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("음성으로 듣고 말하기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    Text(
                        if (isChild) "아동 화면에서는 항상 켜져 있어요" else "화면 글을 읽어 주고, 말로 입력할 수 있어요",
                        style = OnRoadType.Tiny,
                        color = OnRoadColors.TextTertiary,
                    )
                }
                OnRoadSwitch(
                    checked = AppState.voiceOn,
                    onCheckedChange = { AppState.updateVoice(AppContainer.session, it) },
                    enabled = !isChild,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "음성은 휴대폰 안에서 글자로 바뀌고, 목소리 자체는 서버에 저장하지 않아요.",
            style = OnRoadType.Tiny,
            color = OnRoadColors.TextQuaternary,
        )

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("프로필")
        Spacer(Modifier.height(16.dp))
        ListRow(
            "프로필 다시 설정",
            "나이나 상황이 바뀌었을 때 처음부터 다시 입력해요",
            icon = Icons.Outlined.Refresh,
        ) { confirmReset = true }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Color.White,
            shape = OnRoadShapes.Card,
            title = { Text("프로필을 다시 설정할까요?", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
            text = { Text("지금까지 입력한 정보가 지워지고 처음 화면으로 돌아가요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("다시 설정", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            },
        )
    }
}
