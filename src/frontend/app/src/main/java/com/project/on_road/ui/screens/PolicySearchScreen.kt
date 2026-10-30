package com.project.on_road.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.AppContainer
import com.project.on_road.ui.components.EasyTextPanel
import com.project.on_road.ui.components.NextStepList
import com.project.on_road.data.AppState
import com.project.on_road.data.UserStage
import com.project.on_road.data.SignalSource
import com.project.on_road.data.Policy
import com.project.on_road.data.SearchResult
import com.project.on_road.data.Topic
import com.project.on_road.data.Links
import com.project.on_road.data.remote.ApiException
import com.project.on_road.ui.components.CheckBoxMark
import com.project.on_road.ui.components.InfoRow
import com.project.on_road.ui.components.LoadingState
import com.project.on_road.ui.components.NoticeBox
import com.project.on_road.ui.components.NoticeTone
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.OnRoadSearchBar
import com.project.on_road.ui.components.OnRoadSwitch
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.OnRoadTopBar
import com.project.on_road.ui.components.SubHeader
import com.project.on_road.ui.components.SubtleCard
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch

/** 단계별 자주 묻는 주제 (청소년은 학업·장학금·진로 중심, 자립준비청년은 자립 생활 중심) */
private fun suggestionsFor(stage: UserStage): List<String> = when (stage) {
    UserStage.YOUNG_ADULT -> listOf(
        "혼자 살 집을 구하고 싶어요",
        "매달 받을 수 있는 지원금이 있나요?",
        "병원비가 걱정돼요",
        "취업 준비를 도와주는 제도가 있나요?",
        "목돈을 모으는 방법이 궁금해요",
    )
    else -> listOf(
        "받을 수 있는 장학금이 있나요?",
        "디딤씨앗통장이 뭐예요?",
        "진학 준비를 도와주는 제도가 있나요?",
        "자격증 시험 비용을 지원받을 수 있나요?",
        "학원비나 교재비 지원이 있나요?",
    )
}

private fun topicTitleFor(stage: UserStage): String =
    if (stage == UserStage.YOUNG_ADULT) "* 청년들이 자주 묻는 주제" else "* 청소년들이 자주 묻는 주제"

class PolicySearchViewModel : ViewModel() {
    private val api = AppContainer.api
    private val userId: String get() = AppContainer.session.userId.orEmpty()

    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var result by mutableStateOf<SearchResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 원문으로 보는 정책 (기본은 쉬운 말) */
    val originalIds = mutableStateListOf<String>()
    val evidenceIds = mutableStateListOf<String>()
    val compareIds = mutableStateListOf<String>()
    val savedIds = mutableStateListOf<String>()

    private var initialized = false

    fun init(policyId: String?, initialQuery: String?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            runCatching { api.getSavedPolicyIds(userId) }.onSuccess {
                savedIds.clear()
                savedIds.addAll(it)
            }
        }
        when {
            policyId != null -> viewModelScope.launch {
                loading = true
                val policy = runCatching { api.getPolicy(policyId) }.getOrNull()
                result = if (policy != null) {
                    SearchResult("", true, "선택한 정책이에요.", listOf(policy))
                } else {
                    SearchResult("", false, "정책 정보를 불러오지 못했어요.", emptyList())
                }
                loading = false
            }
            !initialQuery.isNullOrBlank() -> search(initialQuery)
        }
    }

    fun search(text: String = query) {
        val q = text.trim()
        if (q.isEmpty() || loading) return
        query = q
        ActivityStore.logQuestion(q, SignalSource.SEARCH)
        viewModelScope.launch {
            loading = true
            error = null
            // 비교 담기는 검색을 바꿔도 유지한다 (다른 검색어로 찾은 정책끼리도 비교할 수 있게)
            evidenceIds.clear()
            try {
                result = api.searchPolicies(userId, q)
            } catch (e: ApiException) {
                error = if (e.code == "RAG_UNAVAILABLE") "지금은 공식 자료 검색을 잠시 쓸 수 없어요. 잠시 후 다시 시도해 주세요."
                else "검색하지 못했어요. 잠시 후 다시 시도해 주세요."
            } catch (e: Exception) {
                error = "검색하지 못했어요. 잠시 후 다시 시도해 주세요."
            } finally {
                loading = false
            }
        }
    }

    fun toggleOriginal(id: String) = toggle(originalIds, id)
    fun toggleEvidence(id: String) = toggle(evidenceIds, id)

    fun toggleCompare(id: String) {
        if (id in compareIds) compareIds.remove(id) else if (compareIds.size < 3) compareIds.add(id)
    }

    fun toggleSave(id: String) {
        val save = id !in savedIds
        if (save) savedIds.add(id) else savedIds.remove(id)
        if (save) result?.policies?.firstOrNull { it.id == id }?.let { ActivityStore.logPolicySaved(it.category, it.name) }
        viewModelScope.launch { runCatching { api.savePolicy(userId, id, save) } }
    }

    private fun toggle(list: MutableList<String>, id: String) {
        if (id in list) list.remove(id) else list.add(id)
    }
}

