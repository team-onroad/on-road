package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Box

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
import androidx.compose.ui.graphics.graphicsLayer
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
 * 시작 → 흥미 30문항(좋아요 · 보통 · 별로, 고르면 자동으로 다음) → 일에서 중요한 것(3개까지) → 준비 기간 · 일하는 곳
 * → 결과(3글자 유형 코드 · 유형별 점수 · 판단 근거) → 직업(54개 중 이유와 함께) → 직업 상세(하는 일 · 직무 · 시험 일정 · 훈련 과정) → 로드맵
 * 아동은 '좋아하는 거 찾기'(ChildFindScreen)를 쓰지만, 혹시 들어오면 같은 흐름에서 자유 입력 없이 진행한다.
 */

enum class InterestStep { LOADING, INTRO, QUESTIONS, VALUES, PREP, ANALYZING, RESULT, TAGS, JOBS, JOB_DETAIL, ROADMAP }

/** 관심 분야는 이만큼까지 고른다 */
const val TAG_MAX = 3

/** 직업 목록 거르기 */
enum class JobFilter(val label: String) { ALL("전체"), SOON("6개월 안에 시작"), CERT("자격증 있는 일") }

/** 직업 상세 탭 */
enum class JobTab(val label: String) { JOB("하는 일"), DUTY("직무"), EXAM("시험 일정"), EDU("훈련 과정") }

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

    /** 일에서 중요한 것 (최대 3개) · 준비 기간 · 일하고 싶은 곳 */
    var values by mutableStateOf<List<WorkValue>>(emptyList())
        private set
    var prep by mutableStateOf<PrepTime?>(null)
    var place by mutableStateOf<WorkPlace?>(null)

    var profile by mutableStateOf<InterestProfile?>(null)
        private set
    var jobs by mutableStateOf<List<JobRecommendation>>(emptyList())
        private set
    var filter by mutableStateOf(JobFilter.ALL)
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

    // 직업 상세: 공공 정보 (못 받으면 그 탭을 숨긴다)
    var tab by mutableStateOf(JobTab.JOB)
    var extJob by mutableStateOf<ExternalJob?>(null)
        private set
    var duties by mutableStateOf<List<DutyInfo>>(emptyList())
        private set
    var exams by mutableStateOf<ExamSchedule?>(null)
        private set
    var trainings by mutableStateOf<List<TrainingCourse>>(emptyList())
        private set
    /** 펼친 훈련 과정의 소개 (과정 id → 소개, 못 받으면 null) */
    var trainingDetails by mutableStateOf<Map<String, TrainingDetail?>>(emptyMap())
        private set
    var externalLoading by mutableStateOf(false)
        private set
    /** 이 직업 준비에 받을 수 있는 지원 (취업 · 교육 분야 정책) */
    var supports by mutableStateOf<List<Policy>>(emptyList())
        private set
    /** 내 구 · 군 (훈련 과정 안내 문구) */
    var district by mutableStateOf<String?>(null)
        private set
    var myRegion by mutableStateOf<String?>(null)
        private set

    /** 보여 줄 탭 (하는 일은 항상) */
    val tabs: List<JobTab>
        get() = buildList {
            add(JobTab.JOB)
            if (duties.isNotEmpty()) add(JobTab.DUTY)
            if (exams != null) add(JobTab.EXAM)
            if (trainings.isNotEmpty()) add(JobTab.EDU)
        }

    val filteredJobs: List<JobRecommendation>
        get() = when (filter) {
            JobFilter.ALL -> jobs
            JobFilter.SOON -> jobs.filter { (it.prep?.months ?: 99) <= PrepTime.SHORT.months }
            JobFilter.CERT -> jobs.filter { t ->
                val c = t.certTag.orEmpty()
                (c.contains("자격") && c != "자격 없이 시작") || c.contains("면허")
            }
        }.take(8)

    init {
        viewModelScope.launch {
            questions = runCatching { AppContainer.coachApi.interestQuestions(stage) }.getOrDefault(emptyList())
            step = InterestStep.INTRO
        }
    }

    fun start() {
        answers.clear()
        index = 0
        values = emptyList()
        prep = null
        place = null
        profile = null
        step = InterestStep.QUESTIONS
    }

    fun answer(option: InterestOption) {
        if (step != InterestStep.QUESTIONS) return
        answers.add(option)
        if (index < questions.lastIndex) index++
        else if (stage == UserStage.CHILD) analyze()
        else step = InterestStep.VALUES
    }

    fun toggleValue(v: WorkValue) {
        values = when {
            v in values -> values - v
            values.size < 3 -> values + v
            else -> values
        }
    }

    fun toPrep() { step = InterestStep.PREP }

    fun analyze() {
        if (step == InterestStep.ANALYZING) return
        step = InterestStep.ANALYZING
        viewModelScope.launch {
            val raw = runCatching { AppContainer.coachApi.analyzeInterest(userId, answers.toList(), "") }.getOrNull()
            val list = mutableListOf<String>()
            // 아동 때 '좋아하는 거 찾기'에서 고른 활동을 이어서 반영 (활동 하나당 +1)
            val kid = ActivityStore.likedCounts().filter { it.first in ActivityStore.kidActivityTypes }
            val scores = if (raw != null && kid.isNotEmpty() && stage != UserStage.CHILD) {
                val bonus = kid.groupBy { ActivityStore.kidActivityTypes.getValue(it.first) }.mapValues { (_, l) -> l.size }
                list += "어릴 때 고른 ${kid.take(2).joinToString(", ") { it.first }}도 반영했어요"
                raw.scores.mapValues { (k, v) -> v + (bonus[k] ?: 0) }
            } else raw?.scores
            // 결과 근거: 유형별로 '좋아요' 고른 활동
            val asked = mutableMapOf<Riasec, Int>()
            val liked = mutableMapOf<Riasec, MutableList<String>>()
            questions.forEachIndexed { i, q ->
                val type = q.options.firstOrNull()?.types?.firstOrNull() ?: return@forEachIndexed
                asked[type] = (asked[type] ?: 0) + 1
                val a = answers.getOrNull(i) ?: return@forEachIndexed
                if (a.weight >= 2) liked.getOrPut(type) { mutableListOf() } += (q.activity ?: q.prompt)
            }
            val p = scores?.let {
                InterestProfile(
                    scores = it,
                    likes = liked.mapValues { (_, l) -> l.size },
                    liked = liked,
                    asked = asked,
                    values = values,
                    prep = prep,
                    place = place,
                )
            }
            // 지난 결과와 비교
            val prev = ActivityStore.interests().firstOrNull()
            if (p != null && prev != null) {
                list += if (prev.top.firstOrNull() == p.top.firstOrNull()) "지난번에도 ${p.top.first().label}, 관심이 꾸준해요"
                else "지난번엔 ${prev.top.joinToString(", ") { it.label }}였어요"
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
        if (loading) return
        loading = true
        viewModelScope.launch {
            jobs = runCatching { AppContainer.coachApi.recommendJobs(userId, p, stage) }.getOrDefault(emptyList())
            filter = JobFilter.ALL
            loading = false
            step = InterestStep.TAGS
        }
    }

    // ───── 관심 분야 (최대 3개) ─────

    /** 고른 관심 분야. 처음엔 지난번에 고른 것 */
    var tags by mutableStateOf(savedTags())
        private set
    var saving by mutableStateOf(false)
        private set

    private fun savedTags(): List<CareerTag> {
        val local = ActivityStore.careerTags()
        val server = ProfileStore.current?.interestTags.orEmpty()
        // 서버에 저장된 이름이 기준. 직업 id 는 휴대폰에 둔 것에서 찾는다
        return if (server.isEmpty()) local else server.map { n -> local.firstOrNull { it.name == n } ?: CareerTag(n) }
    }

    fun toggleTag(j: JobRecommendation) {
        tags = when {
            tags.any { it.name == j.name } -> tags.filterNot { it.name == j.name }
            tags.size >= TAG_MAX -> { OnRoadToast.show("${TAG_MAX}개까지 고를 수 있어요"); tags }
            else -> tags + CareerTag(j.name, j.id, j.types)
        }
    }

    /** 직접 넣은 분야 ("#" · 앞뒤 공백은 뗀다). 추천 직업 이름과 같으면 그 직업으로 */
    fun addTag(raw: String): Boolean {
        val name = raw.trim().trimStart('#').trim().take(20)
        if (name.isEmpty() || tags.any { it.name == name }) return false
        if (tags.size >= TAG_MAX) { OnRoadToast.show("${TAG_MAX}개까지 고를 수 있어요"); return false }
        tags = tags + (jobs.firstOrNull { it.name == name }?.let { CareerTag(it.name, it.id, it.types) } ?: CareerTag(name))
        return true
    }

    fun removeTag(t: CareerTag) { tags = tags - t }

    /** 휴대폰과 내 정보(서버)에 저장. 서버 저장이 안 돼도 휴대폰에 둔 것으로 진로 화면을 그린다 */
    fun saveTags(onDone: () -> Unit) {
        if (saving || tags.isEmpty()) return
        saving = true
        val picked = tags
        ActivityStore.saveCareerTags(picked)
        viewModelScope.launch {
            val id = AppContainer.session.userId
            if (id != null) {
                runCatching {
                    AppContainer.api.updateUser(id, UserUpdate(interestCode = profile?.code?.take(3), interestTags = picked.map { it.name }))
                }.getOrNull()?.let { ProfileStore.set(it) }
            }
            saving = false
            onDone()
        }
    }

    fun showJobs() { step = InterestStep.JOBS }

    /** 직업 상세에서 비슷한 직업으로 넘어간 순서 (뒤로 가면 앞 직업으로) */
    private val trail = mutableListOf<JobRecommendation>()

    /** 비슷한 직업: 추천 목록에서 흥미 유형이 겹치는 순 (대표 유형이 같으면 더 가깝다) */
    fun similarJobs(j: JobRecommendation): List<JobRecommendation> {
        if (j.types.isEmpty()) return emptyList()
        return jobs.filter { it.id != j.id }
            .map { o ->
                val score = (if (o.types.firstOrNull() == j.types.first()) 2 else 0) + o.types.count { it in j.types }
                o to score
            }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }
    }

    /** 비슷한 직업 열기: 지금 직업을 기억해 두고 넘어간다 */
    fun openSimilar(j: JobRecommendation) {
        job?.let { trail.add(it) }
        openJob(j, keepTrail = true)
    }

    /** 공공 정보의 관련 직업(앱 목록에 없는 직업)은 이름만으로 연다 */
    fun openRelated(name: String) {
        val known = jobs.firstOrNull { it.name == name }
        openSimilar(known ?: JobRecommendation("ext:$name", name, "", "", emptyList()))
    }

    fun openJob(j: JobRecommendation, keepTrail: Boolean = false) {
        if (!keepTrail) trail.clear()
        job = j
        detail = null
        detailLoading = true
        tab = JobTab.JOB
        extJob = null
        duties = emptyList()
        exams = null
        trainings = emptyList()
        trainingDetails = emptyMap()
        supports = emptyList()
        step = InterestStep.JOB_DETAIL
        viewModelScope.launch {
            val d = runCatching { AppContainer.coachApi.jobDetail(userId, j, stage) }.getOrNull()
            detail = d
            detailLoading = false
            loadExternal(j, d)
        }
    }

    /** 공공 정보는 서로 기다리지 않고 따로 받는다 (먼저 온 탭부터 생긴다) */
    private fun loadExternal(j: JobRecommendation, d: JobDetail?) {
        val info = AppContainer.careerInfo
        externalLoading = true
        val me = ProfileStore.current
        val region = me?.region
        myRegion = region?.takeIf { it.isNotBlank() }
        district = me?.district?.takeIf { it.isNotBlank() }
        val calls = listOf(
            viewModelScope.launch { info.job(j.name).let { if (job == j) extJob = it } },
            // 받을 수 있는 지원: 검색어 대신 분야(취업, 오래 준비하면 교육)로 실제 정책을 고른다 (검색은 결과가 없을 때가 있다)
            viewModelScope.launch {
                val cats = listOfNotNull("employment", if ((j.prep?.months ?: 0) >= PrepTime.MID.months) "education" else null)
                val found = cats.flatMap { c -> runCatching { AppContainer.api.browsePolicies(userId, c).policies }.getOrDefault(emptyList()) }
                    .filter { !it.forFamily }
                    .distinctBy { it.id }
                    .sortedBy { if (it.eligibility == "match") 0 else 1 }
                if (job == j) supports = found.take(3)
            },
            viewModelScope.launch { d?.keyword?.let { k -> info.duties(k).let { if (job == j) duties = it } } },
            viewModelScope.launch { d?.certificates?.takeIf { it.isNotEmpty() }?.let { c -> info.exams(c).let { if (job == j) exams = it } } },
            viewModelScope.launch { d?.keyword?.let { k -> info.trainings(k, region, district).let { if (job == j) trainings = it } } },
        )
        viewModelScope.launch {
            calls.forEach { it.join() }
            externalLoading = false
        }
    }

    /** 훈련 과정 카드를 펼칠 때 소개를 받는다 (한 번 받은 건 다시 안 받는다) */
    fun loadTraining(c: TrainingCourse) {
        if (trainingDetails.containsKey(c.id + c.round)) return
        trainingDetails = trainingDetails + ((c.id + c.round) to null)
        viewModelScope.launch {
            val d = AppContainer.careerInfo.trainingDetail(c)
            trainingDetails = trainingDetails + ((c.id + c.round) to d)
        }
    }

    /** 이 직업을 관심 직업으로 정한다 → 진로 로드맵에서 되는 길 · 시험 · 훈련 · 지원을 한 화면으로 이어서 본다 */
    fun pickJob(job: JobRecommendation, onDone: () -> Unit) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            roadmap = runCatching { AppContainer.coachApi.buildRoadmap(userId, job, stage) }.getOrNull()
            roadmap?.let { ActivityStore.saveRoadmap(it) }
            // 고른 직업은 관심 분야에도 넣는다 (자리가 있을 때)
            if (tags.none { it.name == job.name } && tags.size < TAG_MAX) {
                tags = tags + CareerTag(job.name, job.id, job.types)
                ActivityStore.saveCareerTags(tags)
                AppContainer.session.userId?.let { id ->
                    runCatching { AppContainer.api.updateUser(id, UserUpdate(interestTags = tags.map { it.name })) }.getOrNull()?.let { ProfileStore.set(it) }
                }
            }
            loading = false
            if (roadmap != null) onDone()
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
        InterestStep.JOB_DETAIL -> {
            val prev = trail.removeLastOrNull()
            if (prev != null) openJob(prev, keepTrail = true) else step = InterestStep.JOBS
            true
        }
        InterestStep.JOBS -> { step = if (profile != null) InterestStep.TAGS else InterestStep.INTRO; true }
        InterestStep.TAGS -> { step = InterestStep.RESULT; true }
        InterestStep.RESULT -> { step = InterestStep.INTRO; true }
        InterestStep.QUESTIONS -> {
            if (index > 0) { index--; answers.removeLastOrNull() } else step = InterestStep.INTRO
            true
        }
        // 마지막 문항의 답을 지우고 그 문항으로
        InterestStep.VALUES -> { answers.removeLastOrNull(); step = InterestStep.QUESTIONS; true }
        InterestStep.PREP -> { step = InterestStep.VALUES; true }
        else -> false
    }
}

