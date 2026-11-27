package com.project.on_road.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Credential
import com.project.on_road.data.CredentialStore
import com.project.on_road.data.DEFAULT_EDUCATION_OPTIONS
import com.project.on_road.data.EducationOption
import com.project.on_road.ui.components.ButtonVariant
import com.project.on_road.ui.components.ChoiceChip
import com.project.on_road.ui.components.ConfirmDeleteDialog
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.OnRoadTextField
import com.project.on_road.ui.components.OnRoadToast
import com.project.on_road.ui.components.SkeletonList
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.components.tap
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DOT = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/**
 * 교육 · 자격증 (3.37). 설정 > 나의 자립 조건에서 연다.
 * - 이수한 교육: 자립정착금 의무교육처럼 정책 조건과 이어지는 교육은 로드맵 · 정책 상세에 '이수함'으로 보인다
 * - 취득한 자격증: 이력서 초안의 '자격증' 칸에 들어간다
 */
@Composable
fun CredentialsScreen(onBack: () -> Unit, startAdding: String? = null) {
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(DEFAULT_EDUCATION_OPTIONS) }
    var adding by remember { mutableStateOf(startAdding) } // education · certificate · null
    var deleting by remember { mutableStateOf<Credential?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (CredentialStore.refresh(force = true) == null) failed = true
        runCatching { AppContainer.api.getEducationOptions() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { options = it }
    }

    OnRoadScreen("교육 · 자격증", onBack) {
        Text(
            "자립정착금처럼 교육을 들어야 받는 지원이 있어요. 들은 교육을 넣어 두면 신청 일정에 '이수함'으로 보이고, 자격증은 이력서 초안에 들어가요.",
            style = OnRoadType.Body3,
            color = OnRoadColors.TextTertiary,
        )
        val items = CredentialStore.items
        if (items == null) {
            Spacer(Modifier.height(20.dp))
            if (failed) EmptyState("불러오지 못했어요. 인터넷 연결을 확인해 주세요.")
            else SkeletonList(rows = 3, header = false)
            return@OnRoadScreen
        }

        CredentialSection(
            title = "이수한 교육",
            empty = "아직 넣은 교육이 없어요",
            items = CredentialStore.educations,
            addLabel = "교육 추가",
            onAdd = { adding = Credential.EDUCATION },
            onDelete = { deleting = it },
        )
        CredentialSection(
            title = "취득한 자격증",
            empty = "아직 넣은 자격증이 없어요",
            items = CredentialStore.certificates,
            addLabel = "자격증 추가",
            onAdd = { adding = Credential.CERTIFICATE },
            onDelete = { deleting = it },
        )
        Spacer(Modifier.height(24.dp))
    }

    adding?.let { kind ->
        AddCredentialDialog(
            kind = kind,
            options = options,
            taken = CredentialStore.educations.mapNotNull { it.code }.toSet(),
            onDismiss = { adding = null },
        ) { name, code, doneOn ->
            adding = null
            scope.launch {
                CredentialStore.add(kind, name, code, doneOn)
                    .onSuccess { OnRoadToast.show(if (kind == Credential.EDUCATION) "교육을 넣었어요" else "자격증을 넣었어요") }
                    .onFailure { OnRoadToast.show("넣지 못했어요. 인터넷 연결을 확인해 주세요.") }
            }
        }
    }
    deleting?.let { item ->
        ConfirmDeleteDialog(
            title = "${item.name} 지우기",
            text = if (item.isEducation && item.code != null) "지우면 신청 일정에서 이 교육이 '들어야 해요'로 바뀌어요." else "목록에서 지워요.",
            onCancel = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    CredentialStore.delete(item).onFailure { OnRoadToast.show("지우지 못했어요. 인터넷 연결을 확인해 주세요.") }
                }
            },
        )
    }
}

@Composable
private fun CredentialSection(
    title: String,
    empty: String,
    items: List<Credential>,
    addLabel: String,
    onAdd: () -> Unit,
    onDelete: (Credential) -> Unit,
) {
    Spacer(Modifier.height(32.dp))
    Text(title, style = OnRoadType.Headline, color = OnRoadColors.TextPrimary)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) EmptyState(empty)
        items.forEach { c -> CredentialRow(c) { onDelete(c) } }
    }
    Spacer(Modifier.height(10.dp))
    OnRoadButton(addLabel, onClick = onAdd, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, leadingIcon = Icons.Filled.Add)
}

@Composable
private fun CredentialRow(item: Credential, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Item)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                if (item.code != null) OnRoadTag("지원 조건", style = TagStyle.Neutral)
            }
            val verb = if (item.isEducation) "이수" else "취득"
            Text(
                item.doneOn?.let { "${it.format(DOT)} $verb" } ?: "$verb 날짜를 넣지 않았어요",
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "삭제",
            style = OnRoadType.Caption,
            color = OnRoadColors.TextTertiary,
            modifier = Modifier.clip(OnRoadShapes.Chip).tap(onClickLabel = "${item.name} 삭제", onClick = onDelete).padding(8.dp),
        )
    }
}

/** 교육: 정해진 교육(지원 조건) 칩 + 직접 입력. 자격증: 이름 입력. 날짜는 고르지 않아도 된다 */
@Composable
private fun AddCredentialDialog(
    kind: String,
    options: List<EducationOption>,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (name: String?, code: String?, doneOn: LocalDate?) -> Unit,
) {
    val education = kind == Credential.EDUCATION
    val choices = options.filter { it.code !in taken }
    var code by remember { mutableStateOf(if (education) choices.firstOrNull()?.code else null) }
    var name by remember { mutableStateOf("") }
    var doneOn by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf(false) }
    val typed = name.trim().take(60)
    val ready = code != null || typed.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = OnRoadShapes.Card,
        title = { Text(if (education) "교육 추가" else "자격증 추가", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (education) {
                    choices.forEach { o ->
                        ChoiceChip(o.name, selected = code == o.code, modifier = Modifier.fillMaxWidth()) { code = o.code; name = "" }
                    }
                    choices.firstOrNull { it.code == code }?.let {
                        Text(it.description, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    }
                    ChoiceChip("직접 입력", selected = code == null, modifier = Modifier.fillMaxWidth()) { code = null }
                }
                if (!education || code == null) {
                    OnRoadTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = if (education) "바리스타 과정" else "컴퓨터활용능력 2급",
                        showClear = true,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(OnRoadShapes.Item)
                        .background(OnRoadColors.SurfaceSubtle)
                        .border(1.dp, OnRoadColors.Border, OnRoadShapes.Item)
                        .tap(onClickLabel = "날짜 고르기") { picking = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (education) "이수한 날" else "취득한 날", style = OnRoadType.Body2, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text(doneOn?.format(DOT) ?: "고르지 않아도 돼요", style = OnRoadType.Body3, color = OnRoadColors.TextTertiary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(typed.ifEmpty { null }.takeIf { code == null }, code, doneOn) }, enabled = ready) {
                Text("추가", color = if (ready) OnRoadColors.Primary else OnRoadColors.TextTertiary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = OnRoadColors.TextSecondary) } },
    )
    if (picking) {
        ProfileDateDialog(doneOn, past = true, onDismiss = { picking = false }) { d -> picking = false; doneOn = d }
    }
}
