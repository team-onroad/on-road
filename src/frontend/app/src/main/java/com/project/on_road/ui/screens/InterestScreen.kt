package com.project.on_road.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * 흥미 탐색 (청소년 · 자립준비청년)
 * 시작 → 질문(한 문제씩, 고르면 자동으로 다음) → 자유롭게 말하기 → 결과(유형 카드 · 육각형 그래프 · 강점) → 직업(적합도) → 로드맵(체크)
 * 아동은 '좋아하는 거 찾기'(ChildFindScreen)를 쓰지만, 혹시 들어오면 같은 흐름에서 자유 입력 없이 진행한다.
 */

enum class InterestStep { LOADING, INTRO, QUESTIONS, FREE_TEXT, ANALYZING, RESULT, JOBS, ROADMAP }

/** 흥미 유형별 강점·해 볼 활동 (결과 화면) */
private val Riasec.strength: String
    get() = when (this) {
        Riasec.R -> "손으로 만들고 고치는 힘"
        Riasec.I -> "궁금한 걸 끝까지 파고드는 힘"
        Riasec.A -> "생각을 새롭게 표현하는 힘"
        Riasec.S -> "사람의 마음을 살피는 힘"
        Riasec.E -> "앞장서서 이끄는 힘"
        Riasec.C -> "꼼꼼하게 정리하는 힘"
    }

private val Riasec.activity: String
    get() = when (this) {
        Riasec.R -> "요리·공예·정비 체험"
        Riasec.I -> "과학 탐구, 코딩 체험"
        Riasec.A -> "그림·음악·영상 만들기"
        Riasec.S -> "봉사활동, 또래 멘토"
        Riasec.E -> "발표, 동아리 운영"
        Riasec.C -> "기록·회계 담당 맡기"
    }

/** 그래프·카드 위 짧은 이름 (현실·탐구 …) */
private val Riasec.short: String get() = label.removeSuffix("형")

private val FreeTextChips = listOf("그림 그리기", "영상 편집", "게임", "요리", "운동", "동물", "사람 돕기", "정리하기", "기계 만지기", "글쓰기")

class InterestViewModel : ViewModel() {
    val stage = AppState.stage
    private val userId get() = AppContainer.session.userId.orEmpty()

