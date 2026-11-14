package com.project.on_road.ui.components

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.heading


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import com.project.on_road.ui.theme.OnRoadMotion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadDimens
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType

/* ================================================================== */
/*  온로드 디자인 시스템                                                  */
/*  새 화면은 이 파일의 컴포넌트와 theme/ 토큰만으로 구성한다.              */
/* ================================================================== */

/** 어느 화면에서든 홈으로 돌아가는 동작 (상단바 X 버튼). NavHost에서 제공한다. */
val LocalGoHome = compositionLocalOf<(() -> Unit)?> { null }

/** 하단 메뉴 탭의 첫 화면이면 true: 뒤로·홈 버튼 없이 제목만 둔다 (하단 메뉴로 오간다) */
val LocalTabRoot = compositionLocalOf { false }

/* ------------------------------------------------------------------ */
/*  로고                                                                */
/* ------------------------------------------------------------------ */

/** 로고 자리. 로고 업로드 후 Image(painterResource(R.drawable.logo), ...)로 교체 */
@Composable
fun LogoSlot(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    OnRoadLogo(modifier.padding(4.dp), size = size - 8.dp)
}
/* ------------------------------------------------------------------ */
/*  화면 뼈대                                                            */
/* ------------------------------------------------------------------ */

/** 상단바: ← 뒤로 / 가운데 제목 / 오른쪽 X(홈으로) 또는 trailing */
@Composable
fun OnRoadTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = LocalGoHome.current,
    trailing: (@Composable () -> Unit)? = null,
    /** 아래 구분선 (바로 밑에 다른 줄이 붙는 화면은 끈다) */
    divider: Boolean = true,
) {
    val tabRoot = LocalTabRoot.current
    val onBack = if (tabRoot) null else onBack
    val onClose = if (tabRoot) null else onClose
    Column(Modifier.fillMaxWidth().background(OnRoadColors.Surface)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(OnRoadDimens.TopBarHeight)
                .padding(horizontal = 12.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로",
                        tint = OnRoadColors.TextPrimary.copy(alpha = 0.85f),
                    )
                }
            }
            Text(
                text = title,
                style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold),
                color = OnRoadColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 오른쪽에 버튼이 두 개(끝내기 + 홈)면 제목이 겹치지 않게 더 비운다
                modifier = Modifier.align(Alignment.Center).padding(horizontal = if (trailing != null) 104.dp else 56.dp),
            )
            // 오른쪽: 화면 버튼(끝내기 등) + 어디서든 한 번에 홈으로
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                if (onClose != null) HomeButton(onClose)
            }
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
    }
}

/** 상단바 + 세로 스크롤 본문 (+ 하단 고정 버튼). 대부분의 하위 화면이 이 뼈대를 쓴다. */
/** 상단 바의 홈 버튼: 집 아이콘 + "홈" 글자 (X 아이콘은 닫기로 오해하기 쉬워 바꿈) */
@Composable
private fun HomeButton(onClick: () -> Unit) {
    Column(
        Modifier
            .clip(OnRoadShapes.Field)
            .tap(onClickLabel = "홈 화면으로 가기", onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Home, contentDescription = null, tint = OnRoadColors.TextSecondary, modifier = Modifier.size(20.dp))
        Text("홈", style = OnRoadType.Tiny, color = OnRoadColors.TextSecondary)
    }
}

@Composable
fun OnRoadScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(OnRoadColors.Background)) {
        // 위 바 아래 구분선 없이 (앱 전체 구분선 쓰지 않기)
        OnRoadTopBar(title = title, onBack = onBack, trailing = trailing, divider = false)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(OnRoadDimens.ScreenTop))
            // 내용이 늘거나 줄 때(불러온 뒤, 펼치기·접기) 높이가 툭 바뀌지 않고 부드럽게 따라간다
            Column(
                Modifier
                    .fillMaxWidth()
                    .reveal()
                    .animateContentSize(com.project.on_road.ui.theme.OnRoadMotion.move()),
                content = content,
            )
            Spacer(Modifier.height(32.dp))
        }
        if (bottomBar != null) {
            HorizontalDivider(thickness = 1.dp, color = OnRoadColors.Divider)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 12.dp),
                content = bottomBar,
            )
        }
    }
}

