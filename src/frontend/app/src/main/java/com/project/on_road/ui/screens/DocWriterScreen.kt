package com.project.on_road.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppState
import com.project.on_road.data.remote.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import java.io.File

private val BubbleLine = OnRoadColors.BorderSage.copy(alpha = 0.4f)

/**
 * 서류 작성 (문서 AI). documentId 로 이어 쓰거나, policyId(정책 신청서)·templateId(앱 양식)로 새로 시작한다.
 * 위: 진행률 + [대화로 채우기 | 칸별로 쓰기]
 */
@Composable
fun DocWriterScreen(
    documentId: Long?,
    policyId: Long?,
    templateId: String?,
    checklistItemId: Long? = null,
    context: String? = null,
    onBack: () -> Unit,
    vm: DocWriterViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        vm.attach(ctx)
        vm.start(documentId, policyId, templateId, checklistItemId, context)
    }
    val d = vm.doc

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(d?.title ?: "서류 작성", onBack)
        when {
            vm.loading -> LoadingState("서류를 준비하고 있어요")
            d == null -> Column(Modifier.padding(OnRoadDimens.ScreenPadding)) {
                NoticeBox(vm.error ?: "잠시 후 다시 시도해 주세요.", title = "서류를 열 수 없어요", tone = NoticeTone.Warning)
            }
            else -> {
                Header(d, vm)
                when (vm.mode) {
                    WriteMode.TALK -> TalkPane(vm, Modifier.weight(1f))
                    WriteMode.FIELDS -> FieldsPane(vm, Modifier.weight(1f))
                }
            }
        }
    }
}

/* ---------------------------- 위: 진행률·모드 ---------------------------- */

@Composable
private fun Header(d: WriterDocumentDto, vm: DocWriterViewModel) {
    Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding).padding(top = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (d.status == "exported") "내보낸 서류" else "작성한 칸",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextSlateMid,
                modifier = Modifier.weight(1f),
            )
            Text("${d.progress.completed}", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Text("/${d.progress.total}칸", style = OnRoadType.Tiny, color = OnRoadColors.TextSlateMid, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(d.progress.ratio)
        Spacer(Modifier.height(14.dp))
        SegmentedTabs(
            options = listOf("대화로 채우기", "칸별로 쓰기"),
            selected = vm.mode.ordinal,
            onSelect = { vm.switchMode(WriteMode.entries[it]) },
        )
    }
    HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
}

/* ---------------------------- 대화로 채우기 ---------------------------- */

@Composable
private fun TalkPane(vm: DocWriterViewModel, modifier: Modifier) {
    val list = rememberLazyListState()
    val context = LocalContext.current
    LaunchedEffect(vm.bubbles.size, vm.busy) {
        if (vm.bubbles.isNotEmpty()) list.animateScrollToItem(vm.bubbles.size - 1 + if (vm.busy) 1 else 0)
    }
    Column(modifier) {
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = OnRoadDimens.ScreenPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(vm.bubbles, key = { it.id }) { b ->
                when (b) {
                    is DocBubble.Me -> MyBubble(b.text)
                    is DocBubble.Ai -> AiBubble(
                        b,
                        pdfReady = vm.pdf != null,
                        onOpenPdf = { openPdf(context, vm.pdf) ?: vm.redownload() },
                        onFix = { id -> vm.select(id); vm.switchMode(WriteMode.FIELDS) },
                    )
                }
            }
            if (vm.busy) item(key = "typing") { TypingLine() }
        }
        val replies = vm.quickReplies()
        if (replies.isNotEmpty() && !vm.busy && vm.question != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                replies.forEach { r -> ChoiceChip(r, selected = false) { vm.send(r) } }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.BorderInput)
        PromptBar(
            value = vm.input,
            onValueChange = { vm.input = it },
            placeholder = if (vm.question == null) "궁금한 걸 물어봐도 돼요" else "답을 입력하세요",
            enabled = !vm.busy && vm.question != null,
            onSend = { vm.send() },
            onVoice = { vm.input = (vm.input + " " + it).trim() },
        )
    }
}

