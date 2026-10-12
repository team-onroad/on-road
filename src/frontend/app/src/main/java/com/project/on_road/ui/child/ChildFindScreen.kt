package com.project.on_road.ui.child

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.project.on_road.ui.components.SpeakOnce

/*
 * 아동 전용 좋아하는 거 찾기 — 시안 D
 * 작은 점선 길(고르기 → 이유 → 직업)
 * 1. 고르기: 활동 12개 중 1~3개 (3칸 그리드)
 * 2. 이유: 왜 좋은지 고르기 (여러 개 가능)
 * 3. 직업: 고른 활동마다 해 볼 수 있는 일 보여 주기 → 다 했어
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidFind {
    const val MaxPick = 3
    val CardDash = Color(0xFFD9CCBA)     // 안 고른 카드 점선
    val StepIdle = Color(0xFFA89A88)     // 아직 안 간 단계 글씨
}

/** 활동 · 그림 · 해 볼 수 있는 일. 그림은 R.drawable.파일이름 (null 이면 점선 자리) */
data class KidActivity(val label: String, val jobs: List<String>, @DrawableRes val image: Int? = null)

val KidActivities = listOf(
    KidActivity("식물 키우기", listOf("정원사", "식물학자", "꽃집 사장님")),
    KidActivity("그림 그리기", listOf("화가", "웹툰 작가", "디자이너")),
    KidActivity("만들기", listOf("공예가", "건축가", "발명가")),
    KidActivity("친구 돕기", listOf("선생님", "간호사", "사회복지사")),
    KidActivity("퍼즐 풀기", listOf("과학자", "프로그래머", "탐정")),
    KidActivity("노래·춤", listOf("가수", "댄서", "뮤지컬 배우")),
    KidActivity("동물 돌보기", listOf("수의사", "사육사", "반려동물 미용사")),
    KidActivity("운동", listOf("운동선수", "체육 선생님", "트레이너")),
    KidActivity("책 읽기", listOf("작가", "사서", "기자")),
    KidActivity("요리", listOf("요리사", "제빵사", "영양사")),
    KidActivity("정리하기", listOf("정리 전문가", "큐레이터", "물류 관리자")),
    KidActivity("발표하기", listOf("아나운서", "유튜버", "선생님")),
)

private val Reasons = listOf("재미있어서", "잘해서", "칭찬받아서", "마음이 편해서")
/* ====================================================== */

/** 찾기 결과 (저장하거나 내 기록에 넘길 때 사용) */
data class ChildFindResult(val activities: List<KidActivity>, val reasons: List<String>)

@Composable
fun ChildFindScreen(onBack: () -> Unit, onDone: (ChildFindResult) -> Unit = { onBack() }) {
    var step by remember { mutableIntStateOf(0) }           // 0 고르기, 1 이유, 2 직업
    val picked = remember { mutableStateListOf<KidActivity>() }
    val reasons = remember { mutableStateListOf<String>() }
    var showHelp by remember { mutableStateOf(false) }

    val back: () -> Unit = { if (step > 0) step -= 1 else onBack() }
    BackHandler(enabled = step > 0) { step -= 1 }

    SpeakOnce(
        when (step) {
            0 -> "어떤 걸 할 때 즐거워? 1개부터 3개까지 골라 줘."
            1 -> "왜 좋아? 마음에 드는 걸 골라 줘."
            else -> "이런 일을 해 볼 수 있어!"
        },
    )

    Column(Modifier.fillMaxSize().background(RoadKit.Background)) {
        RoadSubHeader("좋아하는 거 찾기", Tones.Pink.bg, onBack = back, onHelp = { showHelp = true })

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepRoad(step)

            when (step) {
                0 -> {
                    Title("어떤 걸 할 때 즐거워?", "1개부터 ${KidFind.MaxPick}개까지 골라 줘")
                    KidActivities.chunked(3).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { c, a ->
                                val on = a in picked
                                ActivityCard(a, on, seed = if ((r + c) % 2 == 0) 7 else 21, modifier = Modifier.weight(1f)) {
                                    if (on) picked.remove(a) else if (picked.size < KidFind.MaxPick) picked.add(a)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Title("왜 좋아?", picked.joinToString(", ") { it.label })
                    Reasons.chunked(2).forEachIndexed { r, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEachIndexed { c, label ->
                                val idx = r * 2 + c
                                val on = label in reasons
                                val tone = listOf(Tones.Blue, Tones.Pink, Tones.Purple, Tones.Yellow)[idx % 4]
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(64.dp)
                                        .then(
                                            if (on) Modifier.handOutline(18.dp, if (idx % 2 == 0) 7 else 21)
                                            else Modifier.dashedOutline(KidFind.CardDash, 18.dp, 1.6.dp),
                                        )
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (on) tone.bg else RoadKit.Background)
                                        .clickable { if (on) reasons.remove(label) else reasons.add(label) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(label, style = hanaText(21.sp, if (on) FontWeight.Bold else FontWeight.Normal, 1.2f), color = RoadKit.Ink)
                                    if (on) CheckBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
                                }
                            }
                        }
                    }
                }
                else -> {
                    Title("이런 일을 해 볼 수 있어!", "좋아하는 걸 하는 어른들이야")
                    picked.forEachIndexed { i, a ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 88.dp)
                                .handOutline(18.dp, if (i % 2 == 0) 7 else 21)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ImageSlot(Modifier.size(52.dp), Tones.Pink.slot, radius = 14.dp, image = a.image, fill = Tones.Pink.bg)
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${a.label}를 좋아해!".fixJosa(a.label), style = hanaText(16.sp, FontWeight.Normal, 1.2f), color = Tones.Pink.title)
                                Text(a.jobs.joinToString(" · "), style = hanaText(21.sp, lineHeight = 1.3f), color = RoadKit.Ink)
                            }
                        }
                    }
                }
            }
        }

        // 아래: 고른 개수 + 다음
        val canNext = when (step) {
            0 -> picked.isNotEmpty()
            1 -> reasons.isNotEmpty()
            else -> true
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 24.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (step) {
                    0 -> "${picked.size}개 골랐어"
                    1 -> if (reasons.isEmpty()) "하나 이상 골라 줘" else "${reasons.size}개 골랐어"
                    else -> "잘 찾았어!"
                },
                style = hanaText(20.sp, FontWeight.Normal, 1.3f),
                color = RoadKit.InkSub,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (canNext) RoadKit.Accent else RoadKit.Chip)
                    .clickable(enabled = canNext) {
                        if (step < 2) step += 1 else onDone(ChildFindResult(picked.toList(), reasons.toList()))
                    }
                    .padding(horizontal = 34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (step < 2) "다음" else "다 했어",
                    style = hanaText(23.sp, lineHeight = 1.2f),
                    color = if (canNext) Color.White else RoadKit.InkMuted,
                )
            }
        }
    }

    if (showHelp) {
        Dialog(onDismissRequest = { showHelp = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ChildHelpScreen(onBack = { showHelp = false })
        }
    }
}