/*
 * 제목 위계 (앱 전체 통일)
 *  - ScreenHeadline : 화면 맨 위 큰 안내 문구 (22, 화면당 하나)
 *  - SectionHeader  : 화면 안 큰 묶음 제목 (20 Bold) · 오른쪽 '전체보기 >' 또는 작은 글씨
 *  - SubHeader      : 묶음 안 작은 제목 (15 Bold) · 오른쪽 작은 글씨
 * 그 밖의 크기로 제목을 따로 만들지 않는다.
 */
/** 화면 상단 큰 안내 문구 (예: "나에게 더 유리한 조건을 / 한눈에 비교해 보세요.") */
@Composable
fun ScreenHeadline(first: String, second: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(first, style = OnRoadType.Title1Regular, color = OnRoadColors.TextPrimary)
        if (second != null) Text(second, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
    }
}

/* ------------------------------------------------------------------ */
/*  카드                                                                */
/* ------------------------------------------------------------------ */

@Composable
fun OnRoadCard(
    modifier: Modifier = Modifier,
    background: Color = OnRoadColors.Surface,
    borderColor: Color = OnRoadColors.Border,
    borderWidth: Dp = 1.dp,
    shape: Shape = OnRoadShapes.Card,
    contentPadding: PaddingValues = PaddingValues(OnRoadDimens.CardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(press, enabled = onClick != null, pressed = 0.985f)
            .clip(shape)
            .background(background)
            .border(borderWidth, borderColor, shape)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = press, indication = LocalIndication.current, onClick = onClick)
                else Modifier,
            )
            .padding(contentPadding),
        content = content,
    )
}

/** 보조 카드 (연한 회색 바탕, 12dp 모서리) */
@Composable
fun SubtleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) = OnRoadCard(
    modifier = modifier,
    background = OnRoadColors.SurfaceSubtle,
    borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
    shape = OnRoadShapes.Inner,
    contentPadding = contentPadding,
    onClick = onClick,
    content = content,
)

