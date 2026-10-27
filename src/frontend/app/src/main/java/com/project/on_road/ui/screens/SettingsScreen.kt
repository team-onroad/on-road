package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import android.widget.Toast
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.SavedAccount
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.UserStage
import com.project.on_road.data.UserUpdate
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*

/** 설정 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onReset: () -> Unit, onSwitched: () -> Unit = {}) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(AppContainer.session.status) }
    val isChild = AppState.stage == UserStage.CHILD
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAccounts by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedAccount?>(null) }
    var accountList by remember { mutableStateOf(AppContainer.session.accounts) }
    var switchingId by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    /** 다른 계정으로 바꾸기 (온보딩 없이) */
    fun switchTo(id: String) {
        if (switchingId != null) return
        switchingId = id
        scope.launch {
            runCatching { AppContainer.loginExisting(id) }
                .onSuccess { ok ->
                    if (ok) { showAccounts = false; onSwitched() }
                    else Toast.makeText(context, "서버에서 계정을 찾지 못해 목록에서 지웠어요.", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(context, "서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.", Toast.LENGTH_SHORT).show() }
            switchingId = null
        }
    }

    /** 3.12 퇴소 처리: 상태를 '퇴소 후'로 바꾼다. 서버가 퇴소 예정일을 지운다 */
    fun leaveCare() {
        val userId = AppContainer.session.userId ?: return
        leaving = true
        scope.launch {
            runCatching { AppContainer.api.updateUser(userId, UserUpdate(status = LivingStatus.LEFT)) }
                .onSuccess {
                    AppContainer.applyProfile(it)
                    status = it.status
                    // 퇴소일 기준 알림은 더 이상 맞지 않으니 끈다 (퇴소 후엔 목표 D-day 를 따로 쓴다)
                    com.project.on_road.notification.DdayNotifier.cancel(context)
                    AppContainer.session.ddayAlarm = false
                    Toast.makeText(context, "퇴소 후 상태로 바꿨어요. 이제 '목표 D-day'에서 목표일을 정할 수 있어요.", Toast.LENGTH_LONG).show()
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

        // ── 계정 ──
        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("계정")
        Spacer(Modifier.height(16.dp))
        ListRow(
            "계정 바꾸기",
            "이 휴대폰에서 쓴 계정으로 바로 바꿔요",
            icon = Icons.Outlined.SwitchAccount,
        ) {
            accountList = AppContainer.session.accounts
            showAccounts = true
        }
        Spacer(Modifier.height(8.dp))
        val myId = AppContainer.session.userId.orEmpty()
        ListRow(
            "이 계정 지우기",
            "이 휴대폰에서 지금 계정과 기록을 지워요",
            icon = Icons.Outlined.PersonRemove,
        ) {
            AppContainer.session.accounts.firstOrNull { it.userId == AppContainer.session.userId }?.let { deleting = it }
        }
        Spacer(Modifier.height(8.dp))
        ListRow(
            "내 아이디 복사",
            myId.ifBlank { "아직 없어요" },
            icon = Icons.Outlined.ContentCopy,
        ) {
            if (myId.isNotBlank()) {
                clipboard.setText(AnnotatedString(myId))
                Toast.makeText(context, "아이디를 복사했어요. 다른 휴대폰의 '이미 사용 중이에요'에 붙여 넣으세요.", Toast.LENGTH_LONG).show()
            }
        }

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
        // 퇴소 처리는 자립준비청년만 (청소년·아동에게는 보이지 않게)
        if (AppState.stage == UserStage.YOUNG_ADULT && status != null && status != LivingStatus.LEFT) {
            ListRow(
                if (leaving) "바꾸는 중…" else "퇴소했어요",
                "상황을 '퇴소 후'로 바꿔요. 지금 정해 둔 퇴소 예정일은 지워져요",
                icon = Icons.Outlined.Home,
            ) { if (!leaving) confirmLeave = true }
            Spacer(Modifier.height(8.dp))
        }
        ListRow(
            "프로필 다시 설정",
            "지금 계정은 그대로 남아요",
            icon = Icons.Outlined.Refresh,
        ) { confirmReset = true }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("내 기록")
        Spacer(Modifier.height(16.dp))
        ListRow(
            "앱에 쌓인 기록 지우기",
            "이 계정의 상담·연습·기록장을 지워요",
            icon = Icons.Outlined.DeleteOutline,
        ) { confirmWipe = true }
        Spacer(Modifier.height(8.dp))
        Text(
            "이 기록들은 휴대폰 안에만 저장되고, 다른 사람에게 자동으로 보내지 않아요.",
            style = OnRoadType.Tiny,
            color = OnRoadColors.TextQuaternary,
        )
    }

    if (showAccounts) {
        val current = AppContainer.session.userId
        val accounts = accountList
        AlertDialog(
            onDismissRequest = { if (switchingId == null) showAccounts = false },
            containerColor = Color.White,
            shape = OnRoadShapes.Card,
            title = { Text("계정 바꾸기", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { a ->
                        val now = a.userId == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(OnRoadShapes.Inner)
                                .border(1.dp, if (now) OnRoadColors.Primary else OnRoadColors.BorderSlate, OnRoadShapes.Inner)
                                .clickable(enabled = !now && switchingId == null) { switchTo(a.userId) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.name.ifBlank { "이름 없음" }, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                when {
                                    switchingId == a.userId -> "바꾸는 중…"
                                    now -> "지금 사용 중"
                                    else -> a.stage.label
                                },
                                style = OnRoadType.Micro,
                                color = if (now) OnRoadColors.Primary else OnRoadColors.TextTertiary,
                            )
                            if (!now) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "지우기",
                                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                                    color = OnRoadColors.TextQuaternary,
                                    modifier = Modifier
                                        .clip(OnRoadShapes.Chip)
                                        .clickable(enabled = switchingId == null) { deleting = a }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccounts = false; onReset() }) {
                    Text("새로 시작하기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccounts = false }) { Text("닫기", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
            },
        )
    }

    deleting?.let { a ->
        DeleteAccountDialog(
            name = a.name.ifBlank { "이름 없음" },
            onCancel = { deleting = null },
            onConfirm = {
                val wasCurrent = a.userId == AppContainer.session.userId
                AppContainer.session.removeAccount(a.userId)
                accountList = AppContainer.session.accounts
                deleting = null
                Toast.makeText(context, "이 휴대폰에서 계정을 지웠어요.", Toast.LENGTH_SHORT).show()
                // 지금 계정을 지웠으면 처음 화면으로
                if (wasCurrent) { showAccounts = false; onReset() }
            },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = Color.White,
            shape = OnRoadShapes.Card,
            title = { Text("기록을 지울까요?", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
            text = { Text("지운 기록은 되돌릴 수 없어요. 서버에 저장된 체크리스트·생활비 기록은 그대로예요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    ActivityStore.clear()
                    Toast.makeText(context, "앱에 쌓인 기록을 지웠어요.", Toast.LENGTH_SHORT).show()
                }) { Text("지우기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("취소", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Color.White,
            shape = OnRoadShapes.Card,
            title = { Text("퇴소 처리할까요?", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
            text = {
                Text(
                    "상황이 '퇴소 후'로 바뀌고 퇴소 예정일은 지워져요.\n그다음부터는 '목표 D-day'에서 내 목표일을 따로 정해요.",
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
            text = { Text("처음 화면으로 돌아가 새로 입력해요. 지금 계정은 '이미 사용 중이에요'에서 다시 불러올 수 있어요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) },
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