/** 흥미 탐색 */
@Composable
fun InterestScreen(
    onBack: () -> Unit,
    onRoleplay: () -> Unit,
    onNavigate: (String) -> Unit = {},
    /** 관심 분야를 고른 뒤 나의 진로로 (흥미 탐색은 닫는다). 없으면 그냥 이동 */
    onCareer: (() -> Unit)? = null,
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
            InterestStep.INTRO -> IntroPage(vm.questions.size, onStart = vm::start, onRoadmap = { onNavigate(com.project.on_road.ui.screens.navigation.Routes.CAREER_ROADMAP) })
            InterestStep.QUESTIONS -> vm.questions.getOrNull(vm.index)?.let { q ->
                QuestionPage(q, vm.index, vm.questions.size, child = vm.stage == UserStage.CHILD, onPick = vm::answer, onPrev = { vm.stepBack() })
            }
            InterestStep.VALUES -> ValuesPage(vm)
            InterestStep.PREP -> PrepPage(vm)
            InterestStep.ANALYZING -> LoadingState(
                "대답을 살펴보고 있어요",
                steps = listOf("흥미 유형을 찾고 있어요", "어울리는 직업을 고르고 있어요"),
            )
            InterestStep.RESULT -> vm.profile?.let { ResultPage(it, vm.notes, vm.loading, vm::loadJobs, vm::start) }
            InterestStep.TAGS -> TagsPage(vm, onDone = { onCareer?.invoke() ?: onNavigate(com.project.on_road.ui.screens.navigation.Routes.CAREER_ROADMAP) })
            InterestStep.JOBS -> JobsPage(vm)
            InterestStep.JOB_DETAIL -> vm.job?.let { j -> key(j.id) {
                JobDetailPage(
                    vm = vm,
                    job = j,
                    onCareer = { onNavigate(com.project.on_road.ui.screens.navigation.Routes.CAREER_ROADMAP) },
                    onOpenPolicy = { id -> onNavigate(com.project.on_road.ui.screens.navigation.Routes.policyDetail(id)) },
                    onPolicy = { q -> onNavigate(com.project.on_road.ui.screens.navigation.Routes.policy(query = q.ifBlank { null })) },
                    onPractice = { id -> onNavigate(com.project.on_road.ui.screens.navigation.Routes.roleplay(id)) },
                )
            } }
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
                                        next?.let { "다음 할 일: ${it.title}" } ?: "모든 단계를 해냈어요",
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
                                "${it.top.joinToString(", ") { t -> t.label }}, ${dayText(it.at)}",
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
                            DataCard(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)) {
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
                    "어릴 때 좋아했던 ${kid.joinToString(", ")}도 결과에 반영돼요",
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

/* ---------------------------- 공통: 위계 ---------------------------- */

/** 섹션 제목 (17 굵게): 카드 제목(15)보다 한 단계 크다 */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = OnRoadType.Headline.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = modifier)
}

