package com.project.on_road.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/*
 * 앱 안에 쌓는 활동 기록 (서버 API가 없는 것들).
 * - 상담 대화, 검색·저장·생활비에서 나온 관심 분야 신호 → 관심사 프로필 → 다음 할 일 추천
 * - 실전 연습 결과, 흥미 탐색 이력, 로드맵 진행, 청소년 자립 기초 미션
 * - 아동: 기분 달력, 좋아하는 것 도감, 미션 기록장, 내 기록 타임라인
 * 모두 휴대폰 안(SharedPreferences, JSON)에만 저장하고 누구에게도 자동으로 보내지 않는다.
 * 데모 이후 서버 API가 생기면 이 파일의 저장 부분만 바꾸면 된다.
 */

// ───────────── 관심 분야 ─────────────

enum class Topic(val label: String, val searchQuery: String, private val words: List<String>) {
    HOUSING("주거", "자립준비청년 주거 지원 공공임대 전세임대",
        listOf("집", "월세", "전세", "임대", "주거", "보증금", "이사", "원룸", "기숙사", "lh", "관리비", "확정일자", "전입")),
    MONEY("생활비·금융", "자립수당 자립정착금 생활비 지원",
        listOf("돈", "생활비", "수당", "정착금", "통장", "저축", "적금", "대출", "빚", "카드", "용돈", "디딤씨앗", "월급", "금융")),
    JOB("취업·진로", "자립준비청년 취업 지원 일자리",
        listOf("취업", "일자리", "알바", "아르바이트", "면접", "이력서", "자소서", "자기소개", "직업", "진로", "자격증", "회사", "근로", "직장")),
    STUDY("교육·학업", "자립준비청년 장학금 교육 지원",
        listOf("대학", "학교", "공부", "장학", "학비", "등록금", "검정고시", "학원", "진학")),
    HEALTH("건강·의료", "자립준비청년 의료비 지원",
        listOf("병원", "의료", "아프", "건강", "치료", "보험", "진료")),
    MIND("마음·관계", "자립준비청년 심리상담 지원",
        listOf("막막", "힘들", "외로", "불안", "우울", "걱정", "관계", "고민", "무서", "지쳐", "스트레스", "속상"));

    fun matches(text: String): Boolean {
        val t = text.lowercase().replace(" ", "")
        return words.any { t.contains(it) }
    }

    companion object {
        fun classify(text: String): List<Topic> = entries.filter { it.matches(text) }

        /** 화면 표시용 정책 분야 이름 → 관심 분야 */
        fun ofPolicyCategory(category: String): Topic? = when (category) {
            "주거" -> HOUSING
            "생활·금융", "자립" -> MONEY
            "취업" -> JOB
            "교육" -> STUDY
            "건강" -> HEALTH
            "상담" -> MIND
            else -> null
        }

        fun ofBudget(category: BudgetCategory): Topic = when (category) {
            BudgetCategory.HOUSING -> HOUSING
            else -> MONEY
        }
    }
}

/** 관심 신호가 나온 곳 */
enum class SignalSource(val label: String) {
    CHAT("상담"), SEARCH("검색"), SAVE("정책 저장"), SIMULATION("생활비 계획"), PRACTICE("실전 연습"), MONTH("한 달 살아보기"), DOCUMENT("서류 작성")
}

data class TopicStat(val topic: Topic, val count: Int, val sources: Set<SignalSource>) {
    /** "상담·검색에서 4번" */
    val sourceText: String get() = sources.sortedBy { it.ordinal }.joinToString("·") { it.label }
}

// ───────────── 상담 대화 ─────────────

data class SavedChat(
    val id: Long,
    val startedAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
) {
    val title: String
        get() = messages.firstOrNull { it.role == ChatRole.USER }?.text?.let { if (it.length > 28) it.take(28) + "…" else it } ?: "새 상담"
    val questionCount: Int get() = messages.count { it.role == ChatRole.USER }
    val topics: List<Topic>
        get() = messages.filter { it.role == ChatRole.USER }.flatMap { Topic.classify(it.text) }.distinct()
}

