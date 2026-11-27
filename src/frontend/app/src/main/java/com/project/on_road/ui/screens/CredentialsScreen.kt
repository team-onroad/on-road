package com.project.on_road.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Credential
import com.project.on_road.data.CredentialStore
import com.project.on_road.data.DEFAULT_EDUCATION_OPTIONS
import com.project.on_road.data.EducationOption
import com.project.on_road.ui.components.ButtonVariant
import com.project.on_road.ui.components.Collapsing
import com.project.on_road.ui.components.SwipeSpace
import com.project.on_road.ui.components.SwipeToDelete
import com.project.on_road.ui.components.ConfirmDeleteDialog
import com.project.on_road.ui.components.EmptyState
import com.project.on_road.ui.components.FieldLabel
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadScreen
import com.project.on_road.ui.components.OnRoadTag
import com.project.on_road.ui.components.OnRoadToast
import com.project.on_road.ui.components.SkeletonList
import com.project.on_road.ui.components.TagStyle
import com.project.on_road.ui.components.tap
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadMotion
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DOT = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/**
 * 교육 · 자격증 (3.37). 설정 > 나의 자립 조건에서 연다.
 * - 이수한 교육: 자립정착금 의무교육처럼 정책 조건과 이어지는 교육은 로드맵 · 정책 상세에 '이수함'으로 보인다
 * - 취득한 자격증: 이력서 초안의 '자격증' 칸에 들어간다
 */
@Composable
fun CredentialsScreen(onBack: () -> Unit, startAdding: String? = null) {
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(DEFAULT_EDUCATION_OPTIONS) }
    var adding by remember { mutableStateOf(startAdding) } // education · certificate · null
    var deleting by remember { mutableStateOf<Credential?>(null) }
    // 지우는 중인 카드: 접히며 사라진 뒤 서버에서 지운다
    var removing by remember { mutableStateOf(emptySet<Long>()) }
    var failed by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 목록과 교육 선택지를 동시에 받는다 (하나씩 기다리지 않게)
        launch { runCatching { AppContainer.api.getEducationOptions() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { options = it } }
        if (CredentialStore.refresh(force = true) == null) failed = true
    }

    OnRoadScreen(
        "교육과 자격증",
        onBack,
        trailing = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).tap(onClickLabel = "도움말") { showHelp = !showHelp },
                contentAlignment = Alignment.Center,
            ) {
                val tint by animateColorAsState(
                    if (showHelp) OnRoadColors.TextPrimary else OnRoadColors.TextSecondary, OnRoadMotion.move(), label = "help-tint",
                )
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline, "도움말",
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) {
        // 도움말: 접혀 있다가 부드럽게 펼쳐진다
        AnimatedVisibility(
            showHelp,
            enter = expandVertically(OnRoadMotion.spatialSize, expandFrom = Alignment.Top) + fadeIn(OnRoadMotion.enter()),
            exit = shrinkVertically(OnRoadMotion.collapse(), shrinkTowards = Alignment.Top) + fadeOut(OnRoadMotion.exit()),
        ) {
          Column {
            Text(
                "자립정착금처럼 교육을 들어야 받는 지원이 있어요. 들은 교육을 넣어 두면 신청 일정에 '이수함'으로 보이고, 자격증은 이력서 초안에 들어가요.",
                style = OnRoadType.Body3,
                color = OnRoadColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Inner)
                    .background(OnRoadColors.SurfaceSubtle)
                    .border(1.dp, OnRoadColors.Border, OnRoadShapes.Inner)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(24.dp))
          }
        }
        val items = CredentialStore.items
        if (items == null) {
            Spacer(Modifier.height(20.dp))
            if (failed) EmptyState("불러오지 못했어요. 인터넷 연결을 확인해 주세요.")
            else SkeletonList(rows = 3, header = false)
            return@OnRoadScreen
        }

        CredentialSection(
            title = "이수한 교육",
            empty = "아직 넣은 교육이 없어요",
            items = CredentialStore.educations,
            addLabel = "교육 추가",
            first = true,
            removing = removing,
            onAdd = { adding = Credential.EDUCATION },
            onDelete = { deleting = it },
        )
        CredentialSection(
            title = "취득한 자격증",
            empty = "아직 넣은 자격증이 없어요",
            items = CredentialStore.certificates,
            addLabel = "자격증 추가",
            removing = removing,
            onAdd = { adding = Credential.CERTIFICATE },
            onDelete = { deleting = it },
        )
        Spacer(Modifier.height(24.dp))
    }

    adding?.let { kind ->
        AddCredentialSheet(
            kind = kind,
            options = options,
            taken = CredentialStore.educations.mapNotNull { it.code }.toSet(),
            onDismiss = { adding = null },
        ) { name, code, doneOn ->
            adding = null
            scope.launch {
                CredentialStore.add(kind, name, code, doneOn)
                    .onSuccess { OnRoadToast.show(if (kind == Credential.EDUCATION) "교육을 넣었어요" else "자격증을 넣었어요") }
                    .onFailure { OnRoadToast.show("넣지 못했어요. 인터넷 연결을 확인해 주세요.") }
            }
        }
    }
    deleting?.let { item ->
        ConfirmDeleteDialog(
            title = "${item.name} 지우기",
            text = if (item.isEducation && item.code != null) "지우면 신청 일정에서 이 교육이 '들어야 해요'로 바뀌어요." else "목록에서 지워요.",
            onCancel = { deleting = null },
            onConfirm = {
                deleting = null
                removing = removing + item.id
                scope.launch {
                    delay(SwipeSpace.COLLAPSE.toLong())
                    CredentialStore.delete(item)
                        .onSuccess { OnRoadToast.show(if (item.isEducation) "교육을 지웠어요" else "자격증을 지웠어요") }
                        .onFailure {
                            removing = removing - item.id
                            OnRoadToast.show("지우지 못했어요. 인터넷 연결을 확인해 주세요.")
                        }
                }
            },
        )
    }
}