/** 받침에 맞게 '를/을' */
private fun String.fixJosa(word: String): String {
    val last = word.lastOrNull() ?: return this
    if (last !in '가'..'힣') return this
    val batchim = (last.code - 0xAC00) % 28 != 0
    return if (batchim) replace("${word}를", "${word}을") else this
}

@Composable
private fun Title(big: String, small: String) {
    Column(Modifier.padding(start = 2.dp)) {
        Text(big, style = hanaText(31.sp, lineHeight = 1.25f), color = RoadKit.Ink)
        Text(small, style = hanaText(21.sp, FontWeight.Normal, 1.3f), color = RoadKit.InkSub)
    }
}

/** 진행: 작은 점선 길 위 정거장 3개 */
@Composable
private fun StepRoad(step: Int) {
    val labels = listOf("고르기", "이유", "직업")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(56.dp)) {
        val w = maxWidth
        Box(
            Modifier.fillMaxWidth().height(32.dp).drawBehind {
                val y = size.height / 2
                drawLine(
                    RoadKit.Road,
                    Offset(16.dp.toPx(), y),
                    Offset(size.width - 16.dp.toPx(), y),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 10.dp.toPx())),
                )
            },
        )
        labels.forEachIndexed { i, label ->
            val x = (w - 32.dp) * (i / 2f)
            val reached = i <= step
            Box(
                Modifier
                    .offset(x = x)
                    .size(32.dp)
                    .then(if (reached) Modifier.handOutline(16.dp, if (i % 2 == 0) 7 else 21) else Modifier.dashedOutline(RoadKit.Road, 16.dp, 1.6.dp))
                    .clip(CircleShape)
                    .background(if (reached) Tones.Pink.bg else RoadKit.Background),
                contentAlignment = Alignment.Center,
            ) {
                Text("${i + 1}", style = hanaText(18.sp, if (reached) FontWeight.Bold else FontWeight.Normal, 1.1f), color = if (reached) RoadKit.Ink else KidFind.StepIdle)
            }
            Text(
                label,
                style = hanaText(15.sp, if (i == step) FontWeight.Bold else FontWeight.Normal, 1.2f),
                color = if (i == step) Tones.Pink.title else KidFind.StepIdle,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset(x = x - 6.dp, y = 36.dp).size(width = 44.dp, height = 20.dp),
            )
        }
    }
}

@Composable
private fun ActivityCard(a: KidActivity, on: Boolean, seed: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(104.dp)
            .then(if (on) Modifier.handOutline(18.dp, seed) else Modifier.dashedOutline(KidFind.CardDash, 18.dp, 1.6.dp))
            .clip(shape)
            .background(if (on) Tones.Pink.bg else RoadKit.Background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageSlot(
                Modifier.size(44.dp),
                if (on) Tones.Pink.slot else RoadKit.Road,
                radius = 12.dp,
                image = a.image,
                fill = if (on) Color.White.copy(alpha = 0.6f) else Color.Transparent,
            )
            Text(a.label, style = hanaText(19.sp, if (on) FontWeight.Bold else FontWeight.Normal, 1.2f), color = RoadKit.Ink, maxLines = 1)
        }
        if (on) CheckBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** 고른 표시 (진한 글자색 동그라미 + 체크) */
@Composable
private fun CheckBadge(modifier: Modifier) {
    Box(
        modifier.size(22.dp).clip(CircleShape).background(RoadKit.Ink),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val s = size.width / 24f
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(5 * s, 12 * s); lineTo(10 * s, 17 * s); lineTo(19 * s, 7 * s) }
            drawPath(p, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.4f * s, cap = StrokeCap.Round))
        }
    }
}