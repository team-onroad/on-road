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
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

/** 준비 서류 하나: AI 와 쓸 수 있으면 정책 신청서(policyForm) 또는 앱 양식(templateId) */
data class DocChoice(val doc: ChecklistDoc, val policyForm: Boolean, val templateId: String?) {
    val writable: Boolean get() = policyForm || templateId != null
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
    vm: DocPickerViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load(policyId) }
    val c = vm.checklist

    OnRoadScreen("서류 작성", onBack) {
        when {
            vm.loading -> LoadingState("준비 서류를 불러오고 있어요")
            c == null -> NoticeBox(vm.error ?: "잠시 후 다시 시도해 주세요.", title = "불러오지 못했어요", tone = NoticeTone.Warning)
            else -> {
                ScreenHeadline(c.policyName, "어떤 서류를 준비할까요?")
                Spacer(Modifier.height(8.dp))
                Text(
                    "신청서처럼 직접 쓰는 서류는 AI와 함께 쓰고, 증명서는 기관에서 발급받아요.",
                    style = OnRoadType.Caption2,
                    color = OnRoadColors.TextMuted,
                )

                val writable = vm.choices.filter { it.writable }
                val issued = vm.choices.filter { !it.writable }

                if (writable.isNotEmpty()) {
                    Spacer(Modifier.height(OnRoadDimens.BlockGap))
                    SectionHeader("AI와 함께 쓰는 서류", titleStyle = OnRoadType.Title3)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        writable.forEach { ch ->
                            WritableDocCard(ch) {
                                onWrite(if (ch.policyForm) c.policyId.toLongOrNull() else null, ch.templateId, ch.doc.itemId)
                            }
                        }
                    }
                }

                if (issued.isNotEmpty()) {
                    Spacer(Modifier.height(OnRoadDimens.BlockGap))
                    SectionHeader("발급받아 준비하는 서류", titleStyle = OnRoadType.Title3)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        issued.forEach { IssuedDocCard(it) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Footnote("발급받은 서류는 체크리스트의 준비 서류에서 체크해 주세요.")
                }
            }
        }
    }
}

@Composable
private fun WritableDocCard(ch: DocChoice, onWrite: () -> Unit) {
    val done = ch.doc.checked
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
                Text(
                    if (done) "작성해서 PDF로 내보냈어요" else "묻는 말에 답하면 원래 양식에 채워 드려요",
                    style = OnRoadType.Micro,
                    color = OnRoadColors.TextTertiary,
                )
            }
            if (done) OnRoadTag("완료", style = TagStyle.PrimaryFilled, textStyle = OnRoadType.Tiny)
        }
        Spacer(Modifier.height(12.dp))
        OnRoadButton(
            if (done) "다시 보기" else "AI와 쓰기", onWrite, Modifier.fillMaxWidth(),
            variant = if (done) ButtonVariant.Outline else ButtonVariant.Primary,
            height = 38.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
        )
    }
}

@Composable
private fun IssuedDocCard(ch: DocChoice) {
    SubtleCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.name, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateDark, modifier = Modifier.weight(1f))
            when {
                ch.doc.notApplicable -> OnRoadTag("해당 없음", style = TagStyle.Gray, textStyle = OnRoadType.Tiny)
                ch.doc.checked -> OnRoadTag("준비 완료", style = TagStyle.PrimarySoft, textStyle = OnRoadType.Tiny)
            }
        }
        ch.doc.condition?.let {
            Spacer(Modifier.height(4.dp))
            Text("필요한 경우: $it", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
        }
    }
}
