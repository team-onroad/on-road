package com.project.on_road.ui.screens

import com.project.on_road.data.remote.userMessage
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.DDate
import com.project.on_road.data.DocGuide
import com.project.on_road.data.Policy
import com.project.on_road.data.RequiredDoc
import com.project.on_road.data.UserProfile
import com.project.on_road.ui.components.Appear
import com.project.on_road.ui.components.ButtonVariant
import com.project.on_road.ui.components.ChoiceChip
import com.project.on_road.ui.components.ExpandArea
import com.project.on_road.ui.components.flipWhen
import com.project.on_road.ui.components.EasyTextPanel
import com.project.on_road.ui.components.ErrorState
import com.project.on_road.ui.components.FadeSwitch
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadSwitch
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.OnRoadToast
import com.project.on_road.ui.components.SkeletonList
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.components.policyTitleKey
import com.project.on_road.ui.components.popOn
import com.project.on_road.ui.components.rememberTick
import com.project.on_road.ui.components.sharedTitle
import com.project.on_road.ui.components.smoothSwap
import com.project.on_road.ui.components.stagger
import com.project.on_road.ui.components.tap
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.Period

/* ================================================================== */
/*  정책 상세                                                            */
/*  위에서부터: 받는 금액 요약 → 내 조건 → 쉬운 말 → 신청 방법 → 서류 →     */
/*  문의 → 근거 원문. 숫자는 내 퇴소일 기준으로 앱이 계산한다 (AI 아님)      */
/* ================================================================== */

/** 정책 찾기에서 누른 정책을 잠시 들고 있다가 상세에서 바로 보여 준다 (다시 불러오는 깜빡임 없이) */
object PolicyCache {
    private val map = mutableMapOf<String, Policy>()
    fun put(p: Policy) { map[p.id] = p }
    fun get(id: String): Policy? = map[id]
}

/** 관심 정책: 검색 화면과 상세 화면이 같은 목록을 본다 */
object PolicySaves {
    val ids = mutableStateListOf<String>()
    /** 지금 받고 있다고 표시한 정책 (3.36). 받는 중이면 ids 에도 들어 있다 */
    val receiving = mutableStateListOf<String>()
    /** 받는 중 목록을 이 계정으로 한 번 받았는지 */
    var receivingLoadedFor: String? = null
}

class PolicyDetailViewModel : ViewModel() {
    private val api = AppContainer.api
    private val saveLock = kotlinx.coroutines.sync.Mutex()
    private val userId: String get() = AppContainer.session.userId.orEmpty()

    var policy by mutableStateOf<Policy?>(null)
        private set
    /** 내 나이 · 지역 · 퇴소일 (요약 계산과 내 조건 줄에 쓴다). 못 불러오면 null */
    var profile by mutableStateOf<UserProfile?>(com.project.on_road.data.ProfileStore.current)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var easy by mutableStateOf(true)

    private var loadedId: String? = null

    fun load(id: String) {
        if (loadedId == id) return
        loadedId = id
        PolicyCache.get(id)?.let { policy = it }
        viewModelScope.launch {
            if (PolicySaves.ids.isEmpty()) {
                runCatching { api.getSavedPolicyIds(userId) }.onSuccess { PolicySaves.ids.clear(); PolicySaves.ids.addAll(it) }
            }
            if (PolicySaves.receivingLoadedFor != userId) {
                runCatching { api.getReceivingPolicyIds(userId) }.onSuccess {
                    PolicySaves.receiving.clear(); PolicySaves.receiving.addAll(it); PolicySaves.receivingLoadedFor = userId
                }
            }
        }
        // 받아 둔 회원정보로 내 조건 줄을 바로 그리고, 뒤에서 새로 받아 맞춘다
        viewModelScope.launch { com.project.on_road.data.ProfileStore.refresh()?.let { profile = it } }
        val cached = policy != null
        viewModelScope.launch {
            if (!cached) { loading = true; error = null }
            // 검색 카드에서 온 정책에는 문의처 · 나이 조건이 없어서, 보여 준 뒤에도 상세를 한 번 받아 채운다
            runCatching { api.getPolicy(id) }
                .onSuccess { p ->
                    if (p != null) { policy = p; PolicyCache.put(p) }
                    else if (!cached) error = "정책 정보를 찾지 못했어요."
                }
                .onFailure { if (!cached) error = "정책을 불러오지 못했어요. 잠시 후 다시 시도해 주세요." }
            loading = false
        }
    }

    fun retry(id: String) {
        loadedId = null
        load(id)
    }

    /** 지금 받고 있어요 켜기 · 끄기. 켜면 관심 정책에도 담기고, 끄면 관심 정책으로 남는다. 로드맵을 다시 받는다 */
    fun toggleReceiving(id: String) {
        val on = id !in PolicySaves.receiving
        val wasSaved = id in PolicySaves.ids
        if (on) { PolicySaves.receiving.add(id); if (!wasSaved) PolicySaves.ids.add(id) } else PolicySaves.receiving.remove(id)
        OnRoadToast.show(if (on) "받고 있는 지원으로 표시했어요" else "받고 있는 지원에서 뺐어요")
        viewModelScope.launch {
            saveLock.withLock {
                val result = com.project.on_road.data.BackgroundWork.write { api.setReceiving(userId, id, on) }.await()
                result.exceptionOrNull()?.let {
                    // 화면만 바뀌고 서버엔 안 들어갔으면 되돌린다 (그 사이 다시 누르지 않았을 때만)
                    if ((id in PolicySaves.receiving) == on) {
                        if (on) { PolicySaves.receiving.remove(id); if (!wasSaved) PolicySaves.ids.remove(id) } else PolicySaves.receiving.add(id)
                    }
                    OnRoadToast.show(it.userMessage("받고 있는 지원 표시를 바꾸지 못했어요."))
                } ?: com.project.on_road.data.PlanData.refreshRoadmap()
            }
        }
    }

    fun toggleSave(id: String) {
        val save = id !in PolicySaves.ids
        if (save) PolicySaves.ids.add(id) else PolicySaves.ids.remove(id)
        // 관심 정책에서 빼면 서버 저장 줄이 지워져 받는 중 표시도 함께 사라진다
        val wasReceiving = !save && PolicySaves.receiving.remove(id)
        OnRoadToast.show(if (save) "관심 정책에 담았어요" else "관심 정책에서 뺐어요")
        // 빠르게 여러 번 눌러도 누른 순서대로 서버에 보낸다 (마지막 상태가 서버와 같게)
        viewModelScope.launch {
            saveLock.withLock {
                com.project.on_road.data.BackgroundWork.write { api.savePolicy(userId, id, save) }.await().onFailure {
                    // 화면에는 담겼는데 서버엔 저장되지 않았으면 되돌린다 (그 사이 다시 누르지 않았을 때만)
                    if ((id in PolicySaves.ids) == save) {
                        if (save) PolicySaves.ids.remove(id) else { PolicySaves.ids.add(id); if (wasReceiving) PolicySaves.receiving.add(id) }
                    }
                    OnRoadToast.show(it.userMessage(if (save) "관심 정책에 담지 못했어요." else "관심 정책에서 빼지 못했어요."))
                }
            }
        }
    }
}

private enum class DetailState { Loading, Error, Ready }