@Composable
private fun AiBubble(b: DocBubble.Ai, pdfReady: Boolean, onOpenPdf: () -> Unit, onFix: (String) -> Unit) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(28.dp).clip(OnRoadShapes.Field).background(OnRoadColors.SurfaceMuted).border(1.dp, BubbleLine, OnRoadShapes.Field),
            contentAlignment = Alignment.Center,
        ) { LogoSlot(size = 20.dp) }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(OnRoadShapes.Inner)
                .background(Color.White)
                .border(1.dp, BubbleLine, OnRoadShapes.Inner)
                .padding(14.dp),
        ) {
            if (b.text.isNotBlank()) {
                Text(b.text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextPrimary)
            }
            if (b.policies.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                b.policies.take(3).forEach { BulletText(it, OnRoadColors.Primary, style = OnRoadType.Caption2) }
            }
            b.contacts.forEach { c ->
                Spacer(Modifier.height(8.dp))
                SmallBoxButton("${c.name} ${c.phone}", { runCatching { uri.openUri("tel:${c.phone}") } }, leadingIcon = Icons.Outlined.Call)
            }
            b.problems.forEach { p ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(OnRoadShapes.Field).background(OnRoadColors.DangerTint).clickable { onFix(p.fieldId) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Danger)
                        Text(p.message, style = OnRoadType.Micro, color = OnRoadColors.TextSlateDark)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "고치기", tint = OnRoadColors.Danger, modifier = Modifier.size(16.dp))
                }
            }
            if (b.exported) {
                Spacer(Modifier.height(12.dp))
                OnRoadButton(
                    if (pdfReady) "PDF 열기" else "PDF 받는 중…", onOpenPdf, Modifier.fillMaxWidth(),
                    height = 40.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    leadingIcon = Icons.Outlined.Description, enabled = pdfReady,
                )
            }
        }
    }
}

@Composable
private fun MyBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .widthIn(max = 260.dp)
                .clip(OnRoadShapes.Inner)
                .background(Color.White)
                .border(1.dp, OnRoadColors.Primary, OnRoadShapes.Inner)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) { Text(text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.Primary) }
    }
}

@Composable
private fun TypingLine() {
    Text("적고 있어요…", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(start = 38.dp))
}

/* ---------------------------- 칸별로 쓰기 ---------------------------- */

