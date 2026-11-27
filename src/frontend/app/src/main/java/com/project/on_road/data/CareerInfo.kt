package com.project.on_road.data

import com.project.on_road.data.remote.ExamRoundDto
import com.project.on_road.data.remote.OnRoadService
import java.time.LocalDate

/*
 * 진로 외부 정보 (서버 3.35): 앱 안에서 직업 정보 · 직무 · 시험 일정 · 훈련 과정을 보여 준다.
 * 서버가 공공 API(고용24 · 큐넷 · 커리어넷)를 대신 부르고, 못 받으면 null 을 돌려 화면에서 그 칸을 숨긴다.
 */

/** 시험 일정의 한 줄 (필기 접수 · 실기 시험 …) */
data class ExamEvent(val label: String, val start: LocalDate, val end: LocalDate)

/** 시험 회차 하나. title: 제24회 실기 · 제38회 필기 */
data class ExamRound(val id: String, val cert: String, val title: String, val events: List<ExamEvent>) {
    /** 아직 끝나지 않은 일정 */
    fun upcoming(today: LocalDate = LocalDate.now()) = events.filter { !it.end.isBefore(today) }
}

data class ExamSchedule(val cert: String, val rounds: List<ExamRound>, val note: String?)

data class TrainingCourse(
    val id: String,
    val round: String,
    val institutionId: String?,
    val title: String,
    val institution: String?,
    val address: String?,
    val phone: String?,
    val start: LocalDate?,
    val end: LocalDate?,
    val cost: Int?,
    val nearby: Boolean,
    /** 내 구 · 군 과정 */
    val sameDistrict: Boolean = false,
    val url: String?,
)

data class TrainingDetail(
    val institution: String?,
    val address: String?,
    val target: String?,
    val days: Int?,
    val hours: Int?,
    val cost: Int?,
    val myCost: Int?,
    val phone: String?,
    val url: String?,
)

data class DutyInfo(val unit: String, val definition: String?, val skills: List<String>)

/** 공공 직업 정보 (고용24 직업정보 또는 커리어넷). source: 화면 아래 출처 */
data class ExternalJob(
    val summary: String?,
    val works: List<String>,
    val certificates: List<String>,
    val majors: List<String>,
    val way: List<String>,
    val forecast: String?,
    val wage: String?,
    val satisfaction: String?,
    /** 비슷한 직업 이름 (공공 직업 정보의 관련 직업) */
    val relatedJobs: List<String>,
    val source: String,
)

/** 큐넷 국가자격 종목. kind: 국가기술 · 국가전문 */
data class CertificateName(val code: String, val name: String, val kind: String?)

class CareerInfoRepo(private val service: OnRoadService) {

    /** 자격증 이름 검색 (큐넷 국가자격 종목 목록). 못 받으면 빈 목록 */
    suspend fun certificates(q: String): List<CertificateName> = runCatching {
        val r = service.careerCertificates(q)
        if (!r.available) emptyList()
        else r.items.orEmpty().mapNotNull { c ->
            CertificateName(c.code ?: return@mapNotNull null, c.name ?: return@mapNotNull null, c.kind)
        }
    }.getOrDefault(emptyList())

    suspend fun job(name: String): ExternalJob? = runCatching {
        val r = service.careerJobInfo(name)
        val j = r.job ?: return null
        if (!r.available) return null
        ExternalJob(
            summary = j.summary?.takeIf { it.isNotBlank() },
            works = j.works.orEmpty().take(6),
            certificates = j.certificates.orEmpty().map(::cleanCert).distinct().take(6),
            majors = j.majors.orEmpty().take(4),
            way = (j.prepare?.curriculum.orEmpty() + j.prepare?.training.orEmpty()).take(3),
            forecast = forecastText(j.forecast.orEmpty()),
            wage = wageText(j.wage),
            satisfaction = j.satisfaction?.toDoubleOrNull()?.let { "${it.toInt()}%" },
            relatedJobs = j.relatedJobs.orEmpty().filter { it.isNotBlank() && it != j.name }.distinct().take(6),
            source = r.source ?: "고용24 직업정보",
        )
    }.getOrNull()

    suspend fun duties(keyword: String): List<DutyInfo> = runCatching {
        val r = service.careerDuties(keyword)
        if (!r.available) emptyList()
        else r.items.orEmpty().mapNotNull { d ->
            val unit = d.unit?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DutyInfo(unit, d.definition, d.skills.orEmpty().take(5))
        }.distinctBy { it.unit }.take(6)
    }.getOrDefault(emptyList())

    /** 자격증 목록에서 큐넷 일정이 있는 첫 자격증 (민간 · 면허 · 교육은 건너뛴다) */
    suspend fun exams(certificates: List<String>, today: LocalDate = LocalDate.now()): ExamSchedule? {
        for (cert in certificates.filter { c -> SKIP.none { c.contains(it) } }) {
            val r = runCatching { service.careerExams(cleanCert(cert)) }.getOrNull() ?: continue
            if (!r.available) return null // 키가 없거나 실패: 다른 자격증도 같으므로 그만
            val name = r.cert ?: cleanCert(cert)
            val rounds = r.items.orEmpty().mapNotNull { toRound(name, it) }.filter { it.upcoming(today).isNotEmpty() }
            if (rounds.isNotEmpty()) return ExamSchedule(name, rounds.take(3), r.note)
        }
        return null
    }