/** 목록 줄 카드: 제목 + 설명 + ›  (정책 비교 '세부 조건' 스타일) */
@Composable
fun ListRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    OnRoadCard(
        modifier.fillMaxWidth(),
        borderColor = OnRoadColors.BorderSlate,
        shape = OnRoadShapes.Inner,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconTile(icon)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                // 카드 글씨는 제목 한 줄 + 설명 한 줄까지만 (넘치면 …)
                Text(
                    title,
                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                    color = OnRoadColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    // 설명 글씨: 12 이상 · 흰 바탕에서 대비 4.5:1 이상 (예전 11 · 50% 투명은 읽기 어려웠다)
                    Text(
                        description,
                        style = OnRoadType.Caption2,
                        color = OnRoadColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = OnRoadColors.TextPrimary.copy(alpha = 0.25f),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  태그                                                                */
/* ------------------------------------------------------------------ */

enum class TagStyle { Neutral, Danger, Warning, PrimaryOutline, PrimarySoft, PrimaryFilled, Slate, SlateFilled, SlatePrimary, Gray }

private data class TagColors(val bg: Color, val border: Color?, val fg: Color)

/**
 * 태그 (앱 전체 한 가지 모양: 모서리 Tag · 글자 12 · 안쪽 여백 8×4).
 * 색은 뜻이 있을 때만: 위험(Danger) · 주의(Warning) · 강조(Primary 계열). 분류·상태는 회색 하나(Neutral).
 * shape · contentPadding · textStyle 인자는 예전 코드 호환용으로 남겨 두었고, 글자 굵기만 따른다.
 */
@Composable
fun OnRoadTag(
    text: String,
    modifier: Modifier = Modifier,
    style: TagStyle = TagStyle.Neutral,
    textStyle: TextStyle = OnRoadType.Caption2,
    @Suppress("UNUSED_PARAMETER") shape: Shape = OnRoadShapes.Tag,
    @Suppress("UNUSED_PARAMETER") contentPadding: PaddingValues = TagPadding,
) {
    val neutral = TagColors(Color(0x4DF4F4F5), OnRoadColors.BorderChip, OnRoadColors.TextSecondary)
    val primary = TagColors(Color.White, OnRoadColors.Primary, OnRoadColors.Primary)
    val c = when (style) {
        TagStyle.Neutral, TagStyle.Slate, TagStyle.SlateFilled, TagStyle.Gray -> neutral
        TagStyle.Danger -> TagColors(OnRoadColors.DangerSoft, OnRoadColors.Danger, OnRoadColors.Danger)
        TagStyle.Warning -> TagColors(OnRoadColors.WarningSoft, OnRoadColors.Warning, OnRoadColors.Warning)
        TagStyle.PrimaryOutline, TagStyle.PrimarySoft, TagStyle.SlatePrimary -> primary
        TagStyle.PrimaryFilled -> TagColors(OnRoadColors.Primary, null, Color.White)
    }
    val shape = OnRoadShapes.Tag
    Box(
        modifier
            .clip(shape)
            .background(c.bg)
            .then(if (c.border != null) Modifier.border(1.dp, c.border, shape) else Modifier)
            .padding(TagPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OnRoadType.Caption2.copy(fontWeight = textStyle.fontWeight ?: FontWeight.Medium), color = c.fg, maxLines = 1)
    }
}

private val TagPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

/* ------------------------------------------------------------------ */
/*  버튼                                                                */
/* ------------------------------------------------------------------ */

enum class ButtonVariant { Primary, Outline, Gray }

@Composable
fun OnRoadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    height: Dp = OnRoadDimens.ButtonHeight,
    textStyle: TextStyle = OnRoadType.Body1,
    trailingIcon: ImageVector? = null,
    leadingIcon: ImageVector? = null,
    shape: Shape = OnRoadShapes.Field,
    enabled: Boolean = true,
    loading: Boolean = false,
    borderColor: Color? = null,
) {
    val (bg, variantBorder, fg) = when (variant) {
        ButtonVariant.Primary -> Triple(OnRoadColors.Primary, null, Color.White)
        ButtonVariant.Outline -> Triple(Color.White, OnRoadColors.BorderSlate, OnRoadColors.TextSlateDark)
        ButtonVariant.Gray -> Triple(OnRoadColors.SurfaceMuted, OnRoadColors.BorderSage.copy(alpha = 0.6f), OnRoadColors.TextSlate)
    }
    val active = enabled && !loading
    val press = remember { MutableInteractionSource() }
    // 켜짐 ↔ 꺼짐이 툭 바뀌지 않게 바탕을 서서히 바꾼다
    val bgColor by animateColorAsState(if (enabled) bg else bg.copy(alpha = 0.35f), OnRoadMotion.move(), label = "button-bg")
    Box(
        modifier
            .height(height)
            .pressScale(press, enabled = active)
            .clip(shape)
            .background(bgColor)
            .then((borderColor ?: variantBorder)?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
            .clickable(interactionSource = press, indication = LocalIndication.current, enabled = active, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 글자 ↔ 도는 표시는 겹쳐서 바꾼다
        Crossfade(loading, animationSpec = OnRoadMotion.move(OnRoadMotion.FAST), label = "button-loading") { busy ->
            if (busy) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(text, style = textStyle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (trailingIcon != null) {
                        Spacer(Modifier.width(10.dp))
                        Icon(trailingIcon, null, tint = fg, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** 작은 테두리 버튼 (체크리스트 '정부24 바로가기' 스타일) */
@Composable
fun SmallBoxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    borderColor: Color = OnRoadColors.BorderDark,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(28.dp)
            .pressScale(press, pressed = 0.95f)
            .clip(OnRoadShapes.Badge)
            .background(background)
            .border(1.dp, borderColor, OnRoadShapes.Badge)
            .clickable(interactionSource = press, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, tint = OnRoadColors.TextPrimary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = OnRoadType.Micro, color = OnRoadColors.TextPrimary, maxLines = 1)
        if (trailingIcon != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailingIcon, null, tint = OnRoadColors.TextPrimary, modifier = Modifier.size(13.dp))
        }
    }
}

/** 선택 칩 (기간, 금액 프리셋 등) */
@Composable
fun ChoiceChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = OnRoadShapes.Field
    val press = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) OnRoadColors.Primary else Color.White, OnRoadMotion.move(), label = "chip-bg")
    val line by animateColorAsState(if (selected) OnRoadColors.Primary else OnRoadColors.BorderInput, OnRoadMotion.move(), label = "chip-border")
    val fg by animateColorAsState(if (selected) Color.White else OnRoadColors.TextSecondary, OnRoadMotion.move(), label = "chip-fg")
    val tick = rememberTick()
    Box(
        modifier
            .height(34.dp)
            .pressScale(press, pressed = 0.95f)
            .clip(shape)
            .background(bg)
            .border(1.dp, line, shape)
            .clickable(interactionSource = press, indication = LocalIndication.current) { if (!selected) tick(); onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
    }
}

private val SegmentShape = RoundedCornerShape(6.dp)

/** 탭 전환 (회색 바탕 + 흰 선택) */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(OnRoadShapes.Field)
            .background(OnRoadColors.SurfaceMuted)
            .padding(4.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val tabWidth = maxWidth / count
        // 흰 선택 칸이 눌린 탭으로 미끄러져 간다
        val x by animateDpAsState(tabWidth * selected.coerceIn(0, count - 1), OnRoadMotion.move(), label = "segment-x")
        Box(
            Modifier
                .offset(x = x)
                .width(tabWidth)
                .height(34.dp)
                .clip(SegmentShape)
                .background(Color.White)
                .border(1.dp, OnRoadColors.Border, SegmentShape),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val fg by animateColorAsState(
                    if (on) OnRoadColors.TextPrimary else OnRoadColors.TextTertiary,
                    OnRoadMotion.move(),
                    label = "segment-fg",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(SegmentShape)
                        .tap { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = OnRoadType.Caption.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        color = fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  섹션 헤더                                                            */
/* ------------------------------------------------------------------ */

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = OnRoadType.Title2,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    trailingText: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 화면 읽기 프로그램이 제목으로 읽고, 제목 사이를 건너뛸 수 있게
        Text(title, style = titleStyle, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f).semantics { heading() })
        if (actionText != null) {
            Row(
                Modifier.tap(enabled = onAction != null) { onAction?.invoke() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionText, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = OnRoadColors.TextTertiary, modifier = Modifier.size(16.dp),
                )
            }
        }
        if (trailingText != null) {
            // 오른쪽 작은 글씨(개수·단위·보조 설명)는 앱 전체에서 같은 크기·색
            Text(trailingText, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
    }
}

/** 작은 섹션 제목 (15sp bold) — 화면 안 소단락 */
@Composable
fun SubHeader(title: String, modifier: Modifier = Modifier, trailingText: String? = null) =
    SectionHeader(
        title,
        modifier,
        titleStyle = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
        trailingText = trailingText,
    )

/* ------------------------------------------------------------------ */
/*  입력                                                                */
/* ------------------------------------------------------------------ */

@Composable
fun OnRoadSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    onSearch: (String) -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = OnRoadShapes.Inner
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(Color.White)
            .border(1.2.dp, OnRoadColors.BorderSearch, shape)
            .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = OnRoadType.Caption.copy(color = OnRoadColors.TextPrimary),
            cursorBrush = SolidColor(OnRoadColors.Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = OnRoadType.Caption, color = OnRoadColors.TextQuaternary, maxLines = 1)
                inner()
            },
        )
        trailing?.invoke()
    }
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, required: Boolean = false, hint: String? = null) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
            if (hint != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Normal, fontSize = OnRoadType.Tiny.fontSize, color = OnRoadColors.TextTertiary)) {
                    append(" $hint")
                }
            }
            if (required) withStyle(SpanStyle(color = OnRoadColors.Required)) { append("*") }
        },
        style = OnRoadType.Caption,
        color = OnRoadColors.TextPrimary,
        modifier = modifier,
    )
}

