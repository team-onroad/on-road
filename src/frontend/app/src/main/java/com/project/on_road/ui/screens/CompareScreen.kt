package com.project.on_road.ui.screens

import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.ErrorState
import com.project.on_road.ui.components.FadeSwitch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadMotion
import com.project.on_road.ui.components.ExpandArea
import com.project.on_road.ui.components.tap
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import com.project.on_road.ui.components.DataListCard
import com.project.on_road.ui.components.GroupTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Policy
import com.project.on_road.ui.components.ButtonVariant
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.Footnote
import com.project.on_road.ui.components.ListRow
import com.project.on_road.ui.components.LoadingState
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadCard
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.SectionHeader
import com.project.on_road.ui.components.SubHeader
import com.project.on_road.ui.components.SubtleCard
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch

class CompareViewModel : ViewModel() {
    var policies by mutableStateOf<List<Policy>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loaded = false

    fun load(ids: List<String>) {
        if (loaded) return
        loaded = true
        fetch(ids)
    }

    fun retry(ids: List<String>) = fetch(ids)

    private fun fetch(ids: List<String>) {
        viewModelScope.launch {
            loading = true
            val api = AppContainer.api
            val userId = AppContainer.session.userId.orEmpty()
            // 선택된 정책이 없으면 저장한 정책으로 비교
            runCatching {
                val target = ids.ifEmpty { api.getSavedPolicyIds(userId).toList() }
                api.getPolicies(target).take(3)
            }.onSuccess { policies = it; error = null }
                .onFailure { error = it.userMessage("정책을 불러오지 못했어요.") }
            loading = false
        }
    }
}

/** 비교할 수 있는 항목 (위 비교 조건 태그로 켜고 끈다) */
private val compareRows: List<Pair<String, (Policy) -> String>> = listOf(
    "지원 내용" to { p -> p.amount },
    "지원 기간" to { p -> p.period },
    "필요 서류" to { p -> p.requiredDocs.joinToString(", ") { it.label } },
    "지원 대상" to { p -> p.target },
    "신청 방법" to { p -> p.howToApply },
    "분야" to { p -> p.category },
    "기관" to { p -> p.agency },
    "확인일" to { p -> p.checkedAt },
)

/** 처음 켜 둘 항목 */
private val defaultRows = listOf("지원 내용", "필요 서류", "지원 기간", "지원 대상")

/**
 * '왜 더 맞는지' 이유: 정책 데이터만으로 확실히 말할 수 있는 것만 (금액 · 자격을 지어내지 않는다).
 * 조건 해당 여부 → 필요 서류 수 → 없으면 검색 관련도
 */
private fun reasons(list: List<Policy>, best: Int): List<Pair<String, String>> {
    val b = list[best]
    val others = list.filterIndexed { i, _ -> i != best }
    val out = mutableListOf<Pair<String, String>>()
    // 문장 조각 (마지막 조각만 '~요'로 끝내 한 문장으로 잇는다)
    if (b.eligibility == "match" && others.none { it.eligibility == "match" }) out += "내 조건에 기본으로 해당하고" to "내 조건에 기본으로 해당해요"
    val docs = b.requiredDocs.size
    val fewest = others.minOfOrNull { it.requiredDocs.size }
    if (fewest != null && docs in 1 until fewest) out += "필요한 서류가 ${fewest - docs}개 더 적고" to "필요한 서류가 ${fewest - docs}개 더 적어요"
    if (others.any { it.isOutdated } && !b.isOutdated) out += "최근에 확인된 공고이고" to "최근에 확인된 공고예요"
    if (out.isEmpty()) out += "" to "찾아본 내용과 더 가까운 정책이에요"
    return out
}

/** 이름 뒤 조사: 받침 있으면 '이', 없으면 '가' (한글이 아니면 '이(가)') */
private fun withSubject(name: String): String {
    val c = name.trim().lastOrNull() ?: return name
    if (c !in '가'..'힣') return "$name 이(가)"
    return if ((c - '가') % 28 != 0) "${name}이" else "${name}가"
}

/** 이유 조각들을 한 문장으로: "A하고, B예요." */
private fun reasonSentence(parts: List<Pair<String, String>>): String =
    parts.dropLast(1).joinToString("") { it.first + ", " } + parts.last().second + "."