/** 카드 제목 (15) + 회색 설명 (13) */
@Composable
private fun CardTitle(title: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextPrimary)
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(sub, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        }
    }
}

/** 왼쪽 이름 · 오른쪽 값 한 줄 */
@Composable
private fun JobInfoRow(label: String, value: String) {
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

/** 출처 (아주 작게) */
@Composable
private fun SourceText(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = OnRoadType.Micro, color = OnRoadColors.TextQuaternary)
}

/** 질문 단계 표시: 흥미 · 일에서 중요한 것 · 준비 기간 (지금 단계만 진하게) + 오른쪽 개수 */
@Composable
private fun StageHeader(current: Int, progress: Float, count: String?) {
    ProgressBar(progress, Modifier.fillMaxWidth(), height = 4.dp)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("흥미", "일에서 중요한 것", "준비 기간").forEachIndexed { i, label ->
            Text(
                label,
                style = OnRoadType.Caption2.copy(fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal),
                color = if (i == current) OnRoadColors.TextPrimary else OnRoadColors.TextQuaternary,
            )
            if (i < 2) Spacer(Modifier.width(12.dp))
        }
        Spacer(Modifier.weight(1f))
        if (count != null) Text(count, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
    }
}

/** 큰 선택 버튼 (고르면 메인 색) */
@Composable
private fun OptionButton(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) OnRoadColors.Primary else Color.White, label = "opt-bg")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) Color.White else OnRoadColors.TextPrimary, label = "opt-fg")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(OnRoadShapes.Inner)
            .background(bg)
            .border(1.dp, if (selected) OnRoadColors.Primary else OnRoadColors.BorderChip, OnRoadShapes.Inner)
            .tap(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = OnRoadType.Body2.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium), color = fg)
    }
}