@Composable
private fun CredentialSection(
    title: String,
    empty: String,
    items: List<Credential>,
    addLabel: String,
    first: Boolean = false,
    removing: Set<Long> = emptySet(),
    onAdd: () -> Unit,
    onDelete: (Credential) -> Unit,
) {
    Spacer(Modifier.height(if (first) 8.dp else 32.dp))
    Text(title, style = OnRoadType.Headline, color = OnRoadColors.TextPrimary)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) EmptyState(empty)
        // 왼쪽으로 밀면 '지우기' (신청 준비 · 서류 목록과 같은 동작)
        items.forEach { c ->
            key(c.id) {
                Collapsing(c.id in removing) {
                    SwipeToDelete(key = "cred-${c.id}", onDelete = { onDelete(c) }) { CredentialRow(c) }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    OnRoadButton(addLabel, onClick = onAdd, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, leadingIcon = Icons.Filled.Add)
}

@Composable
private fun CredentialRow(item: Credential) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OnRoadShapes.Item)
            .background(OnRoadColors.SurfaceSubtle)
            .border(1.dp, OnRoadColors.Border, OnRoadShapes.Item)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
                if (item.code != null) OnRoadTag("지원 조건", style = TagStyle.White)
            }
            val verb = if (item.isEducation) "이수" else "취득"
            Text(
                item.doneOn?.let { "${it.format(DOT)} $verb" } ?: "$verb 날짜를 넣지 않았어요",
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  추가 창                                                             */
/* ------------------------------------------------------------------ */

/** 추가 창의 한 줄. code: 지원 조건 교육 코드 (검색 · 직접 입력은 null) */
private data class Pick(val key: String, val name: String, val sub: String?, val tag: String?, val code: String?)

/** 지원 조건 교육 카드 아래 줄 (서버 설명은 길어서 기관만) */
private val OPTION_SUB = mapOf(
    "settlement" to "자립지원전담기관 금융, 주거 교육",
    "finance" to "서민금융진흥원, 아동권리보장원 온라인 교육",
)

/**
 * 교육 추가 · 자격증 추가 (하단 창).
 * 교육: 처음엔 지원 조건 교육, 글자를 넣으면 고용24 훈련과정 검색. 자격증: 큐넷 국가자격 종목 검색.
 * 맨 아래 '그대로 넣기' 카드로 직접 입력. 날짜는 고르지 않아도 된다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCredentialSheet(
    kind: String,
    options: List<EducationOption>,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (name: String?, code: String?, doneOn: LocalDate?) -> Unit,
) {
    val education = kind == Credential.EDUCATION
    val fixed = remember(options, taken) {
        if (!education) emptyList()
        else options.filter { it.code !in taken }.map { Pick("opt:${it.code}", it.name, OPTION_SUB[it.code], null, it.code) }
    }
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Pick>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Pick?>(fixed.firstOrNull()) }
    var doneOn by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf(false) }
    var searchNow by remember { mutableStateOf(0) }

    val q = query.trim().take(60)
    // 글자를 멈추고 0.4초 뒤 찾는다 (찾기 버튼은 바로)
    LaunchedEffect(q, searchNow) {
        if (q.length < 2) { found = emptyList(); searched = ""; loading = false; return@LaunchedEffect }
        if (searched == q) return@LaunchedEffect
        delay(400)
        loading = true
        found = if (education) {
            AppContainer.careerInfo.trainings(q, null).distinctBy { it.title }.take(4)
                .map { Pick("tr:${it.id}:${it.round}", it.title, it.institution, null, null) }
        } else {
            AppContainer.careerInfo.certificates(q).take(4).map { Pick("qn:${it.code}", it.name, null, it.kind, null) }
        }
        searched = q
        loading = false
    }

    val shown: List<Pick> = if (q.isEmpty()) fixed else buildList {
        addAll(fixed.filter { it.name.contains(q) })
        addAll(found)
    }
    val raw = q.takeIf { it.isNotEmpty() }?.let { Pick("raw", it, null, null, null) }
    // 목록에서 사라진 줄을 골라 둔 채로 두지 않는다
    val current = picked?.takeIf { p -> p.key == "raw" && raw != null || shown.any { it.key == p.key } }
        ?.let { if (it.key == "raw") raw else it }

    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(after: () -> Unit = {}) {
        scope.launch { state.hide() }.invokeOnCompletion { after(); onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            Text(if (education) "교육 추가" else "자격증 추가", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(20.dp))
            SheetSearchField(
                value = query,
                onValueChange = { query = it.take(60) },
                onSearch = { searchNow++ },
                onClear = { query = "" },
            )
            // 결과: 목록 높이는 스프링으로 늘고 줄며, 새 카드는 살짝 내려앉듯 나타난다
            AnimatedVisibility(
                shown.isNotEmpty() || raw != null || loading,
                enter = expandVertically(OnRoadMotion.spatialSize, expandFrom = Alignment.Top) + fadeIn(OnRoadMotion.enter()),
                exit = shrinkVertically(OnRoadMotion.collapse(), shrinkTowards = Alignment.Top) + fadeOut(OnRoadMotion.exit()),
            ) {
              Column(Modifier.animateContentSize(OnRoadMotion.spatialSize)) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { p -> key(p.key) { Appear { PickCard(p, q, selected = current?.key == p.key) { picked = p } } } }
                    AnimatedVisibility(loading && found.isEmpty(), enter = fadeIn(OnRoadMotion.enter()), exit = fadeOut(OnRoadMotion.exit())) {
                        Text("찾는 중이에요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp))
                    }
                    raw?.let { r -> key("raw") { Appear { PickCard(r, q, selected = current?.key == "raw", raw = true) { picked = r } } } }
                }
                AnimatedVisibility(found.isNotEmpty(), enter = fadeIn(OnRoadMotion.enter()), exit = fadeOut(OnRoadMotion.exit())) {
                    Text(
                        if (education) "고용24 국민내일배움카드 훈련과정" else "큐넷 국가자격 종목",
                        style = OnRoadType.Caption2,
                        color = OnRoadColors.TextQuaternary,
                        modifier = Modifier.padding(start = 2.dp, top = 8.dp),
                    )
                }
              }
            }
            Spacer(Modifier.height(24.dp))
            FieldLabel(if (education) "이수한 날" else "취득한 날")
            Spacer(Modifier.height(8.dp))
            DateField(doneOn) { picking = true }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OnRoadButton("취소", onClick = { close() }, modifier = Modifier.weight(1f), variant = ButtonVariant.Outline)
                OnRoadButton(
                    "추가",
                    onClick = {
                        val p = current ?: return@OnRoadButton
                        close { onAdd(if (p.code == null) p.name else null, p.code, doneOn) }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = current != null,
                )
            }
        }
    }
    if (picking) {
        ProfileDateDialog(doneOn, past = true, onDismiss = { picking = false }) { d -> picking = false; doneOn = d }
    }
}

/** 처음 그려질 때 한 번: 옅게 시작해 살짝 내려앉으며 나타난다 */
@Composable
private fun Appear(content: @Composable () -> Unit) {
    val seen = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        seen,
        enter = fadeIn(OnRoadMotion.enter()) + expandVertically(OnRoadMotion.spatialSize, expandFrom = Alignment.Top),
    ) { content() }
}