@Composable
fun PolicySearchScreen(
    initialPolicyId: String?,
    initialQuery: String? = null,
    onBack: () -> Unit,
    onPrepare: (String) -> Unit,
    onCompare: (List<String>) -> Unit,
    onNavigate: (String) -> Unit = {},
    vm: PolicySearchViewModel = viewModel(),
) {
    LaunchedEffect(initialPolicyId, initialQuery) { vm.init(initialPolicyId, initialQuery) }
    val focus = LocalFocusManager.current
    val submit: (String) -> Unit = {
        vm.search(it)
        focus.clearFocus()
    }

    Box(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            OnRoadTopBar("정책 검색", onBack)
            Spacer(Modifier.height(16.dp))
            OnRoadSearchBar(
                value = vm.query,
                onValueChange = { vm.query = it },
                placeholder = "무엇이 궁금한가요?",
                height = 48.dp,
                onSearch = submit,
                modifier = Modifier.padding(horizontal = OnRoadDimens.ScreenPadding),
                trailing = {
                    val active = vm.query.isNotBlank() && !vm.loading
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(OnRoadShapes.Field)
                            .background(if (active) OnRoadColors.PrimaryDeep else OnRoadColors.SurfaceMuted)
                            .clickable(enabled = active) { submit(vm.query) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward, "검색",
                            tint = if (active) Color.White else OnRoadColors.TextQuaternary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
            Spacer(Modifier.height(20.dp))

            val result = vm.result
            val error = vm.error
            when {
                vm.loading -> LoadingState("공식 자료에서 근거를 찾고 있어요", Modifier.weight(1f))

                error != null -> Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
                    NoticeBox(error, title = "검색하지 못했어요", tone = NoticeTone.Warning)
                }

                result == null -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    item {
                        Text("궁금한 제도를 물어보면\n공식 자료에서 찾아볼게요.", style = OnRoadType.Title1Regular, color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(24.dp))
                        Text(topicTitleFor(AppState.stage), style = OnRoadType.Caption, color = OnRoadColors.TextSlate)
                        Spacer(Modifier.height(3.dp))
                    }
                    items(suggestionsFor(AppState.stage)) { SuggestionChip(it) { submit(it) } }
                    item {
                        Spacer(Modifier.height(20.dp))
                        NoticeBox(
                            "근거를 찾지 못하면 추측하지 않고, 확인이 필요하다고 알려 드려요.",
                            title = "답변은 공식 자료에 근거해요",
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp, top = 0.dp,
                        bottom = if (vm.compareIds.isNotEmpty()) 120.dp else 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        if (result.grounded) {
                            Column {
                                // 3.5 서버 답변 (명세 6.4: 로딩 후 answer 표시)
                                result.answer?.let { answer ->
                                    SubtleCard(Modifier.fillMaxWidth()) {
                                        Text(answer, style = OnRoadType.Body2, color = OnRoadColors.TextPrimary)
                                    }
                                    Spacer(Modifier.height(20.dp))
                                }
                                SubHeader(result.message, trailingText = "${result.policies.size}건")
                            }
                        } else {
                            Column {
                                NoticeBox(
                                    "${result.message}\n금액이나 자격은 추측해서 알려 드리지 않아요. " +
                                            "보건복지상담센터(129)나 지역 자립지원전담기관에 확인해 주세요.",
                                    title = "확인이 필요해요",
                                    tone = NoticeTone.Warning,
                                )
                                Spacer(Modifier.height(20.dp))
                                Text("* 이렇게 다시 물어볼 수 있어요", style = OnRoadType.Caption, color = OnRoadColors.TextSlate)
                                Spacer(Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    suggestionsFor(AppState.stage).take(3).forEach { SuggestionChip(it) { submit(it) } }
                                }
                            }
                        }
                    }
                    items(result.policies, key = { it.id }) { p ->
                        PolicyCard(
                            policy = p,
                            easy = p.id !in vm.originalIds,
                            evidenceOpen = p.id in vm.evidenceIds,
                            inCompare = p.id in vm.compareIds,
                            compareEnabled = p.id in vm.compareIds || vm.compareIds.size < 3,
                            saved = p.id in vm.savedIds,
                            onToggleEasy = { vm.toggleOriginal(p.id) },
                            onToggleEvidence = { vm.toggleEvidence(p.id) },
                            onToggleCompare = { vm.toggleCompare(p.id) },
                            onToggleSave = { vm.toggleSave(p.id) },
                            onPrepare = { onPrepare(p.id) },
                        )
                    }
                    // 찾은 분야로 이어서 해 볼 것 (연습 · 생활비 · 상담)
                    val topic = Topic.classify(result.question).firstOrNull()
                        ?: result.policies.firstOrNull()?.let { Topic.ofPolicyCategory(it.category) }
                    if (topic != null) {
                        item(key = "next-steps") {
                            Column {
                                Spacer(Modifier.height(16.dp))
                                NextStepList(Links.forTopic(topic), { onNavigate(com.project.on_road.ui.screens.navigation.stepRoute(it)) })
                                Spacer(Modifier.height(if (vm.compareIds.isNotEmpty()) 88.dp else 24.dp))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = vm.compareIds.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            CompareBar(count = vm.compareIds.size) { onCompare(vm.compareIds.toList()) }
        }
    }
}

/** 자주 묻는 주제 칩 (상담 화면과 같은 스타일) */
@Composable
internal fun SuggestionChip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(OnRoadShapes.Chip)
            .background(OnRoadColors.SurfaceMuted)
            .border(1.dp, OnRoadColors.BorderSage.copy(alpha = 0.4f), OnRoadShapes.Chip)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextSlate, modifier = Modifier.size(16.dp))
    }
}

/** 정책 카드 (디자인: 정책) */
@Composable
fun PolicyCard(
    policy: Policy,
    easy: Boolean,
    evidenceOpen: Boolean,
    inCompare: Boolean,
    compareEnabled: Boolean,
    saved: Boolean,
    onToggleEasy: () -> Unit,
    onToggleEvidence: () -> Unit,
    onToggleCompare: () -> Unit,
    onToggleSave: () -> Unit,
    onPrepare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnRoadCard(
        modifier.fillMaxWidth(),
        borderColor = if (inCompare) OnRoadColors.Primary else OnRoadColors.BorderSlate,
        borderWidth = if (inCompare) 2.dp else 1.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag(
                policy.category,
                style = TagStyle.PrimaryOutline,
                textStyle = OnRoadType.Caption2,
                shape = OnRoadShapes.Field,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleSave, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (saved) "저장 취소" else "저장",
                    tint = if (saved) OnRoadColors.Primary else OnRoadColors.TextMuted,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(policy.name, style = OnRoadType.Title3, color = OnRoadColors.TextStrong)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(policy.agency, style = OnRoadType.Caption2, color = OnRoadColors.TextSlateMid, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Text("  ı  ", style = OnRoadType.Tiny, color = OnRoadColors.TextMuted)
            Text("${policy.checkedAt} 기준", style = OnRoadType.Caption2, color = OnRoadColors.TextMuted, maxLines = 1)
        }

        // 조건 판정 (명세 1.3: "신청 가능" 표시 금지) · 오래된 정보 경고 (명세 1.4)
        if (policy.eligibility != null || policy.isOutdated) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (policy.eligibility) {
                    "match" -> OnRoadTag("기본 조건 해당 · 세부 조건은 공식 원문에서 확인", style = TagStyle.PrimarySoft)
                    "needs_check" -> OnRoadTag("조건 확인 필요", style = TagStyle.Warning)
                }
                if (policy.isOutdated) {
                    OnRoadTag("확인일이 오래된 정보예요 · 최신 내용은 원문에서 확인", style = TagStyle.Danger)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Text("쉬운말로 보기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateDark)
            OnRoadSwitch(checked = easy, onCheckedChange = { onToggleEasy() })
        }

        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Field)
                .background(OnRoadColors.SurfaceCool)
                .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Field)
                .padding(16.dp),
        ) {
            AnimatedContent(targetState = easy, label = "summary") { isEasy ->
                if (isEasy) {
                    // 3.23 단계별 쉬운 말 (서버에 없으면 easySummary)
                    EasyTextPanel(policyId = policy.id, fallback = policy.easySummary)
                } else {
                    Text(
                        policy.summary,
                        style = OnRoadType.Body2.copy(lineHeight = OnRoadType.Body2.lineHeight * 1.1f),
                        color = OnRoadColors.TextSlateDark,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val cs = policy.cardSummary
            if (cs != null) {
                // 서버 짧은 요약 (지원 대상·받을 수 없는 경우·지원 내용·지원 기간·신청 방법)
                cs.sections.forEach { sec -> SummaryRow(sec.label, sec.items) }
                cs.notice?.let { Text(it, style = OnRoadType.Tiny, color = OnRoadColors.TextMuted) }
                if (!cs.verified) {
                    Text("AI가 원문에서 정리한 요약이에요. 원문과 함께 확인해 주세요.", style = OnRoadType.Tiny, color = OnRoadColors.TextMuted)
                }
            } else {
                // 요약이 없으면 원문 첫 문장만 두 줄까지, 누르면 전체를 펼친다
                ShortInfoRow("지원 대상 & 자격요건", policy.target)
                ShortInfoRow("지원 혜택 & 내용", policy.amount)
                ShortInfoRow("접수 및 신청 기간", policy.period)
            }
        }

        Spacer(Modifier.height(16.dp))
        val arrow by animateFloatAsState(if (evidenceOpen) 180f else 0f, label = "evidenceArrow")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Field)
                .background(OnRoadColors.SurfaceCool)
                .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Field)
                .clickable(onClick = onToggleEvidence)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("근거 원문 보기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateDark, modifier = Modifier.weight(1f))
                Text("${policy.evidence.size}건", style = OnRoadType.Micro, color = OnRoadColors.TextMuted)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(20.dp).rotate(arrow))
            }
            AnimatedVisibility(evidenceOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    policy.evidence.forEach { ev ->
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(OnRoadColors.BorderSage))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("“${ev.snippet}”", style = OnRoadType.Caption, color = OnRoadColors.TextPrimary)
                                Spacer(Modifier.height(2.dp))
                                Text(ev.source, style = OnRoadType.Tiny, color = OnRoadColors.TextSlateMid)
                            }
                        }
                    }
                }
            }
        }

        // 공식 원문 링크 (명세: source_url 항상 포함)
        if (policy.sourceUrl.isNotBlank()) {
            val uri = LocalUriHandler.current
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Field)
                    .clickable { runCatching { uri.openUri(policy.sourceUrl) } }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("공식 원문 사이트에서 보기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.Primary, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = OnRoadColors.Primary, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .height(48.dp)
                    .clip(OnRoadShapes.Inner)
                    .background(if (inCompare) OnRoadColors.PrimarySoft else Color.White)
                    .border(1.dp, if (inCompare) OnRoadColors.Primary else OnRoadColors.BorderSlate, OnRoadShapes.Inner)
                    .clickable(enabled = compareEnabled, onClick = onToggleCompare)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckBoxMark(inCompare, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "비교 담기",
                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                    color = if (compareEnabled) OnRoadColors.TextSlateDark else OnRoadColors.TextQuaternary,
                )
            }
            OnRoadButton(
                text = "이 정책 신청 준비하기",
                onClick = onPrepare,
                modifier = Modifier.weight(1f),
                textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                shape = OnRoadShapes.Inner,
            )
        }
    }
}

