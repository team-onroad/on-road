package com.project.on_road.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/*
 * 서버 d_date 하나를 상황에 따라 두 뜻으로 쓴다.
 *  - 퇴소 전(시설 생활 · 퇴소 예정): 퇴소 예정일 → '퇴소 D-day 타임라인' (퇴소일 기준 할 일)
 *  - 퇴소 후: 내가 정한 목표일 → '목표 D-day' (목표 이름 + 내가 잡은 일정)
 * 화면은 반드시 DDate.isGoal(status) 로 나눠서 문구가 섞이지 않게 한다.
 */
object DDate {
    fun isGoal(status: LivingStatus?): Boolean = status == LivingStatus.LEFT

    /** "퇴소 예정일" / "목표일" */
    fun label(status: LivingStatus?): String = if (isGoal(status)) "목표일" else "퇴소 예정일"

    /** 홈 태그: "퇴소까지" / "첫 출근까지" */
    fun untilText(status: LivingStatus?, goalTitle: String?): String =
        if (isGoal(status)) "${goalTitle?.takeIf { it.isNotBlank() } ?: "목표"}까지" else "퇴소까지"

    /** 화면 제목 */
    fun screenTitle(status: LivingStatus?): String = if (isGoal(status)) "목표 D-day" else "퇴소 D-day 타임라인"
}

/** 퇴소 후 내가 잡은 일정 한 개 */
data class GoalTask(val id: String, val title: String, val date: LocalDate, val done: Boolean)

internal object GoalTaskCodec {
    fun decode(raw: String?): List<GoalTask> {
        val arr = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val d = runCatching { LocalDate.parse(o.getString("d")) }.getOrNull() ?: return@mapNotNull null
            GoalTask(o.optString("id"), o.optString("t"), d, o.optBoolean("x"))
        }
    }

    fun encode(list: List<GoalTask>): String =
        JSONArray().also { a -> list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("d", it.date.toString()).put("x", it.done)) } }.toString()
}
