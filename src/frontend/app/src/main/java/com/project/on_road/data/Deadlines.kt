package com.project.on_road.data

import com.project.on_road.ui.screens.navigation.Routes
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 마감 알림에 쓰는 날짜 (서버 맞춤 신청 로드맵 3.32 + 일정에 담은 시험).
 * - 나이 상한으로 신청이 끝나는 날 (age_until)
 * - 퇴소 후 N년 안에 신청해야 하는 정책의 끝나는 날 (until). 매달 받는 돈(자립수당 등)은 한 번 신청하면 계속 받으니 빼고
 * - 미리 신청이 열리는 날 (apply_from, 퇴소 전)
 * - 일정에 담은 시험의 다음 일정 (원서 접수 등)
 * 공고마다 따로 정하는 신청 기간은 원문에 날짜가 없는 경우가 많아 넣지 않는다.
 */
object Deadlines {
    enum class Kind { AGE, AFTER_LEAVE, OPENS, EXAM }

    data class Deadline(
        val key: String,
        val kind: Kind,
        val name: String,
        val date: LocalDate,
        /** 둘째 줄 (만 34세까지 · 퇴소 후 5년 안 …) */
        val note: String?,
        val route: String,
    ) {
        fun daysFrom(today: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

        /** 알림 · 목록 제목 */
        fun title(today: LocalDate): String {
            val d = daysFrom(today)
            return when (kind) {
                Kind.OPENS -> if (d <= 0) "$name 미리 신청이 오늘부터 열려요" else "$name 미리 신청이 ${d}일 뒤 열려요"
                Kind.EXAM -> if (d <= 0) "$name 오늘이에요" else "$name ${d}일 남았어요"
                else -> if (d <= 0) "$name 신청이 오늘까지예요" else "$name 신청이 ${d}일 남았어요"
            }
        }
    }

    /** 알림을 보내는 날: 30일 전 · 7일 전 · 당일 */
    val NOTIFY_BEFORE = listOf(30L, 7L, 0L)

    /** 오늘 이후 마감 전부 (날짜 순) */
    fun all(today: LocalDate = LocalDate.now()): List<Deadline> {
        val rm = PlanData.roadmap
        val items = rm?.stages.orEmpty().flatMap { s -> s.items.map { s.stage to it } }
        val list = buildList {
            items.forEach { (stage, i) ->
                val route = Routes.policyDetail(i.policyId)
                val name = i.kindTitle ?: i.name
                i.ageUntil?.let { d ->
                    add(Deadline("age-${i.policyId}", Kind.AGE, name, d, i.ageMax?.let { "만 ${it}세까지 신청할 수 있어요" }, route))
                }
                val monthly = i.amountText?.contains("월") == true
                i.until?.takeIf { !monthly && (i.ageUntil == null || it.isBefore(i.ageUntil)) }?.let { d ->
                    add(Deadline("until-${i.policyId}", Kind.AFTER_LEAVE, name, d, "퇴소 후 정해진 기간 안에 신청해야 해요", route))
                }
                if (stage == "before_leave") i.applyFrom?.let { d ->
                    add(Deadline("opens-${i.policyId}", Kind.OPENS, name, d, i.applyNote.ifBlank { null }, route))
                }
            }
            CareerPlan.savedExams().forEach { e ->
                add(Deadline("exam-${e.id}", Kind.EXAM, e.title, e.date, e.detail.lineSequence().firstOrNull()?.ifBlank { null }, Routes.CAREER_ROADMAP))
            }
        }
        return list.filter { !it.date.isBefore(today) }.sortedBy { it.date }
    }

    /** 알림 화면: 30일 안에 오는 마감 */
    fun soon(today: LocalDate = LocalDate.now(), days: Long = 30): List<Deadline> =
        all(today).filter { it.daysFrom(today) <= days }
}