    /** 훈련 과정: 내 구 → 내 시 · 도 → 나머지 순 (서버가 정렬) */
    suspend fun trainings(keyword: String, region: String?, district: String? = null): List<TrainingCourse> = runCatching {
        val r = service.careerTrainings(keyword, region, district)
        if (!r.available) emptyList()
        else r.items.orEmpty().mapNotNull { t ->
            TrainingCourse(
                id = t.id ?: return@mapNotNull null,
                round = t.round ?: return@mapNotNull null,
                institutionId = t.institutionId,
                title = t.title ?: return@mapNotNull null,
                institution = t.institution,
                address = t.address,
                phone = t.phone,
                start = t.start?.let(::date),
                end = t.end?.let(::date),
                cost = t.cost,
                nearby = t.nearby == true,
                sameDistrict = t.sameDistrict == true,
                url = t.url,
            )
        }.take(8)
    }.getOrDefault(emptyList())

    suspend fun trainingDetail(c: TrainingCourse): TrainingDetail? = runCatching {
        val inst = c.institutionId ?: return null
        val d = service.careerTrainingDetail(c.id, c.round, inst).course ?: return null
        TrainingDetail(d.institution ?: c.institution, d.address ?: c.address, d.target, d.days, d.hours, d.cost, d.myCost,
            d.contactPhone ?: c.phone, d.url ?: c.url)
    }.getOrNull()

    private fun toRound(cert: String, d: ExamRoundDto): ExamRound? {
        fun ev(label: String, s: String?, e: String?): ExamEvent? {
            val start = s?.let(::date) ?: e?.let(::date) ?: return null
            return ExamEvent(label, start, e?.let(::date) ?: start)
        }
        val written = listOfNotNull(
            ev("필기 접수", d.writtenApplyStart, d.writtenApplyEnd),
            ev("필기 시험", d.writtenExamStart, d.writtenExamEnd),
            ev("필기 발표", d.writtenPass, null),
        )
        val practical = listOfNotNull(
            ev("실기 접수", d.practicalApplyStart, d.practicalApplyEnd),
            ev("실기 시험", d.practicalExamStart, d.practicalExamEnd),
            ev("합격 발표", d.practicalPass, null),
        )
        val events = (written + practical).sortedBy { it.start }
        if (events.isEmpty()) return null
        val m = ROUND.find(d.name.orEmpty())
        val no = m?.groupValues?.get(2) ?: d.round?.toString() ?: ""
        val part = m?.groupValues?.get(3)?.takeIf { it.isNotBlank() }
            ?: when { written.isEmpty() -> "실기"; practical.isEmpty() -> "필기"; else -> null }
        val title = listOfNotNull(if (no.isNotBlank()) "제${no}회" else null, part).joinToString(" ").ifBlank { cert }
        return ExamRound("${cert}-${d.year}-${no}-${part.orEmpty()}", cert, title, events)
    }

    companion object {
        private val SKIP = listOf("민간", "면허", "교육")
        private val ROUND = Regex("""(\d{4})년도\s*제?(\d+)회\s*(필기|실기)?""")

        /** "사회복지사 1·2급(국가)" 처럼 붙은 설명을 뗀다. 큐넷 종목명은 서버가 띄어쓰기 없이 맞춘다 */
        fun cleanCert(name: String): String = name.replace(Regex("""\((국가|국가기술|국가전문)\)"""), "").trim()

        fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s.take(10)) }.getOrNull()

        /** "임금 하위(25%) 2600만원, 평균(50%) 3100만원, …" → "3,100만 원". 커리어넷은 "2,800"(만 원) */
        fun wageText(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            Regex("""평균\(50%\)\s*([\d,]+)\s*만원""").find(raw)?.let { return "${fmt(it.groupValues[1])}만 원" }
            Regex("""^[\d,]+$""").find(raw.trim())?.let { return "${fmt(it.value)}만 원" }
            return null
        }

        /** "증가(16%) 현상유지(29%) 감소(55%)" → "늘어남 16%, 그대로 29%, 줄어듦 55%" */
        fun forecastText(lines: List<String>): String? {
            val raw = lines.joinToString(" ")
            val parts = listOf("증가" to "늘어남", "현상유지" to "그대로", "감소" to "줄어듦").mapNotNull { (k, label) ->
                Regex("""$k\((\d+)%\)""").find(raw)?.let { "$label ${it.groupValues[1]}%" }
            }
            return if (parts.isNotEmpty()) parts.joinToString(", ") else lines.firstOrNull()?.takeIf { it.length <= 40 }
        }

        private fun fmt(n: String): String = n.replace(",", "").toIntOrNull()?.let { "%,d".format(it) } ?: n
    }
}

/** 시험 날짜 글자: "11월 26일" · "11월 26일 ~ 11월 27일" */
object ExamText {
    fun day(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일"
    fun range(e: ExamEvent) = if (e.start == e.end) day(e.start) else "${day(e.start)} ~ ${day(e.end)}"
}
