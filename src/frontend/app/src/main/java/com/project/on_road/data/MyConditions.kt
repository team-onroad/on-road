package com.project.on_road.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.time.LocalDate
import java.time.Period

/**
 * 내가 확인한 자립 조건 (정책 상세 '내 조건'의 '확인 필요'를 눌러 맞다 · 아니다로 답한 것).
 * 같은 조건 문장은 모든 정책에서 같은 답으로 보인다. 계정마다 휴대폰에 저장한다 (서버 API 없음).
 * 보호종료일(퇴소한 날)도 여기에 둔다: 퇴소 후 사용자는 서버 d_date 가 목표일이라 퇴소일을 따로 알아야 기간 조건을 계산할 수 있다.
 */
object MyConditions {
    private const val PREFS = "onroad_my_conditions"
    private const val KEY_ANSWERS = "answers"
    private const val KEY_LEFT = "left_date"

    private var appContext: Context? = null
    private var userId: String? = null
    private var prefs: SharedPreferences? = null
    /** 조건 문장(공백 뺀 것) → (원래 문장, 답) */
    private var answers: MutableMap<String, Pair<String, Boolean>> = mutableMapOf()
    private var left: LocalDate? = null

    /** 답이 바뀔 때마다 오른다 (이 값을 읽는 화면이 다시 그려지게) */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 지금 계정의 저장소로 맞춘다 (계정을 바꾸면 그 계정 답으로) */
    private fun sync() {
        val id = AppContainer.session.userId
        if (id == userId && (prefs != null) == (id != null)) return
        userId = id
        prefs = if (id != null) appContext?.getSharedPreferences("${PREFS}_$id", Context.MODE_PRIVATE) else null
        answers = prefs?.getString(KEY_ANSWERS, null)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.let { o ->
                o.keys().asSequence().mapNotNull { k ->
                    val e = o.optJSONObject(k) ?: return@mapNotNull null
                    k to (e.optString("text", k) to e.optBoolean("yes"))
                }.toMap().toMutableMap()
            }
            ?: mutableMapOf()
        left = prefs?.getString(KEY_LEFT, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    private fun key(condition: String) = condition.replace(Regex("""\s+"""), "")

    /** 이 조건에 내가 한 답. 답하지 않았으면 null */
    fun answer(condition: String): Boolean? {
        sync()
        return answers[key(condition)]?.second
    }

    fun setAnswer(condition: String, yes: Boolean?) {
        sync()
        val k = key(condition)
        if (yes == null) answers.remove(k) else answers[k] = condition.trim() to yes
        save()
    }

    private fun save() {
        val o = JSONObject()
        answers.forEach { (k, v) -> o.put(k, JSONObject().put("text", v.first).put("yes", v.second)) }
        prefs?.edit()?.putString(KEY_ANSWERS, o.toString())?.apply()
        version++
    }

    /** 답한 조건 전부 (원래 문장, 맞다면 true). 설정의 '확인한 조건' 목록 */
    fun all(): List<Pair<String, Boolean>> {
        sync()
        return answers.values.toList()
    }

    fun clearAnswers() {
        sync()
        answers.clear()
        save()
    }

    /** 퇴소한 날 (퇴소 후 사용자가 직접 넣은 날) */
    var leftDate: LocalDate?
        get() { sync(); return left }
        set(value) {
            sync()
            left = value
            prefs?.edit()?.apply { if (value == null) remove(KEY_LEFT) else putString(KEY_LEFT, value.toString()) }?.apply()
            version++
        }

    /** 보호가 끝나는(끝난) 날: 퇴소 전이면 퇴소 예정일, 퇴소 후면 직접 넣은 퇴소일 */
    fun leaveDate(profile: UserProfile?): LocalDate? = when {
        profile == null -> null
        profile.status == LivingStatus.LEFT -> leftDate
        else -> profile.dDate
    }
}

/**
 * '확인 필요' 조건을 쉬운 말로 풀고, 기간 조건(보호종료 후 N년 이내)은 날짜까지 계산한다.
 * 조건 문장에 있는 말만 풀어 쓴다 (문장에 없는 조건을 덧붙이지 않는다).
 */
object ConditionHelp {
    /**
     * lines: 쉬운 말 설명 줄들. 문장을 풀지 못하면 비어 있다 (화면은 원문만 보여 준다).
     * period: 기간 조건이면 계산 결과 (needsLeaveDate 면 퇴소일을 먼저 받아야 한다).
     */
    data class Help(val lines: List<String>, val period: PeriodCheck?)

    data class PeriodCheck(
        val years: Int,
        /** "보호종료일" · "18세 생일" */
        val baseLabel: String,
        val base: LocalDate?,
        val end: LocalDate?,
        /** 오늘 기준 기간 안이면 true, 지났으면 false, 날짜를 몰라 계산 못 하면 null */
        val within: Boolean?,
        val needsLeaveDate: Boolean,
    )

    private val PERIOD = Regex("""(\d+)\s*년\s*(이내|안|까지|간)""")
    private val CARE_YEARS = Regex("""(\d+)\s*년\s*이상\s*(연속\s*)?(보호|시설|위탁)""")
    private val SINCE = Regex("""\((\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2}\s*일\s*)?이후\)""")
    private val LIVE_YEARS = Regex("""(\d+)\s*년\s*이상\s*(계속\s*)?거주""")
    private val MEDIAN = Regex("""중위\s*소득\s*(\d+)\s*%""")
    private val AGE_RANGE = Regex("""(\d+)\s*세\s*(이상|부터|가\s*된\s*때로?\s*부터)""")
    private val AGE_SPAN = Regex("""(\d+)\s*세?\s*(이상)?\s*[~∼-]\s*(만\s*)?(\d+)\s*세""")
    private val NO_HISTORY = Regex("""(\d+)\s*(개월|년)[^,]*?(이력|참여)[^,]*?없""")

    fun explain(condition: String, profile: UserProfile?): Help {
        val c = condition.trim()
        val lines = mutableListOf<String>()
        if (Regex("아동복지시설|가정위탁|보호\\s*종료\\s*아동|보호종료아동").containsMatchIn(c)) {
            lines += "보육원, 그룹홈 같은 아동복지시설이나 위탁가정에서 지내다 보호가 끝난 사람이에요"
        }
        if ("자립준비청년" in c && lines.isEmpty()) lines += "아동복지시설이나 위탁가정에서 보호가 끝났거나 곧 끝나는 청년이에요"
        if ("만기" in c || "연장" in c) lines += "18살이 넘어 보호가 끝났거나, 보호를 연장했다가 끝난 경우예요"
        else if (Regex("18\\s*세\\s*이후\\s*보호\\s*종료").containsMatchIn(c)) lines += "18살이 된 뒤에 보호가 끝난 경우예요"
        AGE_SPAN.find(c)?.let { lines += "만 ${it.groupValues[1]}살부터 ${it.groupValues[4]}살까지만 해당돼요" }
        if ("조기" in c && "15세" in c) lines += "15살 이후 18살이 되기 전에 보호가 일찍 끝난 경우예요"
        CARE_YEARS.find(c)?.let { lines += "시설이나 위탁가정에서 ${it.groupValues[1]}년 넘게${if (it.groupValues[2].isNotBlank()) " 이어서" else ""} 지냈어야 해요" }
        SINCE.find(c)?.let { lines += "${it.groupValues[1]}년 ${it.groupValues[2]}월 이후에 보호가 끝났어야 해요" }
        LIVE_YEARS.find(c)?.let { lines += "그 지역에 ${it.groupValues[1]}년 넘게 살고 있어야 해요" }
        if ("무주택" in c) lines += "내 이름으로 된 집이 없어야 해요"
        MEDIAN.find(c)?.let { lines += "가구 소득이 기준 중위소득의 ${it.groupValues[1]}% 이하여야 해요" }
        if ("재학" in c) lines += "지금 학교에 다니고 있어야 해요"
        if ("구직단념" in c) lines += "일자리를 찾는 것을 잠시 멈춘 청년이에요"
        else if ("미취업" in c || "구직" in c) lines += "지금 일하지 않고 있어야 해요"
        NO_HISTORY.find(c)?.let { lines += "최근 ${it.groupValues[1]}${it.groupValues[2]} 동안 일, 교육, 직업훈련을 하지 않았어야 해요" }
        if (Regex("기초\\s*생활|수급자|급여\\s*수급").containsMatchIn(c)) lines += "기초생활보장 급여(생계, 의료, 주거, 교육)를 받고 있어야 해요"
        if ("차상위" in c) lines += "차상위계층으로 정해져 있어야 해요"
        if (Regex("거주|에 사는|주소를 둔").containsMatchIn(c) && LIVE_YEARS.find(c) == null) lines += "그 지역에 주소가 있어야 해요"
        if ("다니는" in c && "재학" !in c) lines += "지금 학교에 다니고 있어야 해요"
        if ("재보호" in c) lines += "다시 보호를 받는 동안에는 멈추고, 그 보호가 끝난 달부터 다시 받아요"
        return Help(lines.distinct(), period(c, profile))
    }

    private fun period(c: String, profile: UserProfile?): PeriodCheck? {
        val m = PERIOD.find(c) ?: return null
        // 보호종료 · 퇴소 기준 기간만 계산한다 (다른 기준의 'N년 이내'는 풀지 않는다)
        if (!Regex("보호\\s*종료|퇴소|종료").containsMatchIn(c)) return null
        val years = m.groupValues[1].toIntOrNull() ?: return null
        val today = LocalDate.now()
        // "18세부터 5년 이내": 18살 생일부터
        val fromAge = AGE_RANGE.find(c.substring(0, m.range.first))?.groupValues?.get(1)?.toIntOrNull()
        if (fromAge != null) {
            val base = profile?.birthDate?.plusYears(fromAge.toLong())
            val end = base?.plusYears(years.toLong())
            return PeriodCheck(years, "${fromAge}살 생일", base, end, end?.let { !today.isAfter(it) }, needsLeaveDate = false)
        }
        val base = MyConditions.leaveDate(profile)
        val end = base?.plusYears(years.toLong())
        return PeriodCheck(
            years, "보호종료일", base, end,
            within = end?.let { !today.isAfter(it) },
            needsLeaveDate = base == null && profile?.status == LivingStatus.LEFT,
        )
    }

    fun ageAt(birth: LocalDate, day: LocalDate): Int = Period.between(birth, day).years
}