/** 비교 화면 간격 (숫자만 바꿔서 조절) */
private object CompareSpace {
    /** 위 바 → 비교 조건 태그 / 표 */
    val Top = 4.dp
    /** 비교 조건 태그 → 설명 카드 */
    val TagsBottom = 20.dp
    /** 설명 카드 → 표 */
    val CardBottom = 28.dp
    /** 표 머리 → 첫 줄 */
    val HeaderGap = 20.dp
    /** 줄 사이 */
    val RowGap = 20.dp
    /** 마지막 줄 → 신청 준비 버튼 */
    val ButtonTop = 24.dp
    /** 왼쪽 항목 열 너비 (왼쪽 여백 포함) */
    val LabelWidth = 68.dp
    /** 항목 이름 왼쪽 여백 */
    val LabelStart = 8.dp
    /** 정책 더 담기(+) 버튼 크기 */
    val AddSize = 32.dp
    /** 신청 준비 버튼 높이 */
    val ButtonHeight = 44.dp
    /** 정책 열 사이 */
    val ColGap = 12.dp
    /** 값 행간 */
    val ValueLineHeight = 19.sp
}

/** 나에게 더 맞는 정책: 서버 자격 판정이 '기본 조건 해당(match)'인 첫 정책, 없으면 검색 순서(관련도) 첫 정책 */
private fun bestIndex(list: List<Policy>): Int = list.indexOfFirst { it.eligibility == "match" }.takeIf { it >= 0 } ?: 0

/**
 * 정책 비교 (참고: 보험 보장 비교표).
 * - 오른쪽 위 필터 아이콘: 비교 조건 태그를 펼치고 접는다. 태그로 표에 넣을 항목을 고른다 (고른 것 = 메인 색 바탕 · 흰 글씨)
 * - 추천 카드(연회색 · 회색 테두리, 버튼 없음): '추천' → 어느 정책이 더 맞는지(크게) → 이유 한 문장 (데이터로 말할 수 있는 것만)
 * - 표: 카드 없이 흰 화면에 바로. 왼쪽 열 = 항목(굵게), 정책마다 한 열. 추천 열은 진하게, 나머지는 연하게. 맨 위 왼쪽 빈칸에 '+' (둥근 네모)
 * - 맨 아래 신청 준비 버튼: 더 맞는 정책만 메인 색 · 흰 글씨
 */