@Composable
fun OnRoadTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    showClear: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    singleLine: Boolean = true,
    minLines: Int = 1,
    suffix: String? = null,
    enabled: Boolean = true,
    background: Color = Color.White,
    borderColor: Color = OnRoadColors.BorderInput,
    /** 입력칸 안 글씨 시작 여백 */
    contentStart: androidx.compose.ui.unit.Dp = 15.dp,
) {
    val shape = OnRoadShapes.Field
    Row(
        modifier
            .heightIn(min = OnRoadDimens.FieldHeight)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .padding(
                start = contentStart,
                end = 12.dp,
                top = if (singleLine) 0.dp else 12.dp,
                bottom = if (singleLine) 0.dp else 12.dp,
            ),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            enabled = enabled,
            textStyle = OnRoadType.Body2.copy(color = OnRoadColors.TextPrimary, textAlign = textAlign),
            cursorBrush = SolidColor(OnRoadColors.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = OnRoadType.Body2.copy(textAlign = textAlign),
                            color = OnRoadColors.TextQuaternary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
            },
        )
        if (suffix != null) {
            Spacer(Modifier.width(6.dp))
            Text(suffix, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
        if (showClear && value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, OnRoadColors.TextSecondary, CircleShape)
                    .tap { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, "지우기", tint = OnRoadColors.TextSecondary, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/** 드롭다운 선택 박스 (지역, 연령대 등) */
/** 드롭다운 선택 박스 (지역, 생년월일 등). 목록은 박스 바로 아래, 박스 폭에 맞춰 열리고 길면 스크롤 */
/** 드롭다운 선택 박스 (지역, 생년월일 등). 목록은 박스 바로 아래, 박스 폭에 맞춰 열리고 길면 스크롤 */
@Composable
fun OnRoadSelectBox(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "선택",
    highlighted: Boolean = false,
    showArrow: Boolean = true,
    centered: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var boxWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val shape = OnRoadShapes.Field
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(OnRoadDimens.FieldHeight)
                .onSizeChanged { boxWidth = it.width }
                .clip(shape)
                .background(Color.White)
                .border(1.dp, if (highlighted || expanded) OnRoadColors.Primary else OnRoadColors.BorderInput, shape)
                .tap(enabled = options.isNotEmpty()) { expanded = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                style = OnRoadType.Body2,
                color = if (value.isEmpty()) OnRoadColors.TextQuaternary else OnRoadColors.TextPrimary,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (showArrow) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = OnRoadColors.TextSlate, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 4.dp),
            modifier = Modifier
                .width(with(density) { boxWidth.toDp() })
                .heightIn(max = 260.dp),
            shape = shape,
            containerColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, OnRoadColors.BorderInput),
        ) {
            options.forEach { option ->
                val selected = option == value
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            style = OnRoadType.Body2.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                            color = if (selected) OnRoadColors.Primary else OnRoadColors.TextPrimary,
                        )
                    },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

/** 큰 선택 카드 (온보딩 '지금 어떤 단계에 계신가요?' 스타일) */
@Composable
fun SelectCard(
    title: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val shape = OnRoadShapes.Field
    val press = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) OnRoadColors.SurfaceSubtle else Color.White, OnRoadMotion.move(), label = "select-bg")
    val line by animateColorAsState(if (selected) OnRoadColors.Primary else OnRoadColors.BorderInput, OnRoadMotion.move(), label = "select-border")
    val tick = rememberTick()
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(press, pressed = 0.985f)
            .clip(shape)
            .background(bg)
            .border(1.dp, line, shape)
            .clickable(interactionSource = press, indication = LocalIndication.current) { if (!selected) tick(); onClick() }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        RadioMark(selected, Modifier.padding(top = 4.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextPrimary)
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = OnRoadType.Micro, color = OnRoadColors.TextPrimary.copy(alpha = 0.6f))
            }
        }
        if (tag != null) {
            Spacer(Modifier.width(12.dp))
            OnRoadTag(tag, style = TagStyle.Gray, textStyle = OnRoadType.Tiny, shape = OnRoadShapes.Badge)
        }
    }
}

