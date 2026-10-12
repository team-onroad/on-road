package com.project.on_road.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.*
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

enum class InterestStep { LOADING, QUESTIONS, FREE_TEXT, ANALYZING, RESULT, JOBS, ROADMAP }

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

    init {
        viewModelScope.launch {
            questions = runCatching { AppContainer.coachApi.interestQuestions(stage) }.getOrDefault(emptyList())
            step = InterestStep.QUESTIONS
        }
    }

    fun answer(option: InterestOption) {
        answers.add(option)
        if (index < questions.lastIndex) index++
        else if (stage == UserStage.CHILD) analyze()
        else step = InterestStep.FREE_TEXT
    }

    fun analyze() {
        step = InterestStep.ANALYZING
        viewModelScope.launch {
            val p = runCatching { AppContainer.coachApi.analyzeInterest(userId, answers.toList(), freeText) }.getOrNull()
            profile = p
            if (p != null) AppContainer.session.interestTypes = p.top
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
            loading = false
            if (roadmap != null) step = InterestStep.ROADMAP
        }
    }

    fun restart() {
        answers.clear()
        index = 0
        freeText = ""
        profile = null
        step = InterestStep.QUESTIONS
    }

    /** 뒤로가기: 단계 안에서 먼저 되돌린다. 더 못 가면 false */
    fun stepBack(): Boolean = when (step) {
        InterestStep.ROADMAP -> { step = InterestStep.JOBS; true }
        InterestStep.JOBS -> { step = InterestStep.RESULT; true }
        InterestStep.QUESTIONS -> if (index > 0) { index--; answers.removeLastOrNull(); true } else false
        InterestStep.FREE_TEXT -> { answers.removeLastOrNull(); step = InterestStep.QUESTIONS; true }
        else -> false
    }
}