@Composable
fun CompareScreen(
    ids: List<String>,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPrepare: (String) -> Unit,
    /** '+' : 정책 더 담기 (기본은 뒤로 = 담던 검색 화면으로) */
    onAdd: () -> Unit = onBack,
    vm: CompareViewModel = viewModel(),
) {
    LaunchedEffect(ids) { vm.load(ids) }
    val list = vm.policies
    var showFilters by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(defaultRows) }

    OnRoadScreen(
        title = "정책 비교",
        onBack = onBack,
        trailing = if (list.size >= 2) {
            {
                // 필터 아이콘: 비교 조건 태그 켜고 끄기 (켜져 있으면 진하게)
                Box(
                    Modifier.size(40.dp).clip(CircleShape).tap(onClickLabel = "비교 조건") { showFilters = !showFilters },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        "비교 조건",
                        tint = if (showFilters) OnRoadColors.TextPrimary else OnRoadColors.TextTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else null,
    ) {
        FadeSwitch(vm.loading, label = "compare") { loading ->
            when {
                loading -> LoadingState("비교표를 만들고 있어요")
                vm.error != null -> ErrorState(vm.error, onRetry = { vm.retry(ids) })
                list.size < 2 -> EmptyState(
                    "정책을 2개 이상 담거나 저장하면 나란히 비교할 수 있어요.",
                    actionLabel = "정책 찾기",
                    onAction = onSearch,
                )
                else -> CompareContent(
                    list = list,
                    showFilters = showFilters,
                    shown = shown,
                    onToggleRow = { label -> shown = if (label in shown) shown - label else shown + label },
                    onAdd = onAdd,
                    onPrepare = onPrepare,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompareContent(
    list: List<Policy>,
    showFilters: Boolean,
    shown: List<String>,
    onToggleRow: (String) -> Unit,
    onAdd: () -> Unit,
    onPrepare: (String) -> Unit,
) {
    val best = bestIndex(list)
    Column {
        Spacer(Modifier.height(CompareSpace.Top))
        // 비교 조건 태그 (필터 아이콘으로 접고 펼친다)
        ExpandArea(showFilters) {
            Column {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 앱 태그 모양: 고른 것 = 메인 색 바탕 · 흰 글씨, 나머지 = 흰 바탕 · 회색 테두리
                    compareRows.forEach { (label, _) ->
                        val on = label in shown
                        val bg by animateColorAsState(if (on) OnRoadColors.Primary else Color.White, OnRoadMotion.move(), label = "row-tag-bg")
                        val line by animateColorAsState(if (on) OnRoadColors.Primary else OnRoadColors.BorderChip, OnRoadMotion.move(), label = "row-tag-line")
                        Text(
                            label,
                            style = OnRoadType.Caption2.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                            color = if (on) Color.White else OnRoadColors.TextSecondary,
                            modifier = Modifier
                                .clip(OnRoadShapes.Tag)
                                .background(bg)
                                .border(1.dp, line, OnRoadShapes.Tag)
                                .tap { onToggleRow(label) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(CompareSpace.TagsBottom))
            }
        }

        // 왜 더 맞는지: 연회색 바탕 · 회색 테두리 정보 카드
        OnRoadCard(
            Modifier.fillMaxWidth(),
            background = OnRoadColors.SurfaceSubtle,
            borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
            shape = OnRoadShapes.Item,
            contentPadding = PaddingValues(16.dp),
        ) {
            // 읽기만 하는 정보 카드 (버튼 없음): 추천 라벨 → 큰 제목 → 이유 한 문장
            Text("추천", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text("${withSubject(list[best].name)} 더 맞아요", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(reasonSentence(reasons(list, best)), style = OnRoadType.Caption.copy(lineHeight = CompareSpace.ValueLineHeight), color = OnRoadColors.TextSecondary)
        }
        Spacer(Modifier.height(CompareSpace.CardBottom))

        // 표 머리: 왼쪽 빈칸에 '+', 정책 이름
        TableLine(
            leading = {
                if (list.size < 3) {
                    // 정책 더 담기: 테두리 있는 둥근 네모
                    Box(
                        Modifier
                            .size(CompareSpace.AddSize)
                            .clip(OnRoadShapes.Field)
                            .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Field)
                            .tap(onClickLabel = "정책 더 담기", onClick = onAdd),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, "정책 더 담기", tint = OnRoadColors.TextSecondary, modifier = Modifier.size(18.dp)) }
                }
            },
            list = list,
            align = Alignment.CenterVertically,
        ) { i, p ->
            // 추천 정책 이름은 진하게, 나머지는 연하게 (표시 · 배경 없이 진하기만으로 추천 열을 앞에)
            Text(
                p.name,
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                color = if (i == best) OnRoadColors.TextPrimary else OnRoadColors.TextTertiary,
                textAlign = TextAlign.Left,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 고른 항목만 줄로
        Spacer(Modifier.height(CompareSpace.HeaderGap))
        Column(Modifier.animateContentSize(OnRoadMotion.move()), verticalArrangement = Arrangement.spacedBy(CompareSpace.RowGap)) {
            // 값이 다른 줄을 위로, 같은 줄을 아래로 (차이가 먼저 읽히게)
            fun same(f: (Policy) -> String) = list.map { f(it).trim() }.distinct().size == 1
            compareRows.filter { it.first in shown }.sortedBy { (_, f) -> same(f) }.forEach { (label, f) ->
                TableLine(
                    // 항목 이름이 표에서 가장 진하다 (굵게)
                    leading = { Text(label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary) },
                    list = list,
                ) { i, p ->
                    Text(
                        f(p).ifBlank { "공고문에서 확인" },
                        style = OnRoadType.Caption.copy(lineHeight = CompareSpace.ValueLineHeight),
                        // 값은 보통 굵기. 추천 열은 진하게, 나머지 열은 연하게
                        color = if (i == best) OnRoadColors.TextPrimary else OnRoadColors.TextTertiary,
                    )
                }
            }
        }

        // 신청 준비: 더 맞는 정책만 메인 색 · 흰 글씨, 나머지는 테두리 버튼
        Spacer(Modifier.height(CompareSpace.ButtonTop))
        TableLine(leading = {}, list = list) { i, p ->
            // 추천 열만 메인 색(테두리 없음), 나머지는 회색 테두리 버튼
            if (i == best) {
                OnRoadButton(
                    "신청 준비",
                    { onPrepare(p.id) },
                    Modifier.fillMaxWidth(),
                    height = CompareSpace.ButtonHeight,
                    textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                )
            } else {
                OnRoadButton(
                    "신청 준비",
                    { onPrepare(p.id) },
                    Modifier.fillMaxWidth(),
                    height = CompareSpace.ButtonHeight,
                    variant = ButtonVariant.Outline,
                    textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                    borderColor = OnRoadColors.BorderChip,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 표 한 줄: 왼쪽 항목 열 + 정책마다 같은 너비 열 (구분선 없이) */
@Composable
private fun TableLine(
    leading: @Composable () -> Unit,
    list: List<Policy>,
    align: Alignment.Vertical = Alignment.Top,
    cell: @Composable ColumnScope.(Int, Policy) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = align) {
        Box(Modifier.width(CompareSpace.LabelWidth).padding(start = CompareSpace.LabelStart, top = 1.dp)) { leading() }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(CompareSpace.ColGap), verticalAlignment = align) {
            list.forEachIndexed { i, p -> Column(Modifier.weight(1f)) { cell(i, p) } }
        }
    }
}