@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) OnRoadColors.Primary else Color.White, OnRoadMotion.move(OnRoadMotion.FAST), label = "radio-bg")
    val line by animateColorAsState(if (selected) OnRoadColors.Primary else OnRoadColors.BorderInput, OnRoadMotion.move(OnRoadMotion.FAST), label = "radio-border")
    Box(
        modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CheckPop(selected, 12.dp)
    }
}

@Composable
fun CheckBoxMark(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val shape = OnRoadShapes.Chip
    val bg by animateColorAsState(if (checked) OnRoadColors.Primary else Color.White, OnRoadMotion.move(OnRoadMotion.FAST), label = "check-bg")
    val line by animateColorAsState(if (checked) OnRoadColors.Primary else OnRoadColors.BorderDark, OnRoadMotion.move(OnRoadMotion.FAST), label = "check-border")
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(bg)
            .border(1.dp, line, shape),
        contentAlignment = Alignment.Center,
    ) {
        CheckPop(checked, size * 0.7f)
    }
}

/** 체크 표시가 톡 튀어나오고, 풀면 오므라들며 사라진다 */
@Composable
private fun CheckPop(visible: Boolean, size: Dp) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(OnRoadMotion.enter(OnRoadMotion.FAST), initialScale = 0.4f) + fadeIn(OnRoadMotion.enter(OnRoadMotion.FAST)),
        exit = scaleOut(OnRoadMotion.exit(), targetScale = 0.4f) + fadeOut(OnRoadMotion.exit()),
        label = "check-pop",
    ) {
        Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
fun OnRoadSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = OnRoadColors.Primary,
            checkedThumbColor = Color.White,
            checkedBorderColor = OnRoadColors.Primary,
            uncheckedTrackColor = OnRoadColors.BorderSlate,
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = OnRoadColors.BorderSlate,
        ),
        modifier = Modifier.scale(0.8f),
    )
}

