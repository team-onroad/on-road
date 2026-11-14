package com.project.on_road.ui.screens


import com.project.on_road.ui.components.sharedTitle
import com.project.on_road.ui.components.policyTitleKey

import com.project.on_road.ui.components.tap
import com.project.on_road.ui.components.stagger
import com.project.on_road.ui.components.rememberTick
import com.project.on_road.ui.components.popOn
import androidx.compose.foundation.lazy.itemsIndexed

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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.project.on_road.ui.components.reveal
import com.project.on_road.ui.components.ErrorState
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

/**
 * 정책 검색 화면 여백 조절값. 숫자만 바꾸면 된다 (단위 dp).
 */
private object SearchSpace {
    /** 오른쪽 맨 위 상담 아이콘 위 여백 */
    val ChatIconTop = 14.dp
    /** 결과 맨 아래 '찾는 게 없나요?' 카드 위 여백 */
    val AskChatTop = 24.dp
    /** 안내 문장 위 여백 (처음 화면 / 검색할 때) */
    val TitleTop = 52.dp
    val TitleTopSearching = 20.dp
    /** 검색창 안 글자 왼쪽 여백 */
    val FieldTextStart = 20.dp
    /** '검색 결과 · ○건' 줄 양쪽 안쪽 여백 (0 이면 검색창 바깥 테두리에 맞춤) */
    val ResultHeaderInset = 0.dp
    /** '분야별로 보기' 위 여백 */
    val BrowseTop = 36.dp
    /** '이어서 해 보기' 위 여백 */
    val NextStepsTop = 32.dp
}

/** 검색창 위 키워드 태그. 누르면 그 말로 바로 찾는다 */
private fun keywordsFor(stage: UserStage): List<String> = when (stage) {
    UserStage.YOUNG_ADULT -> listOf("자립수당", "자립정착금", "전세임대", "월세 지원", "의료비", "취업 지원", "장학금", "목돈 모으기", "심리상담")
    else -> listOf("장학금", "디딤씨앗통장", "진학 지원", "자격증 비용", "학원비", "보호연장")
}

/** 분야별로 보기: 검색어를 넣지 않아도 분야를 고르면 그 분야 정책을 보여 준다 (서버 검색에 분야 이름으로 묻는다) */
private data class BrowseCategory(val label: String, val query: String)

private val browseCategories = listOf(
    BrowseCategory("전체", "자립준비청년 지원 정책"),
    BrowseCategory("주거", "주거 지원 정책"),
    BrowseCategory("생활비", "생활비 지원 정책"),
    BrowseCategory("금융", "자산 형성 금융 지원 정책"),
    BrowseCategory("취업", "취업 지원 정책"),
    BrowseCategory("교육", "장학금 교육 지원 정책"),
    BrowseCategory("의료", "의료비 건강 지원 정책"),
    BrowseCategory("자립", "자립준비청년 자립 지원 정책"),
)

/**
 * 인기 검색어 순위.
 * 아직 서버에 사람들이 찾은 검색어를 세는 API 가 없어서, 자립준비청년이 많이 찾는 제도로 정해 둔 순서다.
 * 서버에서 순위를 주면 이 목록 대신 그 값을 쓰면 된다.
 */