    var step by mutableStateOf(InterestStep.LOADING)
        private set
    var questions by mutableStateOf<List<InterestQuestion>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    private val answers = mutableListOf<InterestOption>()
    var freeText by mutableStateOf("")
    var profile by mutableStateOf<InterestProfile?>(null)
        private set
    var jobs by mutableStateOf<List<JobRecommendation>>(emptyList())
        private set
    var roadmap by mutableStateOf<Roadmap?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** 결과 화면 안내 (아동 때 고른 것 반영, 지난 결과와 비교) — 한 줄짜리 */
    var notes by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            questions = runCatching { AppContainer.coachApi.interestQuestions(stage) }.getOrDefault(emptyList())
            step = InterestStep.INTRO
        }
    }

    fun start() {
        answers.clear()
        index = 0
        freeText = ""
        profile = null
        step = InterestStep.QUESTIONS
    }

    fun answer(option: InterestOption) {
        if (step != InterestStep.QUESTIONS) return
        answers.add(option)
        if (index < questions.lastIndex) index++
        else if (stage == UserStage.CHILD) analyze()
        else step = InterestStep.FREE_TEXT
    }

    fun addChip(text: String) {
        freeText = (freeText.trim() + " " + text).trim().take(300)
    }

    fun analyze() {
        step = InterestStep.ANALYZING
        viewModelScope.launch {
            val raw = runCatching { AppContainer.coachApi.analyzeInterest(userId, answers.toList(), freeText) }.getOrNull()
            val list = mutableListOf<String>()
            // 아동 때 '좋아하는 거 찾기'에서 고른 활동을 이어서 반영 (활동 하나당 +1)
            val kid = ActivityStore.likedCounts().filter { it.first in ActivityStore.kidActivityTypes }
            val p = if (raw != null && kid.isNotEmpty() && stage != UserStage.CHILD) {
                val bonus = kid.groupBy { ActivityStore.kidActivityTypes.getValue(it.first) }.mapValues { (_, l) -> l.size }
                list += "어릴 때 고른 ${kid.take(2).joinToString("·") { it.first }}도 반영했어요"
                InterestProfile(raw.scores.mapValues { (k, v) -> v + (bonus[k] ?: 0) })
            } else raw
            // 지난 결과와 비교
            val prev = ActivityStore.interests().firstOrNull()
            if (p != null && prev != null) {
                list += if (prev.top.firstOrNull() == p.top.firstOrNull()) "지난번에도 ${p.top.first().label}, 관심이 꾸준해요"
                else "지난번엔 ${prev.top.joinToString("·") { it.label }}였어요"
            }
            profile = p
            notes = list
            if (p != null) {
                AppContainer.session.interestTypes = p.top
                ActivityStore.logInterest(p.scores)
            }
            step = InterestStep.RESULT
        }
    }

    fun loadJobs() {
        val p = profile ?: return
        loading = true
        viewModelScope.launch {
            jobs = runCatching { AppContainer.coachApi.recommendJobs(userId, p, stage) }.getOrDefault(emptyList())
            loading = false
            step = InterestStep.JOBS
        }
    }

    fun pickJob(job: JobRecommendation) {
        loading = true
        viewModelScope.launch {
            roadmap = runCatching { AppContainer.coachApi.buildRoadmap(userId, job, stage) }.getOrNull()
            roadmap?.let { ActivityStore.saveRoadmap(it) }
            loading = false
            if (roadmap != null) step = InterestStep.ROADMAP
        }
    }

    /** 시작 화면에서 저장한 로드맵 바로 보기 */
    fun openSavedRoadmap() {
        val saved = ActivityStore.roadmap() ?: return
        roadmap = Roadmap(
            job = JobRecommendation("saved", saved.jobName, "", "", emptyList()),
            steps = saved.steps,
            note = "단계를 해내면 눌러서 체크해요.",
        )
        step = InterestStep.ROADMAP
    }

    /** 뒤로가기: 단계 안에서 먼저 되돌린다. 더 못 가면 false */
    fun stepBack(): Boolean = when (step) {
        InterestStep.ROADMAP -> { step = if (jobs.isEmpty()) InterestStep.INTRO else InterestStep.JOBS; true }
        InterestStep.JOBS -> { step = InterestStep.RESULT; true }
        InterestStep.RESULT -> { step = InterestStep.INTRO; true }
        InterestStep.QUESTIONS -> {
            if (index > 0) { index--; answers.removeLastOrNull() } else step = InterestStep.INTRO
            true
        }
        InterestStep.FREE_TEXT -> { answers.removeLastOrNull(); step = InterestStep.QUESTIONS; true }
        else -> false
    }
}

/** 흥미 탐색 */
@Composable
fun InterestScreen(
    onBack: () -> Unit,
    onRoleplay: () -> Unit,
    onNavigate: (String) -> Unit = {},
    vm: InterestViewModel = viewModel(),
) {
    val back = { if (!vm.stepBack()) onBack() }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar("흥미 탐색", back)
        // 단계가 바뀔 때 부드럽게 겹쳐 바꾼다
        androidx.compose.animation.Crossfade(vm.step, animationSpec = androidx.compose.animation.core.tween(220), label = "interest-step") { step ->
        when (step) {
            InterestStep.LOADING -> LoadingState("질문을 준비하고 있어요")
            InterestStep.INTRO -> IntroPage(vm.questions.size, onStart = vm::start, onRoadmap = vm::openSavedRoadmap)
            InterestStep.QUESTIONS -> vm.questions.getOrNull(vm.index)?.let { q ->
                QuestionPage(q, vm.index, vm.questions.size, vm::answer, onPrev = { vm.stepBack() })
            }
            InterestStep.FREE_TEXT -> FreeTextPage(vm)
            InterestStep.ANALYZING -> LoadingState("대답을 보고 흥미 유형을 살펴보고 있어요")
            InterestStep.RESULT -> vm.profile?.let { ResultPage(it, vm.notes, vm.loading, vm::loadJobs, vm::start) }
            InterestStep.JOBS -> JobsPage(vm.jobs, vm.profile, vm.loading, vm::pickJob)
            InterestStep.ROADMAP -> vm.roadmap?.let { RoadmapPage(it, onRoleplay) { s -> onNavigate(com.project.on_road.ui.screens.navigation.stepRoute(s)) } }
        }
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.height(32.dp))
    }
}

