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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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

/** 나란히 비교할 항목 (짧은 값: 정책마다 칸을 나란히) */
private val keyRows: List<Pair<String, (Policy) -> String>> = listOf(
    "지원 내용" to { p -> p.amount },
    "지원 기간" to { p -> p.period },
)

/** 세부 조건 (긴 글: 정책마다 한 줄씩 아래로) */
private val detailRows: List<Pair<String, (Policy) -> String>> = listOf(
    "지원 대상" to { p -> p.target },
    "신청 방법" to { p -> p.howToApply },
    "필요 서류" to { p -> p.requiredDocs.joinToString(", ") { it.label }.ifBlank { "공고문에서 확인" } },
)

/** 비교 화면 간격 (숫자만 바꿔서 조절. 다른 화면과 같은 값) */
private object CompareSpace {
    /** 묶음 사이 */
    val GroupGap = 32.dp
    /** 묶음 제목 → 내용 */
    val HeaderGap = 12.dp
    /** 항목 라벨 → 값 */
    val LabelGap = 6.dp
    /** 표 항목 사이 (정책 이름 줄 아래도 같은 값) */
    val RowGap = 18.dp
    /** 표 칸 사이 */
    val ColGap = 16.dp
    /** 표 값 행간 */
    val ValueLineHeight = 19.sp
    /** 같은 점 라벨 너비 */
    val SameLabelWidth = 64.dp
}

/**
 * 정책 비교. 어느 쪽이 낫다고 단정하지 않고 조건만 나란히 보여 준다 (첫 정책을 강조하지 않는다).
 * 1. 다른 점: 흰 표 한 장 (맨 위 정책 이름 → 항목 라벨 띠 → 같은 칸 순서의 값). 값이 다른 항목만
 * 2. 같은 점: 값이 모두 같은 항목은 연회색 카드에 한 번만
 * 3. 신청 준비하기: 정책마다 목록 카드 한 줄
 */
@Composable
fun CompareScreen(
    ids: List<String>,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPrepare: (String) -> Unit,
    vm: CompareViewModel = viewModel(),
) {
    LaunchedEffect(ids) { vm.load(ids) }
    val list = vm.policies

    OnRoadScreen(title = "정책 비교", onBack = onBack) {
        FadeSwitch(vm.loading, label = "compare") { loading ->
            when {
                loading -> LoadingState("비교표를 만들고 있어요")
                vm.error != null -> ErrorState(vm.error, onRetry = { vm.retry(ids) })
                list.size < 2 -> EmptyState(
                    "정책을 2개 이상 담거나 저장하면 나란히 비교할 수 있어요.",
                    actionLabel = "정책 찾기",
                    onAction = onSearch,
                )
                else -> CompareContent(list, onPrepare)
            }
        }
    }
}

@Composable
private fun CompareContent(list: List<Policy>, onPrepare: (String) -> Unit) {
    // 모든 항목을 '다른 점'과 '같은 점'으로 나눈다 → 정말 비교해야 할 것만 표로 나란히
    val rows = keyRows + detailRows
    fun v(p: Policy, f: (Policy) -> String) = f(p).ifBlank { "공고문에서 확인" }
    val differ = rows.filter { (_, f) -> list.map { v(it, f).trim() }.distinct().size > 1 }
    val same = rows - differ.toSet()

    Column {
        // 1. 다른 점: 흰 표 한 장. 맨 위 정책 이름 줄 → 항목마다 라벨 + 같은 칸 순서의 값
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupTitle("다른 점", Modifier.weight(1f))
            Text("${differ.size}가지", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
        }
        Spacer(Modifier.height(CompareSpace.HeaderGap))
        // '같은 점' · 다른 화면 정보 카드와 같은 연회색 카드 (카드 모양을 한 가지로)
        OnRoadCard(
            Modifier.fillMaxWidth(),
            background = OnRoadColors.SurfaceSubtle,
            borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
            shape = OnRoadShapes.Item,
            contentPadding = PaddingValues(16.dp),
        ) {
            // 정책 이름 줄 (표의 머리)
            TableRow(list) { p ->
                Text(p.name, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(p.agency, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (differ.isEmpty()) {
                Spacer(Modifier.height(CompareSpace.RowGap))
                Text("고른 정책들의 조건이 모두 같아요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            differ.forEach { (label, f) ->
                Spacer(Modifier.height(CompareSpace.RowGap))
                // 항목 라벨: '같은 점' 라벨과 같은 작은 굵은 회색 글씨 (띠 없이)
                CompareLabel(label)
                Spacer(Modifier.height(CompareSpace.LabelGap))
                TableRow(list) { p ->
                    Text(v(p, f), style = OnRoadType.Caption.copy(lineHeight = CompareSpace.ValueLineHeight), color = OnRoadColors.TextPrimary)
                }
            }
        }

        // 2. 같은 점: 한 줄로 묶어서 짧게 (값 하나만)
        if (same.isNotEmpty()) {
            Spacer(Modifier.height(CompareSpace.GroupGap))
            GroupTitle("같은 점")
            Spacer(Modifier.height(CompareSpace.HeaderGap))
            OnRoadCard(
                Modifier.fillMaxWidth(),
                background = OnRoadColors.SurfaceSubtle,
                borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
                shape = OnRoadShapes.Item,
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    same.forEach { (label, f) ->
                        Row(verticalAlignment = Alignment.Top) {
                            CompareLabel(label, Modifier.width(CompareSpace.SameLabelWidth).padding(top = 1.dp))
                            Text(v(list.first(), f), style = OnRoadType.Caption.copy(lineHeight = CompareSpace.ValueLineHeight), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 3. 신청 준비하기
        Spacer(Modifier.height(CompareSpace.GroupGap))
        GroupTitle("신청 준비하기")
        Spacer(Modifier.height(CompareSpace.HeaderGap))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { p ->
                DataListCard(
                    title = p.name,
                    onClick = { onPrepare(p.id) },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp)) },
                ) {
                    Text("체크리스트로 단계와 서류를 정리해요", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                }
            }
        }
    }
}

/** 항목 라벨 (다른 점 · 같은 점 공통): 12 굵게, 연한 회색 */
@Composable
private fun CompareLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextTertiary, modifier = modifier)
}

/** 표 한 줄: 정책 수만큼 같은 너비 칸 (구분선 없이 칸 사이 간격으로만) */
@Composable
private fun TableRow(list: List<Policy>, cell: @Composable ColumnScope.(Policy) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CompareSpace.ColGap), verticalAlignment = Alignment.Top) {
        list.forEach { p -> Column(Modifier.weight(1f)) { cell(p) } }
    }
}

/** 항목 라벨: 묶음 제목보다 한 단계 아래 (13 굵게, 회색) */
@Composable
private fun RowLabel(text: String) {
    Text(text, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextSecondary)
}

/** 정책마다 같은 너비의 연회색 칸을 나란히 (구분선 없이 칸 사이 간격으로만) */
@Composable
private fun CompareColumns(list: List<Policy>, cell: @Composable ColumnScope.(Policy) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CompareSpace.ColGap)) {
        list.forEach { p ->
            OnRoadCard(
                Modifier.weight(1f).fillMaxHeight(),
                background = OnRoadColors.SurfaceSubtle,
                borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
                shape = OnRoadShapes.Item,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) { cell(p) }
        }
    }
}
