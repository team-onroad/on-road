package com.project.on_road.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.REGIONS
import com.project.on_road.data.UserStage
import com.project.on_road.data.remote.ApiException
import com.project.on_road.ui.child.ChildOnboardingScreen
import com.project.on_road.ui.child.ChildProfile
import com.project.on_road.ui.components.IconTile
import com.project.on_road.ui.components.NoticeBox
import com.project.on_road.ui.components.NoticeTone
import com.project.on_road.ui.components.OnRoadButton
import com.project.on_road.ui.components.OnRoadLogo
import com.project.on_road.ui.components.OnRoadSelectBox
import com.project.on_road.ui.components.OnRoadTextField
import com.project.on_road.ui.components.ProgressBar
import com.project.on_road.ui.components.SelectCard
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.Period
import java.time.YearMonth

/* ================================================================== */
/*  온보딩                                                              */
/*  - 공통: 로고 인트로 → 누가 쓰나요? → 이름 → 생년월일 → 지역 → 상황 → 완료 */
/*  - 아동을 고르면: ui/child/ChildOnboardingScreen.kt (테마 전환 → 인사 →        */
/*    체크리스트 → 길 소개)로 넘어간다. 상황 질문은 생략(시설 생활로 저장).          */
/* ================================================================== */

enum class OnboardStep { INTRO, WHO, NAME, BIRTH, REGION, STATUS, DONE }

/** 단계별 만 나이 범위 (서버 규칙: 10세 이하 아동, 11~17세 청소년, 18세 이상 자립준비청년) */
private fun ageRange(stage: UserStage): IntRange = when (stage) {
    UserStage.CHILD -> 5..10
    UserStage.TEEN -> 11..17
    UserStage.YOUNG_ADULT -> 18..39
}

class OnboardingViewModel : ViewModel() {
    var step by mutableStateOf(OnboardStep.INTRO)
        private set
    /** 앞으로 가는 중인지 (전환 애니메이션 방향) */
    var forward by mutableStateOf(true)
        private set

    var who by mutableStateOf<UserStage?>(null)
        private set
    var name by mutableStateOf("")
    var birthYear by mutableStateOf<Int?>(null)
        private set
    var birthMonth by mutableStateOf<Int?>(null)
        private set
    var birthDay by mutableStateOf<Int?>(null)
        private set
    var region by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<LivingStatus?>(null)
        private set

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 서버가 정한 최종 단계 (완료 화면 분기) */
    var resultStage by mutableStateOf<UserStage?>(null)
        private set
    /** 아동 캐릭터가 기뻐하는 신호 (값이 바뀔 때마다 한 번 반응) */
    var cheer by mutableIntStateOf(0)
        private set

    val isChild: Boolean get() = who == UserStage.CHILD

    private val flow: List<OnboardStep>
        get() = if (isChild) {
            listOf(OnboardStep.INTRO, OnboardStep.WHO, OnboardStep.NAME, OnboardStep.BIRTH, OnboardStep.REGION, OnboardStep.DONE)
        } else {
            listOf(OnboardStep.INTRO, OnboardStep.WHO, OnboardStep.NAME, OnboardStep.BIRTH, OnboardStep.REGION, OnboardStep.STATUS, OnboardStep.DONE)
        }

    /** 질문 단계만 (진행바용) */
    private val questions: List<OnboardStep> get() = flow.filter { it != OnboardStep.INTRO && it != OnboardStep.DONE }

    val progress: Float
        get() {
            val i = questions.indexOf(step)
            return if (i < 0) 0f else (i + 1) / questions.size.toFloat()
        }

    val isLastQuestion: Boolean get() = questions.lastOrNull() == step

    val canGoBack: Boolean get() = step != OnboardStep.INTRO && step != OnboardStep.DONE && !loading

    // ---------------- 생년월일 ----------------

    private val today: LocalDate get() = LocalDate.now()

    /** 고른 단계의 나이 범위에 맞는 출생 연도 (최근 연도부터) */
    val years: List<Int>
        get() {
            val range = ageRange(who ?: UserStage.YOUNG_ADULT)
            return ((today.year - range.last - 1)..(today.year - range.first)).reversed().toList()
        }
    val months: List<Int> = (1..12).toList()
    val days: List<Int>
        get() = (1..YearMonth.of(birthYear ?: 2000, birthMonth ?: 1).lengthOfMonth()).toList()