// ───────────── 실전 연습 · 흥미 · 로드맵 ─────────────

data class PracticeAttempt(val at: Long, val scenarioId: String, val title: String, val good: Int, val missed: Int)

/** 한 달 살아보기 한 번. badEvents: 아쉬운 선택을 한 일의 id (예: smishing) */
data class MonthLifeRecord(
    val at: Long,
    val left: Int,
    val shortage: Int,
    val usedEmergency: Int,
    val home: String,
    val good: Int,
    val bad: Int,
    val badEvents: List<String>,
)

/** 내보낸 서류 (제출 전 연습 추천용) */
data class ExportedDoc(val at: Long, val title: String)

/** 쓰던 서류 (마지막으로 연 때 · 남은 칸). 알림 '쓰다 만 서류'용 */
data class DocProgress(val id: Long, val title: String, val left: Int, val exported: Boolean, val at: Long)

data class InterestSnapshot(val at: Long, val scores: Map<Riasec, Int>) {
    val top: List<Riasec> get() = scores.entries.sortedByDescending { it.value }.take(2).map { it.key }
}

data class SavedRoadmap(val at: Long, val jobName: String, val steps: List<RoadmapStep>, val done: Set<Int>) {
    val progress: Float get() = if (steps.isEmpty()) 0f else done.size / steps.size.toFloat()
    val nextIndex: Int? get() = steps.indices.firstOrNull { it !in done }
}

// ───────────── 아동 ─────────────

/** 아동 기분 (기분 달력). 저장은 이름(name)으로 하므로 순서를 바꿔도 된다 */
enum class KidMood(val label: String) {
    EXCITED("신나"),
    GOOD("좋아"),
    CALM("편안해"),
    UNSURE("잘 몰라"),
    TIRED("피곤해"),
    WORRIED("걱정돼"),
    SAD("속상해"),
    ANGRY("화나"),
}

/** 아동 '내 기록' 한 줄 (kind: MISSION / COUNSEL / FIND / STICKER) */
data class KidLogEntry(val date: LocalDate, val kind: String, val title: String, val sub: String?)

/** 좋아하는 거 찾기 결과 한 번 */
data class KidLiked(val date: LocalDate, val activities: List<String>, val jobs: List<String>, val reasons: List<String>)

/** 미션 기록장 한 줄 */
data class MissionNote(val date: LocalDate, val mission: String, val note: String?)

// ───────────── 저장소 ─────────────

object ActivityStore {
    private var prefs: SharedPreferences? = null

    /** 기록이 바뀔 때마다 1씩 오른다. 화면에서 읽으면 기록이 바뀔 때 다시 그려진다. */
    var version by mutableIntStateOf(0)
        private set

    private var appContext: Context? = null
    private var currentId: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefsName(userId: String) = "${PREFS}_$userId"

    /**
     * 지금 계정의 기록으로 바꾼다 (계정마다 따로 저장 → 한 휴대폰에서 아동·청소년·청년 계정 기록이 섞이지 않음).
     * null 이면 로그아웃 상태: 읽으면 빈 값, 저장은 하지 않는다.
     */
    fun useAccount(userId: String?) {
        if (userId == currentId && (prefs != null) == (userId != null)) return
        currentId = userId
        val ctx = appContext
        prefs = if (ctx != null && userId != null) ctx.getSharedPreferences(prefsName(userId), Context.MODE_PRIVATE) else null
        if (ctx != null && userId != null) migrateLegacy(ctx)
        version++
    }

