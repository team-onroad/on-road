package com.project.on_road.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import com.project.on_road.ui.theme.OnRoadType

private const val WORD_JOINER = '⁠'

/**
 * 한글 낱말 중간에서 줄이 바뀌지 않게 (CSS word-break: keep-all 처럼 띄어쓰기에서만 줄바꿈).
 * 안드로이드는 한글을 글자마다 끊을 수 있어 "알려 주세\n요"처럼 되므로, 한글 글자 사이에 보이지 않는 연결 문자를 넣는다
 */
fun String.keepWords(): String {
    if (isEmpty()) return this
    val out = StringBuilder(length * 2)
    for (i in indices) {
        val c = this[i]
        out.append(c)
        val next = getOrNull(i + 1) ?: continue
        if (!c.isWhitespace() && !next.isWhitespace() && (c.isHangul() || next.isHangul())) out.append(WORD_JOINER)
    }
    return out.toString()
}

private fun Char.isHangul(): Boolean = this in '가'..'힣' || this in 'ㄱ'..'ㆎ'

/** 문장 끝(. ? !) 뒤 띄어쓰기. "1. 항목", "2026.10.28" 같은 숫자 뒤 점은 문장 끝이 아니다 */
private val SENTENCE_END = Regex("""(?<=[^\d\s][.?!]|[.?!]['")])\s+""")

/** 짧은 안내(문장 3개 이하)만 문장마다 나눈다. AI 초안 같은 긴 문단은 문단 그대로 */
private const val MAX_SPLIT_SENTENCES = 3

/** 말풍선 글: 줄 길이를 고르게 (마지막 줄에 한두 글자만 남지 않게) */
private val BubbleLineBreak = LineBreak(
    strategy = LineBreak.Strategy.Balanced,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Default,
)

/**
 * 말풍선 글. 말풍선 폭을 재서
 * - 한 줄에 다 들어가면 그대로
 * - 넘치면 문장 사이에서 줄을 바꾼다 ("…할까요?\n맞으면 '네', 아니면 …")
 * - 한 문장이 길면 낱말 단위로, 줄 길이를 고르게 줄바꿈
 */
@Composable
fun BubbleText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight),
) {
    val measurer = rememberTextMeasurer()
    val lineStyle = remember(style) { style.copy(lineBreak = BubbleLineBreak) }
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val shown = remember(text, maxWidth, lineStyle) {
            text.split("\n").joinToString("\n") { paragraph ->
                val sentences = paragraph.split(SENTENCE_END).filter { it.isNotBlank() }
                val fits = maxWidth == Constraints.Infinity || measurer.measure(
                    paragraph.keepWords(), lineStyle, softWrap = false, maxLines = 1,
                ).size.width <= maxWidth
                if (fits || sentences.size < 2 || sentences.size > MAX_SPLIT_SENTENCES) paragraph.keepWords()
                else sentences.joinToString("\n") { it.trim().keepWords() }
            }
        }
        Text(shown, style = lineStyle, color = color)
    }
}
