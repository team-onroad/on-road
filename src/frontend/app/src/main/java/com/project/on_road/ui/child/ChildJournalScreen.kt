package com.project.on_road.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.KidMood
import com.project.on_road.ui.components.SpeakOnce
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/*
 * 아동 내 기록장 — 시안 D
 * 탭 3개: 기분 달력 / 좋아하는 것 도감 / 미션 기록
 * - 기분 달력: 상담하기에서 고른 기분이 날짜별 동그라미로 쌓인다. 여기서 바로 오늘 기분을 골라도 된다.
 * - 좋아하는 것 도감: '좋아하는 거 찾기'에서 고른 활동을 모은다 (12칸 중 몇 칸 모았는지)
 * - 미션 기록: 연속 성공 날 수, 최근 7일 도장, 한 줄 기록
 * 모두 휴대폰 안에만 저장되고, 누구에게도 자동으로 보내지 않는다.
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidJournal {
    val TabLabels = listOf("기분 달력", "좋아하는 것", "미션 기록")
    val TabTones = listOf(Tones.Blue, Tones.Pink, Tones.Yellow)
}

/* ====================================================== */

@Composable
fun ChildJournalScreen(onBack: () -> Unit, onFind: () -> Unit, onCoach: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var showHelp by remember { mutableStateOf(false) }
    val version = ActivityStore.version

    SpeakOnce(
        when (tab) {
            0 -> "기분 달력이야. 오늘 기분을 골라 봐."
            1 -> "좋아하는 것 도감이야. 몇 개 모았는지 볼까?"
            else -> "미션 기록이야. 지금까지 한 미션을 볼 수 있어."
        },
        key = tab,
    )

    Column(Modifier.fillMaxSize().background(RoadKit.Background)) {
        RoadSubHeader("내 기록장", Tones.Mint.bg, onBack = onBack, onHelp = { showHelp = true })

        // 탭
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KidJournal.TabLabels.forEachIndexed { i, label ->
                val on = i == tab
                val tone = KidJournal.TabTones[i]
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .kidPick(on, tone, 23.dp, if (i % 2 == 0) 7 else 21)
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = hanaText(18.sp, if (on) FontWeight.Bold else FontWeight.Normal, 1.2f), color = if (on) RoadKit.Ink else RoadKit.Body, maxLines = 1)
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                0 -> MoodPage(version, onCoach)
                1 -> LikedPage(version, onFind)
                else -> MissionPage(version)
            }
            Text(
                "내 기록장은 이 휴대폰에만 있어. 다른 사람한테 저절로 보내지 않아.",
                style = hanaText(15.sp, FontWeight.Normal, 1.3f),
                color = RoadKit.InkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    if (showHelp) {
        Dialog(onDismissRequest = { showHelp = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ChildHelpScreen(onBack = { showHelp = false })
        }
    }
}

/* ---------- 기분 달력 ---------- */

@Composable
private fun MoodPage(version: Int, onCoach: () -> Unit) {
    val moods = remember(version) { ActivityStore.moods() }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()
    val todayMood = moods[today]

    // 오늘 기분 고르기: 얼굴 8개 (4 × 2)
    Column(
        Modifier
            .fillMaxWidth()
            .handOutline(20.dp, seed = 7)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(
            if (todayMood == null) "오늘 기분은 어때?" else "오늘은 ‘${todayMood.label}’",
            style = hanaText(23.sp, lineHeight = 1.3f),
            color = RoadKit.Ink,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        KidMood.entries.chunked(4).forEachIndexed { r, row ->
            if (r > 0) Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { c, m ->
                    val on = m == todayMood
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 3.dp)
                            // 기분 고르기: 고르면 손그림 테두리 + 아주 연한 배경 (얼굴 색이 묻히지 않게)
                            .then(
                                if (on) Modifier.handOutline(16.dp, if ((r + c) % 2 == 0) 7 else 21)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(m.tone().bg.copy(alpha = 0.35f))
                                else Modifier.dashedOutline(PickDash, 16.dp, 1.6.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(RoadKit.Background),
                            )
                            .clickable {
                                ActivityStore.setMood(m)
                                if (todayMood == null) ActivityStore.logKid(KidRecordKind.COUNSEL.name, "오늘 기분 · ${m.label}", "기분 달력에 남겼어")
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MoodFace(m, 52.dp, seed = if ((r + c) % 2 == 0) 7 else 21)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            m.label,
                            style = hanaText(17.sp, if (on) FontWeight.Bold else FontWeight.Normal, 1.2f),
                            color = if (on) RoadKit.Ink else RoadKit.Body,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "이야기하고 싶으면 상담하기로 가자",
            style = hanaText(17.sp, FontWeight.Normal, 1.25f),
            color = Tones.Blue.title,
            modifier = Modifier.clickable(onClick = onCoach).padding(start = 4.dp, top = 2.dp),
        )
    }

    // 달력
    Column(
        Modifier
            .fillMaxWidth()
            .handOutline(20.dp, seed = 21)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChipButton({ month = month.minusMonths(1) }) { Chevron(left = true, color = RoadKit.Ink) }
            Text(
                "${month.year}년 ${month.monthValue}월",
                style = hanaText(23.sp, lineHeight = 1.25f),
                color = RoadKit.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            ChipButton({ if (month < YearMonth.now()) month = month.plusMonths(1) }) {
                Chevron(left = false, color = if (month < YearMonth.now()) RoadKit.Ink else RoadKit.Road)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { d ->
                Text(d, style = hanaText(16.sp, FontWeight.Normal, 1.2f), color = RoadKit.InkMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7          // 일요일 = 0
        val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Column(
                        Modifier.weight(1f).padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (day != null) {
                            val m = moods[day]
                            Text(
                                "${day.dayOfMonth}",
                                style = hanaText(14.sp, if (day == today) FontWeight.Bold else FontWeight.Normal, 1.1f),
                                color = when {
                                    day.isAfter(today) -> RoadKit.Road
                                    day == today -> RoadKit.Ink
                                    else -> RoadKit.InkSub
                                },
                            )
                            Spacer(Modifier.height(2.dp))
                            if (m != null) {
                                MoodFace(m, 34.dp, seed = day.dayOfMonth)
                            } else {
                                Box(
                                    Modifier.size(34.dp).dashedOutline(if (day == today) RoadKit.InkMuted else RoadKit.Road.copy(alpha = 0.5f), 17.dp, 1.2.dp),
                                )
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // 이 달 요약
        val inMonth = moods.filterKeys { YearMonth.from(it) == month }.values
        Spacer(Modifier.height(12.dp))
        if (inMonth.isEmpty()) {
            Text("이 달에는 아직 남긴 기분이 없어", style = hanaText(18.sp, FontWeight.Normal, 1.3f), color = RoadKit.InkMuted, modifier = Modifier.padding(start = 4.dp))
        } else {
            val most = inMonth.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MoodFace(most.key, 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    buildAnnotatedString {
                        append("이 달엔 ")
                        withStyle(SpanStyle(color = most.key.tone().title, fontWeight = FontWeight.Bold)) { append("‘${most.key.label}’") }
                        append(" ${most.value}번, 기분 ${inMonth.size}번 남겼어")
                    },
                    style = hanaText(17.sp, FontWeight.Normal, 1.3f),
                    color = RoadKit.InkSub,
                )
            }
        }
    }
}

/* ---------- 좋아하는 것 도감 ---------- */

@Composable
private fun LikedPage(version: Int, onFind: () -> Unit) {
    val counts = remember(version) { ActivityStore.likedCounts().toMap() }
    val liked = remember(version) { ActivityStore.liked() }
    val collected = KidActivities.count { it.label in counts }

    Text(
        buildAnnotatedString {
            append("${KidActivities.size}칸 중 ")
            withStyle(SpanStyle(color = Tones.Pink.title)) { append("${collected}칸") }
            append(" 모았어!")
        },
        style = hanaText(26.sp, lineHeight = 1.3f),
        color = RoadKit.Ink,
        modifier = Modifier.padding(start = 2.dp),
    )

    KidActivities.chunked(3).forEachIndexed { r, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEachIndexed { c, a ->
                val n = counts[a.label] ?: 0
                val on = n > 0
                Box(
                    Modifier
                        .weight(1f)
                        .height(108.dp)
                        .then(if (on) Modifier.handOutline(18.dp, if ((r + c) % 2 == 0) 7 else 21) else Modifier.dashedOutline(RoadKit.Road, 18.dp, 1.6.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (on) Tones.Pink.bg else RoadKit.Background),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (on) {
                            ImageSlot(Modifier.size(42.dp), Tones.Pink.slot, radius = 12.dp, image = a.image, fill = Color.White.copy(alpha = 0.6f))
                            Text(a.label, style = hanaText(18.sp, lineHeight = 1.2f), color = RoadKit.Ink, maxLines = 1)
                        } else {
                            Text("?", style = hanaText(30.sp, lineHeight = 1.1f), color = RoadKit.Road)
                            Text("아직 몰라", style = hanaText(16.sp, FontWeight.Normal, 1.2f), color = RoadKit.InkMuted, maxLines = 1)
                        }
                    }
                    if (n > 1) {
                        Text(
                            "${n}번",
                            style = hanaText(14.sp, lineHeight = 1.1f),
                            color = Tones.Pink.title,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp),
                        )
                    }
                }
            }
        }
    }

    // 해 보고 싶은 일
    val jobs = liked.flatMap { it.jobs }.distinct()
    if (jobs.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .handOutline(20.dp, seed = 21)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(16.dp),
        ) {
            Text("이런 일을 해 볼 수 있어", style = hanaText(21.sp, lineHeight = 1.3f), color = RoadKit.Ink)
            Spacer(Modifier.height(6.dp))
            Text(jobs.take(9).joinToString(" · "), style = hanaText(19.sp, FontWeight.Normal, 1.4f), color = RoadKit.Body)
            liked.firstOrNull()?.reasons?.takeIf { it.isNotEmpty() }?.let { rs ->
                Spacer(Modifier.height(8.dp))
                Text("좋은 이유: ${rs.joinToString(", ")}", style = hanaText(16.sp, FontWeight.Normal, 1.3f), color = Tones.Pink.sub)
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(RoadKit.Accent).clickable(onClick = onFind),
        contentAlignment = Alignment.Center,
    ) { Text(if (collected == 0) "좋아하는 거 찾으러 가기" else "더 찾으러 가기", style = hanaText(22.sp, lineHeight = 1.2f), color = Color.White) }
}

/* ---------- 미션 기록 ---------- */

@Composable
private fun MissionPage(version: Int) {
    val notes = remember(version) { ActivityStore.missionNotes() }
    val streak = remember(version) { ActivityStore.missionStreak() }
    val doneDays = notes.map { it.date }.toSet()
    val today = LocalDate.now()

    Text(
        buildAnnotatedString {
            if (streak > 0) {
                withStyle(SpanStyle(color = RoadKit.Star)) { append("${streak}일") }
                append(" 연속 미션 성공!")
            } else {
                append("오늘 미션부터 시작해 볼까?")
            }
        },
        style = hanaText(26.sp, lineHeight = 1.3f),
        color = RoadKit.Ink,
        modifier = Modifier.padding(start = 2.dp),
    )

    // 최근 7일 도장
    Column(
        Modifier
            .fillMaxWidth()
            .handOutline(20.dp, seed = 7)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text("이번 주 도장", style = hanaText(20.sp, lineHeight = 1.3f), color = RoadKit.Ink, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            (6 downTo 0).map { today.minusDays(it.toLong()) }.forEach { d ->
                val on = d in doneDays
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .then(if (on) Modifier.handOutline(19.dp, seed = d.dayOfMonth) else Modifier.dashedOutline(RoadKit.Road, 19.dp, 1.4.dp))
                            .clip(CircleShape)
                            .background(if (on) Tones.Yellow.bg else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Text("별", style = hanaText(15.sp, lineHeight = 1.1f), color = RoadKit.Star)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (d == today) "오늘" else d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.KOREAN),
                        style = hanaText(14.sp, if (d == today) FontWeight.Bold else FontWeight.Normal, 1.2f),
                        color = if (d == today) RoadKit.Ink else RoadKit.InkMuted,
                    )
                }
            }
        }
    }

    if (notes.isEmpty()) {
        Text(
            "홈에서 오늘의 미션을 하고 한 줄을 남기면\n여기에 차곡차곡 쌓여!",
            style = hanaText(19.sp, FontWeight.Normal, 1.4f),
            color = RoadKit.InkMuted,
            modifier = Modifier.padding(start = 2.dp),
        )
        return
    }

    notes.take(30).forEachIndexed { i, n ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .handOutline(16.dp, if (i % 2 == 0) 21 else 7)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Tones.Yellow.bg), contentAlignment = Alignment.Center) {
                Text("${n.date.dayOfMonth}", style = hanaText(15.sp, lineHeight = 1.1f), color = Tones.Yellow.title)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${n.date.monthValue}월 ${n.date.dayOfMonth}일 ${n.date.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.KOREAN)}",
                    style = hanaText(15.sp, FontWeight.Normal, 1.2f),
                    color = Tones.Yellow.title,
                )
                Text(n.mission, style = hanaText(19.sp, lineHeight = 1.3f), color = RoadKit.Ink)
                n.note?.let { Text("“$it”", style = hanaText(17.sp, FontWeight.Normal, 1.3f), color = RoadKit.Body) }
            }
        }
    }
}