@Composable
fun PolicyDetailScreen(
    policyId: String,
    onBack: () -> Unit,
    onPrepare: (String) -> Unit,
    /** 이 정책 신청서를 서류 작성에서 연다 */
    onWrite: (String) -> Unit = {},
    /** 이 정책을 AI 상담에 묻는다 (질문 문장) */
    onAsk: (String) -> Unit = {},
    /** 같이 보면 좋은 정책을 누르면 그 정책 상세로 */
    onOpenPolicy: (String) -> Unit = {},
    /** 같은 종류 전체 보기 (다른 지역 것까지) */
    onSameKind: (String) -> Unit = {},
    vm: PolicyDetailViewModel = viewModel(key = "policy-detail-$policyId"),
) {
    LaunchedEffect(policyId) { vm.load(policyId) }
    val p = vm.policy
    val saved = policyId in PolicySaves.ids
    val tick = rememberTick()

    val bar: (@Composable ColumnScope.() -> Unit)? = if (p == null) null else {
            {
                // 아래에 늘 붙어 있는 행동: 관심 담기 + 신청 준비하기
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(OnRoadShapes.Inner)
                            .background(Color.White)
                            .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Inner)
                            .tap(onClickLabel = if (saved) "관심 정책에서 빼기" else "관심 정책에 담기") { tick(); vm.toggleSave(policyId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            null,
                            tint = if (saved) OnRoadColors.Primary else OnRoadColors.TextMuted,
                            modifier = Modifier.size(22.dp).popOn(saved, enabled = saved),
                        )
                    }
                    OnRoadButton(
                        text = "이 정책 신청 준비하기",
                        onClick = { onPrepare(policyId) },
                        modifier = Modifier.weight(1f),
                        trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                        shape = OnRoadShapes.Inner,
                    )
                }
            }
        }
    OnRoadScreen(title = "정책 상세", onBack = onBack, bottomBar = bar) {
        val state = when {
            p != null -> DetailState.Ready
            vm.error != null -> DetailState.Error
            else -> DetailState.Loading
        }
        FadeSwitch(state, label = "policy-detail") { s ->
            when (s) {
                DetailState.Loading -> SkeletonList(rows = 3)
                DetailState.Error -> ErrorState(vm.error.orEmpty(), onRetry = { vm.retry(policyId) }, title = "정책을 불러오지 못했어요")
                DetailState.Ready -> vm.policy?.let { pol ->
                    PolicyDetailBody(
                        policy = pol,
                        profile = vm.profile,
                        easy = vm.easy,
                        onToggleEasy = { vm.easy = !vm.easy },
                        onWrite = { onWrite(pol.id) },
                        onAsk = { onAsk("${pol.name} 신청하려면 어떻게 해야 해?") },
                        onOpenPolicy = onOpenPolicy,
                        onSameKind = { onSameKind(pol.id) },
                        receiving = policyId in PolicySaves.receiving,
                        onToggleReceiving = { tick(); vm.toggleReceiving(policyId) },
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  본문                                                                */
/* ------------------------------------------------------------------ */

/** 정책 상세 간격 (위계: 이름 22 > 금액 24 > 섹션 제목 17 > 카드 제목 15 > 내용 13~15) */
private object DetailSpace {
    val MetaTop = 4.dp          // 이름 → 기관 줄
    val SummaryTop = 24.dp      // 머리 → 요약 카드
    val Section = 44.dp         // 섹션 사이
    val TitleGap = 14.dp        // 섹션 제목 → 내용
    val CardGap = 8.dp          // 카드 사이
    val CardH = 16.dp           // 카드 안 가로 여백 (모든 카드 같게)
    val CardV = 14.dp           // 카드 안 세로 여백
}

@Composable
private fun PolicyDetailBody(
    policy: Policy,
    profile: UserProfile?,
    easy: Boolean,
    onToggleEasy: () -> Unit,
    onWrite: () -> Unit,
    onAsk: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    onSameKind: () -> Unit = {},
    receiving: Boolean = false,
    onToggleReceiving: () -> Unit = {},
) {
    // ── 머리: 분야 · 이름 · 기관 · 기준일 ──
    OnRoadTag(policy.category, style = TagStyle.Neutral)
    Spacer(Modifier.height(12.dp))
    Text(policy.name, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary, modifier = Modifier.sharedTitle(policyTitleKey(policy.id)))
    Spacer(Modifier.height(DetailSpace.MetaTop))
    Text("${policy.agency} · ${policy.checkedAt} 기준", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
    if (policy.isOutdated) {
        Spacer(Modifier.height(8.dp))
        OnRoadTag("확인일이 오래된 정보예요 · 최신 내용은 원문에서 확인", style = TagStyle.Danger)
    }

    Spacer(Modifier.height(DetailSpace.SummaryTop))
    // 상세 · 내 정보가 늦게 오면 그 묶음만 부드럽게 다시 나타난다 (뚝 바뀌지 않게)
    Appear(profile != null, policy.applyWays.size, policy.amountWon) { SummaryCard(policy, profile) }
    Spacer(Modifier.height(DetailSpace.CardGap))
    ReceivingCard(receiving, onToggleReceiving)
    policy.requiredEducation?.let { code ->
        Spacer(Modifier.height(DetailSpace.CardGap))
        EducationCard(code)
    }

    Spacer(Modifier.height(DetailSpace.Section))
    Appear(profile != null, policy.conditions.size, policy.exclusions.size) { ConditionSection(policy, profile, onWrite, onAsk) }

    // ── 쉬운 말 ↔ 원문 ──
    Spacer(Modifier.height(DetailSpace.Section))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("어떤 정책인가요", Modifier.weight(1f))
        Text("쉬운 말", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
        Spacer(Modifier.width(6.dp))
        OnRoadSwitch(checked = easy, onCheckedChange = { onToggleEasy() })
    }
    Spacer(Modifier.height(DetailSpace.TitleGap))
    AnimatedContent(targetState = easy, transitionSpec = { smoothSwap() }, label = "detail-summary") { isEasy ->
        if (isEasy) EasyTextPanel(policyId = policy.id, fallback = policy.easySummary, sectioned = true)
        else Text(
            policy.summary,
            style = OnRoadType.Body2.copy(lineHeight = OnRoadType.Body2.lineHeight * 1.1f),
            color = OnRoadColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    Appear(policy.applyWays.size, policy.applySteps.size, policy.howToApply.isBlank()) { ApplyWaysSection(policy) }
    // 정리된 서류 구절이 있으면 그걸로 (원문 서류 줄은 설명이 붙어 길다)
    Appear(policy.docFacts.size, policy.requiredDocs.size) {
        DocsSection(remember(policy.docFacts, policy.requiredDocs) { DetailCalc.docsFrom(policy.docFacts, policy.requiredDocs) ?: policy.requiredDocs })
    }
    Appear(policy.sameKind != null) { SameKindSection(policy, onOpenPolicy, onSameKind) }
    Appear(policy.relatedPolicies.size) { RelatedSection(policy, onOpenPolicy) }
    Appear(policy.contact.isBlank()) { ContactSection(policy) }
    SourceSection(policy)
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = OnRoadType.Headline, color = OnRoadColors.TextPrimary, modifier = modifier)

/** 연회색 데이터 카드 (모든 카드 같은 모양 · 같은 여백) */
@Composable
private fun DataCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Inner)
            .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)
            .padding(horizontal = DetailSpace.CardH, vertical = DetailSpace.CardV),
        content = content,
    )
}

/* ── 지금 받고 있어요 ────────────────────────────────────────────── */

/** 이미 받고 있는 지원이면 켠다. 맞춤 신청 로드맵이 이 정책을 '받고 있어요'로 두고, 더 받을 수 있는 것만 따로 센다 */
@Composable
private fun ReceivingCard(receiving: Boolean, onToggle: () -> Unit) {
    DataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (receiving) "지금 받고 있는 지원이에요" else "이미 받고 있는 지원인가요?",
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold),
                    color = OnRoadColors.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (receiving) "로드맵에서 새로 신청할 지원과 따로 보여 줘요" else "켜 두면 로드맵이 더 받을 수 있는 지원만 골라 줘요",
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(12.dp))
            OnRoadSwitch(checked = receiving, onCheckedChange = { onToggle() })
        }
    }
}

