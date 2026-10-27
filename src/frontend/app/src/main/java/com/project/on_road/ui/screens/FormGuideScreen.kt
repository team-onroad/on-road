package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.FieldSource
import com.project.on_road.data.FormGuideItem
import com.project.on_road.data.FormGuideView
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.LoadingState
import com.project.on_road.ui.components.NoticeBox
import com.project.on_road.ui.components.NoticeTone
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch

class FormGuideViewModel : ViewModel() {
    var loading by mutableStateOf(true)
        private set
    var guide by mutableStateOf<FormGuideView?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loadedId: String? = null

    fun load(policyId: String) {
        if (loadedId == policyId) return
        loadedId = policyId
        viewModelScope.launch {
            loading = true
            runCatching { AppContainer.api.getFormGuide(policyId) }
                .onSuccess { guide = it; error = null }
                .onFailure { error = "신청서 안내를 불러오지 못했어요. 잠시 후 다시 시도해 주세요." }
            loading = false
        }
    }
}

/** 신청서 작성 도우미 (api.md 3.24). 칸마다 쓰는 법·예시·미리 채운 값을 보여준다. 작성은 공식 양식에서 직접 한다. */
@Composable
fun FormGuideScreen(
    policyId: String,
    onBack: () -> Unit,
    vm: FormGuideViewModel = viewModel(),
) {
    LaunchedEffect(policyId) { vm.load(policyId) }
    val g = vm.guide
    val error = vm.error

    OnRoadScreen("신청서 작성 도우미", onBack) {
        when {
            vm.loading -> LoadingState("신청서 안내를 준비하고 있어요")
            error != null -> NoticeBox(error, title = "안내를 열 수 없어요", tone = NoticeTone.Warning)
            g == null -> EmptyState(
                icon = Icons.Filled.Description,
                title = "아직 준비 중인 신청서예요",
                message = "이 정책의 신청서 안내는 곧 추가될 예정이에요. 신청 방법은 공식 원문에서 확인해 주세요.",
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(g.title, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
                g.notices.forEach { NoticeBox(it, tone = if (g.checked) NoticeTone.Info else NoticeTone.Warning) }
                if (g.notes.isNotEmpty()) {
                    NoticeBox(g.notes.joinToString("\n") { "· $it" }, title = "양식 유의사항")
                }

                var lastSection: String? = "\u0000"
                g.items.forEach { item ->
                    if (item.section != lastSection) {
                        lastSection = item.section
                        Spacer(Modifier.height(8.dp))
                        Text(
                            item.section ?: "작성 항목",
                            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                            color = OnRoadColors.TextSlateMid,
                        )
                    }
                    FieldCard(item)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FieldCard(item: FormGuideItem) {
    OnRoadCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, style = OnRoadType.Body1.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextStrong, modifier = Modifier.weight(1f))
            if (item.required == true) {
                OnRoadTag("필수", style = TagStyle.Danger)
                Spacer(Modifier.width(6.dp))
            }
            OnRoadTag(
                when (item.source) {
                    FieldSource.PROFILE -> "미리 채움"
                    FieldSource.USER -> "직접 입력"
                    FieldSource.HANDWRITE -> "양식에 손으로"
                    FieldSource.OFFICE -> "비워 두기"
                },
                style = when (item.source) {
                    FieldSource.PROFILE -> TagStyle.PrimarySoft
                    FieldSource.HANDWRITE -> TagStyle.Warning
                    FieldSource.OFFICE -> TagStyle.Gray
                    FieldSource.USER -> TagStyle.Neutral
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(item.guide, style = OnRoadType.Caption, color = OnRoadColors.TextSlateDark)
        item.prefill?.let { ValueLine("내 정보", it, OnRoadColors.Primary) }
        item.example?.let { ValueLine("예시", it, OnRoadColors.TextSlateMid) }
        if (item.source == FieldSource.HANDWRITE) {
            Spacer(Modifier.height(6.dp))
            Text("개인정보라 앱에 입력하지 않아요. 종이 양식에 직접 적어 주세요.", style = OnRoadType.Tiny, color = OnRoadColors.TextMuted)
        }
        item.evidence?.let {
            Spacer(Modifier.height(6.dp))
            Text("양식 원문: “$it”", style = OnRoadType.Tiny, color = OnRoadColors.TextMuted)
        }
    }
}

@Composable
private fun ValueLine(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.height(6.dp))
    Row {
        Text(label, style = OnRoadType.Caption2, color = OnRoadColors.TextMuted, modifier = Modifier.width(48.dp))
        Text(value, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = color)
    }
}