/** 영문 유형 글자 동그라미 (R I A S E C) */
@Composable
private fun TypeBadge(r: Riasec, on: Boolean = true, size: Int = 28) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (on) OnRoadColors.Primary else OnRoadColors.SurfaceSlate),
        contentAlignment = Alignment.Center,
    ) {
        Text(r.name, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = if (on) Color.White else OnRoadColors.TextSlateMid)
    }
}

/* ---------------------------- 시작 ---------------------------- */

@Composable
private fun IntroPage(questionCount: Int, onStart: () -> Unit, onRoadmap: () -> Unit) {
    val last = remember { ActivityStore.interests().firstOrNull() }
    val saved = remember { ActivityStore.roadmap() }
    val kid = remember { ActivityStore.likedCounts().take(2).map { it.first } }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            OnRoadTag("진로 탐색", style = TagStyle.PrimaryFilled)
            Spacer(Modifier.height(12.dp))
            ScreenHeadline("나는 어떤 일을 할 때", "즐거울까요?")
            Spacer(Modifier.height(6.dp))
            Text("정답은 없어요. 끌리는 쪽을 골라 주세요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)

            // 진행 순서
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            OnRoadCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("질문 ${questionCount}개", "흥미 유형", "직업·로드맵").forEachIndexed { i, label ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(if (i == 0) OnRoadColors.Primary else OnRoadColors.SurfaceSlate),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${i + 1}", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = if (i == 0) Color.White else OnRoadColors.TextSlateMid)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(label, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary, maxLines = 1)
                        }
                        if (i < 2) Box(Modifier.width(18.dp).height(1.dp).background(OnRoadColors.Border))
                    }
                }
            }

            // 6가지 유형 미리 보기
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SubHeader("이런 6가지 유형 중에서 찾아요")
            Spacer(Modifier.height(12.dp))
            Riasec.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    pair.forEach { r ->
                        SubtleCard(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypeBadge(r, on = r in (last?.top.orEmpty()), size = 26)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1)
                                    Text(r.strength, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // 지난 기록
            if (last != null || saved != null || kid.isNotEmpty()) {
                Spacer(Modifier.height(OnRoadDimens.BlockGap - 8.dp))
                SubHeader("이어서 보기")
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    last?.let { ListRow("지난 결과 · ${dayText(it.at)}", "${it.top.joinToString("·") { t -> t.label }} 성향") }
                    saved?.let { ListRow("${it.jobName} 로드맵", "${it.steps.size}단계 중 ${it.done.size}단계 완료", onClick = onRoadmap) }
                    if (kid.isNotEmpty()) ListRow("어릴 때 좋아했던 것", kid.joinToString(" · ") + " · 결과에 반영돼요")
                }
            }
        }
        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp)) {
            OnRoadButton(
                if (last == null) "시작하기" else "다시 탐색하기",
                onStart,
                Modifier.fillMaxWidth(),
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
            )
        }
    }
}

/* ---------------------------- 질문 ---------------------------- */

