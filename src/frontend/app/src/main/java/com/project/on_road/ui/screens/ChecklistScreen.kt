package com.project.on_road.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Checklist
import com.project.on_road.data.ChecklistStep
import com.project.on_road.data.StepType
import com.project.on_road.ui.components.CheckBoxMark
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.GuideBlock
import com.project.on_road.ui.components.LoadingState
import com.project.on_road.ui.components.NoticeBox
import com.project.on_road.ui.components.NoticeTone
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.OnRoadTopBar
import com.project.on_road.ui.components.ProgressBar
import com.project.on_road.ui.components.SmallBoxButton
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/* ======================= 목록 ======================= */

class ChecklistListViewModel : ViewModel() {
    var items by mutableStateOf<List<Checklist>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    fun load() {
        viewModelScope.launch {
            loading = items.isEmpty()
            items = runCatching {
                AppContainer.api.getChecklists(AppContainer.session.userId.orEmpty())
            }.getOrDefault(emptyList())
            loading = false
        }
    }
}

@Composable
fun ChecklistListScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onSearch: () -> Unit,
    vm: ChecklistListViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    OnRoadScreen("신청 체크리스트", onBack) {
        when {
            vm.loading -> LoadingState("불러오는 중이에요")
            vm.items.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Checklist,
                title = "아직 준비 중인 신청이 없어요",
                message = "정책 검색에서 ‘이 정책 신청 준비하기’를 누르면 여기에 추가돼요.",
                actionLabel = "정책 찾으러 가기",
                onAction = onSearch,
            )
            else -> {
                Text("나의 체크리스트", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("단계를 하나씩 체크하며 신청을 준비해요.", style = OnRoadType.Caption2, color = OnRoadColors.TextMuted)
                Spacer(Modifier.height(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.items.forEach { c -> ChecklistSummaryCard(c) { onOpen(c.policyId) } }
                }
            }
        }
    }
}

@Composable
private fun ChecklistSummaryCard(c: Checklist, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = if (c.completed) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.policyName,
                style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            OnRoadTag(
                if (c.completed) "완료" else "신청 준비 중",
                style = if (c.completed) TagStyle.PrimaryFilled else TagStyle.SlatePrimary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("진행 단계", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
            ProgressCount(c.doneCount, c.steps.size, "단계")
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(c.progress)
    }
}

@Composable
private fun ProgressCount(done: Int, total: Int, unit: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = OnRoadType.Headline.fontSize, fontWeight = FontWeight.Bold, color = OnRoadColors.TextPrimary)) { append("$done") }
            withStyle(SpanStyle(fontSize = OnRoadType.Tiny.fontSize, color = OnRoadColors.TextSlateMid)) { append("/$total$unit") }
        },
    )
}

/* ======================= 상세 ======================= */

class ChecklistViewModel : ViewModel() {
    private val api = AppContainer.api
    private val userId: String get() = AppContainer.session.userId.orEmpty()

    var checklist by mutableStateOf<Checklist?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saveState by mutableStateOf(SaveState.IDLE)
        private set

    /** 쉬운 말로 보는 단계 */
    val easy = mutableStateListOf<StepType>()

    private var loadedFor: String? = null
    private var saveJob: Job? = null

    fun load(policyId: String) {
        if (loadedFor == policyId) return
        loadedFor = policyId
        viewModelScope.launch {
            loading = true
            error = null
            try {
                checklist = api.getChecklist(userId, policyId) ?: api.createChecklist(userId, policyId)
            } catch (e: Exception) {
                error = "체크리스트를 불러오지 못했어요."
            } finally {
                loading = false
            }
        }
    }

    fun toggleStep(type: StepType) {
        val c = checklist ?: return
        update(c.copy(steps = c.steps.map { if (it.type == type) it.copy(done = !it.done) else it }))
    }

    fun toggleDoc(doc: String) {
        val c = checklist ?: return
        val docs = if (doc in c.checkedDocs) c.checkedDocs - doc else c.checkedDocs + doc
        update(c.copy(checkedDocs = docs))
    }

    fun toggleEasy(type: StepType) {
        if (type in easy) easy.remove(type) else easy.add(type)
    }

    /** 체크할 때마다 0.7초 뒤 자동 저장 */
    private fun update(next: Checklist) {
        checklist = next
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700L)
            saveState = SaveState.SAVING
            saveState = runCatching { api.saveChecklist(userId, next) }
                .fold(onSuccess = { SaveState.SAVED }, onFailure = { SaveState.FAILED })
        }
    }
}

