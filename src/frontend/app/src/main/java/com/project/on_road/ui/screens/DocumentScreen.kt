package com.project.on_road.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class DocumentViewModel : ViewModel() {
    val types: List<DocType> = DocType.entries.filter { AppState.stage in it.stages }
    var typeIndex by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
        private set
    var doc by mutableStateOf<GeneratedDoc?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 섹션·문장 위치별 사용자가 고친 내용 */
    val edits = mutableStateMapOf<String, String>()

    val type: DocType? get() = types.getOrNull(typeIndex)

    fun select(i: Int) {
        typeIndex = i
        doc = null
        error = null
        edits.clear()
    }

    /** 3.22 초안 만들기. 서버에 없는 앱 기록(저장한 정책 수, 흥미 탐색 결과)을 함께 보낸다 */
    fun generate() {
        val t = type ?: return
        val session = AppContainer.session
        val userId = session.userId.orEmpty()
        loading = true
        error = null
        viewModelScope.launch {
            val records = AppRecords(
                savedPolicyCount = runCatching { AppContainer.api.getSavedPolicyIds(userId).size }.getOrDefault(0),
                interestSummary = AppRecords.interestSentence(session.interestTypes),
            )
            runCatching { AppContainer.coachApi.generateDocument(userId, t, records) }
                .onSuccess {
                    doc = it
                    edits.clear()
                }
                .onFailure { error = it.userMessage("초안을 만들지 못했어요. 잠시 후 다시 시도해 주세요.") }
            loading = false
        }
    }

    fun text(key: String, original: DocSentence): String =
        edits[key] ?: if (original.source == null) "" else original.text

    fun exportText(): String {
        val d = doc ?: return ""
        return buildString {
            appendLine(d.type.label)
            d.sections.forEachIndexed { si, s ->
                appendLine()
                appendLine("■ ${s.title}")
                s.sentences.forEachIndexed { i, sen -> text("$si-$i", sen).takeIf { it.isNotBlank() }?.let { appendLine(it) } }
            }
        }.trim()
    }
}

/** 이력서·자기소개서 (청소년은 활동 기록) */
@Composable
fun DocumentScreen(onBack: () -> Unit, vm: DocumentViewModel = viewModel()) {
    val context = LocalContext.current
    val d = vm.doc

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(if (AppState.stage == UserStage.TEEN) "활동 기록 만들기" else "이력서·자기소개서", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            if (vm.types.size > 1) {
                SegmentedTabs(vm.types.map { it.label }, vm.typeIndex, vm::select)
                Spacer(Modifier.height(24.dp))
            }
            when {
                vm.types.isEmpty() -> EmptyState(Icons.Outlined.Edit, "지금 단계에서는 쓸 수 없어요", "청소년·자립준비청년 화면에서 쓸 수 있어요.")
                vm.loading -> LoadingState("앱에 쌓인 기록으로 초안을 만들고 있어요")
                d == null -> {
                    vm.error?.let {
                        NoticeBox(it, title = "초안을 만들지 못했어요", tone = NoticeTone.Warning)
                        Spacer(Modifier.height(16.dp))
                    }
                    ScreenHeadline("앱에 쌓인 기록으로", "${vm.type?.label} 초안을 만들어요.")
                    Spacer(Modifier.height(24.dp))
                    OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary) {
                        Text("${vm.type?.label} 초안 만들기", style = OnRoadType.Title3, color = OnRoadColors.Primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "체크리스트 완료, 실천 과제, 진로 탐색 기록을 바탕으로 만들어요. " +
                                    "앱에 기록된 내용만 쓰고, 없는 경험은 만들지 않아요.",
                            style = OnRoadType.Caption,
                            color = OnRoadColors.TextTertiary,
                        )
                        Spacer(Modifier.height(16.dp))
                        OnRoadButton("초안 만들기", vm::generate, Modifier.fillMaxWidth(), height = 40.dp, textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold))
                    }
                }
                else -> {
                    // AI 초안 안내 (1.6)
                    NoticeBox("${d.notice}\n직접 확인하고 고쳐서 쓰세요.", title = "AI가 만든 초안이에요")
                    vm.error?.let {
                        Spacer(Modifier.height(12.dp))
                        NoticeBox(it, title = "다시 만들지 못했어요", tone = NoticeTone.Warning)
                    }
                    d.sections.forEachIndexed { si, s ->
                        Spacer(Modifier.height(OnRoadDimens.BlockGap))
                        SubHeader(s.title)
                        Spacer(Modifier.height(12.dp))
                        s.sentences.forEachIndexed { i, sen ->
                            val key = "$si-$i"
                            OnRoadTextField(
                                value = vm.text(key, sen),
                                onValueChange = { vm.edits[key] = it },
                                placeholder = sen.text,
                                singleLine = false,
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (sen.source != null) {
                                    Icon(Icons.Outlined.Link, null, tint = OnRoadColors.Primary, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("근거: ${sen.source}", style = OnRoadType.Micro, color = OnRoadColors.Primary)
                                } else {
                                    Icon(Icons.Outlined.Edit, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("직접 채워 주세요", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OnRoadButton("다시 만들기", vm::generate, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
        if (d != null && !vm.loading) {
            HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
            Column(Modifier.background(Color.White).padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 12.dp)) {
                OnRoadButton("완성본 보내기", {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, vm.exportText())
                    }
                    context.startActivity(Intent.createChooser(send, "보내기"))
                }, Modifier.fillMaxWidth())
            }
        }
    }
}