@Composable
fun OnRoadSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    color: Color = OnRoadColors.Primary,
    enabled: Boolean = true,
) {
    // 값이 일정 칸을 넘을 때마다 아주 짧은 진동 (눈금을 손으로 느끼게)
    val tick = rememberSliderTick(valueRange)
    Slider(
        value = value,
        onValueChange = { tick(it); onValueChange(it) },
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = color,
            activeTrackColor = color,
            inactiveTrackColor = OnRoadColors.SurfaceSlate,
            disabledThumbColor = OnRoadColors.BorderInput,
            disabledActiveTrackColor = OnRoadColors.BorderInput,
            disabledInactiveTrackColor = OnRoadColors.SurfaceSlate,
        ),
    )
}

/* ------------------------------------------------------------------ */
/*  정보 표시                                                            */
/* ------------------------------------------------------------------ */

/** 흰 바탕 둥근 아이콘 타일 (바로가기, 일정 아이콘) */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 18.dp,
    tint: Color = OnRoadColors.TextSecondary,
    background: Color = Color.White,
) {
    val shape = OnRoadShapes.Field
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(background)
            .border(1.dp, OnRoadColors.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 라벨 ↔ 값 한 줄 (정책 카드 '지원 대상 & 자격요건' 스타일) */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = OnRoadColors.TextSlateDark) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextMuted)
        Spacer(Modifier.width(12.dp))
        Text(value, style = OnRoadType.Micro, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** 금액 줄 (라벨 왼쪽, 값 오른쪽) */
@Composable
fun ValueLine(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (emphasize) OnRoadType.Body2.copy(fontWeight = FontWeight.Bold) else OnRoadType.Caption,
            color = if (emphasize) OnRoadColors.Primary else OnRoadColors.TextPrimary,
        )
    }
}

@Composable
fun BulletText(text: String, color: Color, modifier: Modifier = Modifier, style: TextStyle = OnRoadType.Caption) {
    Row(modifier) {
        Text("·", style = style, color = color.copy(alpha = color.alpha * 0.7f))
        Spacer(Modifier.width(8.dp))
        Text(text, style = style, color = color)
    }
}

/** 번호 배지 + 문장 (AI 답변 '핵심 포인트' 스타일) */
@Composable
fun NumberedPoint(index: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier) {
        Box(
            Modifier
                .clip(OnRoadShapes.Badge)
                .background(OnRoadColors.SurfaceBadge)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text("%02d".format(index), style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextPrimary)
    }
}

@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = OnRoadColors.Primary,
    track: Color = OnRoadColors.SurfaceSlate,
    height: Dp = 4.dp,
) {
    // 처음 보일 때 0 에서 차오르고, 값이 바뀌면 그만큼만 움직인다
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val p by animateFloatAsState(
        if (started) progress.coerceIn(0f, 1f) else 0f,
        OnRoadMotion.move(600),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(OnRoadShapes.Badge)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(p)
                .height(height)
                .clip(OnRoadShapes.Badge)
                .background(color),
        )
    }
}

