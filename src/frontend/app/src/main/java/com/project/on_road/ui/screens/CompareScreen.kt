package com.project.on_road.ui.screens

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
    private var loaded = false

    fun load(ids: List<String>) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            loading = true
            val api = AppContainer.api
            val userId = AppContainer.session.userId.orEmpty()
            // 선택된 정책이 없으면 저장한 정책으로 비교
            policies = runCatching {
                val target = ids.ifEmpty { api.getSavedPolicyIds(userId).toList() }
                api.getPolicies(target).take(3)
            }.getOrDefault(emptyList())
            loading = false
        }
    }
}

/** 나란히 비교할 항목 */
private val keyRows: List<Pair<String, (Policy) -> String>> = listOf(
    "[지원 내용]" to { p -> p.amount },
    "[지원 기간]" to { p -> p.period },
)

private val detailRows: List<Pair<String, (Policy) -> String>> = listOf(
    "지원 대상" to { p -> p.target },
    "신청 방법" to { p -> p.howToApply },
    "필요 서류" to { p -> p.requiredDocs.joinToString(", ").ifBlank { "공고문에서 확인" } },
)

/** 정책 비교 (디자인: 정책비교). 어느 쪽이 낫다고 단정하지 않고 조건만 나란히 보여 준다. */
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

    OnRoadScreen(
        title = "정책 비교",
        onBack = onBack,
        trailing = if (list.size >= 2) {
            { OnRoadTag("정책 ${list.size}개", style = TagStyle.Neutral, modifier = Modifier.padding(end = 8.dp)) }
        } else null,
    ) {
        when {
            vm.loading -> LoadingState("비교표를 만들고 있어요")
            list.size < 2 -> EmptyState(
                icon = Icons.Outlined.TableChart,
                title = "비교할 정책을 골라 주세요",
                message = "정책 검색에서 ‘비교 담기’를 2개 이상 체크하거나,\n정책을 2개 이상 저장하면 여기서 나란히 볼 수 있어요.",
                actionLabel = "정책 찾으러 가기",
                onAction = onSearch,
            )
            else -> CompareContent(list, onPrepare)
        }
    }
}

@Composable
private fun CompareContent(list: List<Policy>, onPrepare: (String) -> Unit) {
    Text("나에게 맞는 조건을", style = OnRoadType.Title1Regular, color = OnRoadColors.TextPrimary)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("한눈에 비교") }
            append("해 보세요.")
        },
        style = OnRoadType.Title1Regular,
        color = OnRoadColors.TextPrimary,
    )

    Spacer(Modifier.height(OnRoadDimens.SectionGap))
    SectionHeader("내가 비교할 정책", titleStyle = OnRoadType.Title3, trailingText = "${list.size}개 선택됨")
    Spacer(Modifier.height(16.dp))
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEachIndexed { i, p ->
            PolicyMiniCard(p, highlighted = i == 0, Modifier.weight(1f).fillMaxHeight())
        }
    }

    Spacer(Modifier.height(36.dp))
    SubHeader("${keyRows.size}가지 핵심 차이")
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        keyRows.forEach { (label, value) -> DifferenceCard(label, list, value) }
    }

    Spacer(Modifier.height(40.dp))
    SubHeader("세부 조건 확인하기", trailingText = "${detailRows.size}개 항목")
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detailRows.forEach { (label, value) ->
            ListRow(label, list.joinToString("\n") { "${it.name} · ${value(it)}" })
        }
    }

    Spacer(Modifier.height(32.dp))
    Footnote("확인일 기준 공고 내용으로 정리한 비교예요.\n어떤 정책이 맞는지는 담당 기관에서 최종 확인해 주세요.")

    Spacer(Modifier.height(48.dp))
    list.forEachIndexed { i, p ->
        OnRoadButton(
            text = "${p.name} 신청 준비하기",
            onClick = { onPrepare(p.id) },
            modifier = Modifier.fillMaxWidth(),
            variant = if (i == 0) ButtonVariant.Primary else ButtonVariant.Outline,
            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PolicyMiniCard(p: Policy, highlighted: Boolean, modifier: Modifier) {
    OnRoadCard(
        modifier,
        borderColor = if (highlighted) OnRoadColors.Primary else OnRoadColors.Border,
        borderWidth = if (highlighted) 2.dp else 1.dp,
        shape = OnRoadShapes.Inner,
        contentPadding = PaddingValues(15.dp),
    ) {
        OnRoadTag(
            p.category,
            style = if (highlighted) TagStyle.PrimaryFilled else TagStyle.SlateFilled,
            shape = OnRoadShapes.Badge,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            p.name,
            style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
            color = if (highlighted) OnRoadColors.Primary else OnRoadColors.TextPrimary,
            maxLines = 2,
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Border)
        Spacer(Modifier.height(12.dp))
        Text(p.agency, style = OnRoadType.Micro, color = OnRoadColors.TextPrimary.copy(alpha = if (highlighted) 0.6f else 0.4f), maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text("${p.checkedAt} 확인", style = OnRoadType.Micro, color = OnRoadColors.TextPrimary.copy(alpha = 0.4f))
    }
}

@Composable
private fun DifferenceCard(label: String, list: List<Policy>, value: (Policy) -> String) {
    SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 18.dp)) {
        Text(label, style = OnRoadType.Tiny, color = OnRoadColors.TextPrimary.copy(alpha = 0.4f))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            list.forEachIndexed { i, p ->
                if (i > 0) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(OnRoadColors.Border))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = if (i == 0) 0.dp else 14.dp, end = 10.dp),
                ) {
                    Text(
                        value(p),
                        style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                        color = if (i == 0) OnRoadColors.Primary else OnRoadColors.TextPrimary.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(p.name, style = OnRoadType.Micro, color = OnRoadColors.TextPrimary.copy(alpha = 0.4f), maxLines = 1)
                }
            }
        }
    }
}