@Composable
private fun QuestionPage(q: InterestQuestion, index: Int, total: Int, onPick: (InterestOption) -> Unit, onPrev: () -> Unit) {
    SpeakOnce(q.prompt, key = q.id)
    var picked by remember(q.id) { mutableStateOf<InterestOption?>(null) }
    // 고르면 잠깐 표시한 뒤 다음 질문으로
    LaunchedEffect(picked) {
        val p = picked ?: return@LaunchedEffect
        delay(260)
        onPick(p)
    }

    Page {
        // 진행: 질문 수만큼 칸
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(OnRoadShapes.Badge)
                        .background(if (i <= index) OnRoadColors.Primary else OnRoadColors.SurfaceSlate),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Q${index + 1}", style = OnRoadType.Display, color = OnRoadColors.Primary)
            Spacer(Modifier.width(6.dp))
            Text("/ $total", style = OnRoadType.Caption, color = OnRoadColors.TextQuaternary, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            SpeakButton(q.prompt)
        }
        Spacer(Modifier.height(8.dp))
        Text(q.prompt, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
        Spacer(Modifier.height(28.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            q.options.forEachIndexed { i, o ->
                val on = picked == o
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(OnRoadShapes.Inner)
                        .background(if (on) OnRoadColors.Primary else Color.White)
                        .border(1.dp, if (on) OnRoadColors.Primary else OnRoadColors.BorderSlate, OnRoadShapes.Inner)
                        .clickable(enabled = picked == null) { picked = o }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (on) Color.White else OnRoadColors.SurfaceSlate),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ('A' + i).toString(),
                            style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold),
                            color = if (on) OnRoadColors.Primary else OnRoadColors.TextSlateMid,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        o.text,
                        style = OnRoadType.Body2.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        color = if (on) Color.White else OnRoadColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (index > 0) {
            Spacer(Modifier.height(20.dp))
            Text(
                "이전 질문",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextTertiary,
                modifier = Modifier.clickable(onClick = onPrev).padding(vertical = 6.dp),
            )
        }
    }
}

/* ---------------------------- 자유롭게 말하기 ---------------------------- */

@Composable
private fun FreeTextPage(vm: InterestViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            OnRoadTag("마지막", style = TagStyle.PrimarySoft)
            Spacer(Modifier.height(12.dp))
            ScreenHeadline("요즘 관심 있는 것을", "알려 주세요.")
            Spacer(Modifier.height(6.dp))
            Text("눌러서 고르거나 직접 써도 돼요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(20.dp))
            // 예시 칩 (누르면 글에 더해짐)
            FreeTextChips.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { c -> ChoiceChip(c, selected = vm.freeText.contains(c)) { vm.addChip(c) } }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                OnRoadTextField(
                    value = vm.freeText,
                    onValueChange = { vm.freeText = it.take(300) },
                    placeholder = "예: 영상 편집하는 게 재밌어요",
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.weight(1f),
                )
                MicButton(onResult = { vm.addChip(it) }, modifier = Modifier.padding(start = 8.dp), dim = 48.dp)
            }
        }
        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp)) {
            OnRoadButton("결과 보기", vm::analyze, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                "건너뛰기",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { vm.freeText = ""; vm.analyze() }.padding(vertical = 8.dp),
            )
        }
    }
}

/* ---------------------------- 결과 ---------------------------- */