/** 여러 값을 비율대로 이어 붙인 막대 (생활비 배분) */
@Composable
fun StackedBar(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    track: Color = OnRoadColors.SurfaceSlate,
) {
    val total = segments.sumOf { it.first.toDouble() }.toFloat()
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(OnRoadShapes.Badge)
            .background(track),
    ) {
        if (total > 0f) {
            segments.filter { it.first > 0f }.forEach { (v, c) ->
                Box(Modifier.weight(v / total).height(height).background(c))
            }
        }
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/* ------------------------------------------------------------------ */
/*  안내 · 상태                                                          */
/* ------------------------------------------------------------------ */

enum class NoticeTone { Info, Warning }

/** 안내 박스 (정책 카드 요약 박스 스타일). Warning은 붉은 톤 */
@Composable
fun NoticeBox(text: String, modifier: Modifier = Modifier, title: String? = null, tone: NoticeTone = NoticeTone.Info) {
    val (bg, border, titleColor) = when (tone) {
        NoticeTone.Info -> Triple(OnRoadColors.SurfaceCool, OnRoadColors.BorderSlate, OnRoadColors.Primary)
        NoticeTone.Warning -> Triple(OnRoadColors.DangerTint, OnRoadColors.Danger.copy(alpha = 0.35f), OnRoadColors.Danger)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Field)
            .background(bg)
            .border(1.dp, border, OnRoadShapes.Field)
            .padding(16.dp),
    ) {
        if (title != null) {
            Text(title, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = titleColor)
            Spacer(Modifier.height(4.dp))
        }
        Text(text, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextSlateDark)
    }
}

/** 화면 폭 전체 안내 영역 (체크리스트 '[AI 가이드]' 스타일). 본문 패딩 밖에서 사용 */
@Composable
fun GuideBlock(title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(OnRoadColors.SurfaceGuide)
            .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 24.dp),
    ) {
        Text("[$title]", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.Primary)
        Spacer(Modifier.height(10.dp))
        Text(text, style = OnRoadType.Caption2.copy(lineHeight = OnRoadType.Caption.lineHeight * 1.15f), color = OnRoadColors.TextPrimary)
    }
}

/** 화면 하단 가운데 정렬 각주 */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OnRoadType.Caption2.copy(lineHeight = OnRoadType.Caption.lineHeight * 1.1f),
        color = OnRoadColors.TextPrimary.copy(alpha = 0.5f),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 불러오는 중. 금방 끝나는 요청에 동그라미가 깜빡였다 사라지지 않도록 잠깐 기다렸다가 서서히 나타난다.
 * 자리는 처음부터 잡아 두어 내용이 들어올 때 화면이 덜컹거리지 않게 한다.
 * AI 처럼 오래 걸리는 일은 [steps] 를 주면 지금 무엇을 하는지 문구가 차례로 바뀐다 (마지막 문구에서 멈춤).
 * 빈 상태는 States.kt 의 EmptyState.
 */
@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier, steps: List<String> = emptyList()) {
    val fade = rememberDelayedAlpha(LOADING_DELAY_MS)
    val lines = remember(message, steps) { listOf(message) + steps }
    var index by remember(lines) { mutableStateOf(0) }
    LaunchedEffect(lines) {
        while (index < lines.lastIndex) {
            kotlinx.coroutines.delay(LOADING_STEP_MS)
            index++
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
            .graphicsLayer { alpha = fade },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = OnRoadColors.Primary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = lines[index],
            transitionSpec = { fadeIn(OnRoadMotion.enter()) togetherWith fadeOut(OnRoadMotion.exit()) },
            label = "loading-step",
        ) { line ->
            Text(line, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, textAlign = TextAlign.Center)
        }
    }
}

/** 불러오는 중 표시를 띄우기 전에 기다리는 시간. 이보다 빨리 끝나면 동그라미 없이 바로 내용이 나온다 */
private const val LOADING_DELAY_MS = 280L

/** 단계 문구를 바꾸는 간격 */
private const val LOADING_STEP_MS = 2400L

/** "**굵게**" 마크다운 표기를 AnnotatedString으로 변환 */
fun richText(raw: String): AnnotatedString = buildAnnotatedString {
    raw.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}