/** 흥미 탐색 (아동은 '좋아하는 것 찾기') */
@Composable
fun InterestScreen(onBack: () -> Unit, onRoleplay: () -> Unit, vm: InterestViewModel = viewModel()) {
    val child = vm.stage == UserStage.CHILD
    val back = { if (!vm.stepBack()) onBack() }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar(if (child) "좋아하는 것 찾기" else "흥미 탐색", back)
        when (vm.step) {
            InterestStep.LOADING -> LoadingState("질문을 준비하고 있어요")
            InterestStep.QUESTIONS -> vm.questions.getOrNull(vm.index)?.let { q ->
                QuestionPage(q, vm.index, vm.questions.size, child, vm::answer)
            }
            InterestStep.FREE_TEXT -> FreeTextPage(vm)
            InterestStep.ANALYZING -> LoadingState("대답을 보고 흥미 유형을 살펴보고 있어요")
            InterestStep.RESULT -> vm.profile?.let { ResultPage(it, child, vm.loading, vm::loadJobs, vm::restart, onBack) }
            InterestStep.JOBS -> JobsPage(vm.jobs, vm.loading, vm::pickJob)
            InterestStep.ROADMAP -> vm.roadmap?.let { RoadmapPage(it, onRoleplay) }
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

@Composable
private fun QuestionPage(q: InterestQuestion, index: Int, total: Int, child: Boolean, onPick: (InterestOption) -> Unit) {
    SpeakOnce(q.prompt, key = q.id)
    var picked by remember(q.id) { mutableStateOf<InterestOption?>(null) }
    Page {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("질문", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSlateMid, modifier = Modifier.weight(1f))
            Text("${index + 1}", style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Text("/$total", style = OnRoadType.Tiny, color = OnRoadColors.TextSlateMid, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar((index + 1) / total.toFloat())
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                q.prompt,
                style = if (child) OnRoadType.Display else OnRoadType.Title1,
                color = OnRoadColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            SpeakButton(q.prompt)
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.options.forEach { o ->
                SelectCard(
                    title = o.text,
                    description = null,
                    selected = picked == o,
                    onClick = {
                        picked = o
                        onPick(o)
                    },
                    modifier = if (child) Modifier.heightIn(min = 72.dp) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun FreeTextPage(vm: InterestViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = OnRoadDimens.ScreenPadding)) {
            Spacer(Modifier.height(24.dp))
            ScreenHeadline("요즘 관심 있는 것을", "자유롭게 말해 주세요.")
            Spacer(Modifier.height(6.dp))
            Text("좋아하는 과목, 취미, 해 보고 싶은 일 무엇이든 좋아요. 건너뛰어도 돼요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(24.dp))
            FieldLabel("나의 관심사")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                OnRoadTextField(
                    value = vm.freeText,
                    onValueChange = { vm.freeText = it.take(300) },
                    placeholder = "예: 영상 편집하는 게 재밌어요",
                    singleLine = false,
                    minLines = 4,
                    modifier = Modifier.weight(1f),
                )
                MicButton(onResult = { vm.freeText = (vm.freeText + " " + it).trim() }, modifier = Modifier.padding(start = 8.dp), dim = 48.dp)
            }
        }
        Column(Modifier.padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp)) {
            OnRoadButton("결과 보기", vm::analyze, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultPage(
    p: InterestProfile,
    child: Boolean,
    loading: Boolean,
    onJobs: () -> Unit,
    onRestart: () -> Unit,
    onDone: () -> Unit,
) {
    val top = p.top.first()
    val summary = if (child) "너는 ${top.kidLabel}! ${top.description}" else "${p.top.joinToString("·") { it.label }} 성향이 높게 나왔어요."
    SpeakOnce(summary)
    val uri = LocalUriHandler.current

    Page {
        if (child) {
            // 아동 화면은 캐릭터와 함께
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                OnRoadBuddy(size = 140.dp, mood = BuddyMood.HAPPY)
                Spacer(Modifier.height(8.dp))
                Text(summary, style = OnRoadType.Title2, color = OnRoadColors.TextPrimary, textAlign = TextAlign.Center)
                SpeakButton(summary)
            }
            Spacer(Modifier.height(24.dp))
            OnRoadCard(Modifier.fillMaxWidth(), borderColor = OnRoadColors.Primary) {
                Text("이런 일을 하는 사람들이 있어요", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                Spacer(Modifier.height(8.dp))
                Text(kidJobs(top), style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
            }
            Spacer(Modifier.height(32.dp))
            OnRoadButton("좋아요!", onDone, Modifier.fillMaxWidth(), height = 56.dp)
        } else {
            OnRoadTag("탐색 결과", style = TagStyle.PrimaryFilled)
            Spacer(Modifier.height(12.dp))
            ScreenHeadline(p.top.joinToString("·") { it.label }, "성향이 높게 나왔어요.")

            Spacer(Modifier.height(24.dp))
            OnRoadCard(Modifier.fillMaxWidth()) {
                Riasec.entries.forEach { r ->
                    val v = p.scores[r] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                        Text(
                            r.label,
                            style = OnRoadType.Caption.copy(fontWeight = if (r in p.top) FontWeight.Bold else FontWeight.Normal),
                            color = if (r in p.top) OnRoadColors.Primary else OnRoadColors.TextTertiary,
                            modifier = Modifier.width(56.dp),
                        )
                        ProgressBar(
                            v / p.max.toFloat(),
                            Modifier.weight(1f),
                            color = if (r in p.top) OnRoadColors.Primary else OnRoadColors.BorderSage,
                            height = 8.dp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SubHeader("나의 흥미 유형")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                p.top.forEach { r -> ListRow(r.label, r.description) }
            }

            Spacer(Modifier.height(16.dp))
            NoticeBox(
                "대화로 살펴본 탐색용 결과예요. 정식 검사는 커리어넷에서 받아 볼 수 있어요.",
                modifier = Modifier.clickable { runCatching { uri.openUri("https://www.career.go.kr") } },
            )
            Spacer(Modifier.height(32.dp))
            OnRoadButton("나에게 맞는 직업 보기", onJobs, Modifier.fillMaxWidth(), loading = loading)
        }
        Spacer(Modifier.height(8.dp))
        OnRoadButton("다시 해 보기", onRestart, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
    }
}

private fun kidJobs(r: Riasec): String = when (r) {
    Riasec.R -> "요리사, 목수, 자동차 정비사"
    Riasec.I -> "과학자, 의사, 프로그래머"
    Riasec.A -> "화가, 가수, 디자이너"
    Riasec.S -> "선생님, 간호사, 사회복지사"
    Riasec.E -> "사장님, 아나운서, 기획자"
    Riasec.C -> "회계사, 사서, 은행원"
}

@Composable
private fun JobsPage(jobs: List<JobRecommendation>, loading: Boolean, onPick: (JobRecommendation) -> Unit) {
    Page {
        ScreenHeadline("이런 직업은", "어때요?")
        Spacer(Modifier.height(6.dp))
        Text("하나를 고르면 로드맵을 만들어 드려요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(24.dp))
        if (loading) {
            LoadingState("로드맵을 만들고 있어요")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                jobs.forEachIndexed { i, j ->
                    OnRoadCard(
                        Modifier.fillMaxWidth(),
                        borderColor = if (i == 0) OnRoadColors.Primary else OnRoadColors.Border,
                        contentPadding = PaddingValues(20.dp),
                        onClick = { onPick(j) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                j.name,
                                style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold),
                                color = if (i == 0) OnRoadColors.Primary else OnRoadColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            j.types.forEach { OnRoadTag(it.label, style = TagStyle.PrimarySoft, modifier = Modifier.padding(start = 4.dp)) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(j.description, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        Text("추천 이유 · ${j.reason}", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoadmapPage(r: Roadmap, onRoleplay: () -> Unit) {
    Page {
        OnRoadTag("로드맵", style = TagStyle.PrimaryFilled)
        Spacer(Modifier.height(12.dp))
        ScreenHeadline("${r.job.name}까지", "가는 길이에요.")
        Spacer(Modifier.height(OnRoadDimens.BlockGap))
        r.steps.forEachIndexed { i, s ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(OnRoadColors.Primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = Color.White)
                    }
                    if (i < r.steps.lastIndex) {
                        Box(Modifier.padding(top = 4.dp).width(1.dp).weight(1f).background(OnRoadColors.Border))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = if (i < r.steps.lastIndex) 8.dp else 0.dp)) {
                    OnRoadCard(
                        Modifier.fillMaxWidth(),
                        borderColor = OnRoadColors.BorderSage.copy(alpha = 0.7f),
                        shape = OnRoadShapes.Item,
                        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 14.dp),
                    ) {
                        Text(s.period, style = OnRoadType.Micro.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.Primary)
                        Spacer(Modifier.height(4.dp))
                        Text(s.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(s.detail, style = OnRoadType.Caption2, color = OnRoadColors.TextPrimary.copy(alpha = 0.6f))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Footnote(r.note)
        Spacer(Modifier.height(24.dp))
        OnRoadButton("면접 연습하러 가기", onRoleplay, Modifier.fillMaxWidth())
    }
}
