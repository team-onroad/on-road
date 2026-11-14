package com.project.on_road.ui.screens

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAccounts by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedAccount?>(null) }
    var accountList by remember { mutableStateOf(AppContainer.session.accounts) }
    var switchingId by remember { mutableStateOf<String?>(null) }
    // 알림: 하나로 켜고 끈다 (D-day + 매일 저녁 '오늘 챙길 것')
    var notifyOn by remember { mutableStateOf(AppContainer.session.ddayAlarm) }
    val notifyPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            com.project.on_road.notification.Notifications.setEnabled(context, true)
            notifyOn = true
        } else {
            com.project.on_road.ui.components.OnRoadToast.show("알림을 받으려면 휴대폰 설정에서 알림을 허용해 주세요.")
        }
    }
    fun setNotify(on: Boolean) {
        val needs = on && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) {
            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        com.project.on_road.notification.Notifications.setEnabled(context, on)
        notifyOn = on
    }
    val clipboard = LocalClipboardManager.current

    /** 다른 계정으로 바꾸기 (온보딩 없이) */
    fun switchTo(id: String) {
        if (switchingId != null) return
        switchingId = id
        scope.launch {
            runCatching { AppContainer.loginExisting(id) }
                .onSuccess { ok ->
                    if (ok) { showAccounts = false; onSwitched() }
                    else com.project.on_road.ui.components.OnRoadToast.show("서버에서 계정을 찾지 못해 목록에서 지웠어요.")
                }
                .onFailure { com.project.on_road.ui.components.OnRoadToast.show("서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.") }
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
                    // 퇴소일 기준 알림은 더 이상 맞지 않으니 끈다 (매일 저녁 알림은 그대로)
                    com.project.on_road.notification.Notifications.applyAll(context)
                    com.project.on_road.ui.components.OnRoadToast.show("퇴소 후 상태로 바꿨어요. 이제 '목표 D-day'에서 목표일을 정할 수 있어요.")
                }
                .onFailure {
                    android.util.Log.e("OnRoad", "퇴소 처리 실패", it)
                    com.project.on_road.ui.components.OnRoadToast.show("바꾸지 못했어요. 잠시 후 다시 시도해 주세요.")
                }
            leaving = false
        }
    }

    var editingServer by remember { mutableStateOf(false) }
    var serverDraft by remember { mutableStateOf("") }
    OnRoadScreen("설정", onBack) {
        val myId = AppContainer.session.userId.orEmpty()

        // ── 내 정보: 배경·테두리 없이 글만 ──
        Column(Modifier.padding(start = 4.dp)) {
            Text(AppContainer.session.nickname.ifBlank { "사용자" }, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
        }

        // ── 알림 · 음성 ──
        SettingsGroup("알림 · 음성") {
            SettingRow(
                "알림 받기",
                "D-day 할 일, 쓰다 만 서류, 다시 해 볼 연습",
                trailing = { OnRoadSwitch(checked = notifyOn, onCheckedChange = { setNotify(it) }) },
            )
            SettingRow(
                "음성으로 듣고 말하기",
                "글을 읽어 주고, 말로 입력해요",
                trailing = {
                    OnRoadSwitch(
                        checked = AppState.voiceOn,
                        onCheckedChange = { AppState.updateVoice(AppContainer.session, it) },
                        enabled = true,
                    )
                },
            )
        }

        // ── 계정 ──
        SettingsGroup("계정") {
            SettingRow("계정 바꾸기", "이 휴대폰에서 쓴 계정으로 바로 바꿔요") {
                accountList = AppContainer.session.accounts
                showAccounts = true
            }
            SettingRow(
                "내 아이디 복사",
                myId.ifBlank { "아직 없어요" },
            ) {
                if (myId.isNotBlank()) {
                    clipboard.setText(AnnotatedString(myId))
                    com.project.on_road.ui.components.OnRoadToast.show("아이디를 복사했어요. 다른 휴대폰의 '이미 사용 중이에요'에 붙여 넣으세요.")
                }
            }
            SettingRow("이 계정 지우기", "이 휴대폰에서 지금 계정과 기록을 지워요", danger = true) {
                AppContainer.session.accounts.firstOrNull { it.userId == AppContainer.session.userId }?.let { deleting = it }
            }
        }

        // ── 프로필 · 기록 ──
        SettingsGroup("프로필 · 기록") {
            // 퇴소 처리는 자립준비청년만
            if (AppState.stage == UserStage.YOUNG_ADULT && status != null && status != LivingStatus.LEFT) {
                SettingRow(
                    if (leaving) "바꾸는 중…" else "퇴소했어요",
                    "상황을 '퇴소 후'로 바꿔요. 퇴소 예정일은 지워져요",
                ) { if (!leaving) confirmLeave = true }
            }
            SettingRow("프로필 다시 설정", "지금 계정은 그대로 남아요") { confirmReset = true }
            SettingRow("앱에 쌓인 기록 지우기", "이 계정의 상담·연습·기록장을 지워요", danger = true) { confirmWipe = true }
        }

        // ── 개발 · 시연용: 서버 주소 (PC IP 가 바뀌어도 다시 빌드하지 않게) ──
        if (com.project.on_road.BuildConfig.DEBUG) {
            SettingsGroup("서버 연결") {
                SettingRow("서버 주소", AppContainer.serverUrl) {
                    serverDraft = AppContainer.serverUrl.removePrefix("http://").removeSuffix("/api/")
                    editingServer = true
                }
            }
        }
    }

    if (editingServer) {
        AlertDialog(
            onDismissRequest = { editingServer = false },
            containerColor = Color.White,
            shape = OnRoadShapes.Card,
            title = { Text("서버 주소", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
            text = {
                Column {
                    Text(
                        "PC 의 IP 만 넣어도 돼요 (예: 172.30.1.57). 저장하면 앱이 다시 켜져요.",
                        style = OnRoadType.Caption2,
                        color = OnRoadColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    OnRoadTextField(value = serverDraft, onValueChange = { serverDraft = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppContainer.setServerUrl(serverDraft)
                    editingServer = false
                    // 주소는 앱을 켤 때 정해지므로 다시 켠다
                    val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (restart != null) context.startActivity(restart)
                    Runtime.getRuntime().exit(0)
                }) { Text("저장하고 다시 켜기", color = OnRoadColors.TextPrimary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppContainer.setServerUrl(null)
                    editingServer = false
                    val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (restart != null) context.startActivity(restart)
                    Runtime.getRuntime().exit(0)
                }) { Text("기본 주소로", color = OnRoadColors.TextSecondary) }
            },
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
                                .tap(enabled = !now && switchingId == null) { switchTo(a.userId) }
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
                                        .tap(enabled = switchingId == null) { deleting = a }
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
                com.project.on_road.ui.components.OnRoadToast.show("이 휴대폰에서 계정을 지웠어요.")
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
                    com.project.on_road.ui.components.OnRoadToast.show("앱에 쌓인 기록을 지웠어요.")
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

/* ---------------------------- 설정 조각 ---------------------------- */

/** 묶음: 작은 제목 + 줄마다 따로 된 연회색 카드 (다른 화면 목록과 같은 모양, 선 없음) */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    Text(title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** 설정 한 줄: 연회색 카드, 아이콘 없이 제목 · 설명, 오른쪽에 스위치(있으면). 지우는 줄은 흰 카드 */
@Composable
internal fun SettingRow(
    title: String,
    sub: String? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        // 지우는 줄은 흰 바탕 (나머지는 연회색)
        background = if (danger) OnRoadColors.Surface else OnRoadColors.SurfaceSubtle,
        borderColor = if (danger) OnRoadColors.BorderChip else OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 14.dp, bottom = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = OnRoadColors.TextPrimary,
                )
                sub?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
    }
}
