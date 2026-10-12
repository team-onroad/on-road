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

data class RoleplayFeedback(
    val good: List<String>,
    val missed: List<String>,
    val documents: List<String>,
    val better: List<Pair<String, String>>
)

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
    val outline: String? = null
)

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

// ───────────── API 계약 ─────────────

/** AI 성장 코치 기능 API. 지금은 FakeCoachApi가 구현하고, 백엔드 준비 후 RemoteCoachApi로 교체한다. */
interface CoachApi {
    suspend fun interestQuestions(stage: UserStage): List<InterestQuestion>
    suspend fun analyzeInterest(userId: String, answers: List<InterestOption>, freeText: String): InterestProfile
    suspend fun recommendJobs(userId: String, profile: InterestProfile, stage: UserStage): List<JobRecommendation>
    suspend fun buildRoadmap(userId: String, job: JobRecommendation, stage: UserStage): Roadmap

    suspend fun roleplayScenarios(stage: UserStage): List<RoleplayScenario>
    suspend fun roleplayOpening(scenarioId: String, stage: UserStage): RoleplayTurn
    suspend fun roleplayReply(userId: String, scenarioId: String, stage: UserStage, history: List<RoleplayTurn>): RoleplayTurn
    suspend fun roleplayFeedback(userId: String, scenarioId: String, history: List<RoleplayTurn>): RoleplayFeedback

    suspend fun formTemplates(stage: UserStage): List<FormTemplate>
    suspend fun analyzeTemplate(templateId: String): FormDraft
    suspend fun analyzeUpload(userId: String, bytes: ByteArray, mimeType: String): FormDraft

    suspend fun generateDocument(userId: String, type: DocType): GeneratedDoc
}
