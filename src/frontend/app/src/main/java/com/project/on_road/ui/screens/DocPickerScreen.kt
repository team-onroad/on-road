package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.Checklist
import com.project.on_road.data.ChecklistDoc
import com.project.on_road.data.FormUpload
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

/** 준비 서류 하나: AI 와 쓸 수 있으면 정책 신청서(policyForm) 또는 앱 양식(templateId) */
data class DocChoice(val doc: ChecklistDoc, val policyForm: Boolean, val templateId: String?) {
    val writable: Boolean get() = policyForm || templateId != null || doc.documentId != null
    val name: String get() = doc.shortLabel ?: doc.name
}

class DocPickerViewModel : ViewModel() {
    var checklist by mutableStateOf<Checklist?>(null)
        private set
    var choices by mutableStateOf<List<DocChoice>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loadedFor: String? = null
    /** 양식 사진·PDF 를 읽는 중인 서류 항목 */
    var uploadingItem by mutableStateOf<Long?>(null)
        private set
    var uploadError by mutableStateOf<Pair<Long, String>?>(null)
        private set
    /** 올려서 만든 문서 (작성 화면으로 연다) */
    var opened by mutableStateOf<Long?>(null)
        private set

    fun consumeOpened() {
        opened = null
    }

    /** 발급·기관 서류의 받은 양식을 올리면 AI 가 칸을 읽어 그 서류 항목에 연결한 문서를 만든다 */
    fun upload(context: android.content.Context, uri: android.net.Uri, itemId: Long) {
        if (uploadingItem != null) return
        uploadingItem = itemId
        uploadError = null
        viewModelScope.launch {
            runCatching {
                val file = FormUpload.prepare(context, uri)
                if (file.isHwp) throw FormUpload.NotSupported()
                AppContainer.documents.upload(file, checklistItemId = itemId, policyId = loadedFor?.toLongOrNull())
            }.onSuccess { opened = it.documentId }
                .onFailure {
                    val msg = if (it is FormUpload.TooBig || it is FormUpload.NotSupported) it.message.orEmpty()
                    else it.userMessage("양식을 읽지 못했어요. 양식 전체가 밝게 나오게 다시 찍어 주세요.")
                    uploadError = itemId to msg
                }
            uploadingItem = null
        }
    }

    /** 화면에 돌아올 때마다 다시 받는다 (방금 쓴 서류가 체크됐는지) */
    fun load(policyId: String) {
        val first = loadedFor != policyId
        loadedFor = policyId
        viewModelScope.launch {
            if (first) loading = true
            runCatching {
                val c = AppContainer.api.openChecklist(AppContainer.session.userId.orEmpty(), policyId)
                val hasForm = policyId.toLongOrNull()?.let { AppContainer.documents.hasPolicyForm(it) } ?: false
                val templates = runCatching { AppContainer.coachApi.formTemplates(AppState.stage) }.getOrDefault(emptyList())
                c to c.docs.map { d ->
                    val flat = d.name.replace(" ", "")
                    val form = hasForm && "신청서" in flat
                    val template = if (form) null else templates.firstOrNull { t ->
                        val tn = t.name.replace(" ", "").substringBefore("(")
                        tn.length >= 2 && (tn in flat || flat in tn)
                    }
                    DocChoice(d, form, template?.id)
                }
            }.onSuccess { (c, list) ->
                checklist = c
                // AI 와 쓸 수 있는 서류를 먼저
                choices = list.sortedByDescending { it.writable }
                error = null
            }.onFailure { error = it.userMessage("준비 서류를 불러오지 못했어요.") }
            loading = false
        }
    }
}

/**
 * 서류 작성: 정책의 준비 서류 중 쓸 서류를 고른다.
 * AI 와 쓰는 서류(신청서 등) → 작성 화면(대화로 채우기·칸별로 쓰기), 발급받는 서류 → 준비 방법 안내
 */