/**
 * 들어야 받을 수 있는 교육 (자립정착금 의무교육 · 자립수당 자립교육, 3.37).
 * 이수했다고 넣으면 신청 일정(로드맵)에도 '이수함'으로 보인다. 날짜 · 다른 교육은 설정 > 교육 · 자격증에서
 */
@Composable
private fun EducationCard(code: String) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { com.project.on_road.data.CredentialStore.refresh() }
    val name = com.project.on_road.data.DEFAULT_EDUCATION_OPTIONS.firstOrNull { it.code == code }?.name ?: "의무교육"
    val done = com.project.on_road.data.CredentialStore.items?.firstOrNull { it.isEducation && it.code == code }
    var saving by remember { mutableStateOf(false) }
    DataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (done != null) "${name}을 이수했어요" else "${name}을 들어야 받아요",
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold),
                    color = OnRoadColors.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        done?.doneOn != null -> "${done.doneOn}에 이수했어요. 확인서를 신청 서류와 같이 내요"
                        done != null -> "확인서를 신청 서류와 같이 내요"
                        code == "settlement" -> "자립지원전담기관에 교육 일정을 물어보세요"
                        else -> "온라인 자립교육을 듣고 이수증을 받아 두세요"
                    },
                    style = OnRoadType.Caption,
                    color = OnRoadColors.TextTertiary,
                )
            }
            if (done == null && com.project.on_road.data.CredentialStore.items != null) {
                Spacer(Modifier.width(12.dp))
                OnRoadButton(
                    text = "들었어요",
                    onClick = {
                        if (saving) return@OnRoadButton
                        saving = true
                        scope.launch {
                            com.project.on_road.data.CredentialStore.add(com.project.on_road.data.Credential.EDUCATION, null, code, null)
                                .onFailure { OnRoadToast.show("넣지 못했어요. 인터넷 연결을 확인해 주세요.") }
                            saving = false
                        }
                    },
                    variant = ButtonVariant.Outline,
                    height = 40.dp,
                    textStyle = OnRoadType.Body3,
                    loading = saving,
                )
            }
        }
    }
}

/* ── 받는 금액 요약 ─────────────────────────────────────────────── */