    val birthDate: LocalDate?
        get() {
            val y = birthYear ?: return null
            val m = birthMonth ?: return null
            val d = birthDay ?: return null
            return runCatching { LocalDate.of(y, m, d) }.getOrNull()?.takeIf { !it.isAfter(today) }
        }

    private val age: Int? get() = birthDate?.let { Period.between(it, today).years }

    /** 생년월일이 고른 단계 나이와 안 맞으면 안내 문구 */
    val birthHint: String?
        get() {
            val a = age ?: return null
            val range = ageRange(who ?: return null)
            if (a in range) return null
            return when (who) {
                UserStage.CHILD -> "고른 날짜가 아동(만 10세 이하) 나이가 아니야.\n다시 확인해 줘!"
                UserStage.TEEN -> "청소년은 만 11~17세예요.\n생년월일을 다시 확인해 주세요."
                else -> "자립준비청년은 만 18세 이상이에요.\n생년월일을 다시 확인해 주세요."
            }
        }

    fun selectYear(y: Int) { birthYear = y; clampDay(); cheer++ }
    fun selectMonth(m: Int) { birthMonth = m; clampDay(); cheer++ }
    fun selectDay(d: Int) { birthDay = d; cheer++ }

    /** 2월 30일처럼 없는 날이 되면 그 달 마지막 날로 맞춘다. */
    private fun clampDay() {
        val d = birthDay ?: return
        val last = days.last()
        if (d > last) birthDay = last
    }

    // ---------------- 선택 ----------------

    fun selectWho(stage: UserStage) {
        who = stage
        // 아동이면 음성이 바로 켜지도록 앱 단계도 맞춘다 (가입 후 서버 값으로 다시 맞춤)
        AppState.updateStage(stage)
        // 범위 밖 연도는 지운다
        if (birthYear != null && birthYear !in years) {
            birthYear = null
            birthDay = null
        }
        cheer++
    }

    fun selectRegion(r: String) { region = r; cheer++ }
    fun selectStatus(s: LivingStatus) { status = s; cheer++ }

    fun canNext(): Boolean = when (step) {
        OnboardStep.INTRO, OnboardStep.DONE -> true
        OnboardStep.WHO -> who != null
        OnboardStep.NAME -> name.isNotBlank()
        OnboardStep.BIRTH -> birthDate != null && birthHint == null
        OnboardStep.REGION -> region != null
        OnboardStep.STATUS -> status != null
    }

    // ---------------- 이동 ----------------

    fun next() {
        if (!canNext() || loading) return
        error = null
        if (isLastQuestion) {
            submit()
            return
        }
        val i = flow.indexOf(step)
        if (i in 0 until flow.lastIndex) {
            forward = true
            step = flow[i + 1]
        }
    }

    fun back() {
        if (!canGoBack) return
        error = null
        val i = flow.indexOf(step)
        if (i > 0) {
            forward = false
            step = flow[i - 1]
        }
    }

    /** 아동 온보딩에서 받은 값으로 가입 */
    fun submitChild(p: ChildProfile) {
        if (loading) return
        error = null
        name = p.name
        birthYear = p.birthDate.year
        birthMonth = p.birthDate.monthValue
        birthDay = p.birthDate.dayOfMonth
        region = p.region
        submit()
    }

    private fun submit() {
        val birth = birthDate ?: return
        val reg = region ?: return
        // 아동은 상황 질문 없이 '시설 생활'로 저장
        val st = if (isChild) LivingStatus.IN_FACILITY else status ?: return
        viewModelScope.launch {
            loading = true
            try {
                val profile = AppContainer.api.createUser(name.trim(), birth, reg, st)
                AppContainer.session.save(profile.userId, profile.name, profile.stage)
                AppState.updateStage(profile.stage)
                resultStage = profile.stage
                forward = true
                step = OnboardStep.DONE
            } catch (e: ApiException) {
                error = if (e.httpStatus == 422) "입력한 정보를 다시 확인해 주세요." else "잠시 후 다시 시도해 주세요."
            } catch (e: IOException) {
                error = "서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요."
            } catch (e: Exception) {
                error = "잠시 후 다시 시도해 주세요."
            } finally {
                loading = false
            }
        }
    }
}