@Composable
private fun CompareBar(count: Int, onCompare: () -> Unit) {
    Row(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(OnRoadColors.Primary)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${count}개 담았어요", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Text(
                if (count < 2) "하나 더 담으면 비교할 수 있어요" else "최대 3개까지 담을 수 있어요",
                style = OnRoadType.Micro,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Box(
            Modifier
                .height(40.dp)
                .clip(OnRoadShapes.Field)
                .background(if (count >= 2) Color.White else Color.White.copy(alpha = 0.15f))
                .clickable(enabled = count >= 2, onClick = onCompare)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "비교하기",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                color = if (count >= 2) OnRoadColors.Primary else Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

/** 요약 한 항목: 왼쪽 라벨, 오른쪽 개조식 목록 */
@Composable
private fun SummaryRow(label: String, items: List<String>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
            color = OnRoadColors.TextMuted,
            modifier = Modifier.width(76.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEach { Text(if (items.size > 1) "· $it" else it, style = OnRoadType.Micro, color = OnRoadColors.TextSlateDark) }
        }
    }
}

/** 첫 문장만 최대 두 줄로 보여주고, 잘린 내용이 있으면 눌러서 전체를 펼친다 */
@Composable
private fun ShortInfoRow(label: String, value: String) {
    var open by remember(value) { mutableStateOf(false) }
    val first = remember(value) {
        val i = Regex("""[.。](\s|$)""").find(value)?.range?.first
        if (i != null && i + 1 < value.trim().length) value.substring(0, i + 1) else value
    }
    val cut = first.length < value.trim().length
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = cut || open) { open = !open },
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextMuted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                if (open) value else first,
                style = OnRoadType.Micro,
                color = OnRoadColors.TextSlateDark,
                textAlign = TextAlign.End,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (cut) {
                Text(
                    if (open) "접기" else "더보기",
                    style = OnRoadType.Tiny.copy(fontWeight = FontWeight.Medium),
                    color = OnRoadColors.Primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