@Composable
private fun FieldsPane(vm: DocWriterViewModel, modifier: Modifier) {
    val d = vm.doc ?: return
    val context = LocalContext.current
    val list = rememberLazyListState()
    val selected = vm.field(vm.selectedId)
    // 양식 순서대로: 앱에서 쓰는 칸 + 출력 후 손으로 쓰는 칸 (공무원 기재란은 뺀다)
    val shown = d.fields.filter { it.writable || it.valueSource == "handwrite" }

    LaunchedEffect(vm.selectedId) {
        val i = shown.indexOfFirst { it.fieldId == vm.selectedId }
        if (i >= 0) list.animateScrollToItem(i + 1) // 0번은 안내 묶음
    }
    Column(modifier) {
        // 상단 AI 프롬프트: 고른 칸에 바로 채운다
        if (selected != null && d.status != "exported") {
            Column(Modifier.background(OnRoadColors.SurfaceCool).padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = OnRoadColors.Primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("AI가 「${selected.name}」 칸에 써 드려요", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.Primary)
                }
                Spacer(Modifier.height(6.dp))
                PromptBar(
                    value = vm.prompt,
                    onValueChange = { vm.prompt = it },
                    placeholder = if (selected.essay) "예) 등록금이 부담된다는 내용으로 써 줘" else "예) 여기엔 뭘 써야 돼?",
                    enabled = !vm.busy && selected.writable && !selected.isCompleted,
                    onSend = vm::ask,
                    onVoice = { vm.prompt = (vm.prompt + " " + it).trim() },
                    padded = false,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "notices") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.notice?.let { n -> NoticeCard(n, onClose = vm::dismissNotice, onFix = vm::select) }
                    d.notices.forEach { NoticeBox(it) }
                }
            }
            items(shown, key = { it.fieldId }) { f ->
                if (!f.writable) HandwriteCard(f) else FieldCard(
                    f = f,
                    text = vm.text(f),
                    selected = f.fieldId == vm.selectedId,
                    locked = d.status == "exported",
                    busy = vm.busy,
                    onSelect = { vm.select(f.fieldId) },
                    onEdit = { vm.edit(f, it) },
                    onChoose = { vm.choose(f, it) },
                    onComplete = { vm.complete(f) },
                    onReopen = { vm.reopen(f) },
                    onUndo = { vm.undo(f) },
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
        Column(Modifier.background(Color.White).padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 12.dp)) {
            if (d.status == "exported") {
                OnRoadButton(
                    if (vm.pdf != null) "PDF 열기" else "PDF 받기",
                    { openPdf(context, vm.pdf) ?: vm.redownload() },
                    Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Outlined.Description,
                )
                vm.exported?.checklist?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("체크리스트의 '${it.label}'도 체크했어요.", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
                }
            } else {
                OnRoadButton("검사하고 내보내기", vm::export, Modifier.fillMaxWidth(), loading = vm.busy)
                Spacer(Modifier.height(6.dp))
                Text("모든 칸을 완료하면 원래 양식에 채운 PDF로 내보내요.", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
            }
        }
    }
}

/** 칸 카드: 고른 칸은 진한 테두리, 완료한 칸은 회색 바탕 */
@Composable
private fun FieldCard(
    f: DocFieldDto,
    text: String,
    selected: Boolean,
    locked: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onEdit: (String) -> Unit,
    onChoose: (String) -> Unit,
    onComplete: () -> Unit,
    onReopen: () -> Unit,
    onUndo: () -> Unit,
) {
    val done = f.isCompleted
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = if (done) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
        borderColor = if (selected) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
        borderWidth = if (selected) 2.dp else 1.dp,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        onClick = onSelect,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                    if (f.required == true && !done) {
                        Spacer(Modifier.width(6.dp))
                        Text("필수", style = OnRoadType.Tiny, color = OnRoadColors.Required)
                    }
                }
                if (f.easyLabel != null && f.easyLabel != f.label) {
                    Text("양식: ${f.label}", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                }
            }
            if (done) OnRoadTag("완료", style = TagStyle.PrimaryFilled, textStyle = OnRoadType.Tiny)
            else SpeakButton("${f.name}. ${f.guide}", modifier = Modifier.size(28.dp))
        }
        // 칸별 안내: 쓰는 법 · 예시 · 주의 (완료한 칸은 고를 때만)
        if (!done || selected) {
            Spacer(Modifier.height(6.dp))
            Text(f.guide, style = OnRoadType.Caption2.copy(lineHeight = OnRoadType.Caption.lineHeight), color = OnRoadColors.TextSlateDark)
            f.example?.let {
                Spacer(Modifier.height(2.dp))
                Text("예) $it", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
            }
            f.outline?.takeIf { it.isNotEmpty() && f.essay }?.let {
                Spacer(Modifier.height(2.dp))
                Text("순서: ${it.joinToString(" → ")}", style = OnRoadType.Micro, color = OnRoadColors.Primary)
            }
            f.caution?.let {
                Spacer(Modifier.height(8.dp))
                NoticeBox(it, tone = NoticeTone.Warning)
            }
        }
        f.reusedFrom?.let {
            Spacer(Modifier.height(4.dp))
            Text("'$it'에서 가져왔어요", style = OnRoadType.Micro, color = OnRoadColors.Primary)
        }

        Spacer(Modifier.height(10.dp))
        when {
            done && !selected -> Text(
                text.ifBlank { "(비워 둠)" },
                style = OnRoadType.Caption,
                color = if (text.isBlank()) OnRoadColors.TextQuaternary else OnRoadColors.TextSlateDark,
                maxLines = 3,
            )
            !f.options.isNullOrEmpty() -> FlowChips(f.options, text.split(", "), enabled = !done && !locked, onChoose = onChoose)
            else -> OnRoadTextField(
                value = text,
                onValueChange = onEdit,
                placeholder = if (f.essay) "직접 쓰거나 위 AI 칸에 내용을 말해 주세요" else "",
                singleLine = !(f.multiline || f.essay),
                minLines = if (f.multiline || f.essay) 4 else 1,
                enabled = !done && !locked,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        f.tip?.takeIf { selected && !done }?.let {
            Spacer(Modifier.height(10.dp))
            NoticeBox(it, title = "AI 안내")
        }
        f.warnings.takeIf { it.isNotEmpty() && !done }?.let { w ->
            Spacer(Modifier.height(8.dp))
            w.forEach { Text("· $it", style = OnRoadType.Micro, color = OnRoadColors.Danger) }
        }

        if (selected && !locked) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (f.canUndo && !done) SmallBoxButton("되돌리기", onUndo, leadingIcon = Icons.AutoMirrored.Outlined.Undo)
                Spacer(Modifier.weight(1f))
                if (done) {
                    OnRoadButton("다시 고치기", onReopen, height = 36.dp, variant = ButtonVariant.Outline,
                        textStyle = OnRoadType.Caption, leadingIcon = Icons.Outlined.EditNote, enabled = !busy)
                } else {
                    OnRoadButton("완료", onComplete, height = 36.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                        leadingIcon = Icons.Filled.Check, enabled = !busy && (text.isNotBlank() || f.required != true))
                }
            }
        }
    }
}