/* ================================================================== */
/*  화면                                                                */
/* ================================================================== */

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = viewModel()) {
    BackHandler(enabled = vm.canGoBack) { vm.back() }

    // 인트로 / 질문 / 완료 세 덩어리는 서로 페이드로, 질문끼리는 옆으로 넘긴다
    val phase = when (vm.step) {
        OnboardStep.INTRO -> 0
        OnboardStep.DONE -> 2
        else -> 1
    }
    Box(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(380)) togetherWith fadeOut(tween(240)) },
            label = "onboardPhase",
        ) { p ->
            when (p) {
                0 -> IntroPage(onStart = vm::next)
                // 아동을 고르면 이름부터는 아동 전용 화면 (ui/child/ChildOnboarding.kt)
                // 아동을 고르면 테마가 바뀌고 아동 온보딩으로 (ui/child/ChildOnboardingScreen.kt)
                1 -> if (vm.isChild && vm.step != OnboardStep.WHO) {
                    ChildOnboardingScreen(
                        onDone = vm::submitChild,
                        onBack = vm::back,
                        loading = vm.loading,
                        error = vm.error,
                    )
                } else {
                    QuestionPage(vm)
                }
                // 아동은 길 소개의 '시작하기'가 끝이라 완료 화면 없이 바로 홈으로
                else -> if (vm.isChild) LaunchedEffect(Unit) { onDone() } else DonePage(vm, onDone)
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  인트로 (로고)                                                        */
/* ------------------------------------------------------------------ */

@Composable
private fun IntroPage(onStart: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val drop = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val button = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(150)
        reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        // 점이 위에서 부드럽게 내려와 제자리에
        launch { drop.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        delay(350)
        text.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        button.animateTo(1f, tween(350))
    }

    // 다 나타난 뒤 점이 숨쉬듯 살짝 커졌다 작아짐
    val idle = rememberInfiniteTransition(label = "logoIdle")
    val pulse by idle.animateFloat(
        1f, 1.1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dotPulse",
    )

    Column(
        Modifier.fillMaxSize().padding(horizontal = 27.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        OnRoadLogo(
            size = 128.dp,
            reveal = reveal.value,
            dotDrop = drop.value,
            dotScale = if (drop.value >= 0.99f) pulse else 1f,
        )
        Spacer(Modifier.height(36.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = text.value
                translationY = (1f - text.value) * 36f
            },
        ) {
            Text("온로드", style = OnRoadType.Hero, color = OnRoadColors.TextStrong)
            Spacer(Modifier.height(8.dp))
            Text("너의 오늘도, 성장하는 길 위에", style = OnRoadType.Body2, color = OnRoadColors.TextTertiary)
        }
        Spacer(Modifier.weight(1.2f))
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = button.value
                translationY = (1f - button.value) * 24f
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "필요한 제도를 찾고, 쉽게 이해하고,\n하나씩 준비하는 길을 함께 걸어요.",
                style = OnRoadType.Caption,
                color = OnRoadColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            OnRoadButton(
                text = "시작하기",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
                textStyle = OnRoadType.Title3.copy(fontWeight = FontWeight.Medium),
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = button.value > 0.5f,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  질문                                                                */
/* ------------------------------------------------------------------ */

private data class Question(val title: String, val sub: String)

private fun question(step: OnboardStep): Question = when (step) {
    OnboardStep.WHO -> Question("누가 사용하나요?", "나이에 맞는 화면으로 안내해 드려요.")
    OnboardStep.NAME -> Question("이름을 알려 주세요", "앱에서 불러 드릴 이름이에요.")
    OnboardStep.BIRTH -> Question("생년월일을 선택해 주세요", "만 나이로 나에게 맞는 제도를 찾아요.")
    OnboardStep.REGION -> Question("지금 사는 지역은 어디인가요?", "시·도 기준으로 지원 정보를 골라 드려요.")
    OnboardStep.STATUS -> Question("지금 어떤 상황인가요?", "상황에 맞는 할 일과 제도를 먼저 보여 드려요.")
    else -> Question("", "")
}

/** 청소년·자립준비청년 질문 화면 (아동은 ChildOnboardingFlow) */
@Composable
private fun QuestionPage(vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize()) {
        // 상단: 뒤로 + 진행바
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 24.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::back, enabled = vm.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = OnRoadColors.TextPrimary.copy(alpha = 0.85f))
            }
            Spacer(Modifier.width(4.dp))
            ProgressBar(vm.progress, Modifier.weight(1f), height = 6.dp)
        }

        AnimatedContent(
            targetState = vm.step,
            transitionSpec = {
                val dir = if (vm.forward) 1 else -1
                (slideInHorizontally(tween(320)) { it * dir / 3 } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it * dir / 3 } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label = "onboardStep",
        ) { step ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val boxHeight = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = boxHeight)
                        .padding(start = 24.dp, end = 24.dp, top = boxHeight * 0.12f, bottom = 24.dp),
                ) {
                    val q = question(step)
                    Text(q.title, style = OnRoadType.Display, color = OnRoadColors.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(q.sub, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)
                    Spacer(Modifier.height(28.dp))
                    when (step) {
                        OnboardStep.WHO -> WhoStep(vm)
                        OnboardStep.NAME -> NameStep(vm)
                        OnboardStep.BIRTH -> BirthStep(vm)
                        OnboardStep.REGION -> ChipGrid(
                            items = REGIONS,
                            label = { it },
                            selected = vm.region,
                            columns = 4,
                            height = 46.dp,
                            onSelect = vm::selectRegion,
                        )
                        OnboardStep.STATUS -> StatusStep(vm)
                        else -> Unit
                    }
                }
            }
        }

        // 하단 버튼
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            vm.error?.let {
                NoticeBox(it, title = "시작하지 못했어요", tone = NoticeTone.Warning)
                Spacer(Modifier.height(12.dp))
            }
            OnRoadButton(
                text = if (vm.isLastQuestion) "완료" else "다음",
                onClick = vm::next,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
                textStyle = OnRoadType.Title3.copy(fontWeight = FontWeight.Medium),
                enabled = vm.canNext(),
                loading = vm.loading,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  단계별 입력                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun WhoStep(vm: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WhoCard(
            title = "아동",
            age = "만 10세 이하",
            description = "큰 글씨와 소리로 쉽게 시작해요",
            selected = vm.who == UserStage.CHILD,
            onClick = { vm.selectWho(UserStage.CHILD) },
        ) { IconTile(Icons.Outlined.ChildCare, size = 52.dp, iconSize = 26.dp, tint = OnRoadColors.Primary) }
        WhoCard(
            title = "청소년",
            age = "만 11~17세",
            description = "흥미를 찾고 진로를 준비해요",
            selected = vm.who == UserStage.TEEN,
            onClick = { vm.selectWho(UserStage.TEEN) },
        ) { IconTile(Icons.Outlined.Explore, size = 52.dp, iconSize = 26.dp, tint = OnRoadColors.Primary) }
        WhoCard(
            title = "자립준비청년",
            age = "만 18세 이상",
            description = "자립에 필요한 제도와 준비를 도와요",
            selected = vm.who == UserStage.YOUNG_ADULT,
            onClick = { vm.selectWho(UserStage.YOUNG_ADULT) },
        ) { IconTile(Icons.Outlined.Home, size = 52.dp, iconSize = 26.dp, tint = OnRoadColors.Primary) }
    }
}

@Composable
private fun WhoCard(
    title: String,
    age: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    visual: @Composable () -> Unit,
) {
    val shape = OnRoadShapes.Inner
    val border by animateColorAsState(if (selected) OnRoadColors.Primary else OnRoadColors.BorderInput, label = "whoBorder")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) OnRoadColors.SurfaceSubtle else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { visual() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = OnRoadType.Title3, color = OnRoadColors.TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(age, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }
            Spacer(Modifier.height(4.dp))
            Text(description, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
        }
    }
}

@Composable
private fun NameStep(vm: OnboardingViewModel) {
    OnRoadTextField(
        value = vm.name,
        onValueChange = { vm.name = it.take(50) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        placeholder = "이름을 입력해 주세요",
        showClear = true,
    )
}

/** 청소년·자립준비청년 생년월일: 드롭다운 3개 */
@Composable
private fun BirthStep(vm: OnboardingViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnRoadSelectBox(
            value = vm.birthYear?.let { "${it}년" }.orEmpty(),
            options = vm.years.map { "${it}년" },
            onSelect = { vm.selectYear(it.removeSuffix("년").toInt()) },
            modifier = Modifier.weight(1.4f),
            placeholder = "년",
        )
        OnRoadSelectBox(
            value = vm.birthMonth?.let { "${it}월" }.orEmpty(),
            options = vm.months.map { "${it}월" },
            onSelect = { vm.selectMonth(it.removeSuffix("월").toInt()) },
            modifier = Modifier.weight(1f),
            placeholder = "월",
        )
        OnRoadSelectBox(
            value = vm.birthDay?.let { "${it}일" }.orEmpty(),
            options = vm.days.map { "${it}일" },
            onSelect = { vm.selectDay(it.removeSuffix("일").toInt()) },
            modifier = Modifier.weight(1f),
            placeholder = "일",
        )
    }
    vm.birthHint?.let {
        Spacer(Modifier.height(16.dp))
        NoticeBox(it, tone = NoticeTone.Warning)
    }
}

@Composable
private fun StatusStep(vm: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LivingStatus.entries.forEach { s ->
            SelectCard(
                title = s.label,
                description = s.description,
                selected = vm.status == s,
                onClick = { vm.selectStatus(s) },
            )
        }
    }
}

/** 칸으로 나눈 선택 버튼 묶음 (지역, 아동 생년월일) */
@Composable
private fun <T> ChipGrid(
    items: List<T>,
    label: (T) -> String,
    selected: T?,
    columns: Int,
    height: Dp,
    onSelect: (T) -> Unit,
) {
    val gap = 8.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { item ->
                    GridChip(
                        text = label(item),
                        selected = item == selected,
                        height = height,
                        big = height >= 52.dp,
                        modifier = Modifier.weight(1f),
                    ) { onSelect(item) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GridChip(
    text: String,
    selected: Boolean,
    height: Dp,
    big: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = OnRoadShapes.Field
    val bg by animateColorAsState(if (selected) OnRoadColors.Primary else Color.White, tween(180), label = "chipBg")
    val fg by animateColorAsState(if (selected) Color.White else OnRoadColors.TextSecondary, tween(180), label = "chipFg")
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) OnRoadColors.Primary else OnRoadColors.BorderInput, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = (if (big) OnRoadType.Body1 else OnRoadType.Body2).copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
    }
}

/* ------------------------------------------------------------------ */
/*  완료                                                                */
/* ------------------------------------------------------------------ */

/** 청소년·자립준비청년 완료 화면 (아동은 ChildDonePage) */
@Composable
private fun DonePage(vm: OnboardingViewModel, onDone: () -> Unit) {
    val stage = vm.resultStage ?: vm.who ?: UserStage.YOUNG_ADULT
    val name = vm.name.trim()

    val show = remember { Animatable(0f) }
    LaunchedEffect(Unit) { show.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }

    val sub = if (stage == UserStage.TEEN) "흥미를 찾고 진로를 하나씩 준비해 볼까요?" else "필요한 제도를 찾고 차근차근 준비해 봐요."

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .graphicsLayer {
                alpha = show.value
                translationY = (1f - show.value) * 24f
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        OnRoadLogo(size = 96.dp)
        Spacer(Modifier.height(32.dp))
        Text("${name}님, 준비가 끝났어요", style = OnRoadType.Display, color = OnRoadColors.TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(sub, style = OnRoadType.Body2, color = OnRoadColors.TextTertiary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text(
            "${stage.label} 화면으로 시작해요",
            style = OnRoadType.Caption.copy(fontWeight = FontWeight.Bold),
            color = OnRoadColors.Primary,
            modifier = Modifier
                .clip(OnRoadShapes.Tag)
                .background(OnRoadColors.PrimarySoft)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        Spacer(Modifier.weight(1.2f))
        OnRoadButton(
            text = "시작하기",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            height = 56.dp,
            textStyle = OnRoadType.Title3.copy(fontWeight = FontWeight.Medium),
            trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
        )
        Spacer(Modifier.height(32.dp))
    }
}