    /** 예전 버전(휴대폰 하나에 기록 하나)이 남아 있으면 처음 들어온 계정으로 옮긴다 */
    private fun migrateLegacy(ctx: Context) {
        val legacy = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val target = prefs ?: return
        if (legacy.all.isEmpty() || target.all.isNotEmpty()) return
        val e = target.edit()
        legacy.all.forEach { (k, v) ->
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v as Set<String>)
            }
        }
        e.apply()
        legacy.edit().clear().apply()
    }

    /** 이 휴대폰에서 계정을 지울 때 그 계정 기록도 지운다 */
    fun deleteAccount(userId: String) {
        appContext?.getSharedPreferences(prefsName(userId), Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        if (userId == currentId) version++
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
        version++
    }

    private fun now() = System.currentTimeMillis()

    private fun readArray(key: String): JSONArray =
        prefs?.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun writeArray(key: String, arr: JSONArray, max: Int) {
        val p = prefs ?: return
        // 오래된 것부터 지워서 max 개만 남긴다 (배열은 오래된 → 최근 순)
        val trimmed = if (arr.length() <= max) arr else JSONArray().also { out ->
            for (i in arr.length() - max until arr.length()) out.put(arr.get(i))
        }
        p.edit().putString(key, trimmed.toString()).apply()
        version++
    }

    private fun append(key: String, obj: JSONObject, max: Int) {
        val arr = readArray(key)
        arr.put(obj)
        writeArray(key, arr, max)
    }

    private fun <T> list(key: String, map: (JSONObject) -> T?): List<T> {
        val arr = readArray(key)
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { map(it) }.getOrNull() } }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
    private fun List<String>.toJson(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

    // ───── 관심 신호 ─────

    private fun signal(topic: Topic, source: SignalSource, text: String) =
        append(KEY_SIGNALS, JSONObject().put("t", now()).put("topic", topic.name).put("src", source.name).put("text", text.take(80)), MAX_SIGNALS)

    /** 상담·검색 질문. 분야가 여러 개면 모두 남긴다 */
    fun logQuestion(text: String, source: SignalSource) {
        Topic.classify(text).forEach { signal(it, source, text) }
    }

    fun logPolicySaved(category: String, name: String) {
        Topic.ofPolicyCategory(category)?.let { signal(it, SignalSource.SAVE, name) }
    }

    fun logSimulation(shortages: List<BudgetCategory>) {
        shortages.map(Topic::ofBudget).distinct().forEach { signal(it, SignalSource.SIMULATION, "생활비 부족") }
    }

    /** 관심 분야 순위 (최근 60일, 많이 나온 순) */
    fun topicStats(days: Long = 60): List<TopicStat> {
        val from = now() - days * 24L * 60 * 60 * 1000
        return list(KEY_SIGNALS) { o ->
            if (o.optLong("t") < from) null
            else Topic.valueOf(o.getString("topic")) to SignalSource.valueOf(o.getString("src"))
        }
            .groupBy { it.first }
            .map { (t, l) -> TopicStat(t, l.size, l.map { it.second }.toSet()) }
            .sortedByDescending { it.count }
    }

    // ───── 상담 대화 ─────

    private fun ChatMessage.toJson(): JSONObject = JSONObject()
        .put("role", role.name)
        .put("text", text)
        .put("kind", kind.name)
        .put("check", needsCheck)
        .put("ev", JSONArray().also { a -> evidence.forEach { a.put(JSONObject().put("s", it.source).put("x", it.snippet)) } })
        .put("pol", JSONArray().also { a -> policies.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("cat", it.category)) } })
        .put("tel", JSONArray().also { a -> contacts.forEach { a.put(JSONObject().put("n", it.name).put("p", it.phone)) } })

    private fun chatMessageOf(o: JSONObject, id: Long): ChatMessage {
        val ev = o.optJSONArray("ev") ?: JSONArray()
        val pol = o.optJSONArray("pol") ?: JSONArray()
        val tel = o.optJSONArray("tel") ?: JSONArray()
        return ChatMessage(
            id = id,
            role = ChatRole.valueOf(o.getString("role")),
            text = o.optString("text"),
            evidence = (0 until ev.length()).map { ev.getJSONObject(it).let { e -> Evidence(e.optString("s"), e.optString("x")) } },
            policies = (0 until pol.length()).map { pol.getJSONObject(it).let { p -> stubPolicy(p.optString("id"), p.optString("name"), p.optString("cat")) } },
            needsCheck = o.optBoolean("check"),
            kind = runCatching { ChatKind.valueOf(o.optString("kind")) }.getOrDefault(ChatKind.POLICY),
            contacts = (0 until tel.length()).map { tel.getJSONObject(it).let { c -> HelpLine(c.optString("n"), c.optString("p")) } },
        )
    }

    /** 지난 대화의 정책 버튼용 (id·이름만 쓴다) */
    private fun stubPolicy(id: String, name: String, category: String) = Policy(
        id = id, name = name, category = category, agency = "", checkedAt = "", summary = "", easySummary = "",
        target = "", amount = "", period = "", howToApply = "", applyUrl = "",
        requiredDocs = emptyList(), evidence = emptyList(), keywords = emptyList(),
    )

    /** 대화 저장 (같은 id 면 덮어쓴다). 앱이 만든 첫 인사만 있으면 저장하지 않는다 */
    fun saveChat(id: Long, startedAt: Long, messages: List<ChatMessage>) {
        if (messages.none { it.role == ChatRole.USER }) return
        val arr = readArray(KEY_CHATS)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") != id) out.put(o)
        }
        out.put(
            JSONObject().put("id", id).put("start", startedAt).put("upd", now())
                .put("msgs", JSONArray().also { a -> messages.forEach { a.put(it.toJson()) } }),
        )
        writeArray(KEY_CHATS, out, MAX_CHATS)
    }

    /** 지난 대화 (최근 순) */
    fun chats(): List<SavedChat> = list(KEY_CHATS) { o ->
        val msgs = o.getJSONArray("msgs")
        SavedChat(
            id = o.getLong("id"),
            startedAt = o.optLong("start"),
            updatedAt = o.optLong("upd"),
            messages = (0 until msgs.length()).map { chatMessageOf(msgs.getJSONObject(it), it.toLong()) },
        )
    }.sortedByDescending { it.updatedAt }

    fun chat(id: Long): SavedChat? = chats().firstOrNull { it.id == id }

    // ───── 실전 연습 ─────

    fun logPractice(scenarioId: String, title: String, good: Int, missed: Int) {
        append(KEY_PRACTICE, JSONObject().put("t", now()).put("id", scenarioId).put("title", title).put("good", good).put("missed", missed), MAX_PRACTICE)
        // 연습한 분야도 관심 신호로 (놓친 게 있으면 그 분야를 더 챙기게)
        if (missed > 0) Links.topicOfScenario(scenarioId)?.let { signal(it, SignalSource.PRACTICE, title) }
    }

    // ───── 한 달 살아보기 ─────

    fun logMonthLife(r: MonthLifeRecord) {
        append(
            KEY_MONTH,
            JSONObject().put("t", r.at).put("left", r.left).put("short", r.shortage).put("used", r.usedEmergency)
                .put("home", r.home).put("good", r.good).put("bad", r.bad).put("ev", r.badEvents.toJson()),
            MAX_MONTH,
        )
        if (r.shortage > 0 || r.usedEmergency > 0) signal(Topic.MONEY, SignalSource.MONTH, "생활비 부족")
        if (r.home == "STUDIO") signal(Topic.HOUSING, SignalSource.MONTH, "월세 부담")
    }

    /** 최근 순 */
    fun monthLives(): List<MonthLifeRecord> = list(KEY_MONTH) { o ->
        MonthLifeRecord(
            o.getLong("t"), o.optInt("left"), o.optInt("short"), o.optInt("used"), o.optString("home"),
            o.optInt("good"), o.optInt("bad"), o.optJSONArray("ev")?.strings().orEmpty(),
        )
    }.sortedByDescending { it.at }

    // ───── 내보낸 서류 ─────

    fun logDocExported(title: String) {
        append(KEY_DOCS, JSONObject().put("t", now()).put("title", title.take(60)), MAX_DOCS)
        Topic.classify(title).forEach { signal(it, SignalSource.DOCUMENT, title) }
    }

    /** 서류를 열거나 칸을 채울 때마다: 남은 칸과 마지막으로 연 때 */
    fun logDocProgress(id: Long, title: String, left: Int, exported: Boolean) {
        val arr = readArray(KEY_DOC_PROGRESS)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") != id) out.put(o)
        }
        out.put(JSONObject().put("id", id).put("title", title.take(60)).put("left", left).put("done", exported).put("t", now()))
        writeArray(KEY_DOC_PROGRESS, out, MAX_DOCS)
    }

    /** 다 못 쓴 서류 (오래 안 연 순) */
    fun unfinishedDocs(): List<DocProgress> = list(KEY_DOC_PROGRESS) { o ->
        DocProgress(o.getLong("id"), o.optString("title"), o.optInt("left"), o.optBoolean("done"), o.optLong("t"))
    }.filter { !it.exported && it.left > 0 }.sortedBy { it.at }

    /** 최근 순 */
    fun exportedDocs(): List<ExportedDoc> = list(KEY_DOCS) { o -> ExportedDoc(o.getLong("t"), o.optString("title")) }
        .sortedByDescending { it.at }

    /** 최근 순 */
    fun practices(): List<PracticeAttempt> = list(KEY_PRACTICE) { o ->
        PracticeAttempt(o.getLong("t"), o.getString("id"), o.optString("title"), o.optInt("good"), o.optInt("missed"))
    }.sortedByDescending { it.at }

    // ───── 흥미 탐색 · 로드맵 ─────

    fun logInterest(scores: Map<Riasec, Int>) {
        val s = JSONObject().also { o -> scores.forEach { (k, v) -> o.put(k.name, v) } }
        append(KEY_INTEREST, JSONObject().put("t", now()).put("scores", s), MAX_INTEREST)
    }

    /** 최근 순 */
    fun interests(): List<InterestSnapshot> = list(KEY_INTEREST) { o ->
        val s = o.getJSONObject("scores")
        InterestSnapshot(o.getLong("t"), Riasec.entries.associateWith { s.optInt(it.name, 0) })
    }.sortedByDescending { it.at }

    fun saveRoadmap(r: Roadmap) {
        val p = prefs ?: return
        val o = JSONObject().put("t", now()).put("job", r.job.name)
            .put("steps", JSONArray().also { a -> r.steps.forEach { a.put(JSONObject().put("p", it.period).put("ti", it.title).put("d", it.detail)) } })
            .put("done", JSONArray())
        p.edit().putString(KEY_ROADMAP, o.toString()).apply()
        version++
    }

    fun roadmap(): SavedRoadmap? {
        val raw = prefs?.getString(KEY_ROADMAP, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val steps = o.getJSONArray("steps")
            val done = o.optJSONArray("done") ?: JSONArray()
            SavedRoadmap(
                at = o.optLong("t"),
                jobName = o.getString("job"),
                steps = (0 until steps.length()).map { steps.getJSONObject(it).let { s -> RoadmapStep(s.optString("p"), s.optString("ti"), s.optString("d")) } },
                done = (0 until done.length()).map { done.getInt(it) }.toSet(),
            )
        }.getOrNull()
    }

    fun toggleRoadmapStep(index: Int) {
        val p = prefs ?: return
        val raw = p.getString(KEY_ROADMAP, null) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val done = o.optJSONArray("done") ?: JSONArray()
        val set = (0 until done.length()).map { done.getInt(it) }.toMutableSet()
        if (!set.add(index)) set.remove(index)
        o.put("done", JSONArray().also { a -> set.sorted().forEach { a.put(it) } })
        p.edit().putString(KEY_ROADMAP, o.toString()).apply()
        version++
    }

    // ───── 청소년 자립 기초 미션 ─────

    fun basicsDone(): Set<String> = prefs?.getStringSet(KEY_BASICS, emptySet()).orEmpty().toSet()

    fun completeBasic(id: String) {
        val p = prefs ?: return
        p.edit().putStringSet(KEY_BASICS, basicsDone() + id).apply()
        version++
    }

    // ───── 아동 ─────

    fun logKid(kind: String, title: String, sub: String? = null) =
        append(KEY_KID_LOG, JSONObject().put("d", LocalDate.now().toString()).put("k", kind).put("ti", title).put("s", sub ?: ""), MAX_KID)

    fun kidLog(): List<KidLogEntry> = list(KEY_KID_LOG) { o ->
        KidLogEntry(LocalDate.parse(o.getString("d")), o.getString("k"), o.optString("ti"), o.optString("s").ifBlank { null })
    }

    /** 오늘 기분 (하루에 하나, 마지막에 고른 것) */
    fun setMood(mood: KidMood, date: LocalDate = LocalDate.now()) {
        val p = prefs ?: return
        val o = p.getString(KEY_MOODS, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put(date.toString(), mood.name)
        p.edit().putString(KEY_MOODS, o.toString()).apply()
        version++
    }

    fun moods(): Map<LocalDate, KidMood> {
        val o = prefs?.getString(KEY_MOODS, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            val d = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            val m = runCatching { KidMood.valueOf(o.getString(k)) }.getOrNull() ?: return@mapNotNull null
            d to m
        }.toMap()
    }

    fun addLiked(activities: List<String>, jobs: List<String>, reasons: List<String>) =
        append(
            KEY_LIKED,
            JSONObject().put("d", LocalDate.now().toString()).put("a", activities.toJson()).put("j", jobs.toJson()).put("r", reasons.toJson()),
            MAX_KID,
        )

    /** 최근 순 */
    fun liked(): List<KidLiked> = list(KEY_LIKED) { o ->
        KidLiked(LocalDate.parse(o.getString("d")), o.getJSONArray("a").strings(), o.getJSONArray("j").strings(), o.getJSONArray("r").strings())
    }.sortedByDescending { it.date }

    /** 아동 '좋아하는 거 찾기' 활동 → 흥미 유형 (청소년이 되어 흥미 탐색을 할 때 이어서 반영) */
    val kidActivityTypes: Map<String, Riasec> = mapOf(
        "식물 키우기" to Riasec.R, "만들기" to Riasec.R, "동물 돌보기" to Riasec.R, "운동" to Riasec.R, "요리" to Riasec.R,
        "퍼즐 풀기" to Riasec.I, "책 읽기" to Riasec.I,
        "그림 그리기" to Riasec.A, "노래·춤" to Riasec.A,
        "친구 돕기" to Riasec.S,
        "발표하기" to Riasec.E,
        "정리하기" to Riasec.C,
    )

    /** 좋아하는 활동별로 고른 횟수 (많은 순) */
    fun likedCounts(): List<Pair<String, Int>> =
        liked().flatMap { it.activities }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    fun addMissionNote(mission: String, note: String?) =
        append(KEY_MISSIONS, JSONObject().put("d", LocalDate.now().toString()).put("m", mission).put("n", note.orEmpty()), MAX_KID)

    /** 최근 순 */
    fun missionNotes(): List<MissionNote> = list(KEY_MISSIONS) { o ->
        MissionNote(LocalDate.parse(o.getString("d")), o.optString("m"), o.optString("n").ifBlank { null })
    }.sortedByDescending { it.date }

    /** 오늘(또는 어제)까지 이어서 미션을 한 날 수 */
    fun missionStreak(): Int {
        val days = missionNotes().map { it.date }.toSet()
        var d = LocalDate.now()
        if (d !in days) d = d.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    private const val PREFS = "onroad_activity"
    private const val KEY_SIGNALS = "signals"
    private const val KEY_CHATS = "chats"
    private const val KEY_PRACTICE = "practice"
    private const val KEY_MONTH = "month_life"
    private const val KEY_DOCS = "docs_exported"
    private const val KEY_DOC_PROGRESS = "docs_progress"
    private const val KEY_INTEREST = "interest"
    private const val KEY_ROADMAP = "roadmap"
    private const val KEY_BASICS = "basics"
    private const val KEY_KID_LOG = "kid_log"
    private const val KEY_MOODS = "kid_moods"
    private const val KEY_LIKED = "kid_liked"
    private const val KEY_MISSIONS = "kid_missions"

    private const val MAX_SIGNALS = 300
    private const val MAX_CHATS = 30
    private const val MAX_PRACTICE = 60
    private const val MAX_MONTH = 30
    private const val MAX_DOCS = 30
    private const val MAX_INTEREST = 10
    private const val MAX_KID = 200
}