/** 출력한 뒤 손으로 쓰는 칸 (주민등록번호·서명 등): 앱에 입력하지 않고 쓰는 법만 */
@Composable
private fun HandwriteCard(f: DocFieldDto) {
    SubtleCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSlateDark, modifier = Modifier.weight(1f))
            OnRoadTag("출력 후 직접", style = TagStyle.Warning, textStyle = OnRoadType.Tiny)
        }
        Spacer(Modifier.height(6.dp))
        Text(f.guide, style = OnRoadType.Caption2, color = OnRoadColors.TextSlateDark)
        Spacer(Modifier.height(4.dp))
        Text("개인정보라 앱에 입력하지 않아요. 내보낸 PDF를 출력해서 직접 써 주세요.", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
    }
}

/** 선택지 버튼 (줄바꿈) */
@Composable
private fun FlowChips(options: List<String>, chosen: List<String>, enabled: Boolean, onChoose: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { o -> ChoiceChip(o, selected = o in chosen) { if (enabled) onChoose(o) } }
            }
        }
    }
}

@Composable
private fun NoticeCard(n: DocBubble.Ai, onClose: () -> Unit, onFix: (String) -> Unit) {
    val uri = LocalUriHandler.current
    val warn = n.problems.isNotEmpty() || n.contacts.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Field)
            .background(if (warn) OnRoadColors.DangerTint else OnRoadColors.SurfaceCool)
            .border(1.dp, if (warn) OnRoadColors.Danger.copy(alpha = 0.35f) else OnRoadColors.BorderSlate, OnRoadShapes.Field)
            .padding(14.dp),
    ) {
        Row {
            Text(n.text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextSlateDark, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Close, "닫기", tint = OnRoadColors.TextMuted, modifier = Modifier.size(18.dp).clickable(onClick = onClose))
        }
        n.policies.take(3).forEach { BulletText(it, OnRoadColors.Primary, style = OnRoadType.Caption2) }
        n.contacts.forEach { c ->
            Spacer(Modifier.height(6.dp))
            SmallBoxButton("${c.name} ${c.phone}", { runCatching { uri.openUri("tel:${c.phone}") } }, leadingIcon = Icons.Outlined.Call)
        }
        n.problems.forEach { p ->
            Spacer(Modifier.height(6.dp))
            Text(
                "· ${p.label}: ${p.message}",
                style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.Danger,
                modifier = Modifier.clickable { onFix(p.fieldId) },
            )
        }
    }
}

/* ---------------------------- 공통 ---------------------------- */

/** 입력창 + 마이크 + 보내기 (상담 화면 입력창과 같은 모양) */
@Composable
private fun PromptBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    onSend: () -> Unit,
    onVoice: (String) -> Unit,
    padded: Boolean = true,
) {
    val listen = rememberSpeechInput(onVoice)
    val active = enabled && value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (padded) Color.White else Color.Transparent)
            .then(if (padded) Modifier.padding(horizontal = 20.dp, vertical = 14.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(OnRoadShapes.Inner)
                .background(if (padded) OnRoadColors.SurfaceMuted else Color.White)
                .border(1.dp, OnRoadColors.BorderInput, OnRoadShapes.Inner)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                maxLines = 4,
                enabled = enabled,
                textStyle = OnRoadType.Caption.copy(color = OnRoadColors.TextPrimary),
                cursorBrush = SolidColor(OnRoadColors.Primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, style = OnRoadType.Caption, color = OnRoadColors.TextSlate)
                    inner()
                },
            )
            if (AppState.voiceOn && enabled) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.Mic, "말로 입력", tint = OnRoadColors.TextSlate, modifier = Modifier.size(20.dp).clickable(onClick = listen))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(OnRoadShapes.Field)
                .background(if (active) OnRoadColors.PrimaryDeep else OnRoadColors.SurfaceMuted)
                .clickable(enabled = active, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowUpward, "보내기", tint = if (active) Color.White else OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp))
        }
    }
}

/** 받은 PDF 를 PDF 보기 앱으로 연다. 열 앱이 없으면 공유 창. 파일이 아직 없으면 null */
private fun openPdf(context: Context, file: File?): Unit? {
    file ?: return null
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "서류 보내기"))
    }
    return Unit
}
