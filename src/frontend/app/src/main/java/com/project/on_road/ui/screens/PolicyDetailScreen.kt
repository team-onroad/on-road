package com.project.on_road.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import com.project.on_road.data.Policy
import com.project.on_road.ui.components.ErrorState
import com.project.on_road.ui.components.EasyTextPanel
import com.project.on_road.ui.components.ExpandArea
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadSwitch
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.OnRoadToast
import com.project.on_road.ui.components.SkeletonList
import com.project.on_road.ui.components.SubHeader
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.components.FadeSwitch
import com.project.on_road.ui.components.flipWhen
import com.project.on_road.ui.components.policyTitleKey
import com.project.on_road.ui.components.popOn
import com.project.on_road.ui.components.rememberTick
import com.project.on_road.ui.components.sharedTitle
import com.project.on_road.ui.components.stagger
import com.project.on_road.ui.components.tap
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch

/* ================================================================== */
/*  정책 상세                                                            */
/*  검색 카드에는 핵심만 두고, 쉬운 말·요약·서류·근거는 여기서 본다          */
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
}

class PolicyDetailViewModel : ViewModel() {
    private val api = AppContainer.api
    private val userId: String get() = AppContainer.session.userId.orEmpty()

    var policy by mutableStateOf<Policy?>(null)
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
        }
        if (policy != null) return
        viewModelScope.launch {
            loading = true
            error = null
            runCatching { api.getPolicy(id) }
                .onSuccess { p -> if (p == null) error = "정책 정보를 찾지 못했어요." else { policy = p; PolicyCache.put(p) } }
                .onFailure { error = "정책을 불러오지 못했어요. 잠시 후 다시 시도해 주세요." }
            loading = false
        }
    }

    fun retry(id: String) {
        loadedId = null
        load(id)
    }

    fun toggleSave(id: String) {
        val save = id !in PolicySaves.ids
        if (save) PolicySaves.ids.add(id) else PolicySaves.ids.remove(id)
        OnRoadToast.show(if (save) "관심 정책에 담았어요" else "관심 정책에서 뺐어요")
        viewModelScope.launch { runCatching { api.savePolicy(userId, id, save) } }
    }
}

private enum class DetailState { Loading, Error, Ready }

@Composable
fun PolicyDetailScreen(
    policyId: String,
    onBack: () -> Unit,
    onPrepare: (String) -> Unit,
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
                DetailState.Ready -> vm.policy?.let { PolicyDetailBody(it, vm.easy, onToggleEasy = { vm.easy = !vm.easy }) }
            }
        }
    }
}

@Composable
private fun PolicyDetailBody(policy: Policy, easy: Boolean, onToggleEasy: () -> Unit) {
    val uri = LocalUriHandler.current
    var evidenceOpen by remember(policy.id) { mutableStateOf(false) }

    // ── 머리: 분류 · 이름 · 기관 · 기준일 ──
    OnRoadTag(
        policy.category,
        style = TagStyle.Neutral,
        textStyle = OnRoadType.Caption2,
        shape = OnRoadShapes.Field,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(policy.name, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary, modifier = Modifier.sharedTitle(policyTitleKey(policy.id)))
    Spacer(Modifier.height(4.dp))
    Text("${policy.agency} · ${policy.checkedAt} 기준", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)

    if (policy.eligibility != null || policy.isOutdated) {
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (policy.eligibility) {
                "match" -> OnRoadTag("기본 조건 해당 · 세부 조건은 공식 원문에서 확인", style = TagStyle.PrimarySoft)
                "needs_check" -> OnRoadTag("조건 확인 필요", style = TagStyle.Warning)
            }
            if (policy.isOutdated) OnRoadTag("확인일이 오래된 정보예요 · 최신 내용은 원문에서 확인", style = TagStyle.Danger)
        }
    }

    // ── 한눈에 보기: 받는 것 · 기간 ──
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyFact("지원 내용", policy.amount, Modifier.weight(1f).stagger(0))
        KeyFact("신청 기간", policy.period, Modifier.weight(1f).stagger(1))
    }

    // ── 설명: 쉬운 말 ↔ 원문 ──
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SubHeader("어떤 정책인가요", modifier = Modifier.weight(1f))
        Text("쉬운 말", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
        Spacer(Modifier.width(6.dp))
        OnRoadSwitch(checked = easy, onCheckedChange = { onToggleEasy() })
    }
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Field)
            .background(OnRoadColors.SurfaceCool)
            .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Field)
            .padding(16.dp),
    ) {
        AnimatedContent(targetState = easy, label = "detail-summary") { isEasy ->
            if (isEasy) EasyTextPanel(policyId = policy.id, fallback = policy.easySummary)
            else Text(policy.summary, style = OnRoadType.Body2, color = OnRoadColors.TextSecondary)
        }
    }

    // ── 자세히: 서버 요약이 있으면 그걸, 없으면 원문 항목 ──
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    SubHeader("자세히 보기")
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val cs = policy.cardSummary
        if (cs != null) {
            cs.sections.forEach { sec -> DetailRow(sec.label, sec.items) }
            cs.notice?.let { Text(it, style = OnRoadType.Tiny, color = OnRoadColors.TextMuted) }
            if (!cs.verified) Text("AI가 원문에서 정리한 요약이에요. 원문과 함께 확인해 주세요.", style = OnRoadType.Tiny, color = OnRoadColors.TextMuted)
        } else {
            DetailRow("지원 대상", listOf(policy.target))
            if (policy.howToApply.isNotBlank()) DetailRow("신청 방법", listOf(policy.howToApply))
        }
    }

    // ── 필요 서류 ──
    if (policy.requiredDocs.isNotEmpty()) {
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubHeader("필요 서류", trailingText = "${policy.requiredDocs.size}")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            policy.requiredDocs.forEachIndexed { i, d ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .stagger(i)
                        .clip(OnRoadShapes.Field)
                        .background(OnRoadColors.SurfaceSubtle)
                        .border(1.dp, OnRoadColors.Border, OnRoadShapes.Field)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                        d.condition?.takeIf { it.isNotBlank() }?.let { Text(it, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary) }
                    }
                    // 무엇인지 · 어디서 떼는지 (모르는 서류면 이름만)
                    com.project.on_road.data.DocGuide.explain(d.name)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                    }
                }
            }
        }
    }

    // ── 근거 · 원문 ──
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Field)
            .background(OnRoadColors.SurfaceCool)
            .border(1.dp, OnRoadColors.BorderSlate, OnRoadShapes.Field)
            .tap { evidenceOpen = !evidenceOpen }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("근거 원문 보기", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
            Text("${policy.evidence.size}건", style = OnRoadType.Micro, color = OnRoadColors.TextMuted)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(20.dp).flipWhen(evidenceOpen))
        }
        ExpandArea(evidenceOpen) {
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
    if (policy.sourceUrl.isNotBlank()) {
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
}

/** 한눈에 보는 칸 (연회색 카드: 작은 이름 + 굵은 값, 최대 세 줄) */
@Composable
private fun KeyFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Item)
            .padding(14.dp),
    ) {
        Text(label, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        Text(
            value.ifBlank { "원문에서 확인" },
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            color = OnRoadColors.TextPrimary,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailRow(label: String, items: List<String>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary, modifier = Modifier.width(80.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.filter { it.isNotBlank() }.forEach {
                Text(if (items.size > 1) "· $it" else it, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
            }
        }
    }
}
