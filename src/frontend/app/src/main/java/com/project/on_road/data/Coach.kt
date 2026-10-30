package com.project.on_road.data

// ───────────── 흥미 탐색 (RIASEC) ─────────────

enum class Riasec(val label: String, val kidLabel: String, val description: String) {
    R("현실형", "만들기를 좋아해요", "몸을 움직이고 도구나 기계를 다루는 일을 좋아해요"),
    I("탐구형", "궁금한 게 많아요", "원리를 알아내고 문제를 풀어 보는 걸 좋아해요"),
    A("예술형", "표현하는 걸 좋아해요", "그림, 음악, 글처럼 생각을 자유롭게 표현하는 걸 좋아해요"),
    S("사회형", "도와주는 걸 좋아해요", "사람을 돕고 가르치고 함께하는 걸 좋아해요"),
    E("진취형", "앞장서는 걸 좋아해요", "사람들을 이끌고 새로운 일을 시작하는 걸 좋아해요"),
    C("관습형", "정리하는 걸 좋아해요", "계획을 세우고 꼼꼼하게 정리하는 걸 좋아해요")
}

data class InterestOption(val text: String, val types: List<Riasec>)

data class InterestQuestion(val id: String, val prompt: String, val options: List<InterestOption>)

data class InterestProfile(val scores: Map<Riasec, Int>) {
    val top: List<Riasec> get() = scores.entries.sortedByDescending { it.value }.take(2).map { it.key }
    val max: Int get() = scores.values.maxOrNull()?.coerceAtLeast(1) ?: 1
}

data class JobRecommendation(
    val id: String,
    val name: String,
    val description: String,
    val reason: String,
    val types: List<Riasec>
)

data class RoadmapStep(val period: String, val title: String, val detail: String)

data class Roadmap(val job: JobRecommendation, val steps: List<RoadmapStep>, val note: String)

// ───────────── 롤플레이 (면접 포함) ─────────────

enum class RoleplayLevel(val label: String) {
    EASY("쉬움"),
    NORMAL("보통"),
    HARD("실전")
}

data class RoleplayScenario(
    val id: String,
    val title: String,
    val category: String,
    val partner: String,
    val setting: String,
    val goal: String,
    val level: RoleplayLevel,
    val stages: Set<UserStage>
)

data class RoleplayTurn(val fromUser: Boolean, val text: String)

/** 상대역의 대답 (3.18). finished: 상대역이 대화를 마무리함 → 바로 피드백 */
data class RoleplayReply(val text: String, val finished: Boolean)

data class RoleplayFeedback(
    val good: List<String>,
    val missed: List<String>,
    val documents: List<String>,
    val better: List<Pair<String, String>>,
    /** 성장 기록에 남겼으면 기록 id, 아니면 null */
    val roleplayId: Long? = null,
    /** 0~100 (돌발 상황 대처는 2배). 서버가 안 주면 null */
    val score: Int? = null,
    val grade: String? = null,
    /** 항목별 결과: 기본 · 이번 전개 · 돌발 상황 */
    val items: List<RoleplayCheck> = emptyList(),
    /** 이번 전개 이름 (기본 상황이면 null) */
    val situation: String? = null,
    /** 놓친 항목을 이렇게 말해 보세요 */
    val retryLines: List<String> = emptyList(),
    /** 이어서 해 볼 것 (정책 찾기 · 다른 연습 · 생활비 · 서류 · 상담) */
    val nextSteps: List<NextStep> = emptyList(),
)

/**
 * 한 기능이 끝난 뒤 이어서 해 볼 것 (기능끼리 연결).
 * kind: search(정책 찾기) · practice(실전 연습) · budget(생활비 배분) · month(한 달 살아보기) · forms(서류 작성) · chat(AI 상담)
 */
data class NextStep(val kind: String, val label: String, val query: String? = null, val scenarioId: String? = null)

/** 대화 중 힌트: 이번에 챙길 것과 예시 문장. done 이면 다 챙김 (마무리 인사) */
data class RoleplayHint(val done: Boolean, val label: String, val example: String, val kind: String, val remaining: Int)

/** 자립 준비도 (6개 영역) */
data class Readiness(
    val score: Int,
    val level: String,
    val areas: List<ReadinessArea>,
    val weakest: String?,
    val nextSteps: List<NextStep>,
)

data class ReadinessArea(
    val key: String,
    val label: String,
    val score: Int,
    val doneCount: Int,
    val total: Int,
    val items: List<Pair<String, Boolean>>,
    val nextStep: NextStep?,
)

/** 피드백 항목 하나. kind: basic | situation | surprise */
data class RoleplayCheck(val label: String, val ok: Boolean, val kind: String, val example: String?)

/**
 * 이번 연습의 전개 (같은 상황 다른 전개 + 난이도별 돌발 상황). 서버가 첫 대사와 함께 정해 주고,
 * 앱은 대화·피드백 요청에 그대로 보낸다. 다시 연습하기는 같은 전개(variantId)로 돌발 상황만 새로 받는다
 */
data class RoleplayPlan(
    val variantId: String? = null,
    val situation: String? = null,
    val surpriseIds: List<String> = emptyList(),
    val surpriseCount: Int = 0,
) {
    companion object {
        val NONE = RoleplayPlan()
    }
}

data class RoleplayStart(val opening: RoleplayTurn, val plan: RoleplayPlan)

