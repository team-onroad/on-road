package com.project.on_road.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 진로 로드맵에서 '일정에 넣기'로 고른 방향 (계정마다 휴대폰에 저장, 서버 API 없음).
 * 그 방향의 할 일을 시작한 날부터 한 주에 하나씩 일정 탭에 올린다.
 * 끝낸 일은 일정 할 일과 같은 저장소(PlanData.done)를 써서, 진로 로드맵에서 체크하든 일정에서 체크하든 같이 바뀐다.
 */
object CareerPlan {
    private const val PREFS = "onroad_career_plan"
    private const val KEY = "plan"
    /** 할 일 사이 간격 (일) */
    const val GAP_DAYS = 7L

    data class Saved(val path: String, val title: String, val start: LocalDate, val steps: List<CareerStep>)

    private var appContext: Context? = null
    private var userId: String? = "\u0000"
    private var prefs: SharedPreferences? = null
    private var saved: Saved? = null

    /** 일정에 넣은 자격증 시험 (진로 로드맵 · 흥미 탐색 직업 상세의 '일정에 넣기') */
    data class SavedExam(val id: String, val title: String, val detail: String, val date: LocalDate)
    private const val KEY_EXAMS = "exams"
    private var exams: List<SavedExam> = emptyList()

    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun sync() {
        val id = runCatching { AppContainer.session.userId }.getOrNull()
        if (id == userId) return
        userId = id
        prefs = if (id != null) appContext?.getSharedPreferences("${PREFS}_$id", Context.MODE_PRIVATE) else null
        saved = prefs?.getString(KEY, null)?.let { raw -> runCatching { parse(JSONObject(raw)) }.getOrNull() }
        exams = prefs?.getString(KEY_EXAMS, null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    SavedExam(o.getString("id"), o.getString("title"), o.optString("detail"), LocalDate.parse(o.getString("date")))
                }
            }.getOrNull()
        }.orEmpty()
    }

    fun hasExam(id: String): Boolean { sync(); return exams.any { it.id == id } }

    /** 일정에 담은 시험 (마감 알림) */
    fun savedExams(): List<SavedExam> { sync(); return exams }

    /** 시험 회차를 일정에 넣거나 뺀다. 날짜는 아직 오지 않은 첫 일정(이미 시작했으면 오늘) */
    fun toggleExam(round: ExamRound, today: LocalDate = LocalDate.now()) {
        sync()
        exams = if (exams.any { it.id == round.id }) exams.filterNot { it.id == round.id }
        else {
            val up = round.upcoming(today)
            val first = up.firstOrNull() ?: return
            val date = if (first.start.isBefore(today)) today else first.start
            val detail = up.joinToString("\n") { "${it.label} ${ExamText.range(it)}" }
            exams + SavedExam(round.id, "${round.cert} ${round.title} ${first.label}", detail, date)
        }
        prefs?.edit()?.putString(KEY_EXAMS, JSONArray().also { a ->
            exams.forEach { e -> a.put(JSONObject().put("id", e.id).put("title", e.title).put("detail", e.detail).put("date", e.date.toString())) }
        }.toString())?.apply()
        version++
        com.project.on_road.notification.DeadlineReminder.reschedule()
    }

    /** 일정에 넣은 방향. 없으면 null */
    val current: Saved?
        get() { sync(); return saved }

    /** 이 방향을 일정에 넣는다 (다른 방향이 있었으면 바꾼다). 할 일은 서버에서 받은 그대로 기억한다 */
    fun start(plan: CareerPathPlan, jobSteps: List<CareerStep> = emptyList(), today: LocalDate = LocalDate.now()) {
        sync()
        saved = Saved(plan.path, plan.title, today, plan.steps + jobSteps)
        prefs?.edit()?.putString(KEY, toJson(saved!!).toString())?.apply()
        version++
    }

    /** 서버 할 일이 바뀌었으면 (내용 강화 등) 같은 방향의 할 일만 새것으로 (시작한 날은 그대로) */
    fun refreshSteps(plan: CareerPathPlan, jobSteps: List<CareerStep> = emptyList()) {
        sync()
        val s = saved ?: return
        val steps = plan.steps + jobSteps
        if (s.path != plan.path || s.steps == steps) return
        saved = s.copy(title = plan.title, steps = steps)
        prefs?.edit()?.putString(KEY, toJson(saved!!).toString())?.apply()
        version++
    }

    fun stop() {
        sync()
        saved = null
        prefs?.edit()?.remove(KEY)?.apply()
        version++
    }

    fun taskId(step: CareerStep) = "career-${step.stepId}"

    /** 일정 탭에 올릴 할 일: 시작한 날부터 한 주에 하나씩 */
    fun tasks(): List<PlanTask> {
        val s = current
        val examTasks = exams.map { e ->
            PlanTask(
                id = "exam-${e.id}",
                offsetDays = null,
                date = e.date,
                title = e.title,
                description = e.detail,
                actions = listOf(PlanAction("나의 진로", PlanGo.Career)),
                kind = PlanKind.CAREER,
            )
        }
        if (s == null) return examTasks
        return examTasks + s.steps.mapIndexed { i, step ->
            PlanTask(
                id = taskId(step),
                offsetDays = null,
                date = s.start.plusDays(i * GAP_DAYS),
                title = step.title,
                description = step.detail,
                actions = listOfNotNull(goOf(step)?.let { PlanAction(actionLabel(step), it) }),
                kind = PlanKind.CAREER,
            )
        }
    }

    fun goOf(step: CareerStep): PlanGo? = when (step.kind) {
        "roleplay" -> step.scenarioId?.let { PlanGo.Practice(it) }
        "document" -> step.templateId?.let { PlanGo.Document(it) }
        "search" -> step.query?.let { PlanGo.PolicySearch(it) }
        "app" -> when (step.target) {
            "interest" -> PlanGo.Interest
            "risk" -> PlanGo.RiskCheck
            "chat" -> PlanGo.Chat(step.query ?: step.title)
            else -> null
        }
        "note" -> null
        else -> PlanGo.Career
    }

    fun actionLabel(step: CareerStep): String = when (step.kind) {
        "roleplay" -> "연습하기"
        "document" -> "서류 쓰기"
        "search" -> "찾아보기"
        "app" -> when (step.target) {
            "risk" -> "확인하기"
            "chat" -> "물어보기"
            else -> "해 보기"
        }
        "note" -> ""
        else -> "사이트 열기"
    }

    private fun toJson(s: Saved) = JSONObject()
        .put("path", s.path).put("title", s.title).put("start", s.start.toString())
        .put("steps", JSONArray().also { a ->
            s.steps.forEach { st ->
                a.put(
                    JSONObject().put("id", st.stepId).put("kind", st.kind).put("title", st.title).put("detail", st.detail)
                        .put("scenario", st.scenarioId).put("template", st.templateId).put("url", st.url)
                        .put("phone", st.phone).put("target", st.target).put("query", st.query)
                        .put("guide", JSONArray(st.guide)).put("reason", st.reason),
                )
            }
        })

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }

    private fun parse(o: JSONObject): Saved {
        val a = o.getJSONArray("steps")
        val steps = (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            CareerStep(
                s.getString("id"), s.getString("kind"), s.getString("title"), s.optString("detail"),
                s.str("scenario"), s.str("template"), s.str("url"), s.str("phone"), s.str("target"), s.str("query"),
                s.optJSONArray("guide")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty(), s.str("reason"),
            )
        }
        return Saved(o.getString("path"), o.optString("title"), LocalDate.parse(o.getString("start")), steps)
    }
}
