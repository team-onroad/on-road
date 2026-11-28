package com.project.on_road.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.project.on_road.data.CareerPlan
import com.project.on_road.data.ExamRound
import com.project.on_road.data.ExamText
import com.project.on_road.data.TrainingCourse
import com.project.on_road.data.TrainingDetail
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import java.time.LocalDate

/*
 * 진로 외부 정보 카드 (흥미 탐색 직업 상세 · 진로 로드맵 같이 쓴다)
 * - 시험 회차: 다가오는 일정 + 일정에 넣기
 * - 훈련 과정: 누르면 소개(기관 · 기간 · 비용)가 펼쳐진다
 */

/** 연회색 카드 (진로 정보) */
@Composable
internal fun InfoCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    OnRoadCard(
        modifier,
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border,
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
        content = content,
    )

/** 왼쪽 이름 (회색) · 오른쪽 값 (진하게) */
@Composable
internal fun CareerInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(end = 12.dp))
        Text(
            value,
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.SemiBold),
            color = OnRoadColors.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 펼침 화살표 (펼치면 아래로 돈다) */
@Composable
internal fun ExpandChevron(open: Boolean) {
    val r by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        null,
        tint = OnRoadColors.TextQuaternary,
        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = r },
    )
}

/** 작은 흰 바탕 버튼 (테두리) */
@Composable
internal fun OutlinePill(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
        color = OnRoadColors.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(OnRoadShapes.Chip)
            .background(Color.White)
            .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Chip)
            .tap(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** 1050000 → "105만 원", 597120 → "59만 7,120원" */
internal fun careerWon(n: Int): String {
    val man = n / 10000
    val rest = n % 10000
    return when {
        man == 0 -> "%,d원".format(rest)
        rest == 0 -> "%,d만 원".format(man)
        else -> "%,d만 %,d원".format(man, rest)
    }
}

/** 시험 회차 카드: 제목(제24회 실기) · 가장 가까운 일정 · 일정에 넣기 / 나머지 일정 */
@Composable
internal fun ExamRoundCard(round: ExamRound, today: LocalDate, showCert: Boolean = false) {
    val up = round.upcoming(today)
    val head = up.firstOrNull() ?: return
    val added = remember(CareerPlan.version) { CareerPlan.hasExam(round.id) }
    InfoCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (showCert) "${round.cert} ${round.title}" else round.title,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold),
                    color = OnRoadColors.TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text("${head.label} ${ExamText.range(head)}", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            Spacer(Modifier.width(10.dp))
            // 넣으면 흰 바탕 테두리로 바뀐다 (한 번 더 누르면 뺀다)
            if (added) OutlinePill("일정에서 빼기") { CareerPlan.toggleExam(round, today) }
            else PrimaryPill("일정에 넣기", { CareerPlan.toggleExam(round, today) })
        }
        if (up.size > 1) {
            Spacer(Modifier.height(10.dp))
            up.drop(1).forEach { e -> CareerInfoLine(e.label, ExamText.range(e)) }
        }
    }
}

/**
 * 훈련 과정 카드. 누르면 펼치며 onOpen 으로 소개를 받는다 (detail 이 올 때까지는 목록 값만).
 */
@Composable
internal fun TrainingCourseCard(c: TrainingCourse, detail: TrainingDetail?, onOpen: () -> Unit) {
    val uri = LocalUriHandler.current
    var open by remember(c.id + c.round) { mutableStateOf(false) }
    val toggle: () -> Unit = {
        open = !open
        if (open) onOpen()
    }
    InfoCard(Modifier.fillMaxWidth(), onClick = toggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                val area = c.address?.substringAfter(" ")?.ifBlank { null } ?: c.address
                val period = listOfNotNull(c.start?.let(ExamText::day), c.end?.let(ExamText::day)).joinToString(" ~ ")
                val sub = listOfNotNull(area, period.ifBlank { null }).joinToString(", ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(sub, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            }
            ExpandChevron(open)
        }
        ExpandArea(open) {
            Spacer(Modifier.height(10.dp))
            CareerInfoLine("훈련 기관", detail?.institution ?: c.institution ?: "-")
            detail?.let {
                val dur = listOfNotNull(it.days?.let { n -> "${n}일" }, it.hours?.let { n -> "${n}시간" }).joinToString(", ")
                if (dur.isNotBlank()) CareerInfoLine("기간", dur)
                it.target?.let { t -> CareerInfoLine("대상", t) }
                it.address?.let { a -> CareerInfoLine("주소", a) }
            }
            (detail?.cost ?: c.cost)?.let { CareerInfoLine("훈련비", careerWon(it)) }
            detail?.myCost?.let { CareerInfoLine("내는 돈", careerWon(it)) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val phone = detail?.phone ?: c.phone
                if (phone != null) {
                    OnRoadButton(
                        "전화 문의",
                        { runCatching { uri.openUri("tel:${phone.filter { ch -> ch.isDigit() }}") } },
                        Modifier.weight(1f),
                        variant = ButtonVariant.Outline,
                        borderColor = OnRoadColors.BorderChip,
                        height = 40.dp,
                        textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    )
                }
                (detail?.url ?: c.url)?.let { u ->
                    OnRoadButton(
                        "고용24에서 보기",
                        { runCatching { uri.openUri(u) } },
                        Modifier.weight(1f),
                        height = 40.dp,
                        textStyle = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}
