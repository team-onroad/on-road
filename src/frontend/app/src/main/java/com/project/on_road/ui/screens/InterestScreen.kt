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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.horizontalScroll
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

enum class InterestStep { LOADING, INTRO, QUESTIONS, FREE_TEXT, ANALYZING, RESULT, JOBS, JOB_DETAIL, ROADMAP }

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
    /** 고른 직업과 그 상세 (상세를 못 불러오면 detail 은 null, 로드맵 만들기는 그대로 된다) */
    var job by mutableStateOf<JobRecommendation?>(null)
        private set
    var detail by mutableStateOf<JobDetail?>(null)
        private set
    var detailLoading by mutableStateOf(false)
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

    fun openJob(j: JobRecommendation) {
        job = j
        detail = null
        detailLoading = true
        step = InterestStep.JOB_DETAIL
        viewModelScope.launch {
            detail = runCatching { AppContainer.coachApi.jobDetail(userId, j, stage) }.getOrNull()
            detailLoading = false
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
        InterestStep.ROADMAP -> {
            step = when {
                jobs.isEmpty() -> InterestStep.INTRO
                job != null -> InterestStep.JOB_DETAIL
                else -> InterestStep.JOBS
            }
            true
        }
        InterestStep.JOB_DETAIL -> { step = InterestStep.JOBS; true }
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
            InterestStep.JOBS -> JobsPage(vm.jobs, vm.profile, vm::openJob)
            InterestStep.JOB_DETAIL -> vm.job?.let { j ->
                JobDetailPage(
                    job = j,
                    detail = vm.detail,
                    detailLoading = vm.detailLoading,
                    roadmapLoading = vm.loading,
                    onRoadmap = { vm.pickJob(j) },
                    onPolicy = { q -> onNavigate(com.project.on_road.ui.screens.navigation.Routes.policy(query = q)) },
                    onPractice = { id -> onNavigate(com.project.on_road.ui.screens.navigation.Routes.roleplay(id)) },
                )
            }
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

/** 연회색 데이터 카드 */
@Composable
private fun DataCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) = OnRoadCard(
    modifier,
    background = OnRoadColors.SurfaceSubtle,
    borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
    shape = OnRoadShapes.Item,
    contentPadding = contentPadding,
    onClick = onClick,
    content = content,
)

/** 영문 유형 글자 동그라미 (R I A S E C): 내 유형은 메인 색, 나머지는 흰 바탕 + 연한 테두리 */
@Composable
private fun TypeBadge(r: Riasec, on: Boolean = true, size: Int = 28) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (on) OnRoadColors.Primary else OnRoadColors.Surface)
            .border(1.dp, if (on) OnRoadColors.Primary else OnRoadColors.BorderChip, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(r.name, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = if (on) Color.White else OnRoadColors.TextTertiary)
    }
}

/** 화면 머리: 헤드라인 + 회색 설명 */
@Composable
private fun PageHead(first: String, second: String, caption: String) {
    ScreenHeadline(first, second)
    Spacer(Modifier.height(6.dp))
    Text(caption, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
}

/** 아래 고정 버튼 영역 */
@Composable
private fun BottomBar(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.background(OnRoadColors.Background).padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp), content = content)
}

/* ---------------------------- 시작 ---------------------------- */