/** 정책 검색창과 같은 모양: 흰 바탕 · 카드 테두리, 누르면 테두리가 메인 색, 글자가 있을 때만 찾기 버튼이 메인 색 */
@Composable
private fun SheetSearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = OnRoadShapes.Inner
    val line by androidx.compose.animation.animateColorAsState(
        if (focused) OnRoadColors.Primary else OnRoadColors.BorderSlate,
        OnRoadMotion.move(),
        label = "sheet-search-line",
    )
    val active = value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(OnRoadColors.Surface)
            .border(1.dp, line, shape)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = OnRoadType.Body2.copy(color = OnRoadColors.TextPrimary),
            cursorBrush = SolidColor(OnRoadColors.Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (value.isEmpty()) Text("검색하거나 직접 입력", style = OnRoadType.Body2, color = OnRoadColors.TextQuaternary, maxLines = 1)
                inner()
            },
        )
        AnimatedVisibility(
            value.isNotEmpty(),
            enter = fadeIn(OnRoadMotion.enter()) + scaleIn(OnRoadMotion.bouncy(), initialScale = 0.6f),
            exit = fadeOut(OnRoadMotion.exit()) + scaleOut(OnRoadMotion.exit(), targetScale = 0.6f),
        ) {
            Row {
                Box(
                    Modifier.size(32.dp).clip(OnRoadShapes.Field).tap(onClickLabel = "검색어 지우기", onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, "지우기", tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(2.dp))
            }
        }
        val goBg by animateColorAsState(if (active) OnRoadColors.Primary else Color.Transparent, OnRoadMotion.move(), label = "go-bg")
        val goTint by animateColorAsState(if (active) Color.White else OnRoadColors.TextTertiary, OnRoadMotion.move(), label = "go-tint")
        Box(
            Modifier
                .size(36.dp)
                .clip(OnRoadShapes.Field)
                .background(goBg)
                .tap(enabled = active, onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Search, "찾기", tint = goTint, modifier = Modifier.size(20.dp))
        }
    }
}

