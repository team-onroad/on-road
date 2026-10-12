package com.project.on_road.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
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

    init {
        viewModelScope.launch {
            templates = runCatching { AppContainer.coachApi.formTemplates(stage) }.getOrDefault(emptyList())
        }
    }

    fun openTemplate(id: String) = load { AppContainer.coachApi.analyzeTemplate(id) }

    fun upload(bytes: ByteArray, mime: String) = load { AppContainer.coachApi.analyzeUpload(userId, bytes, mime) }

    private fun load(block: suspend () -> FormDraft) {
        analyzing = true
        error = null
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { d ->
                    values.clear()
                    draft = d
                }
                .onFailure { error = "양식을 읽지 못했어요. 사진이 선명한지 확인하고 다시 올려 주세요." }
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
fun FormHelperScreen(onBack: () -> Unit, vm: FormHelperViewModel = viewModel()) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) vm.upload(bytes, mime)
        }
    }
    val back = { if (vm.draft != null) vm.close() else onBack() }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(vm.draft?.title ?: "서류 작성 도우미", back)
        val d = vm.draft
        when {
            vm.analyzing -> LoadingState("양식을 읽고 작성할 항목을 정리하고 있어요")
            d != null -> DraftEditor(d, vm) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, d.title)
                    putExtra(Intent.EXTRA_TEXT, vm.exportText())
                }
                context.startActivity(Intent.createChooser(send, "작성한 내용 보내기"))
            }
            else -> TemplateList(vm.templates, vm.error, onPick = vm::openTemplate, onUpload = { picker.launch("*/*") })
        }
    }
}

@Composable
private fun TemplateList(
    templates: List<FormTemplate>?,
    error: String?,
    onPick: (String) -> Unit,
    onUpload: () -> Unit,
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
        OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary, onClick = onUpload) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Outlined.UploadFile, tint = OnRoadColors.Primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("내 양식 올리기", style = OnRoadType.Title3, color = OnRoadColors.Primary)
                    Text("사진이나 PDF로 받은 신청서를 올려요", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                }
            }
            Spacer(Modifier.height(16.dp))
            OnRoadButton(
                "파일 선택하기", onUpload, Modifier.fillMaxWidth(),
                height = 40.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
            )
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            NoticeBox(it, title = "읽지 못했어요", tone = NoticeTone.Warning)
        }

        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        SectionHeader("자주 쓰는 양식", trailingText = "원래 양식 그대로 채워요")
        Spacer(Modifier.height(16.dp))
        if (templates == null) {
            LoadingState("양식을 불러오고 있어요")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { t -> ListRow(t.name, t.description, icon = Icons.Outlined.Description) { onPick(t.id) } }
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
                    "실제 양식과 다를 수 있어요. 작성한 내용은 원본 양식에 옮겨 적어 제출해 주세요.",
                    title = "AI가 읽은 항목이에요",
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
                if (d.originalExport) "원래 양식 파일로 내보내기는 서버 연결 후 지원돼요. 지금은 작성 내용을 보낼 수 있어요."
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
        if (field.essay && field.outline != null && value.isBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(OnRoadShapes.Badge)
                    .border(1.dp, OnRoadColors.Primary, OnRoadShapes.Badge)
                    .clickable { onChange(field.outline) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = OnRoadColors.Primary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
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