@Composable
private fun ResultPage(
    p: InterestProfile,
    notes: List<String>,
    loading: Boolean,
    onJobs: () -> Unit,
    onRestart: () -> Unit,
) {
    val top = p.top
    val first = top.first()
    SpeakOnce("${top.joinToString(", ") { it.label }} 성향이 높게 나왔어요.")
    val uri = LocalUriHandler.current

    Page {
        // 대표 유형 카드 (진한 남색)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(OnRoadShapes.Card)
                .background(OnRoadColors.Primary)
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("나의 흥미 유형", style = OnRoadType.Caption, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Text(top.joinToString("") { it.name }, style = OnRoadType.Title2, color = Color.White.copy(alpha = 0.9f))
            }
            Spacer(Modifier.height(10.dp))
            Text(top.joinToString(" · ") { it.label }, style = OnRoadType.Display, color = Color.White, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(first.description, style = OnRoadType.Caption, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        notes.forEach { n ->
            Spacer(Modifier.height(8.dp))
            SubtleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Text(n, style = OnRoadType.Micro, color = OnRoadColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // 육각형 그래프
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubHeader("6가지 유형 점수")
        Spacer(Modifier.height(12.dp))
        OnRoadCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            RiasecRadar(p, Modifier.fillMaxWidth().aspectRatio(1.15f))
        }

        // 강점 카드
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        SubHeader("나의 강점")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            top.forEach { r ->
                OnRoadCard(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(16.dp)) {
                    TypeBadge(r)
                    Spacer(Modifier.height(10.dp))
                    Text(r.strength, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Text(r.activity, style = OnRoadType.Tiny, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "탐색용 결과예요 · 정식 검사는 커리어넷",
            style = OnRoadType.Tiny,
            color = OnRoadColors.TextQuaternary,
            modifier = Modifier.clickable { runCatching { uri.openUri("https://www.career.go.kr") } },
        )
        Spacer(Modifier.height(24.dp))
        OnRoadButton("나에게 맞는 직업 보기", onJobs, Modifier.fillMaxWidth(), loading = loading, trailingIcon = Icons.AutoMirrored.Filled.ArrowForward)
        Spacer(Modifier.height(8.dp))
        OnRoadButton("다시 해 보기", onRestart, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
    }
}

/** 육각형(레이더) 그래프: 6개 유형 점수 */
@Composable
private fun RiasecRadar(p: InterestProfile, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = OnRoadColors.TextSecondary)
    val topStyle = labelStyle.copy(fontWeight = FontWeight.Bold, color = OnRoadColors.Primary)
    val grid = OnRoadColors.Border
    val fill = OnRoadColors.Primary.copy(alpha = 0.16f)
    val stroke = OnRoadColors.Primary
    val types = Riasec.entries
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width, size.height) / 2 * 0.70f
        fun point(i: Int, r: Float): Offset {
            val a = -PI / 2 + i * 2 * PI / types.size
            return Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
        }
        // 배경 육각형 3겹 + 축
        for (ring in 1..3) {
            val path = Path()
            types.indices.forEach { i ->
                val pt = point(i, radius * ring / 3f)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            path.close()
            drawPath(path, grid, style = Stroke(width = 1.dp.toPx()))
        }
        types.indices.forEach { i -> drawLine(grid, c, point(i, radius), strokeWidth = 1.dp.toPx()) }

        // 내 점수
        val max = p.max.toFloat()
        val shape = Path()
        types.forEachIndexed { i, r ->
            val v = ((p.scores[r] ?: 0) / max).coerceIn(0.08f, 1f)
            val pt = point(i, radius * v)
            if (i == 0) shape.moveTo(pt.x, pt.y) else shape.lineTo(pt.x, pt.y)
        }
        shape.close()
        drawPath(shape, fill)
        drawPath(shape, stroke, style = Stroke(width = 2.dp.toPx()))
        types.forEachIndexed { i, r ->
            val v = ((p.scores[r] ?: 0) / max).coerceIn(0.08f, 1f)
            drawCircle(stroke, 3.dp.toPx(), point(i, radius * v))
        }

        // 이름
        types.forEachIndexed { i, r ->
            val layout = measurer.measure(r.short, if (r in p.top) topStyle else labelStyle)
            val pt = point(i, radius + 18.dp.toPx())
            drawText(layout, topLeft = Offset(pt.x - layout.size.width / 2, pt.y - layout.size.height / 2))
        }
    }
}

/* ---------------------------- 직업 ---------------------------- */

/** 직업 적합도 (%): 직업 유형 점수 합 / 최고점 기준 */
private fun matchPercent(job: JobRecommendation, p: InterestProfile?): Int {
    if (p == null || job.types.isEmpty()) return 0
    val sum = job.types.sumOf { p.scores[it] ?: 0 }
    return (sum * 100f / (job.types.size * p.max)).toInt().coerceIn(40, 99)
}

@Composable
private fun JobsPage(jobs: List<JobRecommendation>, profile: InterestProfile?, loading: Boolean, onPick: (JobRecommendation) -> Unit) {
    Page {
        OnRoadTag("직업 추천", style = TagStyle.PrimarySoft)
        Spacer(Modifier.height(12.dp))
        ScreenHeadline("이런 직업은", "어때요?")
        Spacer(Modifier.height(6.dp))
        Text("하나를 고르면 로드맵을 만들어요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(24.dp))
        if (loading) {
            LoadingState("로드맵을 만들고 있어요")
            return@Page
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            jobs.forEachIndexed { i, j ->
                val pct = matchPercent(j, profile)
                OnRoadCard(
                    Modifier.fillMaxWidth(),
                    borderColor = if (i == 0) OnRoadColors.Primary else OnRoadColors.Border,
                    contentPadding = PaddingValues(18.dp),
                    onClick = { onPick(j) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 적합도
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(if (i == 0) OnRoadColors.Primary else OnRoadColors.SurfaceSlate),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$pct%",
                                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
                                color = if (i == 0) Color.White else OnRoadColors.TextSlateDark,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    j.name,
                                    style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                                    color = OnRoadColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                j.types.forEach { Spacer(Modifier.width(4.dp)); TypeBadge(it, on = it in profile?.top.orEmpty(), size = 20) }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(j.description, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------- 로드맵 ---------------------------- */

@Composable
private fun RoadmapPage(r: Roadmap, onRoleplay: () -> Unit, onStep: (com.project.on_road.data.NextStep) -> Unit = {}) {
    // 저장된 로드맵의 체크 상태 (체크하면 바로 다시 그린다)
    val done = remember(ActivityStore.version, r.job.name) {
        ActivityStore.roadmap()?.takeIf { it.jobName == r.job.name }?.done.orEmpty()
    }
    Page {
        OnRoadTag("로드맵", style = TagStyle.PrimaryFilled)
        Spacer(Modifier.height(12.dp))
        ScreenHeadline("${r.job.name}까지", "가는 길이에요.")
        Spacer(Modifier.height(6.dp))
        Text("해낸 단계는 눌러서 체크해요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(16.dp))
        ProgressBar(if (r.steps.isEmpty()) 0f else done.size / r.steps.size.toFloat(), height = 6.dp)
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        r.steps.forEachIndexed { i, s ->
            val checked = i in done
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(if (checked) OnRoadColors.Primary else OnRoadColors.SurfaceSlate),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (checked) "✓" else "${i + 1}",
                            style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold),
                            color = if (checked) Color.White else OnRoadColors.TextSlateMid,
                        )
                    }
                    if (i < r.steps.lastIndex) {
                        Box(Modifier.padding(top = 4.dp).width(1.dp).weight(1f).background(OnRoadColors.Border))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = if (i < r.steps.lastIndex) 8.dp else 0.dp)) {
                    OnRoadCard(
                        Modifier.fillMaxWidth(),
                        borderColor = if (checked) OnRoadColors.Primary else OnRoadColors.BorderSage.copy(alpha = 0.7f),
                        shape = OnRoadShapes.Item,
                        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 14.dp),
                        onClick = { ActivityStore.toggleRoadmapStep(i) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.period, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary, modifier = Modifier.weight(1f))
                            CheckBoxMark(checked)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(s.detail, style = OnRoadType.Caption2, color = OnRoadColors.TextPrimary.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (r.note.isNotBlank()) {
            Spacer(Modifier.height(24.dp))
            Footnote(r.note)
        }
        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        NextStepList(com.project.on_road.data.Links.afterCareer(r.job.name), onStep)
        Spacer(Modifier.height(16.dp))
        OnRoadButton("다른 면접 상황도 보기", onRoleplay, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, borderColor = OnRoadColors.BorderChip)
    }
}
