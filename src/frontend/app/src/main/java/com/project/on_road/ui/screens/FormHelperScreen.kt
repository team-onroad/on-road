package com.project.on_road.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.data.remote.DocumentSummaryDto
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class FormHelperViewModel : ViewModel() {
    private val stage = AppState.stage
    private val userId get() = AppContainer.session.userId.orEmpty()

    var templates by mutableStateOf<List<FormTemplate>?>(null)
        private set
    var analyzing by mutableStateOf(false)
        private set
    var draft by mutableStateOf<FormDraft?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val values = mutableStateMapOf<String, String>()
    /** 문서 AI 로 쓰던 서류 (서버) */
    var documents by mutableStateOf<List<DocumentSummaryDto>>(emptyList())
        private set

    fun loadDocuments() {
        viewModelScope.launch { documents = runCatching { AppContainer.documents.list() }.getOrDefault(emptyList()) }
    }

    init {
        viewModelScope.launch {
            templates = runCatching { AppContainer.coachApi.formTemplates(stage) }
                .onFailure { error = it.userMessage("양식 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.") }
                .getOrDefault(emptyList())
        }
    }

    fun openTemplate(id: String) = load("양식을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.") {
        AppContainer.coachApi.analyzeTemplate(id)
    }

    /** 사진·PDF 를 올려 만든 문서 (작성 화면으로 연다) */
    var opened by mutableStateOf<Long?>(null)
        private set

    fun consumeOpened() {
        opened = null
    }

    /**
     * 받은 양식 올리기. 사진·PDF → 서버 AI 가 칸을 읽어 문서 AI 로 (대화로 채우기·칸별로 쓰기).
     * 한글(hwp·hwpx)은 예전처럼 앱 안에서 항목만 나눈다
     */
    fun upload(context: android.content.Context, uri: android.net.Uri) {
        if (analyzing) return
        analyzing = true
        error = null
        viewModelScope.launch {
            runCatching {
                val file = FormUpload.prepare(context, uri)
                if (file.isHwp) {
                    val d = AppContainer.coachApi.analyzeUpload(userId, file.bytes, file.mime)
                    val name = file.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                    values.clear()
                    draft = if (d.title == "올린 양식" && name != null) d.copy(title = name) else d
                } else {
                    opened = AppContainer.documents.upload(file).documentId
                    loadDocuments()
                }
            }.onFailure {
                error = when (it) {
                    is FormUpload.TooBig, is FormUpload.NotSupported -> it.message
                    is FormParser.ReadError -> it.message
                    else -> it.userMessage("양식을 읽지 못했어요. 양식 전체가 밝게 나오게 다시 찍어 주세요.")
                }
            }
            analyzing = false
        }
    }

    private fun load(failMessage: String, block: suspend () -> FormDraft) {
        analyzing = true
        error = null
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { d ->
                    values.clear()
                    draft = d
                }
                .onFailure { error = (it as? FormParser.ReadError)?.message ?: it.userMessage(failMessage) }
            analyzing = false
        }
    }

    fun close() {
        draft = null
    }

    fun exportText(): String {
        val d = draft ?: return ""
        return buildString {
            appendLine(d.title)
            appendLine()
            d.fields.forEach { f ->
                appendLine("■ ${f.label}")
                appendLine(values[f.id].orEmpty().ifBlank { "(작성 안 함)" })
                appendLine()
            }
        }.trim()
    }
}

/** 서류 작성 도우미 */
@Composable
fun FormHelperScreen(
    onBack: () -> Unit,
    /** 쓰던 서류 이어 쓰기 (문서 AI) */
    onOpenDocument: (Long) -> Unit = {},
    /** 자주 쓰는 양식을 문서 AI 로 쓰기. null 이면 예전처럼 앱 안에서 항목만 채운다 */
    onStartTemplate: ((String) -> Unit)? = null,
    vm: FormHelperViewModel = viewModel(),
) {
    val context = LocalContext.current
    // 작성 화면에서 돌아올 때마다 진행률을 새로 받는다
    LaunchedEffect(Unit) { vm.loadDocuments() }
    val uploader = rememberFormUploader { uri -> vm.upload(context, uri) }
    LaunchedEffect(vm.opened) {
        vm.opened?.let { id ->
            vm.consumeOpened()
            onOpenDocument(id)
        }
    }
    val back = { if (vm.draft != null) vm.close() else onBack() }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(vm.draft?.title ?: "서류 작성 도우미", back)
        val d = vm.draft
        when {
            vm.analyzing -> LoadingState("양식에서 쓸 칸을 읽고 있어요")
            d != null -> DraftEditor(d, vm) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, d.title)
                    putExtra(Intent.EXTRA_TEXT, vm.exportText())
                }
                context.startActivity(Intent.createChooser(send, "작성한 내용 보내기"))
            }
            else -> TemplateList(
                vm.templates,
                vm.error,
                documents = vm.documents,
                onOpenDocument = onOpenDocument,
                onPick = onStartTemplate ?: vm::openTemplate,
                onTakePhoto = uploader.takePhoto,
                onPickFile = uploader.pickFile,
            )
        }
    }
}