@Composable
private fun SummaryCard(policy: Policy, profile: UserProfile?) {
    val s = remember(policy, profile) { DetailCalc.summary(policy, profile) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Inner)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Inner)
            .padding(horizontal = DetailSpace.CardH, vertical = 20.dp),
    ) {
        // 위계: 라벨(12 회색) → 받는 것(돈이면 금액 24 굵게, 돈이 아니면 내용 20) → 보조(13 회색) → 날짜 줄
        Text("받는 것", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        Text(
            s.amount,
            style = when {
                s.money && s.amount.length <= 14 -> OnRoadType.Display
                s.amount.length <= 24 -> OnRoadType.Title2.copy(fontWeight = FontWeight.SemiBold)
                else -> OnRoadType.Headline
            },
            color = OnRoadColors.TextPrimary,
        )
        if (s.sub.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(s.sub, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
        if (s.rows.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(label, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.width(72.dp))
                        Text(
                            value,
                            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                            color = OnRoadColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

/* ── 내 조건 ───────────────────────────────────────────────────── */

/** 보호 종료와 같은 뜻의 대상 조건 */
private val LEAVE_COND = Regex("""보호\s*종료|퇴소|보호조치""")

private enum class CondState(val label: String) { Yes("해당"), No("해당 없음"), Check("확인 필요") }

@Composable
private fun ConditionRow(
    label: String,
    state: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    /** 이름 아래 회색 한 줄 (기간 조건의 마감 날짜 등) */
    sub: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Field)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Field)
            .then(if (onClick != null) Modifier.tap(onClickLabel = "$label 확인하기", onClick = onClick) else Modifier)
            .padding(horizontal = DetailSpace.CardH, vertical = DetailSpace.CardV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium, lineHeight = OnRoadType.Body2.fontSize * 1.5f),
                color = OnRoadColors.TextStrong,
            )
            sub?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(state, style = OnRoadType.Body3.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/**
 * 대상 조건 확인 시트: 조건을 쉬운 말로 풀고, 기간 조건은 내 보호종료일로 마감 날짜를 계산한다.
 * '맞아요' · '아니에요'로 답하면 내 자립 조건에 저장되고, 같은 조건이 있는 모든 정책에 같이 반영된다.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConditionSheet(text: String, profile: UserProfile?, onDismiss: () -> Unit) {
    val answered = com.project.on_road.data.MyConditions.version
    val help = remember(text, profile, answered) { com.project.on_road.data.ConditionHelp.explain(text, profile) }
    val current = remember(text, answered) { com.project.on_road.data.MyConditions.answer(text) }
    var pickingLeft by remember { mutableStateOf(false) }
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun close(after: () -> Unit = {}) {
        scope.launch { state.hide() }.invokeOnCompletion { after(); onDismiss() }
    }
    val dot = java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd")
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(text, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
            if (help.lines.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("쉽게 말하면", style = OnRoadType.Caption.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextTertiary)
                Spacer(Modifier.height(6.dp))
                help.lines.forEach { line ->
                    Text(line, style = OnRoadType.Body2.copy(lineHeight = OnRoadType.Body2.fontSize * 1.6f), color = OnRoadColors.TextStrong, modifier = Modifier.padding(top = 4.dp))
                }
            }
            help.period?.let { p ->
                Spacer(Modifier.height(16.dp))
                DataCard {
                    if (p.needsLeaveDate) {
                        Text("퇴소한 날을 넣으면 언제까지 받을 수 있는지 계산해요", style = OnRoadType.Body3, color = OnRoadColors.TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        OnRoadButton(
                            text = "퇴소한 날 넣기",
                            onClick = { pickingLeft = true },
                            modifier = Modifier.fillMaxWidth(),
                            variant = ButtonVariant.Outline,
                            height = 44.dp,
                            textStyle = OnRoadType.Body3.copy(fontWeight = FontWeight.Medium),
                        )
                    } else if (p.base != null && p.end != null) {
                        SheetRow(p.baseLabel, p.base.format(dot))
                        Spacer(Modifier.height(6.dp))
                        SheetRow("받을 수 있는 날", "${p.end.format(dot)}까지")
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (p.within == true) "지금은 기간 안이에요" else "기간이 지났어요",
                            style = OnRoadType.Body3.copy(fontWeight = FontWeight.SemiBold),
                            color = if (p.within == true) OnRoadColors.TextPrimary else OnRoadColors.TextTertiary,
                        )
                    } else {
                        Text("퇴소 예정일을 정하면 언제까지 받을 수 있는지 계산해요", style = OnRoadType.Body3, color = OnRoadColors.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("나에게 맞는 조건인가요?", style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OnRoadButton(
                    text = "아니에요",
                    onClick = {
                        close {
                            com.project.on_road.data.MyConditions.setAnswer(text, false)
                            OnRoadToast.show("해당 없음으로 저장했어요")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    variant = if (current == false) ButtonVariant.Primary else ButtonVariant.Outline,
                )
                OnRoadButton(
                    text = "맞아요",
                    onClick = {
                        close {
                            com.project.on_road.data.MyConditions.setAnswer(text, true)
                            OnRoadToast.show("내 자립 조건에 넣었어요")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    variant = if (current == false) ButtonVariant.Outline else ButtonVariant.Primary,
                )
            }
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = { close { com.project.on_road.data.MyConditions.setAnswer(text, null) } },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("답 지우기", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary) }
            }
        }
    }
    if (pickingLeft) {
        ProfileDateDialog(com.project.on_road.data.MyConditions.leftDate, past = true, onDismiss = { pickingLeft = false }) { d ->
            pickingLeft = false
            com.project.on_road.data.MyConditions.leftDate = d
        }
    }
}

@Composable
private fun SheetRow(label: String, value: String) {
    Row {
        Text(label, style = OnRoadType.Body3, color = OnRoadColors.TextTertiary, modifier = Modifier.width(96.dp))
        Text(value, style = OnRoadType.Body3.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
    }
}

/**
 * 내 조건: 나이 · 지역은 앱이 판정하고, 그 밖의 대상 조건은 정책 요약의 '지원 대상' 구절을 그대로 한 줄씩 (확인 필요).
 * '받을 수 없는 경우'는 따로 묶는다. 안내 문구는 두지 않는다
 */
@Composable
private fun ConditionSection(policy: Policy, profile: UserProfile?, onWrite: () -> Unit, onAsk: () -> Unit) {
    // 내가 답한 조건 · 퇴소일이 바뀌면 다시 계산한다
    val answered = com.project.on_road.data.MyConditions.version
    val base = remember(policy, profile) { DetailCalc.conditions(policy, profile) }
    val conds = remember(policy.conditions, profile, answered) { policy.conditions.map { DetailCalc.myCondition(it, profile) } }
    // 보호 종료 줄과 같은 뜻의 대상 조건(보호종료 · 퇴소 …)이 있으면 한 카드로 합친다
    val leaveAt = if (policy.leave in setOf("after_leave", "now")) conds.indexOfFirst { LEAVE_COND.containsMatchIn(it.text) } else -1
    val rows = if (leaveAt >= 0) base.filterNot { it.first.startsWith("보호 종료") } else base
    val extra = if (leaveAt < 0) conds else conds.mapIndexed { i, c ->
        when {
            i != leaveAt -> c
            policy.leave == "after_leave" -> c.copy(state = CondState.No, sub = "지금은 보호 중이라 퇴소 후 신청")
            c.state == CondState.Check -> c.copy(state = CondState.Yes)
            else -> c
        }
    }
    var opened by remember { mutableStateOf<String?>(null) }
    fun colorOf(state: CondState) = when (state) {
        CondState.Yes -> OnRoadColors.TextSecondary
        CondState.No -> OnRoadColors.TextTertiary
        CondState.Check -> OnRoadColors.Warning
    }
    SectionTitle("내 조건")
    Spacer(Modifier.height(DetailSpace.TitleGap))
    Column(verticalArrangement = Arrangement.spacedBy(DetailSpace.CardGap)) {
        rows.forEachIndexed { i, (label, state) ->
            ConditionRow(label, state.label, colorOf(state), Modifier.stagger(i))
        }
        // 그 밖의 대상 조건: 누르면 쉬운 설명과 함께 맞다 · 아니다를 고른다 (모든 정책에 같이 반영)
        extra.forEachIndexed { j, c ->
            ConditionRow(c.text, c.state.label, colorOf(c.state), Modifier.stagger(rows.size + j), sub = c.sub) { opened = c.text }
        }
    }
    opened?.let { text -> ConditionSheet(text, profile, onDismiss = { opened = null }) }
    if (policy.exclusions.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("이런 경우엔 받을 수 없어요", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(DetailSpace.CardGap)) {
            policy.exclusions.forEach { ConditionRow(it, "제외", OnRoadColors.TextTertiary, sub = com.project.on_road.data.ConditionHelp.explainExclusion(it)) }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnRoadButton(
            text = "신청서 쓰기",
            onClick = onWrite,
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Outline,
            height = 44.dp,
            textStyle = OnRoadType.Body3.copy(fontWeight = FontWeight.Medium),
        )
        OnRoadButton(
            text = "AI에게 묻기",
            onClick = onAsk,
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Outline,
            height = 44.dp,
            textStyle = OnRoadType.Body3.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/* ── 신청 방법 ─────────────────────────────────────────────────── */

@Composable
private fun ApplyWaysSection(policy: Policy) {
    // 정리본(쉬운 문장)이 있으면 그대로, 없으면 구절 · 원문을 갈래별로 잘라서
    val plan = remember(policy.applyWays, policy.applySteps, policy.howToApply) { DetailCalc.plan(policy) }
    if (plan.ways.isEmpty()) return
    Spacer(Modifier.height(DetailSpace.Section))
    SectionTitle("신청 방법")
    Spacer(Modifier.height(DetailSpace.TitleGap))
    Column(verticalArrangement = Arrangement.spacedBy(DetailSpace.CardGap)) {
        plan.ways.forEachIndexed { i, w ->
            DataCard(Modifier.stagger(i)) {
                // 방문 · 온라인 · 전화 … 는 "○○ 신청"으로
                val title = if (w.label in DetailCalc.WAY_NAMES) "${w.label} 신청" else w.label
                Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                w.lines.forEachIndexed { j, line ->
                    Spacer(Modifier.height(if (j == 0) 6.dp else 4.dp))
                    Text(line, style = OnRoadType.Body3, color = OnRoadColors.TextSecondary)
                }
            }
        }
    }
}

/* ── 필요 서류 ─────────────────────────────────────────────────── */

/** 서류 탭: 조건 문장으로 나눈다. 조건이 없거나 기본(방문 신청 시 준비 등)이면 '직접' */
private enum class DocTab(val label: String) { Direct("직접"), Online("온라인"), Proxy("대리인"), Mail("우편·팩스"), Case("해당할 때만") }

/**
 * 필요 서류: 직접 · 대리인 · 우편·팩스 · 해당할 때만 탭으로 나눠 한 번에 보이는 서류를 줄인다 (나뉠 게 없으면 탭 없이).
 * '또는' · '중 1종'으로 이어진 서류는 접힌 카드로, 누르면 고를 수 있는 서류가 펼쳐진다
 */
@Composable
private fun DocsSection(docs: List<RequiredDoc>) {
    if (docs.isEmpty()) return
    // 서류가 아닌 줄(문의처 · 홈페이지 확인 · 방문 안내 · "서류 없음")은 빼고, 제목 줄은 '언제'로, 같은 서류는 한 번만 (DocClean)
    val views = remember(docs) {
        com.project.on_road.data.DocClean.clean(docs.filterNot { d -> d.name.contains("http") })
            .map(DetailCalc::doc)
    }
    if (views.isEmpty()) return
    val tabs = remember(views) { DocTab.entries.filter { t -> views.any { it.tab == t } } }
    var picked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val tab = tabs.getOrElse(picked) { tabs.first() }
    Spacer(Modifier.height(DetailSpace.Section))
    SectionTitle("필요 서류")
    Spacer(Modifier.height(DetailSpace.TitleGap))
    if (tabs.size > 1) {
        Row(
            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { i, t -> ChoiceChip(t.label, selected = t == tab) { picked = i } }
        }
        Spacer(Modifier.height(12.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(DetailSpace.CardGap)) {
        views.filter { tabs.size <= 1 || it.tab == tab }.forEachIndexed { i, v ->
            Box(Modifier.stagger(i)) {
                when {
                    v.options.isEmpty() -> DocCard(v)
                    // 받는 방식(온라인 · 상담)으로 나뉘는 서류는 펼쳐서, 신분증처럼 이름만 다른 서류는 태그로
                    v.options.any { DocGuide.option(it).way != null } -> ChoiceDocCard(v)
                    else -> TagChoiceDocCard(v)
                }
            }
        }
    }
}

@Composable
private fun DocCard(v: DocView) {
    DataCard {
        Text(v.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
        v.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = OnRoadType.Body3, color = OnRoadColors.TextSecondary)
        }
        v.rows.forEachIndexed { j, (label, value) ->
            Row(Modifier.padding(top = if (j == 0) 8.dp else 4.dp)) {
                Text(label, style = OnRoadType.Body3, color = OnRoadColors.TextTertiary, modifier = Modifier.width(48.dp))
                Text(value, style = OnRoadType.Body3, color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * 하나만 내면 되는 서류: 처음엔 접혀 있고, 누르면 고를 수 있는 서류가 카드 안에서 펼쳐진다.
 * 서류마다 왼쪽에 받는 방식 태그(온라인 · 상담), 이름과 기관, 누르면 어디서 · 어떻게 줄이 열린다.
 */
@Composable
private fun ChoiceDocCard(v: DocView) {
    var open by remember(v.name) { mutableStateOf(false) }
    val options = remember(v.options) { v.options.map(DocGuide::option) }
    DataCard {
        Row(
            Modifier.fillMaxWidth().tap(onClickLabel = if (open) "접기" else "펼치기") { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(v.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("${options.size}개 중 하나만 내면 돼요", style = OnRoadType.Body3, color = OnRoadColors.TextSecondary)
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                null,
                tint = OnRoadColors.TextTertiary,
                modifier = Modifier.size(22.dp).flipWhen(open),
            )
        }
        ExpandArea(open) {
            Column(Modifier.padding(top = 4.dp)) {
                // 처음 펼칠 때는 첫 서류의 안내까지 열어 둔다
                options.forEachIndexed { i, o -> ChoiceOptionRow(o, startOpen = i == 0) }
            }
        }
    }
}

/** 선택지 한 줄: [태그] 이름 / 기관, 누르면 안내 줄이 펼쳐진다 (안내가 없으면 펼치지 않음) */
@Composable
private fun ChoiceOptionRow(o: DocGuide.Option, startOpen: Boolean) {
    val canOpen = o.rows.isNotEmpty()
    var open by remember(o.title) { mutableStateOf(startOpen && canOpen) }
    val indent = if (o.way != null) DocOption.TagWidth + DocOption.TagGap else 0.dp
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (canOpen) Modifier.tap(onClickLabel = if (open) "접기" else "펼치기") { open = !open } else Modifier)
                .padding(top = 12.dp, bottom = if (open) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            o.way?.let { WayTag(it) }
            if (o.way != null) Spacer(Modifier.width(DocOption.TagGap))
            Column(Modifier.weight(1f)) {
                Text(o.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                o.org?.let {
                    Spacer(Modifier.height(1.dp))
                    Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            }
            if (canOpen) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    null,
                    tint = OnRoadColors.TextTertiary,
                    modifier = Modifier.size(20.dp).flipWhen(open),
                )
            }
        }
        ExpandArea(open) {
            Column(Modifier.padding(start = indent, bottom = 12.dp)) {
                o.rows.forEachIndexed { j, (label, value) ->
                    Row(Modifier.padding(top = if (j == 0) 0.dp else 4.dp)) {
                        Text(label, style = OnRoadType.Body3, color = OnRoadColors.TextTertiary, modifier = Modifier.width(48.dp))
                        Text(value, style = OnRoadType.Body3, color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 이름만 다른 서류 중 하나 (신분증: 주민등록증 · 여권 …): 펼치지 않고 태그로 나열한다.
 * 태그를 누르면 아래 한 줄 안내가 그 서류 것으로 바뀐다. 안내가 있는 서류가 없으면 태그만.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagChoiceDocCard(v: DocView) {
    val options = remember(v.options) { v.options.map(DocGuide::option) }
    var picked by remember(v.name) { mutableStateOf(0) }
    val hasInfo = options.any { it.rows.isNotEmpty() }
    DataCard {
        Text(v.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { i, o ->
                OptionTag(o.title, selected = hasInfo && i == picked) { picked = i }
            }
        }
        if (hasInfo) {
            AnimatedContent(targetState = picked, transitionSpec = { smoothSwap() }, label = "doc-option-info") { i ->
                val rows = options.getOrNull(i)?.rows.orEmpty()
                Column(Modifier.padding(top = 10.dp)) {
                    rows.forEachIndexed { j, (label, value) ->
                        Row(Modifier.padding(top = if (j == 0) 0.dp else 4.dp)) {
                            Text(label, style = OnRoadType.Body3, color = OnRoadColors.TextTertiary, modifier = Modifier.width(48.dp))
                            Text(value, style = OnRoadType.Body3, color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** 서류 이름 태그: 흰 바탕 + 진한 테두리, 고르면 메인 색 */
@Composable
private fun OptionTag(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) OnRoadColors.Primary else OnRoadColors.Surface, label = "opt-bg")
    val line by androidx.compose.animation.animateColorAsState(if (selected) OnRoadColors.Primary else OnRoadColors.BorderChip, label = "opt-line")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) OnRoadColors.Surface else OnRoadColors.TextPrimary, label = "opt-fg")
    Box(
        Modifier
            .height(30.dp)
            .clip(OnRoadShapes.Tag)
            .background(bg)
            .border(1.dp, line, OnRoadShapes.Tag)
            .tap(onClickLabel = "$text 안내 보기", onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OnRoadType.Caption.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** 받는 방식 태그: 흰 바탕 + 진한 테두리, 모든 태그 같은 폭 (서류 이름이 한 줄로 서게) */
@Composable
private fun WayTag(text: String) {
    Box(
        Modifier
            .width(DocOption.TagWidth)
            .height(26.dp)
            .clip(OnRoadShapes.Tag)
            .background(OnRoadColors.Surface)
            .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary, maxLines = 1)
    }
}

private object DocOption {
    val TagWidth = 56.dp
    val TagGap = 12.dp
}

/* ── 같은 종류 지원: 함께 받기 어려운 것 중 내 조건에서 더 유리한 것 (서버 3.32 와 같은 기준) ── */

@Composable
private fun SameKindSection(policy: Policy, onOpen: (String) -> Unit, onAll: () -> Unit) {
    val k = policy.sameKind ?: return
    Spacer(Modifier.height(DetailSpace.Section))
    SectionTitle("같은 종류 ${k.kindTitle}")
    Spacer(Modifier.height(6.dp))
    Text(k.reason, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(DetailSpace.TitleGap))
    // 더 유리한 정책이 따로 있으면 그것부터, 이 정책이 가장 유리하면 나머지만
    val list = if (k.isBest) k.others else listOf(k.best) + k.others.filter { it.policyId != k.best.policyId }
    Column(verticalArrangement = Arrangement.spacedBy(DetailSpace.CardGap)) {
        list.forEachIndexed { i, a ->
            DataCard(Modifier.stagger(i), onClick = { onOpen(a.policyId) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(3.dp))
                        Text(listOfNotNull(a.region, a.amountText).joinToString(", "), style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    }
                    if (!k.isBest && i == 0) {
                        OnRoadTag("더 유리", style = TagStyle.PrimaryOutline)
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    Text(
        "다른 지역 것까지 보기 ›",
        style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
        color = OnRoadColors.TextSecondary,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp).tap(onClick = onAll).padding(vertical = 6.dp),
    )
}

/* ── 같이 보면 좋은 정책 (서버가 같은 분야 · 받을 수 있는 정책으로 고름) ── */

@Composable
private fun RelatedSection(policy: Policy, onOpen: (String) -> Unit) {
    if (policy.relatedPolicies.isEmpty()) return
    Spacer(Modifier.height(DetailSpace.Section))
    SectionTitle("같이 보면 좋은 정책")
    Spacer(Modifier.height(DetailSpace.TitleGap))
    Column(verticalArrangement = Arrangement.spacedBy(DetailSpace.CardGap)) {
        policy.relatedPolicies.forEachIndexed { i, r ->
            DataCard(Modifier.stagger(i), onClick = { onOpen(r.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(3.dp))
                        // 받는 것 (돈이 아닌 정책도 있어서 금액 대신). 없으면 기관 · 금액
                        Text(r.benefit ?: listOfNotNull(r.agency, r.amountText).joinToString(" · "), style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/* ── 문의 ──────────────────────────────────────────────────────── */

@Composable
private fun ContactSection(policy: Policy) {
    val c = remember(policy.contact, policy.agency) { DetailCalc.contact(policy.contact, policy.agency) } ?: return
    val uri = LocalUriHandler.current
    Spacer(Modifier.height(DetailSpace.Section))
    SectionTitle("문의")
    Spacer(Modifier.height(DetailSpace.TitleGap))
    DataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                if (c.sub.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(c.sub, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            }
            c.phone?.let { phone ->
                Spacer(Modifier.width(12.dp))
                OnRoadButton(
                    text = "전화",
                    onClick = { runCatching { uri.openUri("tel:${phone.filter { it.isDigit() }}") } },
                    variant = ButtonVariant.Outline,
                    height = OnRoadDimens.ButtonHeightSmall,
                    textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

/* ── 근거 원문 ─────────────────────────────────────────────────── */

@Composable
private fun SourceSection(policy: Policy) {
    if (policy.sourceUrl.isBlank()) return
    val uri = LocalUriHandler.current
    Spacer(Modifier.height(DetailSpace.Section))
    SectionTitle("근거 원문")
    Spacer(Modifier.height(DetailSpace.TitleGap))
    DataCard(onClick = { runCatching { uri.openUri(policy.sourceUrl) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${policy.agency} 공식 안내", style = OnRoadType.Body3.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(3.dp))
                Text(DetailCalc.host(policy.sourceUrl), style = OnRoadType.Caption2, color = OnRoadColors.TextMuted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  계산 (규칙): 금액 · 기간 · 날짜 · 조건 · 서류 · 문의 정리               */
/* ------------------------------------------------------------------ */

private data class MyCondView(val text: String, val state: CondState, val sub: String?)
private data class SummaryView(val amount: String, val sub: String, val rows: List<Pair<String, String>>, val money: Boolean = true)
private data class DocView(
    val name: String,
    /** 이름 아래 회색 한 줄 (본인 명의 같은 짧은 조건, '해당할 때만' 탭의 경우) */
    val note: String?,
    val rows: List<Pair<String, String>>,
    val tab: DocTab = DocTab.Direct,
    /** 하나만 내면 되는 서류의 선택지 (비어 있으면 보통 서류) */
    val options: List<String> = emptyList(),
)
private data class ContactView(val title: String, val sub: String, val phone: String?)

private object DetailCalc {
    private val MONTHLY = Regex("""월\s*([\d,.]+)\s*(만\s*)?원""")
    private val YEARS = Regex("""(보호\s*종료|퇴소)\s*(후|일로부터|부터|된\s*때부터)?\s*(\d+)\s*년""")
    private val PAYDAY = Regex("""매월\s*(\d{1,2})\s*일""")
    private val PRE_DAYS = Regex("""종료\s*전\s*(\d+)\s*일""")
    private val WAY = Regex("""(방문|온라인|우편·팩스|우편|팩스|전화)\s*:\s*""")
    private val PAREN = Regex("""\([^)]*\)""")
    private val PHONE = Regex("""\d{2,4}-\d{3,4}-\d{4}|(?<!\d)1\d{2,3}(?!\d)""")

    fun summary(p: Policy, u: UserProfile?): SummaryView {
        // 받는 것: 서버가 읽은 금액이 있으면 금액, 없으면 지원 내용 한 줄 (주거 · 의료 · 상담처럼 돈이 아닌 정책)
        val money = p.amountShort.isNotBlank()
        val amount = p.amountShort.ifBlank { p.benefit.orEmpty() }.ifBlank { p.amount }.ifBlank { "원문에서 확인" }
        // 서버가 읽은 값(amount_won · period_years)을 먼저 쓰고, 없으면 문장에서 직접 읽는다
        val monthly = if (p.amountWon != null) p.amountWon.takeIf { p.amountCycle == "monthly" }
        else MONTHLY.find(amount)?.let { m ->
            val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let null
            (if (m.groupValues[2].isNotBlank()) n * 10_000 else n).toLong()
        }
        val years = p.periodYears ?: YEARS.find(p.period)?.groupValues?.get(3)?.toIntOrNull()
        val payday = listOf(p.easySummary, p.summary, p.howToApply, p.amount).firstNotNullOfOrNull { PAYDAY.find(it)?.groupValues?.get(1) }
        // 퇴소 후(목표일)인 사람의 dDate 는 퇴소일이 아니라서 계산에 쓰지 않는다
        val leave = u?.let { pu -> pu.dDate?.takeIf { !DDate.isGoal(pu.status) } }
        val end = if (leave != null && years != null) leave.plusYears(years.toLong()) else null

        val sub = listOfNotNull(
            if (money) payday?.let { "매월 ${it}일" } else null,
            end?.let { "${it.year}년 ${it.monthValue}월까지" },
        ).joinToString(" · ")
        val rows = buildList {
            // 신청 기한은 맨 위 카드에 (신청 방법 카드에서는 뺐다)
            plan(p).deadline?.let { add("신청 기한" to shortDeadline(it)) }
            if (monthly != null && years != null) add("최대 총액" to won(monthly * 12 * years))
            if (leave != null) PRE_DAYS.find(p.howToApply)?.groupValues?.get(1)?.toLongOrNull()?.let { d ->
                val from = leave.minusDays(d)
                if (!from.isBefore(LocalDate.now())) add("사전신청" to "${dot(from)}부터")
            }
            if (leave != null && !leave.isBefore(LocalDate.now())) add("퇴소 예정일" to dot(leave))
            if (end == null && p.period.isNotBlank() && p.period.length <= 24 && p.period != "공식 원문에서 확인") add("받는 기간" to p.period)
        }
        return SummaryView(amount, sub, rows, money)
    }

    /** "2026. 1. 29.(목). ~ 9. 30.(수) 프로그램별 … 상이" → "2026. 1. 29. ~ 9. 30." (요일 괄호 · 덧붙인 설명은 뗀다) */
    private fun shortDeadline(text: String): String {
        val t = text.replace(Regex("""\([월화수목금토일]\)\.?"""), "").replace(Regex("""\s+"""), " ").trim()
        val cut = Regex("""^(.*?\d{1,2}\.?)(\s+[가-힣].*)$""").find(t)?.groupValues?.get(1)?.takeIf { "~" in t && it.length >= 8 }
        return (cut ?: t).trim()
    }

    /** 대상 조건 한 줄: 내가 답했으면 그 답, 기간 조건이면 날짜로 계산, 아니면 확인 필요 */
    fun myCondition(text: String, u: UserProfile?): MyCondView {
        val period = com.project.on_road.data.ConditionHelp.explain(text, u).period
        val sub = period?.end?.let { "${it.year}년 ${it.monthValue}월 ${it.dayOfMonth}일까지" }
        val state = when (com.project.on_road.data.MyConditions.answer(text)) {
            true -> CondState.Yes
            false -> CondState.No
            null -> when (period?.within) {
                true -> CondState.Yes
                false -> CondState.No
                null -> CondState.Check
            }
        }
        return MyCondView(text, state, sub)
    }

    fun conditions(p: Policy, u: UserProfile?): List<Pair<String, CondState>> = buildList {
        if (u != null) {
            if (p.ageMin != null || p.ageMax != null) {
                val age = Period.between(u.birthDate, LocalDate.now()).years
                val ok = (p.ageMin == null || age >= p.ageMin) && (p.ageMax == null || age <= p.ageMax)
                add("나이 만 ${age}세" to if (ok) CondState.Yes else CondState.No)
            }
            if (p.region.isNotBlank()) {
                val ok = p.region == "전국" || p.region == u.region
                val where = listOfNotNull(u.region, u.district).joinToString(" ")
                // 시 · 군 · 구 정책은 서버 판정(확인 필요)을 따른다
                val state = when {
                    !ok -> CondState.No
                    p.eligibility == "needs_check" -> CondState.Check
                    else -> CondState.Yes
                }
                add("거주 $where" to state)
            }
        } else if (p.eligibility != null) {
            add("나이 · 지역" to if (p.eligibility == "match") CondState.Yes else CondState.Check)
        }
        // 보호가 끝나야 받는 정책: 아직 보호 중이면 지금은 해당 없음 (서버 판정)
        when (p.leave) {
            "after_leave" -> add("보호 종료 후 신청 (지금은 보호 중)" to CondState.No)
            "now" -> add("보호 종료" to CondState.Yes)
        }
        // 나이 · 지역 말고 따로 확인할 대상 조건은 ConditionSection 이 따로 그린다 (눌러서 확인)
    }

    /** 신청 방법 한 갈래 (방문 · 온라인 …)와 그 안의 짧은 줄들 */
    data class ApplyWay(val label: String, val lines: List<String>)
    data class ApplyPlan(val ways: List<ApplyWay>, val deadline: String?)
    val WAY_NAMES = setOf("방문", "온라인", "우편·팩스", "전화", "카카오톡")

    private val WAY_PREFIX = Regex("""^(온라인|인터넷|방문|전화|우편·팩스|우편|팩스|카카오톡)\s*((신청|접수)\s*)?[:：]\s*|^(온라인|인터넷|방문|전화|우편|팩스|카카오톡)\s*(신청|접수)\s+""")
    private val ONLY_WAY = Regex("""(온라인|인터넷|방문|전화|우편·팩스|우편|팩스|카카오톡)\s*신청""")
    private val DEADLINE = Regex("""^신청\s*기한\s*[:：]\s*""")
    private val BULLET = Regex("""^[\s○●◦•\-*·※▶]+""")

    /** 줄에 나온 신청 갈래. 우편 · 팩스를 방문보다 먼저 본다 ("방문 제한 시 우편") */
    private fun wayOf(line: String): String? = when {
        Regex("신청\\s*(없이|절차\\s*없)|자동").containsMatchIn(line) -> "따로 신청하지 않아요"
        Regex("우편|팩스").containsMatchIn(line) -> "우편·팩스"
        Regex("온라인|인터넷|홈페이지|누리집|복지로|정부24|www\\.|앱에서").containsMatchIn(line) -> "온라인"
        "카카오톡" in line -> "카카오톡"
        Regex("전화|콜센터").containsMatchIn(line) -> "전화"
        Regex("방문|센터|구청|군청|시청|주민센터|사무소|팀 신청").containsMatchIn(line) -> "방문"
        else -> null
    }

    /**
     * 신청 방법을 갈래별 카드로: 정리된 구절(applySteps)이 있으면 그걸, 없으면 원문(howToApply)을 줄 · 글머리표로 잘라 쓴다.
     * 갈래 낱말이 없는 줄은 바로 앞 갈래에 붙인다. "신청기한: …" 은 따로 뺀다.
     */
    fun plan(p: Policy): ApplyPlan =
        if (p.applyWays.isNotEmpty()) ApplyPlan(p.applyWays.map { (w, lines) -> ApplyWay(w, lines) }, p.applyDeadline)
        else applyPlan(p.applySteps, p.howToApply)

    fun applyPlan(steps: List<String>, raw: String): ApplyPlan {
        val lines = steps.ifEmpty {
            raw.split("\n").flatMap { l -> l.replace(BULLET, "").split(Regex("""(?<=[.다])\s+(?=\S)""")) }
        }.map { it.replace(BULLET, "").trim().removeSuffix(".").trim() }.filter { it.isNotBlank() }
        var deadline: String? = null
        val groups = LinkedHashMap<String, MutableList<String>>()
        var current: String? = null
        for (line in lines) {
            if (Regex("""^상시\s*신청$""").matches(line)) { deadline = "상시 신청"; continue }
            if (DEADLINE.containsMatchIn(line)) {
                deadline = line.replace(DEADLINE, "").replace("상시신청", "상시 신청").trim().ifBlank { null }
                continue
            }
            val way = wayOf(line) ?: current ?: "신청"
            current = way
            val text = line.replace(WAY_PREFIX, "").trim().removeSurrounding("(", ")").trim()
            val list = groups.getOrPut(way) { mutableListOf() }
            // "방문 신청" · "인터넷 신청"처럼 갈래 이름뿐인 줄은 카드 제목으로 충분하다
            if (text.isNotBlank() && !ONLY_WAY.matches(text) && text != "신청" && text !in list) list += text
        }
        return ApplyPlan(groups.map { (k, v) -> ApplyWay(k, v) }, deadline)
    }

    /** 정리된 서류 구절 → 서류 줄. "위임장 (대리 신청 시)", "혼인관계증명서 (조건: 신혼부부)", "[별첨 1]" 정리. 비어 있으면 null */
    fun docsFrom(facts: List<String>, raw: List<RequiredDoc>): List<RequiredDoc>? {
        if (facts.isEmpty()) return null
        // "하나만 내는 서류"는 원문 줄에 기관 이름까지 있어서 그쪽이 더 자세하다
        val rawChoice = raw.filter { r -> "또는" in r.name || r.name.startsWith("[택") }
        val docs = facts.mapNotNull { f ->
            if (("또는" in f || f.startsWith("[택")) && rawChoice.isNotEmpty()) return@mapNotNull rawChoice.first()
            var t = f.replace(Regex("""\[별첨\s*\d+]"""), "").trim()
            // 서류가 아니라 안내인 줄 ("기타 구비서류는 읍면사무소 안내", "신청서 … 비치")
            if (Regex("(안내|비치|확인)$").containsMatchIn(t) && !Regex("(증|서)\\s*$").containsMatchIn(t.removeSuffix("확인"))) return@mapNotNull null
            // 끝 괄호: "(조건: 신혼부부)" · "(대리 신청 시)" · "(방문신청)" 은 언제 내는지로 떼고, "(구비서류)" · "(필수)" 는 지운다.
            // 이름의 일부인 괄호("(1차, 2차)", "(자립전담기관 발급)")는 그대로 둔다
            val m = Regex("""\s*\(([^()]*)\)\s*$""").find(t)
            val inner = m?.groupValues?.get(1)?.trim().orEmpty()
            val tagged = inner.startsWith("조건")
            val c = inner.replace(Regex("""^조건\s*[:：]\s*"""), "").trim()
            val cond: String? = when {
                m == null -> null
                c.isBlank() || c == "구비서류" || c == "필수" -> ""
                Regex("""^(온라인|방문|우편|팩스|대리)\s*신청$""").matches(c) -> c.replace(Regex("""\s*신청$"""), " 신청 시")
                c.endsWith("시") || c.endsWith("때") || c.endsWith("경우") -> c
                tagged -> if ("해당" in c) "해당할 때" else "$c 해당 시"
                else -> null
            }
            if (cond != null && m != null) t = t.substring(0, m.range.first).trim()
            RequiredDoc(t.replace("지참", "").trim(), cond?.ifBlank { null })
        }
        return docs.ifEmpty { null }
    }

    fun applyWays(text: String): List<Pair<String, String>> {
        val marks = WAY.findAll(text).toList()
        return marks.mapIndexed { i, m ->
            val end = marks.getOrNull(i + 1)?.range?.first ?: text.length
            m.groupValues[1] to firstSentence(text.substring(m.range.last + 1, end))
        }.filter { it.second.isNotBlank() }
    }

    private val CHOICE_TAIL = Regex("""\s*중\s*(1|한)\s*(종|개|가지)\s*$""")
    private val COPIES = Regex("""\s*\d+\s*부(?=\s|$)""")

    private fun tabOf(cond: String?): DocTab = when {
        cond.isNullOrBlank() -> DocTab.Direct
        "대리" in cond -> DocTab.Proxy
        cond.startsWith("우편") || cond.startsWith("팩스") -> DocTab.Mail
        cond.startsWith("온라인") -> DocTab.Online
        "신청 시 준비" in cond || cond == "필수" || cond == "구비서류" || cond.startsWith("방문") -> DocTab.Direct
        else -> DocTab.Case
    }

    fun doc(d: RequiredDoc): DocView {
        var raw = d.name.trim()
        // 서류 이름 뒤에 붙은 설명 문장("… 1부. 정보북의 … 확인 필요")은 떼고 첫 문장만
        raw = raw.replace(Regex("""^\[[^\]]*]\s*"""), "").split(Regex("""(?<=[.])\s+""")).first().removeSuffix(".")
            .replace(COPIES, "").trim()
        val cond = d.condition?.let { firstSentence(it) }?.takeIf { it.isNotBlank() }
        val tab = tabOf(d.condition)
        // 하나만 내면 되는 서류: "신분증: 주민등록증·여권 중 1종" · "A 또는 B 또는 C"
        val head = raw.substringBefore(":").trim()
        val tail = raw.substringAfter(":", "").trim()
        val options = when {
            tail.isNotBlank() && CHOICE_TAIL.containsMatchIn(tail) ->
                tail.replace(CHOICE_TAIL, "").split("·", ",").map { it.trim() }.filter { it.isNotBlank() }
            " 또는 " in raw -> raw.split(" 또는 ").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        if (options.size > 1) {
            val title = when {
                tail.isNotBlank() && CHOICE_TAIL.containsMatchIn(tail) -> head
                // 자립수당: 재무상담 확인서 · 자립교육 이수증 중 하나
                options.any { "자립교육" in it } && options.all { Regex("(이수증|수료증|확인서)$").containsMatchIn(it) } -> "자립교육 확인서"
                else -> "다음 서류 중 하나"
            }
            return DocView(title, null, emptyList(), tab, options)
        }
        val name = head
        val what = tail.takeIf { it.isNotBlank() }
        // 직접 탭의 짧은 조건(본인 명의 등)과 '해당할 때만' 탭의 경우는 이름 아래 한 줄로
        val note = cond?.takeIf { tab == DocTab.Case || (tab == DocTab.Direct && it.length <= 12 && "준비" !in it && !it.startsWith("방문")) }
        val rows = buildList {
            what?.let { add("무엇을" to it) }
            DocGuide.explain(name)?.let { add("어디서" to it) }
        }
        return DocView(name, note, rows, tab)
    }

    fun contact(text: String, agency: String): ContactView? {
        if (text.isBlank()) return null
        val parts = text.split("/").map { it.trim() }.filter { it.isNotBlank() }
        val phone = PHONE.find(text)?.value
        val title = parts.first().let { if (phone != null) it.replace(phone, "") else it }.trim().ifBlank { agency }
        val sub = (listOfNotNull(phone) + parts.drop(1)).joinToString(" · ")
        return ContactView(title, sub, phone)
    }

    fun host(url: String): String = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { url }

    private fun firstSentence(s: String): String =
        s.replace(PAREN, "").trim().split(Regex("""(?<=[.])\s+""")).first().trim().removeSuffix(".").trim()

    private fun dot(d: LocalDate) = "${d.year}.${d.monthValue}.${d.dayOfMonth}"

    private fun won(v: Long): String = if (v % 10_000 == 0L) "%,d만 원".format(v / 10_000) else "%,d원".format(v)
}
