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
fun EasyTextPanel(policyId: String, fallback: String, modifier: Modifier = Modifier) {
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
    Column(modifier.fillMaxWidth()) {
        when {
            v == null && loading -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OnRoadColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
            v == null || failed -> BodyText(AnnotatedString(fallback))
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
                                    Text(t.gloss, style = OnRoadType.Caption, color = OnRoadColors.TextSlateDark)
                                }
                            }
                        }
                    }
                }
                if (!v.verified) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "AI가 바꾼 문장이에요. 금액·조건은 원문과 함께 확인해 주세요.",
                        style = OnRoadType.Tiny,
                        color = OnRoadColors.TextMuted,
                    )
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
            Box(
                Modifier
                    .clip(OnRoadShapes.Tag)
                    .background(if (selected) OnRoadColors.Primary else OnRoadColors.Surface)
                    .border(1.dp, if (selected) OnRoadColors.Primary else OnRoadColors.BorderSlate, OnRoadShapes.Tag)
                    .clickable(enabled = !selected) { onPick(lv.code) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium),
                    color = if (selected) OnRoadColors.Surface else OnRoadColors.TextSlateDark,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "expand")
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnRoadColors.TextMuted, modifier = Modifier.size(18.dp).rotate(arrow))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

@Composable
private fun BodyText(text: AnnotatedString) {
    Text(
        text,
        style = OnRoadType.Body2.copy(lineHeight = OnRoadType.Body2.lineHeight * 1.1f),
        color = OnRoadColors.TextSlateDark,
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