@Composable
private fun TemplateList(
    templates: List<FormTemplate>?,
    error: String?,
    documents: List<DocumentSummaryDto>,
    onOpenDocument: (Long) -> Unit,
    onPick: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        ScreenHeadline("양식을 고르거나 올리면", "항목을 쉽게 풀어 드려요.")

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        UploadFormCard(onTakePhoto, onPickFile)
        error?.let {
            Spacer(Modifier.height(12.dp))
            NoticeBox(it, title = "읽지 못했어요", tone = NoticeTone.Warning)
        }

        if (documents.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("쓰던 서류", trailingText = "이어서 쓸 수 있어요")
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                documents.forEach { d ->
                    val state = if (d.status == "exported") "PDF로 내보냄" else "${d.progress.completed}/${d.progress.total}칸 작성"
                    GrayRow(d.title, state) { onOpenDocument(d.documentId) }
                }
            }
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("자주 쓰는 양식", trailingText = "AI와 대화하며 채워요")
        Spacer(Modifier.height(16.dp))
        if (templates == null) {
            LoadingState("양식을 불러오고 있어요")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { t -> GrayRow(t.name, t.description) { onPick(t.id) } }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DraftEditor(d: FormDraft, vm: FormHelperViewModel, onExport: () -> Unit) {
    val filled = d.fields.count { vm.values[it.id].orEmpty().isNotBlank() }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            if (!d.originalExport) {
                NoticeBox(
                    "원본과 다를 수 있어요. 다 쓰면 원본 양식에 옮겨 적어요.",
                    title = "파일에서 찾은 항목 ${d.fields.size}개",
                )
                Spacer(Modifier.height(20.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("작성한 항목", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
                Text("$filled", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Text("/${d.fields.size}개", style = OnRoadType.Tiny, color = OnRoadColors.TextSlateMid, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(filled / d.fields.size.toFloat().coerceAtLeast(1f))
            Spacer(Modifier.height(24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.fields.forEach { f ->
                    FieldCard(f, vm.values[f.id].orEmpty()) { vm.values[f.id] = it }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
        Column(Modifier.background(Color.White).padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 12.dp)) {
            OnRoadButton(
                if (d.originalExport) "작성한 서류 내보내기" else "작성한 내용 보내기",
                onExport,
                Modifier.fillMaxWidth(),
                enabled = filled > 0,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (d.originalExport) "원래 양식 파일로 내보내기는 아직 지원하지 않아요. 지금은 작성 내용을 보낼 수 있어요."
                else "작성 내용을 메모나 메신저로 보낼 수 있어요.",
                style = OnRoadType.Tiny,
                color = OnRoadColors.TextTertiary,
            )
        }
    }
}

/** 항목 카드: 작성하면 완료 스타일(회색 바탕 + 녹색 테두리) */
@Composable
private fun FieldCard(field: FormField, value: String, onChange: (String) -> Unit) {
    val done = value.isNotBlank()
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = if (done) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
        borderColor = if (done) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(field.label, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                if (field.easyLabel != field.label) {
                    Text("= ${field.easyLabel}", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.Primary)
                }
            }
            SpeakButton("${field.easyLabel}. ${field.guide}")
        }
        Spacer(Modifier.height(4.dp))
        Text(field.guide, style = OnRoadType.Micro, color = OnRoadColors.TextPrimary.copy(alpha = 0.6f))
        field.example?.let {
            Spacer(Modifier.height(2.dp))
            Text("예) $it", style = OnRoadType.Micro, color = OnRoadColors.TextPrimary.copy(alpha = 0.45f))
        }
        field.caution?.let {
            Spacer(Modifier.height(10.dp))
            NoticeBox(it, tone = NoticeTone.Warning)
        }
        val outline = field.outlineText
        if (field.essay && outline != null && value.isBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(OnRoadShapes.Badge)
                    .border(1.dp, OnRoadColors.Primary, OnRoadShapes.Badge)
                    .clickable { onChange(outline) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AI가 만든 글의 틀 넣기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.Primary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            OnRoadTextField(
                value = value,
                onValueChange = onChange,
                singleLine = !field.multiline,
                minLines = if (field.multiline) 4 else 1,
                modifier = Modifier.weight(1f),
            )
            MicButton(onResult = { onChange((value + " " + it).trim()) }, modifier = Modifier.padding(start = 8.dp), dim = 48.dp)
        }
    }
}


/** 목록 한 줄: 연회색 카드 (홈 다음 일정과 같은 모양, 아이콘 없음) */
@Composable
private fun GrayRow(title: String, description: String?, onClick: () -> Unit) {
    SubtleCard(Modifier.fillMaxWidth().heightIn(min = 60.dp), onClick = onClick, contentPadding = PaddingValues(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                description?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
        }
    }
}
