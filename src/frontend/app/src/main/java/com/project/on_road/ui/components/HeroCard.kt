package com.project.on_road.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.on_road.ui.theme.*

/** 큰 카드 공통 값 (숫자만 바꿔서 조절) */
object HeroSpace {
    /** 큰 카드 기본 높이 */
    val Height = 128.dp
    /** 카드 안 여백 */
    val Pad = 18.dp
    /** 오른쪽 아래 아이콘 크기 */
    val IconSize = 112.dp
    /** 제목 → 설명 사이 */
    val SubTop = 8.dp
    /** 설명 행간 (12sp 글씨 기준) */
    val SubLineHeight = 16.sp
    /** 오른쪽 › 자리 (글이 겹치지 않게 비워 두는 너비) */
    val ActionSpace = 44.dp
    /** 아이콘 투명도 (0.07~0.10) */
    const val IconAlpha = 0.08f
}

/**
 * 화면의 대표 기능 하나만 쓰는 메인 색 큰 카드 (생활 가이드 '한 달 살아보기', 실전 연습 '이번에 해 볼 연습').
 * 위계: [label](작은 옅은 흰 글씨, 없어도 됨) → [title](Title3 흰 글씨) → [sub](옅은 흰 글씨), 오른쪽 세로 가운데 › 하나.
 * 오른쪽 아래에 옅은 아이콘이 카드 밖으로 살짝 잘린다
 */
@Composable
fun PrimaryHeroCard(
    title: String,
    sub: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    height: Dp = HeroSpace.Height,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.Primary)
            .tap(onClickLabel = title, onClick = onClick),
    ) {
        Icon(
            icon,
            null,
            tint = Color.White.copy(alpha = HeroSpace.IconAlpha),
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 20.dp).size(HeroSpace.IconSize),
        )
        // 글은 세로 가운데, 오른쪽 '시작하기' 자리만큼 비워 둔다
        Column(Modifier.align(Alignment.CenterStart).padding(start = HeroSpace.Pad, end = HeroSpace.ActionSpace)) {
            label?.let {
                Text(it, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                Spacer(Modifier.height(4.dp))
            }
            Text(title, style = OnRoadType.Title3, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(HeroSpace.SubTop))
            Text(
                sub,
                style = OnRoadType.Caption2.copy(lineHeight = HeroSpace.SubLineHeight),
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 오른쪽 세로 가운데에 › 하나만
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = Color.White,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = HeroSpace.Pad - 6.dp).size(22.dp),
        )
    }
}

/** 묶음 제목 (생활 가이드 · 실전 연습 · 상황 대처 연습 공통): Headline 17 굵게 */
@Composable
fun GroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold),
        color = OnRoadColors.TextPrimary,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * 목록 카드 공통 모양: 연회색 바탕 · 회색 테두리.
 * 첫 줄 이름(굵게) + 오른쪽 [trailing], 그 아래 설명 줄들
 */
@Composable
fun DataListCard(
    title: String,
    /** null 이면 누를 수 없는 카드 (보여 주기만) */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    /** 이름 글씨: 기본은 굵게. 보여 주기만 하는 항목은 보통 굵기로 낮춘다 */
    titleBold: Boolean = true,
    /** 이름이 길 수 있는 항목(서류 이름 등)은 2 */
    titleMaxLines: Int = 1,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    OnRoadCard(
        modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = if (titleBold) OnRoadType.Body2.copy(fontWeight = FontWeight.Bold) else OnRoadType.Body2,
                color = if (titleBold) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
        content?.let {
            Spacer(Modifier.height(4.dp))
            it()
        }
    }
}