/** 신청 체크리스트 (디자인: 체크리스트) */
@Composable
fun ChecklistScreen(
    policyId: String,
    onBack: () -> Unit,
    vm: ChecklistViewModel = viewModel(),
) {
    LaunchedEffect(policyId) { vm.load(policyId) }
    val uri = LocalUriHandler.current
    val c = vm.checklist
    val error = vm.error

    Column(Modifier.fillMaxSize()) {
        OnRoadTopBar("체크리스트", onBack)
        when {
            vm.loading -> LoadingState("체크리스트를 준비하고 있어요")
            error != null || c == null -> Column(Modifier.padding(OnRoadDimens.ScreenPadding)) {
                NoticeBox(error ?: "잠시 후 다시 시도해 주세요.", title = "체크리스트를 열 수 없어요", tone = NoticeTone.Warning)
            }
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OnRoadTag(
                            if (c.completed) "모든 단계를 마쳤어요" else "신청 준비 중",
                            style = TagStyle.SlatePrimary,
                            textStyle = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                            shape = OnRoadShapes.Field,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        SaveIndicator(vm.saveState)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(c.policyName, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "체크할 때마다 자동으로 저장돼요. 단계를 꼼꼼히 확인해 보세요.",
                        style = OnRoadType.Caption2,
                        color = OnRoadColors.TextMuted,
                    )

                    Spacer(Modifier.height(32.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("진행 단계", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
                        ProgressCount(c.doneCount, c.steps.size, "단계")
                    }
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(c.progress)

                    Spacer(Modifier.height(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        c.steps.forEachIndexed { i, step ->
                            StepCard(
                                index = i + 1,
                                step = step,
                                easy = step.type in vm.easy,
                                onToggle = { vm.toggleStep(step.type) },
                                onToggleEasy = { vm.toggleEasy(step.type) },
                            )
                        }
                    }

                    if (c.requiredDocs.isNotEmpty()) {
                        Spacer(Modifier.height(40.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("준비 서류", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
                            ProgressCount(c.checkedDocs.size, c.requiredDocs.size, "개")
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(c.checkedDocs.size / c.requiredDocs.size.toFloat())
                        Spacer(Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            c.requiredDocs.forEach { doc ->
                                DocRow(doc, checked = doc in c.checkedDocs) { vm.toggleDoc(doc) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(40.dp))
                GuideBlock(
                    "AI 가이드",
                    "모든 서류는 공고일 이후 발급본이어야 효력이 인정되는 경우가 많아요.\n" +
                        "신청 전 최종 조건과 제출 서류는 담당 기관에서 꼭 확인해 주세요.",
                )

                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(24.dp))
                    OnRoadButton(
                        text = "공식 사이트에서 신청하기",
                        onClick = { runCatching { uri.openUri(c.applyUrl) } },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = Icons.Filled.NorthEast,
                        shape = OnRoadShapes.Inner,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable { runCatching { uri.openUri("tel:129") } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("문의 보건복지상담센터", style = OnRoadType.Caption, color = OnRoadColors.TextSlateMid)
                        Spacer(Modifier.width(6.dp))
                        Text("129", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun SaveIndicator(state: SaveState) {
    val (text, color) = when (state) {
        SaveState.IDLE -> "" to OnRoadColors.TextMuted
        SaveState.SAVING -> "저장 중" to OnRoadColors.TextMuted
        SaveState.SAVED -> "저장됨" to OnRoadColors.Primary
        SaveState.FAILED -> "저장 실패" to OnRoadColors.Danger
    }
    if (text.isNotEmpty()) Text(text, style = OnRoadType.Micro, color = color)
}

/** 단계 카드: 완료 시 회색 바탕 + 녹색 테두리 (디자인의 '첨부 완료' 카드 스타일) */
@Composable
private fun StepCard(
    index: Int,
    step: ChecklistStep,
    easy: Boolean,
    onToggle: () -> Unit,
    onToggleEasy: () -> Unit,
) {
    val done = step.done
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = if (done) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
        borderColor = if (done) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$index. ${step.type.title}",
                style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (done) OnRoadTag("완료", style = TagStyle.PrimaryFilled, shape = OnRoadShapes.Badge)
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(targetState = easy, label = "stepText") { isEasy ->
            Text(
                if (isEasy) step.easyDescription else step.description,
                style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight),
                color = OnRoadColors.TextPrimary.copy(alpha = 0.65f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (easy) "원문 보기" else "쉬운 말로 보기",
                style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextSlateMid,
                modifier = Modifier.clickable(onClick = onToggleEasy).padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            SmallBoxButton(
                text = if (done) "완료 취소" else "이 단계 완료로 표시",
                onClick = onToggle,
                background = if (done) OnRoadColors.SurfaceMuted else OnRoadColors.Surface,
                borderColor = if (done) OnRoadColors.BorderSage else OnRoadColors.BorderDark,
                leadingIcon = if (done) null else Icons.Filled.Check,
            )
        }
    }
}

@Composable
private fun DocRow(doc: String, checked: Boolean, onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = if (checked) OnRoadColors.SurfaceFile else OnRoadColors.Surface,
        borderColor = if (checked) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckBoxMark(checked)
            Spacer(Modifier.width(12.dp))
            Text(
                doc,
                style = OnRoadType.Body2.copy(fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal),
                color = OnRoadColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (checked) Text("준비 완료", style = OnRoadType.Micro, color = OnRoadColors.Primary)
        }
    }
}
