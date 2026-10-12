package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import android.widget.Toast
import androidx.compose.material.icons.outlined.Home
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
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.UserStage
import com.project.on_road.data.UserUpdate
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*

/** 설정 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onReset: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(AppContainer.session.status) }
    val isChild = AppState.stage == UserStage.CHILD
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 3.12 퇴소 처리: 상태를 '퇴소 후'로 바꾼다. 서버가 퇴소 예정일을 지운다 */
    fun leaveCare() {
        val userId = AppContainer.session.userId ?: return
        leaving = true
        scope.launch {
            runCatching { AppContainer.api.updateUser(userId, UserUpdate(status = LivingStatus.LEFT)) }
                .onSuccess {
                    AppContainer.applyProfile(it)
                    status = it.status
                    Toast.makeText(context, "퇴소 후 상태로 바꿨어요. 타임라인에서 목표일을 정할 수 있어요.", Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    android.util.Log.e("OnRoad", "퇴소 처리 실패", it)
                    Toast.makeText(context, "바꾸지 못했어요. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                }
            leaving = false
        }
    }

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
        // 퇴소 예정 · 시설 생활 중인 청소년·자립준비청년만
        if (!isChild && status != null && status != LivingStatus.LEFT) {
            ListRow(
                if (leaving) "바꾸는 중…" else "퇴소했어요",
                "상황을 '퇴소 후'로 바꿔요. 지금 정해 둔 퇴소 예정일은 지워져요",
                icon = Icons.Outlined.Home,
            ) { if (!leaving) confirmLeave = true }
            Spacer(Modifier.height(8.dp))
        }
        ListRow(
            "프로필 다시 설정",
            "나이나 상황이 바뀌었을 때 처음부터 다시 입력해요",
            icon = Icons.Outlined.Refresh,
        ) { confirmReset = true }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Color.White,
            shape = OnRoadShapes.Card,
            title = { Text("퇴소 처리할까요?", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
            text = {
                Text(
                    "상황이 '퇴소 후'로 바뀌고 퇴소 예정일은 지워져요.\n타임라인에서 새 목표일을 정할 수 있어요.",
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextTertiary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    leaveCare()
                }) { Text("퇴소 처리", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            },
        )
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