// ───────────── 서류 양식 도우미 ─────────────

data class FormTemplate(
    val id: String,
    val name: String,
    val description: String,
    val stages: Set<UserStage>
)

data class FormField(
    val id: String,
    val label: String,
    val easyLabel: String,
    val guide: String,
    val caution: String? = null,
    val example: String? = null,
    val multiline: Boolean = false,
    /** 자기소개·포트폴리오처럼 AI가 글의 틀을 만들어 주는 항목 */
    val essay: Boolean = false,
    /** 글의 틀 (줄마다 하나). 없으면 null */
    val outline: List<String>? = null
) {
    /** 입력칸에 넣을 글의 틀: "1) 지금 나의 상황\n2) …" */
    val outlineText: String? get() = outline?.mapIndexed { i, line -> "${i + 1}) $line" }?.joinToString("\n")
}

data class FormDraft(
    val templateId: String?,
    val title: String,
    val fields: List<FormField>,
    /** true: 원래 양식 그대로 채워서 내보내기 가능 (자주 쓰는 양식) */
    val originalExport: Boolean
)

// ───────────── 이력서·자기소개서·활동 기록 ─────────────

enum class DocType(val label: String, val stages: Set<UserStage>) {
    ACTIVITY_RECORD("진학용 활동 기록", setOf(UserStage.TEEN)),
    RESUME("이력서", setOf(UserStage.YOUNG_ADULT)),
    COVER_LETTER("자기소개서", setOf(UserStage.TEEN, UserStage.YOUNG_ADULT))
}

/** source: 이 문장의 근거가 된 앱 기록. null이면 사용자가 직접 채울 자리 */
data class DocSentence(val text: String, val source: String?)

data class DocSection(val title: String, val sentences: List<DocSentence>)

data class GeneratedDoc(val type: DocType, val sections: List<DocSection>, val notice: String)

/** 초안에 쓰는 앱에만 있는 기록 (3.22 app_records) */
data class AppRecords(
    val savedPolicyCount: Int,
    /** 흥미 탐색 결과 문장. 없으면 null */
    val interestSummary: String?,
) {
    companion object {
        /** 흥미 탐색 첫 유형으로 만든 문장 (예: "저는 사람을 돕고 … 좋아합니다.") */
        fun interestSentence(types: List<Riasec>): String? =
            types.firstOrNull()?.let { "저는 ${it.description.replace("좋아해요", "좋아합니다")}." }
    }
}

// ───────────── API 계약 ─────────────

/**
 * AI 성장 코치 기능 API.
 * RemoteCoachApi: 롤플레잉(3.16~3.19)·서류 양식(3.20·3.21)·초안(3.22)은 서버,
 * 흥미 탐색·직업 추천·로드맵·양식 올리기는 서버 API가 없어서 FakeCoachApi 로 처리한다.
 */
interface CoachApi {
    suspend fun interestQuestions(stage: UserStage): List<InterestQuestion>
    suspend fun analyzeInterest(userId: String, answers: List<InterestOption>, freeText: String): InterestProfile
    suspend fun recommendJobs(userId: String, profile: InterestProfile, stage: UserStage): List<JobRecommendation>
    suspend fun buildRoadmap(userId: String, job: JobRecommendation, stage: UserStage): Roadmap

    suspend fun roleplayScenarios(stage: UserStage): List<RoleplayScenario>
    suspend fun roleplayOpening(scenarioId: String, stage: UserStage): RoleplayTurn
    /** history: 첫 대사부터 방금 보낸 내 말까지 (마지막이 내 말) */
    suspend fun roleplayReply(userId: String, scenarioId: String, stage: UserStage, history: List<RoleplayTurn>): RoleplayReply
    /** save = true 면 피드백 결과를 성장 기록에 남긴다 (대화 내용은 저장하지 않음) */
    suspend fun roleplayFeedback(userId: String, scenarioId: String, history: List<RoleplayTurn>, save: Boolean = false): RoleplayFeedback

    /** 첫 대사 + 이번 전개. variantId 를 주면 그 전개로 (다시 연습하기) */
    suspend fun roleplayStart(scenarioId: String, stage: UserStage, variantId: String? = null): RoleplayStart =
        RoleplayStart(roleplayOpening(scenarioId, stage), RoleplayPlan.NONE)

    suspend fun roleplayReplyWith(
        userId: String, scenarioId: String, stage: UserStage, history: List<RoleplayTurn>, plan: RoleplayPlan,
    ): RoleplayReply = roleplayReply(userId, scenarioId, stage, history)

    suspend fun roleplayFeedbackWith(
        userId: String, scenarioId: String, history: List<RoleplayTurn>, save: Boolean, plan: RoleplayPlan,
    ): RoleplayFeedback = roleplayFeedback(userId, scenarioId, history, save)

    /** 대화 중 힌트. 서버가 없으면 null */
    suspend fun roleplayHint(scenarioId: String, history: List<RoleplayTurn>, plan: RoleplayPlan): RoleplayHint? = null

    suspend fun formTemplates(stage: UserStage): List<FormTemplate>
    suspend fun analyzeTemplate(templateId: String): FormDraft
    suspend fun analyzeUpload(userId: String, bytes: ByteArray, mimeType: String): FormDraft

    suspend fun generateDocument(userId: String, type: DocType, records: AppRecords): GeneratedDoc
}