/* ---------------------------- 질문 ---------------------------- */

@Composable
private fun QuestionPage(q: InterestQuestion, index: Int, total: Int, child: Boolean, onPick: (InterestOption) -> Unit, onPrev: () -> Unit) {
    SpeakOnce(q.prompt, key = q.id)
    var picked by remember(q.id) { mutableStateOf<InterestOption?>(null) }
    // 고르면 잠깐 표시한 뒤 다음 질문으로
    LaunchedEffect(picked) {
        val p = picked ?: return@LaunchedEffect
        delay(260)
        onPick(p)
    }

    Page {
        if (child) ProgressBar((index + 1f) / total, Modifier.fillMaxWidth(), height = 4.dp)
        else StageHeader(0, (index + 1f) / total, "${index + 1} / $total")
        Spacer(Modifier.height(28.dp))
        // 질문이 바뀔 때 글자만 부드럽게 바꾼다
        Appear(q.id) {
            Row(verticalAlignment = Alignment.Top) {
                Text(q.prompt, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                SpeakButton(q.prompt)
            }
            if (q.activity != null) {
                Spacer(Modifier.height(6.dp))
                Text("해 본 적 없으면 상상해서 골라 주세요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            q.options.forEach { o -> OptionButton(o.text, picked == o, enabled = picked == null) { picked = o } }
        }
        if (index > 0) {
            Spacer(Modifier.height(20.dp))
            Text(
                "이전 질문",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextTertiary,
                modifier = Modifier.tap(onClick = onPrev).padding(vertical = 6.dp),
            )
        }
    }
}

/* ---------------------------- 일에서 중요한 것 ---------------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValuesPage(vm: InterestViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            StageHeader(1, 1f, "${vm.values.size} / 3")
            Spacer(Modifier.height(28.dp))
            Text("일할 때 가장 중요한 걸\n3개까지 골라 주세요", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text("추천 직업 순서에 반영돼요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkValue.entries.forEach { v -> ChoiceChip(v.label, v in vm.values) { vm.toggleValue(v) } }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar { OnRoadButton("다음", vm::toPrep, Modifier.fillMaxWidth(), enabled = vm.values.isNotEmpty()) }
    }
}

/* ---------------------------- 준비 기간 · 일하는 곳 ---------------------------- */

@Composable
private fun PrepPage(vm: InterestViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            StageHeader(2, 1f, null)
            Spacer(Modifier.height(28.dp))
            Text("일을 시작하기 전에\n얼마나 준비할 수 있어요?", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text("학비와 생활비 지원은 직업 상세에서 같이 볼 수 있어요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrepTime.entries.forEach { p -> OptionButton(p.label, vm.prep == p) { vm.prep = p } }
            }
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SectionTitle("어디서 일하고 싶어요?")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkPlace.entries.forEach { w -> ChoiceChip(w.label, vm.place == w) { vm.place = w } }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar { OnRoadButton("결과 보기", vm::analyze, Modifier.fillMaxWidth(), enabled = vm.prep != null) }
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
    val top = p.top3
    val first = top.first()
    SpeakOnce("나의 흥미 유형은 ${top.joinToString(", ") { it.label }}이에요.")

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("나의 흥미 유형", style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(4.dp))
            Text(p.code, style = OnRoadType.Hero.copy(fontSize = 40.sp, letterSpacing = 2.sp), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            // 세 유형: 같은 크기 칸
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                top.forEach { r ->
                    DataCard(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(r.name, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(r.label, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(first.description, style = OnRoadType.Body3, color = OnRoadColors.TextSecondary)
            if (top.size > 1) {
                Text(
                    "${top.drop(1).joinToString(", ") { it.label }} 성향도 함께 있어요.",
                    style = OnRoadType.Body3,
                    color = OnRoadColors.TextSecondary,
                )
            }
            notes.forEach { n ->
                Spacer(Modifier.height(4.dp))
                Text(n, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }

            // 유형별 점수: 막대 (내 세 유형은 진하게)
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SectionTitle("유형별 점수")
            Spacer(Modifier.height(12.dp))
            DataCard(Modifier.fillMaxWidth()) {
                val ranked = Riasec.entries.sortedByDescending { p.scores[it] ?: 0 }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ranked.forEach { r ->
                        val full = ((p.asked[r] ?: 5) * 2).coerceAtLeast(1)
                        val pct = ((p.scores[r] ?: 0) * 100 / full).coerceIn(0, 100)
                        ScoreBar(r.label, pct, strong = r in top)
                    }
                }
            }

            // 근거
            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            SectionTitle("이렇게 판단했어요")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                top.take(2).forEach { r ->
                    val liked = p.liked[r].orEmpty()
                    DataCard(Modifier.fillMaxWidth()) {
                        CardTitle(
                            "${r.label} ${p.asked[r] ?: 5}문항 중 ${p.likes[r] ?: 0}개 좋아요",
                            if (liked.isEmpty()) r.strength else "${liked.take(3).joinToString(", ")}를 좋아한다고 답했어요",
                        )
                    }
                }
                if (p.values.isNotEmpty() || p.prep != null) {
                    DataCard(Modifier.fillMaxWidth()) {
                        CardTitle("고른 조건", listOfNotNull(
                            p.values.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label },
                            p.prep?.label,
                            p.place?.takeIf { it != WorkPlace.ANY }?.let { "${it.label}에서 일하고 싶어요" },
                        ).joinToString("\n"))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar {
            OnRoadButton("관심 분야 고르기", onJobs, Modifier.fillMaxWidth(), loading = loading)
            Spacer(Modifier.height(6.dp))
            Text(
                "다시 해 보기",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().tap(onClick = onRestart).padding(vertical = 8.dp),
            )
        }
    }
}

/** 유형 점수 막대: 처음 그릴 때 0에서 차오른다 */
@Composable
private fun ScoreBar(label: String, pct: Int, strong: Boolean) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val w by androidx.compose.animation.core.animateFloatAsState(
        if (shown) pct / 100f else 0f,
        androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "score",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OnRoadType.Caption, color = if (strong) OnRoadColors.TextPrimary else OnRoadColors.TextTertiary, modifier = Modifier.width(48.dp))
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(OnRoadShapes.Badge)
                .background(OnRoadColors.SurfaceMuted),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .fillMaxHeight()
                    .clip(OnRoadShapes.Badge)
                    .background(if (strong) OnRoadColors.Primary else OnRoadColors.TextQuaternary),
            )
        }
        Text(
            "$pct",
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            color = if (strong) OnRoadColors.TextPrimary else OnRoadColors.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
    }
}

/* ---------------------------- 직업 ---------------------------- */

/** 직업 적합도 (%): 직업 유형 점수 합 / 최고점 기준 (서버나 추천에서 match 를 안 줄 때) */
private fun matchPercent(job: JobRecommendation, p: InterestProfile?): Int {
    if (p == null || job.types.isEmpty()) return 0
    val sum = job.types.sumOf { p.scores[it] ?: 0 }
    return (sum * 100f / (job.types.size * p.max)).toInt().coerceIn(40, 99)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsPage(vm: InterestViewModel, onDone: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    val suggested = vm.jobs.take(8)
    val extra = vm.tags.filter { t -> suggested.none { it.name == t.name } }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("관심 있는 분야를\n${TAG_MAX}개까지 골라 주세요", style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text("고른 분야로 자격증, 훈련, 받을 수 있는 지원을 시기별로 정리해요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)

            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("추천 분야", style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                Text("${vm.tags.size} / $TAG_MAX", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggested.forEach { j -> ChoiceChip("#${j.name}", vm.tags.any { it.name == j.name }) { vm.toggleTag(j) } }
                extra.forEach { t -> ChoiceChip("#${t.name}", true) { vm.removeTag(t) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "직업 자세히 보기 ›",
                style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium),
                color = OnRoadColors.TextSecondary,
                modifier = Modifier.tap(onClick = vm::showJobs).padding(vertical = 6.dp),
            )

            Spacer(Modifier.height(28.dp))
            Text("직접 추가", style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnRoadTextField(custom, { custom = it.take(20) }, Modifier.weight(1f), placeholder = "분야나 직업 이름")
                Spacer(Modifier.width(8.dp))
                OutlinePill("추가") { if (vm.addTag(custom)) custom = "" }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar { OnRoadButton("로드맵 만들기", { vm.saveTags(onDone) }, Modifier.fillMaxWidth(), enabled = vm.tags.isNotEmpty(), loading = vm.saving) }
    }
}

@Composable
private fun JobsPage(vm: InterestViewModel) {
    val profile = vm.profile
    Page {
        PageHead("이런 직업은", "어때요?", "흥미 유형, 중요한 것, 준비 기간이 맞는 순서예요")
        Spacer(Modifier.height(20.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JobFilter.entries.forEach { f -> ChoiceChip(f.label, vm.filter == f) { vm.filter = f } }
        }
        Spacer(Modifier.height(14.dp))
        val list = vm.filteredJobs
        Appear(vm.filter) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { j ->
                    val pct = j.match ?: matchPercent(j, profile)
                    DataCard(Modifier.fillMaxWidth(), onClick = { vm.openJob(j) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(j.name, style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text(j.reason.ifBlank { j.description }, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOfNotNull(
                                        j.types.joinToString("") { it.name }.takeIf { it.isNotEmpty() },
                                        j.prep?.short,
                                        j.certTag,
                                    ).forEach { OnRoadTag(it) }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("$pct%", style = OnRoadType.Title2, color = OnRoadColors.TextPrimary)
                                Text("잘 맞아요", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                            }
                        }
                    }
                }
                if (list.isEmpty()) {
                    Text("조건에 맞는 직업이 없어요. 다른 조건을 골라 보세요.", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                }
            }
        }
    }
}

/* ---------------------------- 직업 상세 ---------------------------- */

@Composable
private fun JobDetailPage(
    vm: InterestViewModel,
    job: JobRecommendation,
    onCareer: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    onPolicy: (String) -> Unit,
    onPractice: (String) -> Unit,
) {
    val detail = vm.detail
    val ext = vm.extJob
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(job.name, style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            (job.description.ifBlank { ext?.summary.orEmpty() }).takeIf { it.isNotBlank() }?.let {
                Text(it, style = OnRoadType.Body3, color = OnRoadColors.TextSecondary)
            }

            // 탭: 받은 정보만 (먼저 온 탭부터 생긴다)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.tabs.forEach { t -> ChoiceChip(t.label, vm.tab == t) { vm.tab = t } }
            }

            when {
                vm.detailLoading -> {
                    Spacer(Modifier.height(OnRoadDimens.BlockGap))
                    LoadingState("직업 정보를 불러오고 있어요")
                }
                else -> FadeSwitch(vm.tab) { tab ->
                    Column {
                        when (tab) {
                            JobTab.JOB -> {
                                JobTabContent(detail, ext, vm.supports, onPolicy, onPractice, onOpenPolicy)
                                SimilarJobs(vm.similarJobs(job), ext?.relatedJobs.orEmpty(), vm::openSimilar, vm::openRelated)
                            }
                            JobTab.DUTY -> DutyTab(vm.duties)
                            JobTab.EXAM -> vm.exams?.let { ExamTab(it) }
                            JobTab.EDU -> EduTab(vm)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar {
            OnRoadButton("이 직업을 관심 직업으로 정하기", { vm.pickJob(job, onCareer) }, Modifier.fillMaxWidth(), loading = vm.loading)
        }
    }
}

@Composable
private fun JobTabContent(
    detail: JobDetail?,
    ext: ExternalJob?,
    supports: List<Policy>,
    onPolicy: (String) -> Unit,
    onPractice: (String) -> Unit,
    onOpenPolicy: (String) -> Unit,
) {
    val works = ext?.works?.takeIf { it.isNotEmpty() } ?: detail?.tasks.orEmpty()
    if (works.isNotEmpty()) {
        DetailSection("하는 일") { DataCard(Modifier.fillMaxWidth()) { Bullets(works.take(5)) } }
    }
    // 한눈에 보기: 공공 직업 정보가 있을 때만
    if (ext != null && (ext.wage != null || ext.forecast != null || ext.majors.isNotEmpty())) {
        DetailSection("한눈에 보기") {
            DataCard(Modifier.fillMaxWidth()) {
                ext.wage?.let { JobInfoRow("평균 연봉", it) }
                ext.forecast?.let { JobInfoRow("일자리 전망", it) }
                ext.satisfaction?.let { JobInfoRow("직업 만족도", it) }
                if (ext.majors.isNotEmpty()) JobInfoRow("관련 학과", ext.majors.take(3).joinToString(", "))
            }
            SourceText(ext.source)
        }
    }
    val certs = (detail?.certificates.orEmpty() + ext?.certificates.orEmpty()).map { CareerInfoRepo.cleanCert(it) }.distinct()
    if (certs.isNotEmpty()) {
        DetailSection("도움 되는 자격증") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LaunchedEffect(Unit) { com.project.on_road.data.CredentialStore.refresh() }
                certs.take(6).forEach { c ->
                    // 이미 딴 자격증 (설정 > 교육 · 자격증)
                    if (com.project.on_road.data.CredentialStore.holds(c)) {
                        OnRoadTag("$c · 취득함", style = TagStyle.PrimarySoft, textStyle = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium))
                    } else {
                        OnRoadTag(c, textStyle = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
    // 되는 길: 앱의 단계 (공공 정보의 설명은 아래 작은 글로)
    val path = detail?.path.orEmpty()
    if (path.isNotEmpty()) {
        DetailSection("이렇게 준비해요") {
            DataCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    path.forEachIndexed { i, line ->
                        Row(verticalAlignment = Alignment.Top) {
                            RoadmapMark(i + 1, done = false, current = i == 0, size = 22)
                            Spacer(Modifier.width(12.dp))
                            Text(line, style = OnRoadType.Caption, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f).padding(top = 2.dp))
                        }
                    }
                }
            }
            if (ext != null && ext.way.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Bullets(ext.way.take(2))
                SourceText(ext.source)
            }
        }
    }
    // 받을 수 있는 지원: 실제 정책 카드 (못 받았으면 검색으로)
    DetailSection("받을 수 있는 지원") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            supports.forEach { p ->
                InfoCard(Modifier.fillMaxWidth(), onClick = { onOpenPolicy(p.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardTitle(
                            p.name,
                            p.amountShort.ifBlank { p.easySummary.ifBlank { p.agency } }.lineSequence().firstOrNull(),
                            Modifier.weight(1f),
                        )
                        ExpandChevron(false)
                    }
                }
            }
            if (supports.isEmpty()) {
                LinkRow("취업, 교육 지원 찾아보기", "정책 검색에서 분야별로 볼 수 있어요") { onPolicy("") }
            }
        }
    }
    if (detail?.practices?.isNotEmpty() == true) {
        DetailSection("미리 연습해요") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.practices.forEach { pr -> LinkRow(pr.title, "상황 연습") { onPractice(pr.scenarioId) } }
            }
        }
    }
}

/** 직무: NCS 능력단위. 누르면 필요한 지식과 기술이 펼쳐진다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DutyTab(duties: List<DutyInfo>) {
    DetailSection("실제로 하는 업무") {
        Text("국가직무능력표준(NCS) 기준이에요", style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            duties.forEach { d ->
                var open by remember(d.unit) { mutableStateOf(false) }
                val toggle: () -> Unit = { open = !open }
                DataCard(Modifier.fillMaxWidth(), onClick = if (d.skills.isNotEmpty()) toggle else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardTitle(d.unit, d.definition?.let { shortDef(d.unit, it) }, Modifier.weight(1f))
                        if (d.skills.isNotEmpty()) Chevron(open)
                    }
                    ExpandArea(open) {
                        Spacer(Modifier.height(12.dp))
                        Text("필요한 지식과 기술", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.skills.forEach { OnRoadTag(it) }
                        }
                    }
                }
            }
        }
        SourceText("고용24 직무정보")
    }
}

/**
 * NCS 정의를 짧게: 앞의 '능력단위 이름 + 이란 · 은 · 는'을 떼고, 끝의 '…하는 능력이다'는 '…하는 일'로.
 * "빵류제품 반죽익힘이란 제품의 특성에 맞게 … 익힘을 하는 능력이다." → "제품의 특성에 맞게 … 익힘을 하는 일"
 */
private fun shortDef(unit: String, def: String): String {
    var d = def.trim().removeSuffix(".").trim()
    // 이름이 그대로 앞에 오면 떼고, 조금 다르게 시작해도 30자 안의 첫 '이란 · 란 · 은 · 는'까지는 주어로 보고 뗀다
    val name = Regex("""^\s*""" + Regex.escape(unit.trim()) + """\s*(이란|란|은|는|이라 함은)\s+""")
    d = d.replace(name, "")
    Regex("""^(.{1,30}?)(이란|이라 함은)\s+""").find(d)?.let { d = d.substring(it.range.last + 1) }
    d = d.replace(Regex("""(하는|할 수 있는)\s*능력이다$"""), "하는 일")
        .replace(Regex("""\s*능력이다$"""), " 일")
        .replace(Regex("""이다$"""), "")
    return d.trim()
}

@Composable
private fun Chevron(open: Boolean) {
    val r by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, label = "chev")
    androidx.compose.material3.Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        null,
        tint = OnRoadColors.TextQuaternary,
        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = r },
    )
}

/** 시험 일정: 다가오는 회차, 일정에 넣기 */
@Composable
private fun ExamTab(s: ExamSchedule) {
    val today = remember { java.time.LocalDate.now() }
    DetailSection("${s.cert} 다가오는 시험") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.rounds.forEach { round -> ExamRoundCard(round, today) }
        }
        s.note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
        }
        SourceText("큐넷 국가자격 시험일정")
    }
}

/** 훈련 과정: 내 지역 먼저. 누르면 소개(기관 · 기간 · 비용)를 받아 펼친다 */
@Composable
private fun EduTab(vm: InterestViewModel) {
    val uri = LocalUriHandler.current
    DetailSection("내일배움카드로 들을 수 있어요") {
        LinkRow("국민내일배움카드 신청하기", "고용24 발급 안내, 훈련비 일부를 지원받아요") {
            runCatching { uri.openUri("https://www.work24.go.kr/hr/h/a/1100/selectIssuGudn.do") }
        }
        Spacer(Modifier.height(16.dp))
        val caption = when {
            vm.district != null && vm.trainings.any { it.sameDistrict } -> "${vm.district} 과정부터 보여 줘요"
            vm.myRegion != null && vm.trainings.any { it.nearby } -> "${vm.myRegion} 과정부터 보여 줘요"
            else -> null
        }
        if (caption != null) {
            Text(caption, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.trainings.forEach { c ->
                TrainingCourseCard(c, vm.trainingDetails[c.id + c.round]) { vm.loadTraining(c) }
            }
        }
        SourceText("고용24 국민내일배움카드 훈련과정")
    }
}

/** 비슷한 직업: 추천 목록에서 유형이 겹치는 직업 + 공공 직업 정보의 관련 직업 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimilarJobs(
    similar: List<JobRecommendation>,
    related: List<String>,
    onOpen: (JobRecommendation) -> Unit,
    onRelated: (String) -> Unit,
) {
    val names = similar.map { it.name }.toSet()
    val extra = related.filter { it !in names }
    if (similar.isEmpty() && extra.isEmpty()) return
    DetailSection("비슷한 직업도 살펴봐요") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            similar.forEach { j ->
                InfoCard(Modifier.fillMaxWidth(), onClick = { onOpen(j) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardTitle(j.name, j.description, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOfNotNull(j.types.joinToString("") { it.name }.takeIf { it.isNotEmpty() }, j.prep?.short).forEach { OnRoadTag(it) }
                        }
                        ExpandChevron(false)
                    }
                }
            }
        }
        if (extra.isNotEmpty()) {
            Spacer(Modifier.height(if (similar.isEmpty()) 0.dp else 16.dp))
            Text("관련 직업", style = OnRoadType.Caption.copy(fontWeight = FontWeight.SemiBold), color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                extra.forEach { name -> OutlinePill(name) { onRelated(name) } }
            }
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
    DataCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 16.dp, bottom = 16.dp)) {
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