private val popularSearches = listOf(
    "자립수당", "자립정착금", "전세임대주택", "청년 월세 지원", "디딤씨앗통장",
    "자립지원전담기관 상담", "의료비 지원", "국민내일배움카드", "청년도약계좌", "보호연장",
)

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
    /** 관심 정책은 상세 화면과 같은 목록을 쓴다 */
    val savedIds = PolicySaves.ids

    private var initialized = false

    /** 분야별로 보기: 고른 분야 · 분야마다 받아 둔 정책 (한 번 받은 분야는 다시 묻지 않는다) */
    var category by mutableStateOf(browseCategories.first().label)
        private set
    val categoryPolicies = androidx.compose.runtime.mutableStateMapOf<String, List<Policy>>()
    var categoryLoading by mutableStateOf(false)
        private set
    var categoryError by mutableStateOf(false)
        private set

    fun pickCategory(label: String) {
        category = label
        if (label in categoryPolicies) return
        val c = browseCategories.firstOrNull { it.label == label } ?: return
        viewModelScope.launch {
            categoryLoading = true
            categoryError = false
            runCatching { api.searchPolicies(userId, c.query) }
                .onSuccess { categoryPolicies[label] = it.policies }
                .onFailure { if (category == label) categoryError = true }
            categoryLoading = false
        }
    }

    /** 최근 검색어 (계정마다 휴대폰에 저장) */
    val recent = mutableStateListOf<String>().apply { addAll(AppContainer.session.recentSearches) }

    fun removeRecent(text: String) {
        recent.remove(text)
        AppContainer.session.recentSearches = recent.toList()
    }

    fun clearRecent() {
        recent.clear()
        AppContainer.session.recentSearches = emptyList()
    }

    private fun addRecent(text: String) {
        recent.remove(text)
        recent.add(0, text)
        while (recent.size > 10) recent.removeAt(recent.lastIndex)
        AppContainer.session.recentSearches = recent.toList()
    }

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
        addRecent(q)
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

    /** 검색어를 지우면 처음 화면(분야별로 보기)으로 돌아간다 */
    fun reset() {
        query = ""
        result = null
        error = null
    }

    fun toggleOriginal(id: String) = toggle(originalIds, id)
    fun toggleEvidence(id: String) = toggle(evidenceIds, id)

    fun toggleCompare(id: String) {
        if (id in compareIds) compareIds.remove(id)
        else if (compareIds.size < 3) compareIds.add(id)
        else com.project.on_road.ui.components.OnRoadToast.show("비교는 3개까지 담을 수 있어요")
    }

    fun toggleSave(id: String) {
        val save = id !in savedIds
        if (save) savedIds.add(id) else savedIds.remove(id)
        com.project.on_road.ui.components.OnRoadToast.show(if (save) "관심 정책에 담았어요" else "관심 정책에서 뺐어요")
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
    onOpen: (String) -> Unit = {},
    onNavigate: (String) -> Unit = {},
    vm: PolicySearchViewModel = viewModel(),
) {
    LaunchedEffect(initialPolicyId, initialQuery) { vm.init(initialPolicyId, initialQuery) }
    val focus = LocalFocusManager.current
    val submit: (String) -> Unit = {
        vm.search(it)
        focus.clearFocus()
    }

    // 검색창을 누르면(키보드가 올라오면) 최근 검색 · 인기 검색어를 보여 준다
    var searching by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = searching) { focus.clearFocus() }
    val closeDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    val swipeDownToClose = remember(focus, closeDistance) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            private var pulled = 0f
            // 목록이 맨 위에 닿은 뒤에도 더 끌어내리면 (목록이 쓰고 남은 만큼) 닫는다
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                pulled = if (available.y > 0f) pulled + available.y else 0f
                if (pulled > closeDistance) {
                    pulled = 0f
                    focus.clearFocus()
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            // 처음 화면: 안내 문장 → 검색창 → 키워드 태그 (검색 중에는 안내 문장과 태그를 접는다)
            val start = vm.result == null && !vm.loading && vm.error == null && !searching
            // 처음 화면의 안내 문장은 홈 인사말과 비슷한 높이에서 시작한다. 검색할 때는 위로 올라간다
            val topGap by androidx.compose.animation.core.animateDpAsState(
                if (start) SearchSpace.TitleTop else SearchSpace.TitleTopSearching,
                com.project.on_road.ui.theme.OnRoadMotion.move(),
                label = "search-top",
            )
            Spacer(Modifier.height(topGap))
            AnimatedVisibility(
                visible = start,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
                    // 홈 인사말과 같은 크기 · 위계 (첫 줄 보통, 둘째 줄 굵게)
                    Text("궁금한 제도를 물어보면", style = OnRoadType.DisplayRegular, color = OnRoadColors.TextPrimary)
                    Text("공식 자료에서 찾아볼게요.", style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
                    Spacer(Modifier.height(20.dp))
                }
            }
            PolicySearchField(
                value = vm.query,
                onValueChange = { vm.query = it },
                onSearch = submit,
                onClear = { vm.reset() },
                enabled = !vm.loading,
                modifier = Modifier
                    .padding(horizontal = OnRoadDimens.ScreenPadding)
                    .onFocusChanged { searching = it.hasFocus },
            )
            AnimatedVisibility(
                visible = start,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding).padding(top = 16.dp)) {
                    KeywordTags(keywordsFor(AppState.stage)) { submit(it) }
                }
            }
            Spacer(Modifier.height(20.dp))

            val result = vm.result
            val error = vm.error
            when {
                searching -> LazyColumn(
                    // 아래로 끌어내리면 키보드를 닫고 처음 화면(안내 문장 · 태그)으로 돌아간다
                    modifier = Modifier.weight(1f).nestedScroll(swipeDownToClose),
                    contentPadding = PaddingValues(start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, bottom = 32.dp),
                ) {
                    item { SearchSuggestions(vm, onPick = submit) }
                }

                vm.loading -> LoadingState(
                    "공식 자료에서 근거를 찾고 있어요",
                    Modifier.weight(1f),
                    steps = listOf("내 상황에 맞는 정책을 고르고 있어요", "답변을 정리하고 있어요"),
                )

                error != null -> Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding)) {
                    ErrorState(error, onRetry = { vm.search() }, title = "검색하지 못했어요")
                }

                // 분야별로 보기: 제목 · 분야 칩은 고정, 정책 목록만 스크롤
                result == null -> Column(Modifier.weight(1f)) {
                    LaunchedEffect(Unit) { vm.pickCategory(vm.category) }
                    Column(Modifier.padding(start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, top = SearchSpace.BrowseTop, bottom = 12.dp)) {
                        SubHeader("분야별로 보기")
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            browseCategories.forEach { c ->
                                com.project.on_road.ui.components.ChoiceChip(c.label, selected = vm.category == c.label) { vm.pickCategory(c.label) }
                            }
                        }
                    }
                    val list = vm.categoryPolicies[vm.category]
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, top = 4.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            list == null && vm.categoryLoading -> item(key = "browse-loading") { com.project.on_road.ui.components.SkeletonList(rows = 4) }
                            list == null && vm.categoryError -> item(key = "browse-error") {
                                ErrorState("정책을 불러오지 못했어요.", onRetry = { vm.pickCategory(vm.category) }, title = "불러오지 못했어요")
                            }
                            list.isNullOrEmpty() -> item(key = "browse-empty") {
                                Text("이 분야에서 찾은 정책이 아직 없어요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                            }
                            else -> itemsIndexed(list.orEmpty(), key = { _, it -> "browse-${vm.category}-${it.id}" }) { i, p ->
                                Box(Modifier.animateItem().stagger(i)) {
                                    SmallPolicyCard(
                                        policy = p,
                                        showCategory = vm.category == browseCategories.first().label,
                                        onOpen = { PolicyCache.put(p); onOpen(p.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f).reveal(),
                    contentPadding = PaddingValues(
                        start = OnRoadDimens.ScreenPadding, end = OnRoadDimens.ScreenPadding, top = 0.dp,
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
                                // 서버 문장("○개를 찾았어요")과 건수가 겹쳐서 제목은 짧게, 건수만 오른쪽에
                                // 검색창 · 카드 안 글자와 같은 줄에서 시작하고 끝나게 (양쪽 20dp 안쪽)
                                SubHeader(
                                    "검색 결과",
                                    Modifier.padding(horizontal = SearchSpace.ResultHeaderInset),
                                    trailingText = "${result.policies.size}건",
                                )
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
                                Text("이 키워드로 다시 찾아볼 수 있어요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                                Spacer(Modifier.height(10.dp))
                                KeywordTags(keywordsFor(AppState.stage).take(6)) { submit(it) }
                            }
                        }
                    }
                    itemsIndexed(result.policies, key = { _, it -> it.id }) { i, p ->
                        Box(Modifier.animateItem().stagger(i)) {
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
                            onOpen = { PolicyCache.put(p); onOpen(p.id) },
                        )
                        }
                    }
                    // 찾은 분야로 이어서 해 볼 것 (연습 · 생활비 · 상담)
                    val topic = Topic.classify(result.question).firstOrNull()
                        ?: result.policies.firstOrNull()?.let { Topic.ofPolicyCategory(it.category) }
                    if (topic != null) {
                        item(key = "next-steps") {
                            Column {
                                Spacer(Modifier.height(SearchSpace.NextStepsTop))
                                SearchNextSteps(Links.forTopic(topic)) { onNavigate(com.project.on_road.ui.screens.navigation.stepRoute(it)) }
                            }
                        }
                    }
                    // 결과 맨 아래: 검색으로 못 찾았으면 바로 상담으로
                    item(key = "ask-chat") {
                        Column {
                            Spacer(Modifier.height(SearchSpace.AskChatTop))
                            AskChatCard { onNavigate(com.project.on_road.ui.screens.navigation.Routes.chat(question = result.question)) }
                            // 아래 비교 바에 마지막 줄이 가리지 않게
                            Spacer(Modifier.height(if (vm.compareIds.isNotEmpty()) 88.dp else 24.dp))
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

        // 오른쪽 맨 위 '상담하기': 처음 화면에서만 (검색해서 결과가 나오면 숨기고, 결과 맨 아래 '찾는 게 없나요?' 카드로 대신한다)
        AnimatedVisibility(
            visible = vm.result == null && !vm.loading && vm.error == null && !searching,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = SearchSpace.ChatIconTop, end = OnRoadDimens.ScreenPadding),
        ) {
            // 흰 테두리 작은 버튼: 아이콘 + '상담하기' (아이콘만 있으면 뜻이 잘 안 보여서)
            Row(
                Modifier
                    .clip(OnRoadShapes.Chip)
                    .background(Color.White)
                    .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Chip)
                    .tap(onClickLabel = "상담하러 가기") { onNavigate(com.project.on_road.ui.screens.navigation.Routes.chat()) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /*Icon(Icons.Outlined.SupportAgent, null, tint = OnRoadColors.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))*/
                Text("상담하기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
            }
        }
    }
}

/** 결과 맨 아래 '찾는 게 없나요?': 흰 카드 · 칩 테두리 (생활 가이드 진로 카드와 같은 모양), 오른쪽 가운데 '상담하기 ›' */
@Composable
private fun AskChatCard(onClick: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderChip,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("찾는 게 없나요?", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text("상담에서 내 상황에 맞게 물어봐요", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("상담하기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 키워드 태그: 앱 기본 태그(연회색 바탕 · 회색 테두리) 모양, 여러 줄로 감싼다 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KeywordTags(words: List<String>, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.forEach { w ->
            OnRoadTag(
                w,
                style = TagStyle.Neutral,
                modifier = Modifier.clip(OnRoadShapes.Tag).tap(onClickLabel = "$w 찾기") { onPick(w) },
            )
        }
    }
}

/**
 * 정책 검색창: 아래 정책 카드와 같은 톤 (흰 바탕 · 카드 테두리, 누르면 테두리가 메인 색).
 * 오른쪽 찾기 버튼은 글자를 넣었을 때만 메인 색.
 */
@Composable
private fun PolicySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClear: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = OnRoadShapes.Inner
    val line by androidx.compose.animation.animateColorAsState(
        if (focused) OnRoadColors.Primary else OnRoadColors.BorderSlate,
        com.project.on_road.ui.theme.OnRoadMotion.move(),
        label = "search-line",
    )
    val active = value.isNotBlank() && enabled
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(OnRoadColors.Surface)
            .border(1.dp, line, shape)
            // 글자 시작 위치를 아래 정책 카드 안쪽 글자와 맞춘다
            .padding(start = SearchSpace.FieldTextStart, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = OnRoadType.Body2.copy(color = OnRoadColors.TextPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(OnRoadColors.Primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch(value) }),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (value.isEmpty()) Text("무엇이 궁금한가요?", style = OnRoadType.Body2, color = OnRoadColors.TextQuaternary, maxLines = 1)
                inner()
            },
        )
        // 글자가 있으면 지우기 (지우면 검색 결과도 닫고 처음 화면으로)
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clip(OnRoadShapes.Field).tap(onClickLabel = "검색어 지우기", onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(androidx.compose.material.icons.Icons.Filled.Close, "지우기", tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(2.dp))
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(OnRoadShapes.Field)
                .background(if (active) OnRoadColors.Primary else Color.Transparent)
                .tap(enabled = active) { onSearch(value) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.Search, "찾기",
                tint = if (active) Color.White else OnRoadColors.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 작은 정책 카드 (분야별로 보기): 연회색 카드.
 * 첫 줄: 정책 이름(왼쪽) · 기관(오른쪽 정렬). 둘째 줄: 지원 내용, '전체'에서는 오른쪽에 분야.
 * 누르면 정책 상세로 간다 (저장은 상세 화면에서)
 */
@Composable
private fun SmallPolicyCard(policy: Policy, showCategory: Boolean, onOpen: () -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                policy.name,
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).sharedTitle(policyTitleKey(policy.id)),
            )
            if (policy.agency.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(policy.agency, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
            }
        }
        val category = policy.category.takeIf { showCategory && it.isNotBlank() }
        if (policy.amount.isNotBlank() || category != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    policy.amount,
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                category?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 검색 결과 아래 '이어서 해 보기': 분야별 작은 카드와 같은 모양 (연회색 카드, 왼쪽 이름 · 오른쪽 종류).
 * 카드 전체를 누르면 간다 (진한 '바로 가기' 버튼이 검색 버튼 · 정책 카드와 겹쳐 보여서 뺐다)
 */
@Composable
private fun SearchNextSteps(steps: List<com.project.on_road.data.NextStep>, onOpen: (com.project.on_road.data.NextStep) -> Unit) {
    if (steps.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SubHeader("이어서 해 보기")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEach { st ->
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    background = OnRoadColors.SurfaceSubtle,
                    borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
                    shape = OnRoadShapes.Item,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    onClick = { onOpen(st) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            st.label,
                            style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                            color = OnRoadColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(Links.stepKindLabel(st.kind), style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 검색창을 눌렀을 때: 최근 검색 · 인기 검색어 */
@Composable
private fun SearchSuggestions(vm: PolicySearchViewModel, onPick: (String) -> Unit) {
    Column {
        if (vm.recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("최근 검색", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    "전체 삭제",
                    style = OnRoadType.Caption2,
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.clip(OnRoadShapes.Chip).tap { vm.clearRecent() }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            vm.recent.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clip(OnRoadShapes.Chip).tap { onPick(q) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(q, style = OnRoadType.Body2, color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        "삭제",
                        style = OnRoadType.Micro,
                        color = OnRoadColors.TextQuaternary,
                        modifier = Modifier.clip(OnRoadShapes.Chip).tap { vm.removeRecent(q) }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
        }
        Text("인기 검색어", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        popularSearches.forEachIndexed { i, q ->
            Row(
                Modifier.fillMaxWidth().clip(OnRoadShapes.Chip).tap { onPick(q) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = if (i < 3) OnRoadColors.Primary else OnRoadColors.TextTertiary,
                    modifier = Modifier.width(28.dp),
                )
                Text(q, style = OnRoadType.Body2, color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
            .tap(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp))
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
    /** 있으면 핵심만 담은 카드로 그리고, 누르면 정책 상세로 간다 */
    onOpen: (() -> Unit)? = null,
) {
    if (onOpen != null) {
        CompactPolicyCard(policy, inCompare, compareEnabled, saved, onToggleCompare, onToggleSave, onOpen, modifier)
        return
    }
    OnRoadCard(
        modifier.fillMaxWidth(),
        borderColor = if (inCompare) OnRoadColors.Primary else OnRoadColors.BorderSlate,
        borderWidth = if (inCompare) 2.dp else 1.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnRoadTag(
                policy.category,
                style = TagStyle.Neutral,
                textStyle = OnRoadType.Caption2,
                shape = OnRoadShapes.Field,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            val tick = rememberTick()
            IconButton(onClick = { tick(); onToggleSave() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (saved) "저장 취소" else "저장",
                    tint = if (saved) OnRoadColors.Primary else OnRoadColors.TextMuted,
                    modifier = Modifier.popOn(saved),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(policy.name, style = OnRoadType.Title3, color = OnRoadColors.TextStrong, modifier = Modifier.sharedTitle(policyTitleKey(policy.id)))
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(policy.agency, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
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
            Text("쉬운말로 보기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
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
                        color = OnRoadColors.TextSecondary,
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
                .tap(onClick = onToggleEvidence)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("근거 원문 보기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
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
                                Text(ev.source, style = OnRoadType.Tiny, color = OnRoadColors.TextSecondary)
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
                    .tap { runCatching { uri.openUri(policy.sourceUrl) } }
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
                    .tap(haptic = true, enabled = compareEnabled, onClick = onToggleCompare)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckBoxMark(inCompare, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "비교 담기",
                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                    color = if (compareEnabled) OnRoadColors.TextSecondary else OnRoadColors.TextQuaternary,
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
                .tap(enabled = count >= 2, onClick = onCompare)
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
            items.forEach { Text(if (items.size > 1) "· $it" else it, style = OnRoadType.Micro, color = OnRoadColors.TextSecondary) }
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
            .tap(enabled = cut || open) { open = !open },
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextMuted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                if (open) value else first,
                style = OnRoadType.Micro,
                color = OnRoadColors.TextSecondary,
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

/** 검색 결과 카드: 이름 · 기관 · 조건 · 한두 줄 요약 · 받는 것만. 자세한 내용은 정책 상세에서 */
@Composable
private fun CompactPolicyCard(
    policy: Policy,
    inCompare: Boolean,
    compareEnabled: Boolean,
    saved: Boolean,
    onToggleCompare: () -> Unit,
    onToggleSave: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnRoadCard(
        modifier.fillMaxWidth(),
        borderColor = if (inCompare) OnRoadColors.Primary else OnRoadColors.BorderSlate,
        borderWidth = if (inCompare) 2.dp else 1.dp,
        onClick = onOpen,
    ) {
        // 하트 · 분야는 빼고, 이름 줄 오른쪽에 기관 (분야별 작은 카드와 같은 배치)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                policy.name,
                style = OnRoadType.Title3,
                color = OnRoadColors.TextStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).sharedTitle(policyTitleKey(policy.id)),
            )
            if (policy.agency.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(policy.agency, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1)
            }
        }
        when (policy.eligibility) {
            "match" -> { Spacer(Modifier.height(10.dp)); OnRoadTag("기본 조건 해당", style = TagStyle.PrimarySoft) }
            "needs_check" -> { Spacer(Modifier.height(10.dp)); OnRoadTag("조건 확인 필요", style = TagStyle.Warning) }
        }
        val line = policy.easySummary.ifBlank { policy.summary }
        if (line.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(line, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (policy.amount.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Field)
                    .background(OnRoadColors.SurfaceSubtle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("지원 내용", style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
                Spacer(Modifier.width(12.dp))
                Text(policy.amount, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(OnRoadShapes.Field)
                    .tap(haptic = true, enabled = compareEnabled, onClick = onToggleCompare)
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckBoxMark(inCompare, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "비교 담기",
                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                    color = if (compareEnabled) OnRoadColors.TextSecondary else OnRoadColors.TextQuaternary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("자세히 보기", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.Primary, modifier = Modifier.size(18.dp))
        }
    }
}