@Composable
private fun IntroPage(questionCount: Int, onStart: () -> Unit, onRoadmap: () -> Unit) {
    val last = remember { ActivityStore.interests().firstOrNull() }
    val saved = remember(ActivityStore.version) { ActivityStore.roadmap() }
    val kid = remember { ActivityStore.likedCounts().take(2).map { it.first } }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            PageHead("나는 어떤 일을 할 때", "즐거울까요?", "정답은 없어요. 끌리는 쪽을 골라 주세요.")

            // 이어서 보기: 저장한 로드맵 · 지난 결과
            if (saved != null || last != null) {
                Spacer(Modifier.height(OnRoadDimens.BlockGap))
                SubHeader("이어서 보기")
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saved?.let { rm ->
                        val next = rm.nextIndex?.let { rm.steps[it] }
                        DataCard(Modifier.fillMaxWidth(), onClick = onRoadmap) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${rm.jobName} 로드맵", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        next?.let { "다음 · ${it.title}" } ?: "모든 단계를 해냈어요",
                                        style = OnRoadType.Caption2,
                                        color = OnRoadColors.TextTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                PrimaryPill("이어 보기", onRoadmap)
                            }
                            Spacer(Modifier.height(12.dp))
                            ProgressBar(rm.progress, Modifier.fillMaxWidth(), height = 5.dp)
                        }
                    }
                    last?.let {
                        DataCard(Modifier.fillMaxWidth()) {
                            Text("지난 결과", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${it.top.joinToString(" · ") { t -> t.label }} · ${dayText(it.at)}",
                                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                                color = OnRoadColors.TextPrimary,
                            )
                        }
                    }
                }
            }

            // 6가지 유형 미리 보기 (지난번 내 유형은 메인 색)
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SubHeader("이 6가지 유형 중에서 찾아요")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Riasec.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { r ->
                            DataCard(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TypeBadge(r, on = r in (last?.top.orEmpty()), size = 26)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(r.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1)
                                        Spacer(Modifier.height(2.dp))
                                        Text(r.strength, style = OnRoadType.Micro, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (kid.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "어릴 때 좋아했던 ${kid.joinToString("·")}도 결과에 반영돼요",
                    style = OnRoadType.Caption2,
                    color = OnRoadColors.TextTertiary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar {
            OnRoadButton(
                if (last == null) "질문 ${questionCount}개로 시작하기" else "다시 탐색하기",
                onStart,
                Modifier.fillMaxWidth(),
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
                        .background(if (i <= index) OnRoadColors.Primary else OnRoadColors.SurfaceMuted),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(q.prompt, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SpeakButton(q.prompt)
        }
        Spacer(Modifier.height(28.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            q.options.forEach { o ->
                val on = picked == o
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(OnRoadShapes.Inner)
                        .background(if (on) OnRoadColors.Primary else Color.White)
                        .border(1.dp, if (on) OnRoadColors.Primary else OnRoadColors.BorderChip, OnRoadShapes.Inner)
                        .clickable(enabled = picked == null) { picked = o }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
            PageHead("요즘 관심 있는 것을", "알려 주세요.", "눌러서 고르거나 직접 써도 돼요.")
            Spacer(Modifier.height(24.dp))
            // 예시 칩 (누르면 글에 더해짐)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeTextChips.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c -> ChoiceChip(c, selected = vm.freeText.contains(c)) { vm.addChip(c) } }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
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
        BottomBar {
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

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            // 대표 유형: 카드 없이 크게
            Text("나의 흥미 유형", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Text(top.joinToString(" · ") { it.label }, style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(first.description, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
            notes.forEach { n ->
                Spacer(Modifier.height(4.dp))
                Text(n, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }

            // 육각형 그래프 (흰 카드)
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            OnRoadCard(
                Modifier.fillMaxWidth(),
                borderColor = OnRoadColors.BorderChip,
                shape = OnRoadShapes.Item,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            ) {
                RiasecRadar(p, Modifier.fillMaxWidth().height(250.dp))
            }

            // 강점: 유형마다 연회색 카드 한 줄
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SubHeader("나의 강점")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                top.forEach { r ->
                    DataCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypeBadge(r)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.strength, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                                Spacer(Modifier.height(2.dp))
                                Text("해 보기 · ${r.activity}", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar {
            OnRoadButton("나에게 맞는 직업 보기", onJobs, Modifier.fillMaxWidth(), loading = loading)
            Spacer(Modifier.height(6.dp))
            Text(
                "다시 해 보기",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onRestart).padding(vertical = 8.dp),
            )
        }
    }
}

/** 육각형(레이더) 그래프: 6개 유형 점수. 자립 준비도 차트와 같은 눈금 (안쪽은 점선, 바깥으로 갈수록 실선) */
@Composable
private fun RiasecRadar(p: InterestProfile, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = OnRoadColors.TextTertiary)
    val topStyle = labelStyle.copy(fontWeight = FontWeight.Bold, color = OnRoadColors.TextPrimary)
    val ink = OnRoadColors.Primary
    val types = Riasec.entries
    val rings = listOf(
        0.25f to floatArrayOf(1f, 2.5f),
        0.5f to floatArrayOf(3f, 3f),
        0.75f to floatArrayOf(7f, 3f),
        1f to null,
    )
    val ringAlpha = listOf(0.25f, 0.4f, 0.55f, 0.8f)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width, size.height) / 2 * 0.74f
        fun point(i: Int, r: Float): Offset {
            val a = -PI / 2 + i * 2 * PI / types.size
            return Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
        }
        types.indices.forEach { i -> drawLine(ink.copy(alpha = 0.12f), c, point(i, radius), strokeWidth = 1.dp.toPx()) }
        rings.forEachIndexed { k, (ratio, dash) ->
            val path = Path()
            types.indices.forEach { i ->
                val pt = point(i, radius * ratio)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            path.close()
            drawPath(
                path,
                ink.copy(alpha = ringAlpha[k]),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = dash?.let { d -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(d[0].dp.toPx(), d[1].dp.toPx())) },
                ),
            )
        }

        // 내 점수
        val max = p.max.toFloat().coerceAtLeast(1f)
        fun ratio(r: Riasec) = ((p.scores[r] ?: 0) / max).coerceIn(0f, 1f)
        val shape = Path()
        types.forEachIndexed { i, r ->
            val pt = point(i, radius * ratio(r))
            if (i == 0) shape.moveTo(pt.x, pt.y) else shape.lineTo(pt.x, pt.y)
        }
        shape.close()
        drawPath(shape, ink.copy(alpha = 0.14f))
        drawPath(shape, ink, style = Stroke(width = 2.dp.toPx()))
        types.forEach { r -> drawCircle(ink, 3.5.dp.toPx(), point(types.indexOf(r), radius * ratio(r))) }

        // 이름 (내 유형은 진하게)
        types.forEachIndexed { i, r ->
            val layout = measurer.measure(r.short, if (r in p.top) topStyle else labelStyle)
            val pt = point(i, radius + 16.dp.toPx())
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
private fun JobsPage(jobs: List<JobRecommendation>, profile: InterestProfile?, onPick: (JobRecommendation) -> Unit) {
    Page {
        PageHead("이런 직업은", "어때요?", "눌러서 하는 일과 되는 길을 살펴봐요.")
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            jobs.forEach { j ->
                val pct = j.match ?: matchPercent(j, profile)
                DataCard(Modifier.fillMaxWidth(), onClick = { onPick(j) }, contentPadding = PaddingValues(start = 16.dp, end = 14.dp, top = 16.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.name, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(j.description, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (j.types.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    j.types.forEach { TypeBadge(it, on = it in profile?.top.orEmpty(), size = 20) }
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        // 적합도
                        Column(horizontalAlignment = Alignment.End) {
                            Text("$pct%", style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
                            Text("잘 맞아요", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------- 직업 상세 ---------------------------- */

@Composable
private fun JobDetailPage(
    job: JobRecommendation,
    detail: JobDetail?,
    detailLoading: Boolean,
    roadmapLoading: Boolean,
    onRoadmap: () -> Unit,
    onPolicy: (String) -> Unit,
    onPractice: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(job.name, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(job.description, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
            if (job.reason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(job.reason, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }

            when {
                detailLoading -> {
                    Spacer(Modifier.height(OnRoadDimens.BlockGap))
                    LoadingState("직업 정보를 불러오고 있어요")
                }
                detail != null -> {
                    // 하는 일
                    DetailSection("하는 일") {
                        DataCard(Modifier.fillMaxWidth()) { Bullets(detail.tasks) }
                    }
                    // 되는 길: 번호 동그라미
                    if (detail.path.isNotEmpty()) {
                        DetailSection("이렇게 준비해요") {
                            DataCard(Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    detail.path.forEachIndexed { i, line ->
                                        Row(verticalAlignment = Alignment.Top) {
                                            RoadmapMark(i + 1, done = false, current = i == 0, size = 22)
                                            Spacer(Modifier.width(12.dp))
                                            Text(line, style = OnRoadType.Caption, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f).padding(top = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 자격증: 같은 모양 태그
                    if (detail.certificates.isNotEmpty()) {
                        DetailSection("도움 되는 자격증") {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                detail.certificates.forEach {
                                    OnRoadTag(
                                        it,
                                        style = TagStyle.Neutral,
                                        textStyle = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                    // 관련 지원: 정책 검색으로 이어서 찾기
                    detail.policyQuery?.let { q ->
                        DetailSection("받을 수 있는 지원") {
                            LinkRow("‘$q’ 지원 찾아보기", "정책 검색에서 나에게 맞는 지원을 찾아요") { onPolicy(q) }
                        }
                    }
                    // 해 볼 연습
                    if (detail.practices.isNotEmpty()) {
                        DetailSection("미리 연습해요") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                detail.practices.forEach { pr -> LinkRow(pr.title, "상황 연습") { onPractice(pr.scenarioId) } }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar {
            OnRoadButton("이 직업으로 로드맵 만들기", onRoadmap, Modifier.fillMaxWidth(), loading = roadmapLoading)
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    SubHeader(title)
    Spacer(Modifier.height(12.dp))
    content()
}

@Composable
private fun Bullets(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            Row {
                Text("·", style = OnRoadType.Caption.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextTertiary, modifier = Modifier.width(14.dp))
                Text(line, style = OnRoadType.Caption.copy(lineHeight = OnRoadType.Body3.lineHeight), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 연회색 줄 카드: 제목 · 설명 · › */
@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    DataCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(sub, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = OnRoadColors.TextQuaternary,
                modifier = Modifier.size(20.dp),
            )
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
    val current = r.steps.indices.firstOrNull { it !in done }
    Page {
        PageHead("${r.job.name}까지", "가는 길이에요.", "해낸 단계는 눌러서 체크해요.")
        Spacer(Modifier.height(16.dp))
        ProgressBar(if (r.steps.isEmpty()) 0f else done.size / r.steps.size.toFloat(), Modifier.fillMaxWidth(), height = 6.dp)
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        // 체크리스트 단계와 같은 모양: 번호 동그라미 + 연회색 카드
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.steps.forEachIndexed { i, s ->
                val checked = i in done
                DataCard(
                    Modifier.fillMaxWidth(),
                    onClick = { ActivityStore.toggleRoadmapStep(i) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        RoadmapMark(i + 1, checked, i == current)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.period, style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                s.title,
                                style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold),
                                color = if (checked) OnRoadColors.TextTertiary else OnRoadColors.TextPrimary,
                            )
                            if (s.detail.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(s.detail, style = OnRoadType.Caption2, color = OnRoadColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(OnRoadDimens.SectionGap))
        NextStepList(com.project.on_road.data.Links.afterCareer(r.job.name), onStep)
        Spacer(Modifier.height(16.dp))
        OnRoadButton("다른 면접 상황도 보기", onRoleplay, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, borderColor = OnRoadColors.BorderChip)
    }
}

/** 번호 동그라미: 해낸 단계는 메인 색 + 체크, 지금 단계는 메인 색 테두리, 나머지는 연한 테두리 */
@Composable
internal fun RoadmapMark(index: Int, done: Boolean, current: Boolean, size: Int = 26) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (done) OnRoadColors.Primary else OnRoadColors.Surface)
            .border(if (current) 1.5.dp else 1.dp, if (done || current) OnRoadColors.Primary else OnRoadColors.BorderChip, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size((size * 0.58f).dp))
        } else {
            Text("$index", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = if (current) OnRoadColors.Primary else OnRoadColors.TextQuaternary)
        }
    }
}