@Composable
fun DocPickerScreen(
    policyId: String,
    onBack: () -> Unit,
    onWrite: (policyId: Long?, templateId: String?, itemId: Long) -> Unit,
    /** 이미 쓰던 서류 열기 */
    onOpenDocument: (Long) -> Unit = {},
    vm: DocPickerViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load(policyId) }
    val c = vm.checklist
    val context = androidx.compose.ui.platform.LocalContext.current
    var uploadFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    val uploader = rememberFormUploader { uri -> uploadFor?.let { vm.upload(context, uri, it) } }
    LaunchedEffect(vm.opened) {
        vm.opened?.let { id ->
            vm.consumeOpened()
            onOpenDocument(id)
        }
    }

    OnRoadScreen("서류 작성", onBack) {
        when {
            vm.loading -> LoadingState("준비 서류를 불러오고 있어요")
            c == null -> NoticeBox(vm.error ?: "잠시 후 다시 시도해 주세요.", title = "불러오지 못했어요", tone = NoticeTone.Warning)
            else -> {
                ScreenHeadline(c.policyName, "어떤 서류를 준비할까요?")

                val writable = vm.choices.filter { it.writable }
                // 체크리스트에서 꺼 둔 상황(대리 신청 등)의 서류는 빼고 보여 준다
                val issued = vm.choices.filter { !it.writable && !it.doc.notApplicable }

                if (writable.isNotEmpty()) {
                    Spacer(Modifier.height(OnRoadDimens.BlockGap))
                    SectionHeader("AI와 함께 쓰는 서류", titleStyle = OnRoadType.Title3)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        writable.forEach { ch ->
                            WritableDocCard(ch) {
                                val id = ch.doc.documentId
                                if (id != null) onOpenDocument(id)
                                else onWrite(if (ch.policyForm) c.policyId.toLongOrNull() else null, ch.templateId, ch.doc.itemId)
                            }
                        }
                    }
                }

                if (issued.isNotEmpty()) {
                    Spacer(Modifier.height(OnRoadDimens.BlockGap))
                    SectionHeader("발급받아 준비하는 서류", titleStyle = OnRoadType.Title3)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        issued.forEach { ch ->
                            IssuedDocCard(
                                ch,
                                uploading = vm.uploadingItem == ch.doc.itemId,
                                busy = vm.uploadingItem != null,
                                error = vm.uploadError?.takeIf { it.first == ch.doc.itemId }?.second,
                                onTakePhoto = { uploadFor = ch.doc.itemId; uploader.takePhoto() },
                                onPickFile = { uploadFor = ch.doc.itemId; uploader.pickFile() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WritableDocCard(ch: DocChoice, onWrite: () -> Unit) {
    val done = ch.doc.written
    val writing = ch.doc.documentStatus == "writing"
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = if (done) OnRoadColors.BorderSlate else OnRoadColors.Primary,
        shape = OnRoadShapes.Inner,
        contentPadding = PaddingValues(16.dp),
        onClick = onWrite,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.Description, tint = OnRoadColors.Primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            }
            if (done) OnRoadTag("완료", style = TagStyle.PrimaryFilled, textStyle = OnRoadType.Tiny)
            else if (writing) OnRoadTag("작성 중", style = TagStyle.PrimaryOutline, textStyle = OnRoadType.Tiny)
        }
        Spacer(Modifier.height(12.dp))
        OnRoadButton(
            when {
                done -> "다시 보기"
                writing -> "이어서 쓰기"
                else -> "AI와 쓰기"
            }, onWrite, Modifier.fillMaxWidth(),
            variant = if (done) ButtonVariant.Outline else ButtonVariant.Primary,
            height = 38.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
        )
    }
}

/** 발급받는 서류. 기관에서 받은 양식이 있으면 찍어 올려 AI와 쓸 수 있다 */
@Composable
private fun IssuedDocCard(
    ch: DocChoice,
    uploading: Boolean,
    busy: Boolean,
    error: String?,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SubtleCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.name, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateDark, modifier = Modifier.weight(1f))
            when {
                ch.doc.notApplicable -> OnRoadTag("해당 없음", style = TagStyle.Gray, textStyle = OnRoadType.Tiny)
                ch.doc.checked -> OnRoadTag("준비 완료", style = TagStyle.PrimarySoft, textStyle = OnRoadType.Tiny)
                else -> SmallBoxButton(if (open) "닫기" else "양식 올리기", { open = !open }, leadingIcon = Icons.Outlined.Description)
            }
        }
        if (uploading) {
            Spacer(Modifier.height(10.dp))
            Text("양식에서 쓸 칸을 읽고 있어요…", style = OnRoadType.Micro, color = OnRoadColors.Primary)
        } else if (open) {
            Spacer(Modifier.height(10.dp))
            UploadButtons(onTakePhoto, onPickFile, enabled = !busy)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = OnRoadType.Micro, color = OnRoadColors.Danger)
        }
    }
}