/** 결과 카드. 고르면 흰 바탕 · 메인 색 테두리 · 체크. raw: '그대로 넣기' 카드 (흰 바탕 · 점선) */
@Composable
private fun PickCard(p: Pick, q: String, selected: Boolean, raw: Boolean = false, onClick: () -> Unit) {
    val shape = OnRoadShapes.Item
    val dashColor = OnRoadColors.BorderChip
    // 고를 때 바탕 · 테두리 색이 스르르 바뀐다
    val bg by animateColorAsState(if (selected || raw) Color.White else OnRoadColors.SurfaceSubtle, OnRoadMotion.move(), label = "pick-bg")
    val line by animateColorAsState(
        when { selected -> OnRoadColors.Primary; raw -> Color.Transparent; else -> OnRoadColors.Border },
        OnRoadMotion.move(), label = "pick-line",
    )
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(bg)
    val bordered = when {
        !raw || selected -> base.border(1.dp, line, shape)
        else -> base.drawBehind {
            val w = 1.dp.toPx()
            drawRoundRect(
                color = dashColor,
                topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
        }
    }
    Row(
        bordered
            .tap(onClickLabel = p.name, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    highlight(if (raw) "'${p.name}' 그대로 넣기" else p.name, q),
                    style = OnRoadType.Body2.copy(fontWeight = if (selected && !raw) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (raw) OnRoadColors.TextTertiary else OnRoadColors.TextSecondary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                p.tag?.let { OnRoadTag(it, style = TagStyle.White) }
            }
            p.sub?.let {
                Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextQuaternary, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
            }
        }
        AnimatedVisibility(
            selected,
            enter = fadeIn(OnRoadMotion.enter()) + scaleIn(OnRoadMotion.bouncy(), initialScale = 0.5f),
            exit = fadeOut(OnRoadMotion.exit()) + scaleOut(OnRoadMotion.exit(), targetScale = 0.5f),
        ) {
            Row {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.Check, "고름", tint = OnRoadColors.Primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 검색어 부분만 진하게 */
private fun highlight(text: String, q: String) = buildAnnotatedString {
    val i = if (q.isEmpty()) -1 else text.indexOf(q)
    if (i < 0) {
        withStyle(SpanStyle(color = OnRoadColors.TextStrong, fontWeight = FontWeight.SemiBold)) { append(text) }
        return@buildAnnotatedString
    }
    append(text.substring(0, i))
    withStyle(SpanStyle(color = OnRoadColors.TextStrong, fontWeight = FontWeight.SemiBold)) { append(text.substring(i, i + q.length)) }
    append(text.substring(i + q.length))
}

@Composable
private fun DateField(date: LocalDate?, onClick: () -> Unit) {
    val shape = OnRoadShapes.Inner
    val line by animateColorAsState(if (date != null) OnRoadColors.Primary else OnRoadColors.BorderSlate, OnRoadMotion.move(), label = "date-line")
    val textColor by animateColorAsState(if (date != null) OnRoadColors.TextPrimary else OnRoadColors.TextQuaternary, OnRoadMotion.move(), label = "date-text")
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(OnRoadColors.Surface)
            .border(1.dp, line, shape)
            .tap(onClickLabel = "날짜 고르기", onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            date?.format(DOT) ?: "날짜 고르기",
            style = OnRoadType.Body2,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.CalendarToday, null, tint = OnRoadColors.TextTertiary, modifier = Modifier.size(18.dp))
    }
}
