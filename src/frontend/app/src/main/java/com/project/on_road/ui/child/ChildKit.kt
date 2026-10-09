package com.project.on_road.ui.child

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.project.on_road.ui.components.BuddyMood
import com.project.on_road.ui.components.OnRoadBuddy
import com.project.on_road.ui.components.SpeakButton
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadType

/*
 * 아동 화면 공통 조각
 * - BuddyTalk: 말풍선(꼬리 달림) + 그 아래 캐릭터. 온보딩·홈에서 같은 모양으로 쓴다.
 * 캐릭터 그림(OnRoadBuddy)은 그대로 쓰고 배치만 정한다.
 */

private val BubbleShape = RoundedCornerShape(20.dp)

/**
 * 캐릭터가 말하는 모양.
 * @param title 크게 보일 말 (질문, 인사)
 * @param sub 작게 덧붙이는 말 (없으면 null)
 * @param speakText 읽어 주기 버튼이 읽을 내용
 * @param onTapBuddy 캐릭터를 눌렀을 때 (null 이면 누를 수 없음)
 */
@Composable
fun BuddyTalk(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    speakText: String = listOfNotNull(title, sub).joinToString(" ").replace("\n", " "),
    happy: Boolean = false,
    buddySize: Dp = 132.dp,
    onTapBuddy: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 말풍선
        Box(
            Modifier
                .fillMaxWidth()
                .clip(BubbleShape)
                .background(Color.White)
                .border(1.5.dp, OnRoadColors.Border, BubbleShape),
        ) {
            AnimatedContent(
                targetState = title to sub,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                modifier = Modifier.fillMaxWidth(),
                label = "buddyTalk",
            ) { (t, s) ->
                Column(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary, textAlign = TextAlign.Center)
                    if (s != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(s, style = OnRoadType.Body2, color = OnRoadColors.TextTertiary, textAlign = TextAlign.Center)
                    }
                    SpeakButton(speakText)
                }
            }
        }
        // 말풍선 꼬리 (테두리를 덮도록 살짝 위로 겹침)
        BubbleTail(Modifier.offset(y = (-1.5).dp))

        // 캐릭터
        Box(
            if (onTapBuddy != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTapBuddy,
                )
            } else Modifier,
        ) {
            OnRoadBuddy(size = buddySize, mood = if (happy) BuddyMood.HAPPY else BuddyMood.IDLE, showTrail = false)
        }
    }
}

@Composable
private fun BubbleTail(modifier: Modifier = Modifier) {
    val border = OnRoadColors.Border
    Canvas(modifier.size(width = 22.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val fill = Path().apply {
            moveTo(0f, 0f); lineTo(w / 2f, h); lineTo(w, 0f); close()
        }
        drawPath(fill, Color.White)
        val edge = Path().apply {
            moveTo(0f, 0f); lineTo(w / 2f, h); lineTo(w, 0f)
        }
        drawPath(edge, border, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/**
 * 질문용 작은 모양 (온보딩): 왼쪽 캐릭터 + 오른쪽 말풍선(왼쪽 꼬리).
 * 높이가 낮아서 아래 선택지가 한 화면에 들어간다.
 */
@Composable
fun BuddyAsk(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    happy: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OnRoadBuddy(size = 84.dp, mood = if (happy) BuddyMood.HAPPY else BuddyMood.IDLE, showTrail = false)
        Spacer(Modifier.width(4.dp))
        // 꼬리가 말풍선 테두리를 덮도록 겹치고 위에 그림
        SideTail(Modifier.offset(x = 1.5.dp).zIndex(1f))
        Row(
            Modifier
                .weight(1f)
                .clip(BubbleShape)
                .background(Color.White)
                .border(1.5.dp, OnRoadColors.Border, BubbleShape)
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = title to sub,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                modifier = Modifier.weight(1f),
                label = "buddyAsk",
            ) { (t, s) ->
                Column {
                    Text(t, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
                    if (s != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(s, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    }
                }
            }
            SpeakButton(listOfNotNull(title, sub).joinToString(" ").replace("\n", " "))
        }
    }
}

/** 왼쪽을 가리키는 말풍선 꼬리 */
@Composable
private fun SideTail(modifier: Modifier = Modifier) {
    val border = OnRoadColors.Border
    Canvas(modifier.size(width = 10.dp, height = 18.dp)) {
        val w = size.width
        val h = size.height
        drawPath(Path().apply { moveTo(w, 0f); lineTo(0f, h / 2f); lineTo(w, h); close() }, Color.White)
        drawPath(
            Path().apply { moveTo(w, 0f); lineTo(0f, h / 2f); lineTo(w, h) },
            border,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}