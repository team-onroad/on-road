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
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.ui.draw.drawBehind
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
    /** 서류 속 숨겨진 위험 찾기 (계약서 · 등기부등본 · 근로계약서) */
    onRiskCheck: (() -> Unit)? = null,
    vm: FormHelperViewModel = viewModel(),
) {
    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(vm.draft?.title ?: "서류 작성 도우미", { if (vm.draft != null) vm.close() else onBack() })
        FormHelperContent(onOpenDocument, onStartTemplate, onRiskCheck, onBack = onBack, vm = vm, modifier = Modifier.weight(1f))
    }
}

/**
 * 서류 작성 도우미 내용 (제목 줄 없이). 서류 작성 도우미 화면과 신청 관리의 '서류' 칸이 같이 쓴다.
 * 받은 양식 올리기 → 계약서 위험 찾기 → 활동 기록 초안 → 쓰던 서류 → 자주 쓰는 양식
 */
@Composable
fun FormHelperContent(
    onOpenDocument: (Long) -> Unit = {},
    onStartTemplate: ((String) -> Unit)? = null,
    onRiskCheck: (() -> Unit)? = null,
    onActivityDraft: (() -> Unit)? = null,
    /** true: 신청 관리 '서류' 칸 모양 (정책 검색 화면과 같은 작은 연회색 줄로만) */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    /** 작성 중인 양식이 없을 때 뒤로 가기 (탭 안에서는 null: 시스템 뒤로 가기에 맡긴다) */
    onBack: (() -> Unit)? = null,
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
    // 양식을 쓰는 중이면 뒤로 가기는 목록으로
    BackHandler(enabled = vm.draft != null || onBack != null) { if (vm.draft != null) vm.close() else onBack?.invoke() }

    val d = vm.draft
    // 목록 ↔ 양식 읽는 중 ↔ 작성 화면이 툭 바뀌지 않고 부드럽게 넘어간다
    val phase = when {
        vm.analyzing -> 1
        d != null -> 2
        else -> 0
    }
    FadeSwitch(phase, modifier, label = "form-helper") { p -> when {
        p == 1 -> LoadingState(
            "양식을 읽고 있어요",
            steps = listOf("쓸 칸을 나누고 있어요", "앱에 있는 정보로 미리 채우고 있어요"),
        )
        p == 2 && d != null -> DraftEditor(d, vm) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, d.title)
                putExtra(Intent.EXTRA_TEXT, vm.exportText())
            }
            context.startActivity(Intent.createChooser(send, "작성한 내용 보내기"))
        }
        else -> if (compact) CompactDocList(
            vm.templates,
            vm.error,
            documents = vm.documents,
            onOpenDocument = onOpenDocument,
            onPick = onStartTemplate ?: vm::openTemplate,
            onTakePhoto = uploader.takePhoto,
            onPickFile = uploader.pickFile,
            onRiskCheck = onRiskCheck,
            onActivityDraft = onActivityDraft,
        ) else TemplateList(
            vm.templates,
            vm.error,
            documents = vm.documents,
            onOpenDocument = onOpenDocument,
            onPick = onStartTemplate ?: vm::openTemplate,
            onTakePhoto = uploader.takePhoto,
            onPickFile = uploader.pickFile,
            onRiskCheck = onRiskCheck,
            onActivityDraft = onActivityDraft,
        )
    } }
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
    onRiskCheck: (() -> Unit)? = null,
    onActivityDraft: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(20.dp))
        UploadFormCard(onTakePhoto, onPickFile)
        // 서명하기 전 계약서 위험 찾기: 흰 카드 + 메인 색 작은 버튼
        onRiskCheck?.let { open ->
            Spacer(Modifier.height(8.dp))
            OnRoadCard(
                Modifier.fillMaxWidth(),
                borderColor = OnRoadColors.BorderChip,
                shape = OnRoadShapes.Item,
                contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                onClick = open,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("계약서 위험 찾기", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
                        Text("서명 전에 숨은 위험을 찾아요", style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
                    }
                    Spacer(Modifier.width(10.dp))
                    PrimaryPill("찾아보기", open)
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            NoticeBox(it, title = "읽지 못했어요", tone = NoticeTone.Warning)
        }

        if (documents.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.SectionGap))
            SectionHeader("쓰던 서류")
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                documents.forEach { d ->
                    val state = if (d.status == "exported") "PDF로 내보냄" else "${d.progress.completed}/${d.progress.total}칸 작성"
                    GrayRow(d.title, state) {
                        // 서류 화면 제목 바에 바로 보이게
                        com.project.on_road.data.ScreenCache.put("title:doc:${d.documentId}", d.title)
                        onOpenDocument(d.documentId)
                    }
                }
            }
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("자주 쓰는 양식")
        Spacer(Modifier.height(16.dp))
        // 양식 목록 자리를 미리 그려 두었다가 부드럽게 바꾼다 (큰 동그라미 대신)
        FadeSwitch(templates == null, label = "form-templates") { loading ->
            if (loading || templates == null) {
                SkeletonList(rows = 3, header = false)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { t ->
                        GrayRow(t.name, t.description) {
                            // 서류 작성 화면을 여는 동안에도 제목 바에 양식 이름이 보이게
                            com.project.on_road.data.ScreenCache.put("title:doc-template:${t.id}", t.name)
                            onPick(t.id)
                        }
                    }
                }
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
                Text("작성한 항목", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
                Text("$filled", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Text("/${d.fields.size}개", style = OnRoadType.Tiny, color = OnRoadColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
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
                    .tap { onChange(outline) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("글의 틀 넣기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.Primary)
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


/**
 * 신청 관리 '서류' 칸: 정책 검색 화면과 같은 구성.
 * 입력칸 모양 '양식 올리기' → 키워드 태그(사진 · 도구) → 묶음 제목 → 작은 연회색 카드.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CompactDocList(
    templates: List<FormTemplate>?,
    error: String?,
    documents: List<DocumentSummaryDto>,
    onOpenDocument: (Long) -> Unit,
    onPick: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onRiskCheck: (() -> Unit)?,
    onActivityDraft: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        // 맨 처음: 계약서 확인하기 (받은 양식 카드와 같은 메인 색 카드, 한 장으로 넓게)
        onRiskCheck?.let { open ->
            // 흰 카드 · 칩 테두리. 진한 버튼 대신 오른쪽에 메인 색 글씨 '올리기 ›' (카드 전체를 눌러도 된다)
            OnRoadCard(
                Modifier.fillMaxWidth(),
                borderColor = OnRoadColors.BorderChip,
                shape = OnRoadShapes.Item,
                contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                onClick = open,
            ) {
                // 왼쪽: 제목 · 설명 한 줄, 오른쪽: '올리기 ›' 를 카드 세로 가운데에
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("계약서 위험 찾기", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("서명 전에 숨은 위험을 찾아요", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("올리기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.Primary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        // 받은 양식 올리기: 메인 색 카드 두 장 (사진으로 찍기 · 파일 고르기)
        SubHeader("받은 양식 올리기")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UploadCard("사진으로 찍기", "양식을 찍어 올려요", Icons.Outlined.PhotoCamera, Modifier.weight(1f), onTakePhoto)
            UploadCard("파일 고르기", "PDF · JPG · HWP", Icons.Outlined.Description, Modifier.weight(1f), onPickFile)
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            NoticeBox(it, title = "읽지 못했어요", tone = NoticeTone.Warning)
        }

        // 쓰던 서류 (있을 때만)
        if (documents.isNotEmpty()) {
            Spacer(Modifier.height(36.dp))
            SubHeader("쓰던 서류")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                documents.forEach { d ->
                    val state = if (d.status == "exported") "내보냄" else "${d.progress.completed}/${d.progress.total}칸"
                    DocCard(d.title, state, null) {
                        com.project.on_road.data.ScreenCache.put("title:doc:${d.documentId}", d.title)
                        onOpenDocument(d.documentId)
                    }
                }
            }
        }

        // 자주 쓰는 양식: 정책 검색 '분야별로 보기' 카드와 같은 모양
        Spacer(Modifier.height(36.dp))
        SubHeader("자주 쓰는 양식")
        Spacer(Modifier.height(12.dp))
        FadeSwitch(templates == null, label = "form-templates-compact") { loading ->
            if (loading || templates == null) {
                SkeletonList(rows = 3, header = false)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { t ->
                        DocCard(t.name, null, t.description) {
                            com.project.on_road.data.ScreenCache.put("title:doc-template:${t.id}", t.name)
                            onPick(t.id)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 양식 올리기 점선 상자 (예시: 'Upload your attachments').
 * 연회색 바탕 · 회색 점선 테두리, 가운데에 올리기 표시 · 올릴 수 있는 형식 · 글씨 버튼 두 개.
 * 상자 어디를 눌러도 파일 고르기, '사진으로 찍기'만 카메라.
 */
@Composable
private fun UploadDropZone(onPickFile: () -> Unit, onTakePhoto: () -> Unit) {
    val line = OnRoadColors.BorderChip
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.SurfaceSubtle)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = line,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .tap(onClickLabel = "양식 파일 고르기", onClick = onPickFile)
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.UploadFile, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        // 다른 카드와 같은 위계: 굵은 한 줄 → 보조 글씨 → 글씨 버튼
        Text("눌러서 양식 파일 올리기", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("PDF · JPG · PNG · HWP", style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(
            "사진으로 찍기",
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
            color = OnRoadColors.Primary,
            modifier = Modifier.clip(OnRoadShapes.Chip).tap(onClickLabel = "양식 사진 찍기", onClick = onTakePhoto).padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/**
 * 받은 양식 올리기 카드 (사진 · 파일): 메인 색 바탕, 흰 글씨는 왼쪽 위, 큰 아이콘은 오른쪽 아래에 흰색 8% 로 옅게, 카드 밖으로 살짝 잘리게
 */
@Composable
private fun UploadCard(
    title: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(UploadCardHeight)
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.Primary)
            .tap(onClickLabel = title, onClick = onClick),
    ) {
        Icon(
            icon,
            null,
            tint = Color.White.copy(alpha = UploadIconAlpha),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // 오른쪽 아래, 카드 밖으로 살짝 잘리게
                .offset(x = 10.dp, y = 16.dp)
                .size(68.dp),
        )
        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = OnRoadType.Caption2, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** 올리기 카드 높이 · 아이콘 투명도(0.07~0.10) */
private val UploadCardHeight = 88.dp
private const val UploadIconAlpha = 0.08f

/** 키워드 태그 모양 버튼 (정책 검색 키워드 태그와 같음) */
@Composable
private fun DocTag(text: String, onClick: () -> Unit) {
    OnRoadTag(text, style = TagStyle.Neutral, modifier = Modifier.clip(OnRoadShapes.Tag).tap(onClickLabel = text, onClick = onClick))
}

/**
 * 작은 연회색 카드 (정책 검색 '분야별로 보기' 카드와 같은 배치).
 * 첫 줄: 이름(굵게) · 오른쪽 작은 회색 글씨, 둘째 줄: 설명
 */
@Composable
private fun DocCard(title: String, right: String?, description: String?, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            right?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
            }
        }
        description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 반반 도구 칸: 다른 카드와 같은 연회색 카드, 굵은 이름 + 설명 */
@Composable
private fun ToolTile(title: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    OnRoadCard(
        modifier,
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(description, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 2)
    }
}

/** 목록 카드: 연회색 카드 한 장 안에 줄을 여러 개 (구분선 없이 간격으로만 나눈다) */
@Composable
private fun ListCard(content: @Composable ColumnScope.() -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(vertical = 6.dp),
        content = content,
    )
}

/** 목록 카드 안 한 줄: 이름 · (오른쪽 상태) · › */
@Composable
private fun ListLine(title: String, trailing: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tap(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
    }
}

/** 작은 연회색 줄 (정책 검색 '분야별로 보기' 카드와 같은 모양). trailing 이 있으면 오른쪽에 붙인다 */
@Composable
private fun SmallDocRow(
    title: String,
    description: String?,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = if (trailing != null) 10.dp else 16.dp, top = 14.dp, bottom = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** 목록 한 줄: 연회색 카드 (홈 다음 일정과 같은 모양, 아이콘 없음) */
@Composable
private fun GrayRow(title: String, description: String?, onClick: () -> Unit) {
    SubtleCard(Modifier.fillMaxWidth().heightIn(min = 60.dp), onClick = onClick, contentPadding = PaddingValues(16.dp)) {
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
