package com.project.on_road.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import com.project.on_road.ui.components.ChoiceChip
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
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.ApplyMethod
import com.project.on_road.data.Checklist
import com.project.on_road.data.ChecklistDoc
import com.project.on_road.data.ChecklistStep
import com.project.on_road.data.ChecklistSummary
import com.project.on_road.data.Policy
import com.project.on_road.data.StepType
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.CheckBoxMark
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.GuideBlock
import com.project.on_road.ui.components.LoadingState
import com.project.on_road.ui.components.NoticeBox
import com.project.on_road.ui.components.NoticeTone
import com.project.on_road.ui.components.ButtonVariant
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/* ======================= 목록 ======================= */

class ChecklistListViewModel : ViewModel() {
    var items by mutableStateOf<List<ChecklistSummary>>(emptyList())
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
private fun ChecklistSummaryCard(c: ChecklistSummary, onClick: () -> Unit) {
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
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag(c.policy.category, style = TagStyle.Neutral)
            if (!c.policy.isActive) {
                Spacer(Modifier.width(6.dp))
                OnRoadTag("더 이상 운영되지 않는 정책", style = TagStyle.Danger)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("진행 단계", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
            ProgressCount(c.doneSteps, c.totalSteps, "단계")
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

/**
 * 준비 서류를 사람마다 다르게 하는 신청 상황 (서류 condition 으로 나눈다).
 * 켜면 그 상황의 서류를 보여 주고, 끄면 숨기고 '해당 없음'으로 둔다 (서류 진행률에서 빠짐)
 */
enum class DocSituation(val label: String) {
    PROXY("대리인이 신청"),
    MAIL("우편·팩스로 신청"),
    SEIZURE("압류방지통장 사용"),
}

fun situationOf(condition: String?): DocSituation? {
    val c = condition ?: return null
    return when {
        "대리" in c -> DocSituation.PROXY
        c.startsWith("방문 신청 시") -> null // 기본 서류 (방문 신청)
        "우편" in c || "팩스" in c -> DocSituation.MAIL
        "압류방지" in c -> DocSituation.SEIZURE
        else -> null
    }
}

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

    /** 대상 확인 카드용 정책 정보 (지원 내용·기간). 못 불러와도 체크리스트는 그대로 쓴다 */
    var policyInfo by mutableStateOf<Policy?>(null)
        private set

    /** 켜 둔 신청 상황. 처음 연 체크리스트는 모두 꺼 둔다 (본인이 직접 방문 신청) */
    val situations = mutableStateMapOf<DocSituation, Boolean>()
    private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("onroad_doc_situations", Context.MODE_PRIVATE)
    }

    private fun key(checklistId: Long, s: DocSituation) = "c${checklistId}_${s.name}"

    /** 체크리스트를 받은 뒤: 저장된 상황(없으면 꺼짐)에 맞춰 서류의 '해당 없음'을 맞춘다 */
    private fun applySituations(c: Checklist) {
        situations.clear()
        val present = c.docs.mapNotNull { situationOf(it.condition) }.distinct()
        present.forEach { s ->
            val k = key(c.id, s)
            val stored = prefs?.takeIf { it.contains(k) }?.getBoolean(k, false)
            val on = stored ?: false
            situations[s] = on
            if (stored == null) {
                prefs?.edit()?.putBoolean(k, false)?.apply()
                setGroup(c, s, on = false)
            }
        }
    }

    fun toggleSituation(s: DocSituation) {
        val c = checklist ?: return
        val on = situations[s] != true
        situations[s] = on
        prefs?.edit()?.putBoolean(key(c.id, s), on)?.apply()
        setGroup(c, s, on)
    }

    private fun setGroup(c: Checklist, s: DocSituation, on: Boolean) {
        c.docs.filter { situationOf(it.condition) == s && it.notApplicable == on && !it.written }.forEach { d ->
            updateDoc(d.itemId) { if (on) it.copy(notApplicable = false) else it.copy(notApplicable = true, checked = false) }
            send(d.itemId, notApplicable = !on)
        }
    }

    /** 지금 상황에 맞는 서류 (꺼 둔 상황의 서류는 숨김) */
    fun visibleDocs(c: Checklist): List<ChecklistDoc> =
        c.docs.filter { d -> situationOf(d.condition)?.let { situations[it] == true } ?: true }

    private var loadedFor: String? = null
    /** 항목별 보내기 대기 (연속 입력은 0.3초 모아서 마지막 값만 보냄) */
    private val pending = mutableMapOf<Long, Job>()

    /** 3.9 이 정책의 체크리스트 열기 (없으면 서버가 새로 만든다) */
    fun load(policyId: String) {
        if (loadedFor == policyId) {
            // 다른 화면(서류 작성 등)에서 돌아오면 조용히 새로 받는다
            val c = checklist ?: return
            viewModelScope.launch { runCatching { api.getChecklist(userId, c.id) }.onSuccess { checklist = it } }
            return
        }
        loadedFor = policyId
        viewModelScope.launch {
            loading = true
            error = null
            try {
                checklist = api.openChecklist(userId, policyId)
                checklist?.let(::applySituations)
                policyInfo = runCatching { api.getPolicy(policyId) }.getOrNull()
            } catch (e: Exception) {
                error = e.userMessage("체크리스트를 불러오지 못했어요.")
            } finally {
                loading = false
            }
        }
    }

    fun toggleStep(step: ChecklistStep) {
        val c = checklist ?: return
        val next = !step.done
        checklist = c.copy(steps = c.steps.map { if (it.itemId == step.itemId) it.copy(done = next) else it })
        send(step.itemId, checked = next)
    }

    /** 서류 체크. 해당 없음인 서류를 체크하면 해당 없음이 풀린다 */
    fun toggleDoc(doc: ChecklistDoc) {
        if (doc.written) return // 문서 AI 로 쓴 서류는 서류 작성 화면에서만 바뀐다
        val next = !doc.checked || doc.notApplicable
        updateDoc(doc.itemId) { it.copy(checked = next, notApplicable = false) }
        send(doc.itemId, checked = next)
    }

    /** "해당 없음" (조건이 있는 서류만). 고르면 체크가 풀리고, 취소하면 체크하지 않은 상태로 둔다 */
    fun toggleNotApplicable(doc: ChecklistDoc) {
        if (doc.condition == null || doc.written) return
        val next = !doc.notApplicable
        updateDoc(doc.itemId) { if (next) it.copy(notApplicable = true, checked = false) else it.copy(notApplicable = false) }
        send(doc.itemId, notApplicable = next)
    }

    fun toggleEasy(type: StepType) {
        if (type in easy) easy.remove(type) else easy.add(type)
    }

    private fun updateDoc(itemId: Long, change: (ChecklistDoc) -> ChecklistDoc) {
        val c = checklist ?: return
        checklist = c.copy(docs = c.docs.map { if (it.itemId == itemId) change(it) else it })
    }

    /** 3.11 항목 체크. 화면은 바로 바꾸고, 0.3초 동안 더 누르지 않으면 서버에 보낸다 */
    private fun send(itemId: Long, checked: Boolean? = null, notApplicable: Boolean? = null) {
        val c = checklist ?: return
        pending.remove(itemId)?.cancel()
        val job = viewModelScope.launch {
            delay(300L)
            saveState = SaveState.SAVING
            try {
                val state = api.updateChecklistItem(userId, c.id, itemId, checked, notApplicable)
                // 그 사이 또 눌렀으면 새 요청 결과를 따른다
                if (pending[itemId] === coroutineContext[Job]) {
                    pending.remove(itemId)
                    applyServer(state.itemId, state.checked, state.notApplicable)
                }
                saveState = SaveState.SAVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending.remove(itemId)
                saveState = SaveState.FAILED
                // 서버 상태로 다시 맞춘다
                runCatching { api.getChecklist(userId, c.id) }.onSuccess { checklist = it }
            }
        }
        pending[itemId] = job
    }

    private fun applyServer(itemId: Long, checked: Boolean, notApplicable: Boolean) {
        val c = checklist ?: return
        checklist = c.copy(
            steps = c.steps.map { if (it.itemId == itemId) it.copy(done = checked) else it },
            docs = c.docs.map { if (it.itemId == itemId) it.copy(checked = checked, notApplicable = notApplicable) else it },
        )
    }
}

/** 신청 체크리스트 (디자인: 체크리스트) */
@Composable
fun ChecklistScreen(
    policyId: String,
    onBack: () -> Unit,
    onFormGuide: (String) -> Unit = {},
    /** 서류 작성 (문서 AI): 이 정책의 준비 서류 중 쓸 서류 고르기 */
    onOpenDocs: (String) -> Unit = {},
    /** 문서 AI 로 쓴 서류 열기 */
    onOpenDocument: (Long) -> Unit = {},
    vm: ChecklistViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    LaunchedEffect(policyId) {
        vm.attach(ctx)
        vm.load(policyId)
    }
    // 신청서 항목이 준비된 정책(자립수당 등)이면 '신청서 쓰기'
    var hasForm by remember { mutableStateOf(false) }
    LaunchedEffect(policyId) {
        hasForm = policyId.toLongOrNull()?.let { AppContainer.documents.hasPolicyForm(it) } ?: false
    }
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
                        "${c.policy.agency} · ${c.policy.checkedAt} 기준",
                        style = OnRoadType.Caption2,
                        color = OnRoadColors.TextMuted,
                    )
                    if (!c.policy.isActive) {
                        Spacer(Modifier.height(16.dp))
                        NoticeBox(
                            "지금은 운영되지 않는 정책이에요. 체크리스트는 계속 보고 체크할 수 있어요.",
                            title = "더 이상 운영되지 않는 정책",
                            tone = NoticeTone.Warning,
                        )
                    }

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
                                policyName = c.policyName,
                                agency = c.policy.agency,
                                info = vm.policyInfo,
                                easy = step.type in vm.easy,
                                onToggle = { vm.toggleStep(step) },
                                onToggleEasy = { vm.toggleEasy(step.type) },
                            )
                        }
                    }

                    // 서류 작성 (문서 AI): 준비 서류 바로 위. 쓸 서류는 다음 화면에서 고른다
                    if (hasForm) {
                        val writtenForm = c.docs.firstOrNull { "신청서" in it.name }?.checked == true
                        Spacer(Modifier.height(40.dp))
                        OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary, onClick = { onOpenDocs(c.policyId) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("서류 작성 도우미", style = OnRoadType.Title3, color = OnRoadColors.Primary, modifier = Modifier.weight(1f))
                                if (writtenForm) OnRoadTag("신청서 작성 완료", style = TagStyle.PrimaryFilled, textStyle = OnRoadType.Tiny)
                            }
                            Spacer(Modifier.height(12.dp))
                            OnRoadButton(
                                "서류 고르고 쓰기",
                                { onOpenDocs(c.policyId) },
                                Modifier.fillMaxWidth(),
                                height = 40.dp,
                                textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }

                    if (c.docs.isNotEmpty()) {
                        Spacer(Modifier.height(40.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("준비 서류", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
                            ProgressCount(c.docChecked, c.docTotal, "개")
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(c.docProgress)
                        // 신청 상황 태그: 켠 상황의 서류만 보인다
                        if (vm.situations.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DocSituation.entries.filter { it in vm.situations }.forEach { s ->
                                    ChoiceChip(s.label, selected = vm.situations[s] == true) { vm.toggleSituation(s) }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.visibleDocs(c).forEach { doc ->
                                DocRow(
                                    doc,
                                    onClick = { doc.documentId?.takeIf { doc.written }?.let(onOpenDocument) ?: vm.toggleDoc(doc) },
                                    onNotApplicable = { vm.toggleNotApplicable(doc) },
                                )
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
                    // 공식 신청 페이지가 없으면 버튼을 숨긴다
                    c.policy.applyUrl?.let { url ->
                        OnRoadButton(
                            text = "공식 사이트에서 신청하기",
                            onClick = { runCatching { uri.openUri(url) } },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = Icons.Filled.NorthEast,
                            shape = OnRoadShapes.Inner,
                        )
                        Spacer(Modifier.height(18.dp))
                    }
                    // 문의처가 없으면 숨긴다
                    c.policy.contact?.let { contact ->
                        val tel = contact.filter { it.isDigit() || it == '-' }.trim('-')
                        Row(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .clickable(enabled = tel.isNotEmpty()) { runCatching { uri.openUri("tel:$tel") } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("문의", style = OnRoadType.Caption, color = OnRoadColors.TextSlateMid)
                            Spacer(Modifier.width(6.dp))
                            Text(contact, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                        }
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
    policyName: String,
    agency: String,
    info: Policy?,
    @Suppress("UNUSED_PARAMETER") easy: Boolean,
    onToggle: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleEasy: () -> Unit,
) {
    val done = step.done
    var showOriginal by remember(step.itemId) { mutableStateOf(false) }
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
        Spacer(Modifier.height(4.dp))
        // 단계마다 "무엇을 하면 되는지" 한 줄
        Text(
            stepGuide(step.type),
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
            color = OnRoadColors.TextSlateMid,
        )
        if (step.type != StepType.DOCS) Spacer(Modifier.height(12.dp))

        when (step.type) {
            StepType.TARGET -> TargetSummary(policyName, agency, info)
            StepType.CONDITION -> ConditionList(step)
            StepType.DOCS -> Unit // 아래 준비 서류 목록이 본문
            StepType.APPLY -> ApplyMethods(step)
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 원문은 카드에 다 펼치지 않고 시트로 본다 (서류 준비는 아래 목록이 본문)
            if (step.type != StepType.DOCS && step.description.isNotBlank()) {
                Text(
                    "원문 전체 보기 >",
                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.TextSlateMid,
                    modifier = Modifier.clickable { showOriginal = true }.padding(vertical = 4.dp),
                )
            }
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

    if (showOriginal) {
        OriginalSheet("${step.type.title} 원문", stepText(step.description)) { showOriginal = false }
    }
}

private fun stepGuide(type: StepType): String = when (type) {
    StepType.TARGET -> "어떤 지원인지 먼저 살펴봐요"
    StepType.CONDITION -> "나에게 해당하는지 확인해요"
    StepType.DOCS -> "필요한 서류를 하나씩 챙겨요"
    StepType.APPLY -> "아래 방법 중 하나로 신청해요"
}

/** 체크리스트 본문: 원문을 문장마다 끊어 개조식(~함, ~필요) 목록으로 보여준다. 말줄임 없음 */
@Composable
private fun StepBody(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sentences(text).map(::plain).filter { it.isNotBlank() }.forEach { line ->
            Row {
                Text("·", style = BODY, color = OnRoadColors.TextSlateMid, modifier = Modifier.width(12.dp))
                Text(line, style = BODY, color = OnRoadColors.TextSlateDark, modifier = Modifier.weight(1f))
            }
        }
    }
}

private val BODY = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight)

/** 라벨 – 값 한 줄 (대상 확인 요약) */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = BODY, color = OnRoadColors.TextMuted, modifier = Modifier.width(64.dp))
        Text(value, style = BODY.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
    }
}

/** 대상 확인: 어떤 정책인지 한눈에 (정책·담당·지원 내용·지원 기간) */
@Composable
private fun TargetSummary(policyName: String, agency: String, info: Policy?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoLine("정책", policyName)
        InfoLine("담당", agency)
        info?.amount?.takeIf { it.isNotBlank() }?.let { InfoLine("지원 내용", plain(sentences(it).first())) }
        info?.period?.takeIf { it.isNotBlank() }?.let { InfoLine("지원 기간", plain(sentences(it).first())) }
    }
}

/** "…입니다. …해요. 온라인: …" 를 문장 단위로 나눈다. 괄호 안이나 숫자 뒤(24. 2. 9.)의 마침표에서는 나누지 않는다 */
private fun sentences(text: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    val src = stepText(text)
    for ((i, ch) in src.withIndex()) {
        when (ch) {
            '(' -> depth++
            ')' -> if (depth > 0) depth--
        }
        if (ch == '\n') {
            if (sb.isNotBlank()) out += sb.toString().trim()
            sb.clear()
            continue
        }
        sb.append(ch)
        val next = src.getOrNull(i + 1)
        if (ch == '.' && depth == 0 && (next == null || next == ' ') && sb.length > 1 && !sb[sb.length - 2].isDigit()) {
            out += sb.toString().trim()
            sb.clear()
        }
    }
    if (sb.isNotBlank()) out += sb.toString().trim()
    return out.filter { it.isNotEmpty() }.ifEmpty { listOf(text.trim()) }
}

/** 문장 끝을 개조식으로 맞춘다: "신청한다." → "신청", "필요하다" → "필요", "있다" → "있음" */
private fun plain(sentence: String): String {
    var t = sentence.trim().removeSuffix(".").trim()
    val rules = listOf(
        "필요하다" to "필요", "가능하다" to "가능", "불가하다" to "불가",
        "있다" to "있음", "없다" to "없음", "된다" to "됨", "않는다" to "않음",
        "합니다" to "함", "해요" to "함", "됩니다" to "됨", "이에요" to "", "예요" to "", "입니다" to "",
        "이다" to "", "한다" to "",
    )
    for ((from, to) in rules) {
        if (t.endsWith(from)) { t = t.dropLast(from.length) + to; break }
    }
    return t.trimEnd(',', ' ')
}

/** 조건 확인: 서버가 판정한 나이·지역은 결과로, 나머지는 확인 필요 */
@Composable
private fun ConditionList(step: ChecklistStep) {
    if (step.checks.isEmpty() && step.summary == null) {
        StepBody(step.description)
        return
    }
    // 서버 요약이 있으면 "세부 조건" 한 줄 대신 요약 항목을 확인 필요로 보여준다
    val checks = if (step.summary != null) step.checks.filter { it.ok != null || it.label in setOf("나이", "지역") } else step.checks
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (c.ok) { true -> "✓"; false -> "✕"; null -> "?" },
                    style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = when (c.ok) { true -> OnRoadColors.Primary; false -> OnRoadColors.Danger; null -> OnRoadColors.Warning },
                    modifier = Modifier.width(20.dp),
                )
                Text(c.label, style = OnRoadType.Caption, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                OnRoadTag(
                    when (c.ok) { true -> "기본 조건 해당"; false -> "해당 안 됨"; null -> "확인 필요" },
                    style = when (c.ok) { true -> TagStyle.PrimarySoft; false -> TagStyle.Danger; null -> TagStyle.Warning },
                    textStyle = OnRoadType.Tiny,
                )
            }
        }
        step.summary?.sections?.forEach { sec ->
            val exclusion = sec.label.contains("받을 수 없")
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sec.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                OnRoadTag(
                    if (exclusion) "해당하면 제외" else "확인 필요",
                    style = if (exclusion) TagStyle.Danger else TagStyle.Warning,
                    textStyle = OnRoadType.Tiny,
                )
            }
            BulletList(sec.items)
        }
        Text(
            step.summary?.notice ?: "확인 필요 항목은 원문이나 담당 기관에서 확인해 주세요.",
            style = OnRoadType.Tiny,
            color = OnRoadColors.TextMuted,
        )
    }
}

/** 개조식 목록 (서버 요약 항목) */
@Composable
private fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { line ->
            Row {
                Text("·", style = BODY, color = OnRoadColors.TextSlateMid, modifier = Modifier.width(12.dp))
                Text(line, style = BODY, color = OnRoadColors.TextSlateDark, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 신청: 방법을 칩으로 고르고, 고른 방법 설명만 보여준다 */
@Composable
private fun ApplyMethods(step: ChecklistStep) {
    val summaryItems = step.summary?.sections?.flatMap { it.items }.orEmpty()
    val grouped = remember(summaryItems) { groupApplyItems(summaryItems) }
    val methods = if (summaryItems.isNotEmpty()) grouped.map { ApplyMethod(it.first, "") } else step.methods
    if (methods.isEmpty()) {
        if (summaryItems.isNotEmpty()) BulletList(summaryItems) else StepBody(step.description)
        return
    }
    var picked by remember(step.itemId) { mutableStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        methods.forEachIndexed { i, m ->
            val on = i == picked
            Text(
                m.label,
                style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                color = if (on) OnRoadColors.Surface else OnRoadColors.TextSlateDark,
                modifier = Modifier
                    .clip(OnRoadShapes.Tag)
                    .background(if (on) OnRoadColors.Primary else OnRoadColors.Surface)
                    .border(1.dp, if (on) OnRoadColors.Primary else OnRoadColors.BorderSlate, OnRoadShapes.Tag)
                    .clickable { picked = i }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    val idx = picked.coerceIn(0, methods.lastIndex)
    if (summaryItems.isNotEmpty()) BulletList(grouped[idx].second) else StepBody(methods[idx].text)
}

/** 요약 항목을 방문·온라인·우편·팩스로 나눈다. "우편·팩스: …" 처럼 앞에 붙은 방법 이름은 뗀다 */
private fun groupApplyItems(items: List<String>): List<Pair<String, List<String>>> {
    val order = listOf("방문", "온라인", "우편·팩스")
    val groups = linkedMapOf<String, MutableList<String>>()
    for (raw in items) {
        val key = when {
            raw.contains("우편") || raw.contains("팩스") -> "우편·팩스"
            raw.contains("온라인") || raw.contains("복지로") -> "온라인"
            else -> "방문"
        }
        val text = raw.replace(Regex("""^(방문|온라인|우편·팩스|우편|팩스)\s*:\s*"""), "")
        groups.getOrPut(key) { mutableListOf() } += text
    }
    return order.mapNotNull { k -> groups[k]?.let { k to it.toList() } }
}

/** 원문 전체 보기 시트 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OriginalSheet(title: String, text: String, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("공식 자료 원문이에요. 최종 조건은 담당 기관에서 확인해 주세요.", style = OnRoadType.Caption2, color = OnRoadColors.TextMuted)
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight),
                color = OnRoadColors.TextPrimary,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DocRow(doc: ChecklistDoc, onClick: () -> Unit, onNotApplicable: () -> Unit) {
    val checked = doc.checked && !doc.notApplicable
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = when {
            checked -> OnRoadColors.SurfaceFile
            doc.notApplicable -> OnRoadColors.SurfaceMuted
            else -> OnRoadColors.Surface
        },
        borderColor = if (checked) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckBoxMark(checked)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // 서버 짧은 이름(조건 포함)이 있으면 그걸 쓰고, 원래 이름·조건은 자세히 보기로 접는다
                val short = doc.shortLabel
                val label = remember(doc.name, short) { docLabel(short ?: doc.name) }
                var more by remember(doc.itemId) { mutableStateOf(false) }
                val dim = if (doc.notApplicable) OnRoadColors.TextMuted else OnRoadColors.TextPrimary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (label.pickOne) {
                        OnRoadTag("1가지 선택", style = TagStyle.PrimaryOutline, textStyle = OnRoadType.Tiny)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        label.title,
                        style = OnRoadType.Body2.copy(fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal),
                        color = dim,
                    )
                }
                DocLabelParts(label)
                if (short == null) {
                    DocCondition(doc.condition)
                } else {
                    Text(
                        if (more) "접기" else "자세히 보기",
                        style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                        color = OnRoadColors.TextSlateMid,
                        modifier = Modifier.clickable { more = !more }.padding(top = 4.dp),
                    )
                    if (more) {
                        val full = remember(doc.name) { docLabel(doc.name) }
                        Spacer(Modifier.height(2.dp))
                        Text(full.title, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateDark)
                        DocLabelParts(full)
                        DocCondition(doc.condition)
                    }
                }
            }
            when {
                doc.written -> OnRoadTag("AI로 작성 완료", style = TagStyle.PrimaryFilled, textStyle = OnRoadType.Tiny)
                doc.documentStatus == "writing" -> OnRoadTag("작성 중", style = TagStyle.PrimaryOutline, textStyle = OnRoadType.Tiny)
                checked -> Text("준비 완료", style = OnRoadType.Micro, color = OnRoadColors.Primary)
                doc.notApplicable -> Text("해당 없음", style = OnRoadType.Micro, color = OnRoadColors.TextMuted)
            }
            if (doc.condition != null && !doc.written) {
                Spacer(Modifier.width(8.dp))
                SmallBoxButton(
                    text = if (doc.notApplicable) "해당 없음 취소" else "해당 없음",
                    onClick = onNotApplicable,
                    background = if (doc.notApplicable) OnRoadColors.SurfaceMuted else OnRoadColors.Surface,
                    borderColor = if (doc.notApplicable) OnRoadColors.BorderSage else OnRoadColors.BorderDark,
                )
            }
        }
    }
}


/** "방문: … 온라인: … 우편·팩스: …" 처럼 이어진 원문을 항목별로 줄을 나눈다 */
private fun stepText(text: String): String =
    text.trim().replace(Regex("""\s+(?=(방문|온라인|우편·팩스|우편|팩스|전화)\s*:)"""), "\n")

@Composable
private fun DocLabelParts(label: DocLabel) {
    label.detail?.let {
        Spacer(Modifier.height(2.dp))
        Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextSlateDark)
    }
    if (label.options.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        label.options.forEach { Text("· $it", style = OnRoadType.Caption2, color = OnRoadColors.TextSlateDark) }
    }
    label.note?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = OnRoadType.Micro, color = OnRoadColors.TextMuted)
    }
}

@Composable
private fun DocCondition(condition: String?) {
    condition ?: return
    Spacer(Modifier.height(4.dp))
    Text(
        "필요한 경우 · " + sentences(condition).map(::plain).joinToString(" / "),
        style = OnRoadType.Micro,
        color = OnRoadColors.TextSlateMid,
    )
}

/** 서류 이름을 보기 좋게 나눈 결과 */
private data class DocLabel(
    val title: String,
    /** "신분증: 주민등록증·여권 중 1종" 의 뒷부분 */
    val detail: String? = null,
    /** [택1] 서류의 고를 수 있는 항목 */
    val options: List<String> = emptyList(),
    /** 이름 뒤에 붙은 참고 문장 */
    val note: String? = null,
    val pickOne: Boolean = false,
)

/**
 * "[택1] A 또는 B 또는 C 1부. 참고 문장" → 1가지 선택 + 항목 목록 + 참고
 * "신분증: 주민등록증·여권·… 중 1종" → 제목 "신분증" + 설명
 * "방문 제한 사유 증빙: 해외유학 재학증명서·… 등 해당 서류" → 제목 + "해외유학 재학증명서, … 등"
 */
private fun docLabel(raw: String): DocLabel {
    var name = raw.trim()
    val pick = Regex("""^\[택\s*\d+]\s*""").find(name)
    if (pick != null) name = name.substring(pick.range.last + 1)
    // 첫 마침표 뒤는 참고 문장
    val dot = Regex("""\.\s""").find(name)
    val note = dot?.let { plain(name.substring(it.range.last + 1)) }?.takeIf { it.isNotBlank() }
    if (dot != null) name = name.substring(0, dot.range.first)

    fun listText(s: String) = s.replace("·", ", ").replace(Regex("""\s*해당 서류$"""), "").trim()

    if (pick != null) {
        val count = Regex("""\s*(\d+부)$""").find(name)
        val body = if (count != null) name.substring(0, count.range.first) else name
        return DocLabel(
            title = "아래 중 1가지" + (count?.let { " (${it.groupValues[1]})" } ?: ""),
            options = body.split(Regex("""\s+또는\s+""")).map { it.trim() }.filter { it.isNotEmpty() },
            note = note,
            pickOne = true,
        )
    }
    val colon = name.indexOf(':')
    if (colon > 0) {
        return DocLabel(title = name.substring(0, colon).trim(), detail = listText(name.substring(colon + 1)), note = note)
    }
    return DocLabel(title = name, note = note)
}