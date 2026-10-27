package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.remote.DocumentSummaryDto
import com.project.on_road.data.remote.TemplateSummaryDto
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

class FormHelperViewModel : ViewModel() {
    var templates by mutableStateOf<List<TemplateSummaryDto>?>(null)
        private set
    var documents by mutableStateOf<List<DocumentSummaryDto>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            runCatching { AppContainer.documents.templates(AppState.stage) }
                .onSuccess { templates = it; error = null }
                .onFailure { templates = emptyList(); error = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." }
            documents = runCatching { AppContainer.documents.list() }.getOrDefault(emptyList())
        }
    }
}

/** 서류 작성 도우미: 쓰던 서류 이어 쓰기 + 앱 양식 고르기 (정책 신청서는 체크리스트에서 시작) */
@Composable
fun FormHelperScreen(
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onStartTemplate: (String) -> Unit,
    vm: FormHelperViewModel = viewModel(),
) {
    // 작성 화면에서 돌아올 때마다 진행률을 새로 받는다
    LaunchedEffect(Unit) { vm.load() }

    OnRoadScreen("서류 작성 도우미", onBack) {
        ScreenHeadline("대화하듯 답하면", "AI가 서류를 채워 드려요.")
        Spacer(Modifier.height(8.dp))
        Text(
            "정책 신청서는 체크리스트의 '신청서 쓰기'에서 시작할 수 있어요.",
            style = OnRoadType.Caption2,
            color = OnRoadColors.TextMuted,
        )
        vm.error?.let {
            Spacer(Modifier.height(16.dp))
            NoticeBox(it, title = "불러오지 못했어요", tone = NoticeTone.Warning)
        }

        if (vm.documents.isNotEmpty()) {
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SectionHeader("쓰던 서류")
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.documents.forEach { d ->
                    val state = if (d.status == "exported") "내보냄" else "${d.progress.completed}/${d.progress.total}칸 작성"
                    ListRow(d.title, state, icon = Icons.Outlined.EditNote) { onOpenDocument(d.documentId) }
                }
            }
        }

        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SectionHeader("자주 쓰는 양식")
        Spacer(Modifier.height(16.dp))
        val t = vm.templates
        if (t == null) {
            LoadingState("양식을 불러오고 있어요")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.forEach { ListRow(it.name, it.description, icon = Icons.Outlined.Description) { onStartTemplate(it.templateId) } }
            }
        }
    }
}
