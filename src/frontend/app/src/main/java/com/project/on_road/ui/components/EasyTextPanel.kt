package com.project.on_road.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.data.EasyTerm
import com.project.on_road.data.EasyTextView
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType

/** 정책별·단계별 쉬운 말 캐시 (화면을 다시 열어도 다시 부르지 않게) */
private object EasyTextCache {
    private val map = mutableMapOf<String, EasyTextView?>()
    fun key(policyId: String, level: String?) = "$policyId:${level ?: "auto"}"
    fun get(key: String): Pair<Boolean, EasyTextView?> = (key in map) to map[key]
    fun put(key: String, value: EasyTextView?) { map[key] = value }
}

/**
 * 정책 카드의 "쉬운말로 보기" 내용 (api.md 3.23).
 * 서버에 단계별 변환이 있으면 단계 선택·용어 풀이·더 알아보기를 보여주고, 없으면 [fallback] 문장을 그대로 보여준다.
 */
@Composable
fun EasyTextPanel(
    policyId: String,
    fallback: String,
    modifier: Modifier = Modifier,
    /** true 면 정책 상세용: [받는 것] 같은 항목을 나눠 보여 주고, 어려운 말 풀이 · 조건 자세히 보기를 따로 둔다 */
    sectioned: Boolean = false,
) {
    var level by remember(policyId) { mutableStateOf<String?>(null) }
    var view by remember(policyId) { mutableStateOf<EasyTextView?>(null) }
    var loading by remember(policyId) { mutableStateOf(true) }
    var failed by remember(policyId) { mutableStateOf(false) }

    LaunchedEffect(policyId, level) {
        val key = EasyTextCache.key(policyId, level)
        val (hit, cached) = EasyTextCache.get(key)
        if (hit) {
            view = cached ?: view
            failed = cached == null && view == null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val result = runCatching { AppContainer.api.getEasyText(policyId, level) }
        result.onSuccess { EasyTextCache.put(key, it) }
        val loaded = result.getOrNull()
        if (loaded != null) view = loaded
        failed = loaded == null && view == null
        loading = false
    }

    val v = view
    // 불러오는 중 → 내용으로 바뀔 때 뚝 바뀌지 않고 서서히 (단계를 바꿀 때도 같은 방식)
    val phase = when {
        v == null && loading -> "loading"
        v == null || failed -> "fallback"
        else -> "ready-${v.level}"
    }
    FadeSwitch(phase, modifier.fillMaxWidth(), label = "easy-text") { _ ->
        when {
            v == null && loading -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OnRoadColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
            v == null || failed -> if (sectioned) EasySections(fallback) else BodyText(AnnotatedString(fallback))
            sectioned -> SectionedEasy(v, onPick = { level = it })
            else -> {
                LevelChips(v, onPick = { level = it })
                Spacer(Modifier.height(12.dp))
                BodyText(highlight(v.coreText, v.terms))
                if (!v.moreText.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Expandable("더 알아보기") { BodyText(highlight(v.moreText, v.terms)) }
                }
                if (v.terms.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Expandable("어려운 말 풀이 ${v.terms.size}개") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            v.terms.forEach { t ->
                                Row {
                                    Text(t.term, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.Primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(t.gloss, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChips(v: EasyTextView, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        v.levels.forEach { lv ->
            val selected = lv.code == v.level
            val label = if (lv.code == v.recommendedLevel) "${lv.name} · 추천" else lv.name
            // 필요 서류 탭 · 다른 선택 칩과 같은 크기 (34dp, Caption)
            ChoiceChip(label, selected = selected) { if (!selected) onPick(lv.code) }
        }
    }
}

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "expand")
    Column {
        Row(
            Modifier.fillMaxWidth().tap { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(18.dp).rotate(arrow))
        }
        AnimatedVisibility(open, enter = expandVertically(com.project.on_road.ui.theme.OnRoadMotion.spatialSize) + fadeIn(com.project.on_road.ui.theme.OnRoadMotion.enter()), exit = shrinkVertically(com.project.on_road.ui.theme.OnRoadMotion.collapse()) + fadeOut(com.project.on_road.ui.theme.OnRoadMotion.exit())) {
            Column(Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

@Composable
private fun BodyText(text: AnnotatedString) {
    Text(
        text,
        // 본문은 한 단계 옅게, 숫자 · 기간은 굵고 진하게 (강조가 확실히 보이게)
        style = OnRoadType.Body1.copy(fontWeight = FontWeight.Normal, lineHeight = OnRoadType.Body1.fontSize * 1.75f),
        color = OnRoadColors.TextSecondary,
    )
}

/** 본문의 "[받는 것]" 같은 항목 제목은 굵게, 사전 용어는 밑줄로 표시 */
private fun highlight(text: String, terms: List<EasyTerm>): AnnotatedString = buildAnnotatedString {
    append(text)
    Regex("""\[[^\]\n]+]""").findAll(text).forEach {
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = OnRoadColors.TextPrimary), it.range.first, it.range.last + 1)
    }
    terms.map { it.term }.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { term ->
        var from = 0
        while (true) {
            val i = text.indexOf(term, from)
            if (i < 0) break
            addStyle(SpanStyle(color = OnRoadColors.Primary, textDecoration = TextDecoration.Underline), i, i + term.length)
            from = i + term.length
        }
    }
}


/* ------------------------------------------------------------------ */
/*  정책 상세용 쉬운 말: 항목별로 나누고, 어려운 말 풀이는 아래 상자에        */
/* ------------------------------------------------------------------ */

private val EASY_SECTION = Regex("""\[([^\]\n]+)]""")

/** 정책 상세 '어떤 정책인가요'에 보여 줄 항목 (이 순서로) */
private val SHOWN_SECTIONS = listOf("받는 것", "받는 사람")

/** 금액 · 기간 · 나이 같은 숫자 조건 ("월 50만 원", "2년 이상", "5년 이내") */
private val EASY_NUMBER = Regex("""((?<![가-힣])월\s*)?\d[\d,.]*\s*(만\s*|천\s*|억\s*)?(원|년|개월|주|일|세|살|%|회|시간)(\s*(이상|이내|이하|미만|까지))?""")

private fun easyNumbers(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in EASY_NUMBER.findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OnRoadColors.TextPrimary)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
private fun SectionedEasy(v: EasyTextView, onPick: (String) -> Unit) {
    var termsOpen by remember(v.level) { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (termsOpen) 180f else 0f, label = "terms")
    Column(Modifier.fillMaxWidth()) {
        LevelChips(v, onPick)
        EasySections(v.coreText)

        val terms = v.terms.filter { it.term.isNotBlank() && it.gloss.isNotBlank() }
        if (terms.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Field)
                    .background(OnRoadColors.SurfaceSubtle)
                    .border(1.dp, OnRoadColors.Border, OnRoadShapes.Field)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (terms.size > 3) 6.dp else 14.dp),
            ) {
                val shown = if (termsOpen) terms else terms.take(3)
                shown.forEachIndexed { i, t ->
                    Row(Modifier.padding(top = if (i == 0) 0.dp else 10.dp)) {
                        Text(t.term, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary, modifier = Modifier.width(84.dp))
                        Text(t.gloss, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
                    }
                }
                if (terms.size > 3) {
                    Box(
                        Modifier.fillMaxWidth().tap(onClickLabel = if (termsOpen) "어려운 말 접기" else "어려운 말 더 보기") { termsOpen = !termsOpen }.padding(top = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(20.dp).rotate(arrow))
                    }
                }
            }
        }

        // 조건 자세히 보기는 두지 않는다 (조건은 '내 조건' 항목에 정리돼 있다)
    }
}

/** "[받는 것]\n내용" 묶음을 항목 이름(13 굵게) + 내용(15 회색, 숫자만 진하게)으로 */
@Composable
private fun EasySections(text: String) {
    val marks = EASY_SECTION.findAll(text).toList()
    if (marks.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        EasyBody(text)
        return
    }
    val all = marks.mapIndexed { i, m ->
        val end = marks.getOrNull(i + 1)?.range?.first ?: text.length
        m.groupValues[1].trim() to text.substring(m.range.last + 1, end).trim()
    }.filter { (label, body) -> body.isNotBlank() && label != "더 알아보기" }
    // 정책 상세는 받는 것 · 받는 사람만 (신청 방법 · 서류 · 문의는 아래 항목에 따로 있다). 둘 다 없으면 전부
    val shown = SHOWN_SECTIONS.mapNotNull { want -> all.firstOrNull { it.first == want } }.ifEmpty { all }
    var first = true
    shown.forEach { (label, body) ->
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = if (first) 16.dp else 24.dp)) {
            // 데이터 이름은 '받는 사람'이지만 화면에는 '받는 대상'으로
            Text(if (label == "받는 사람") "받는 대상" else label, style = OnRoadType.Body3.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextSlate)
            Spacer(Modifier.height(8.dp))
            EasyBody(body)
        }
        first = false
    }
}

@Composable
private fun EasyBody(text: String) {
    // 금액 표기(300천 원 · 10,000,000원)를 앱 전체와 같게 맞추고, 줄은 그대로 둔다
    val lines = text.trim().split("\n").map { com.project.on_road.data.remote.GovText.amount(it) ?: it }.filter { it.isNotBlank() }
    Text(
        easyNumbers(lines.joinToString("\n")),
        style = OnRoadType.Body2.copy(lineHeight = OnRoadType.Body2.lineHeight * 1.1f),
        color = OnRoadColors.TextSecondary,
    )
}
