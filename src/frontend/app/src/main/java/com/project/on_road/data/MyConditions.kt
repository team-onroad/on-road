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
    private val BEFORE_MONTHS = Regex("""직전\s*(\d+)\s*개월\s*이상""")
    private val SINCE = Regex("""\(?(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2}\s*일\s*)?이후\)?""")
    private val LIVE_YEARS = Regex("""(\d+)\s*년\s*이상\s*(계속\s*)?거주""")
    private val MEDIAN = Regex("""중위\s*소득\s*(\d+)\s*%""")
    private val AGE_RANGE = Regex("""(\d+)\s*세\s*(이상|부터|가\s*된\s*때로?\s*부터)""")
    private val AGE_SPAN = Regex("""(\d+)\s*세?\s*(이상)?\s*[~∼-]\s*(만\s*)?(\d+)\s*세""")
    private val AGE_SPAN2 = Regex("""(\d+)\s*세\s*(이상|이후|부터)\s*(만\s*)?(\d+)\s*세\s*(이하|까지)""")
    private val UNDER = Regex("""(\d+)\s*세\s*미만""")
    private val UPTO = Regex("""(\d+)\s*세\s*이하""")
    private val OVER = Regex("""(\d+)\s*세\s*(이상|부터)""")
    private val WITHIN = Regex("""(\d+)\s*년\s*(이내|안|간)""")
    private val NO_HISTORY = Regex("""(\d+)\s*(개월|년)[^,]*?(이력|참여)[^,]*?없""")
    private val LEAVER = Regex("""보호\s*종료|퇴소|자립\s*준비|만기|조기\s*종료|종결|종료\s*(자|아동)|위탁\s*종료|보호가\s*조기""")
    private val EXTENDED = Regex("""연장한|연장\s*아동|보호\s*기간\s*(을\s*)?연장|퇴소일을\s*연장""")
    private val EXTENDED_END = Regex("""연장\s*(보호)?\s*종료|연장\s*보호종료""")
    private val IN_CARE = Regex("""입소|생활\s*(하는|중|아동|자)|보호\s*중|보호\s*아동|위탁\s*아동|위탁\s*보호|아동\s*양육\s*시설|공동\s*생활\s*가정|가정\s*위탁|아동\s*복지\s*시설|그룹홈""")
    private val AT_18 = Regex("""18\s*세\s*(이후|도달)""")
    private val EVEN_IF = Regex("""이어도|이라도""")

    /**
     * 대상 조건 한 줄을 쉬운 말로. 먼저 '누구인지' 한 줄(보호가 끝난 사람 · 지금 보호받는 아동 · 보호를 연장한 사람 …),
     * 그다음 나이 · 기간 · 학교 · 소득 · 지역 같은 세부 조건을 한 줄씩.
     */
    fun explain(condition: String, profile: UserProfile?): Help {
        val c = condition.trim()
        val lines = mutableListOf<String>()
        val leaver = LEAVER.containsMatchIn(c)
        val care = IN_CARE.containsMatchIn(c)
        val extended = EXTENDED.containsMatchIn(c)
        val evenIf = EVEN_IF.containsMatchIn(c)

        // 누구인지
        when {
            "소년소녀" in c -> lines += "부모 없이 18살이 안 된 아이가 가장인 가정이에요"
            "학대" in c -> lines += "학대를 받아 나라의 보호가 필요한 아동이에요"
            Regex("""보호자가\s*없|이탈|양육하기\s*(어려|곤란)""").containsMatchIn(c) ->
                lines += "보호자가 없거나, 보호자가 키우기 어려워 나라의 보호가 필요한 아동이에요"
            extended && !EXTENDED_END.containsMatchIn(c) ->
                lines += "18살이 넘어서도 보호 기간을 늘려 아직 시설이나 위탁가정에서 지내는 사람이에요"
            leaver && Regex("""보호\s*아동|입소|생활자""").containsMatchIn(c) && !Regex("""직전|만기""").containsMatchIn(c) ->
                lines += "지금 시설이나 위탁가정에서 지내는 아동과, 보호가 끝난 청년 모두 해당돼요"
            leaver -> {
                lines += "보육원, 그룹홈 같은 아동복지시설이나 위탁가정에서 지내다 보호가 끝난 사람이에요"
                if ("예정" in c) lines += "보호가 곧 끝나는 사람도 포함돼요"
            }
            "보호대상" in c && !care -> lines += "보호자가 없거나 키우기 어려워 나라의 보호를 받는 아동이에요"
            care -> lines += "지금 보육원, 그룹홈 같은 시설이나 위탁가정에서 보호받고 있는 아동이에요"
        }
        if ("청소년복지시설" in c || "청소년쉼터" in c) lines += "청소년쉼터 같은 청소년복지시설에서 지냈던 청년도 포함돼요"

        // 보호가 끝난 방식
        if ("만기" in c || EXTENDED_END.containsMatchIn(c)) lines += "18살이 넘어 보호가 끝났거나, 보호를 연장했다가 끝난 경우예요"
        else if (AT_18.containsMatchIn(c) && leaver && "조기" !in c) lines += "18살이 된 뒤에 보호가 끝난 경우예요"
        if ("조기" in c) lines += if ("15" in c) "15살 이후 18살이 되기 전에 보호가 일찍 끝난 경우도 포함돼요" else "18살이 되기 전에 보호가 일찍 끝난 경우예요"
        if (Regex("""18\s*세\s*초과""").containsMatchIn(c) || "보호종료신청" in c.replace(" ", "")) {
            lines += "18살이 넘었거나, 18살 전에 보호를 끝내겠다고 스스로 신청한 경우예요"
        }

        // 나이
        val span = AGE_SPAN.find(c) ?: AGE_SPAN2.find(c)
        when {
            span != null -> lines += "만 ${span.groupValues[1]}살부터 ${span.groupValues[4]}살까지만 해당돼요"
            evenIf -> if ("고등학교" in c) lines += "18살이 넘어도 고등학교에 다니고 있으면 포함돼요"
            else -> {
                UNDER.find(c)?.let { lines += "만 ${it.groupValues[1]}살이 안 된 아동만 해당돼요" }
                UPTO.find(c)?.let { lines += "만 ${it.groupValues[1]}살까지만 해당돼요" }
                OVER.find(c)?.let { if (!AT_18.containsMatchIn(c)) lines += "만 ${it.groupValues[1]}살 이상만 해당돼요" }
            }
        }

        // 기간 · 보호 이력
        WITHIN.find(c)?.let {
            if (leaver || "보호" in c) {
                lines += if (Regex("""18\s*세가\s*된\s*때""").containsMatchIn(c)) "18살이 된 날부터 ${it.groupValues[1]}년이 안 됐어야 해요"
                else "보호가 끝난 지 ${it.groupValues[1]}년이 안 됐어야 해요"
            }
        }
        CARE_YEARS.find(c)?.let { lines += "시설이나 위탁가정에서 ${it.groupValues[1]}년 넘게${if (it.groupValues[2].isNotBlank()) " 이어서" else ""} 지냈어야 해요" }
        BEFORE_MONTHS.find(c)?.let { lines += "퇴소하기 전 ${it.groupValues[1]}개월 넘게 시설이나 위탁가정에서 지냈어야 해요" }
        SINCE.find(c)?.let { lines += "${it.groupValues[1]}년 ${it.groupValues[2]}월 이후에 보호가 끝났어야 해요" }

        // 집 · 소득
        LIVE_YEARS.find(c)?.let { lines += "그 지역에 ${it.groupValues[1]}년 넘게 살고 있어야 해요" }
        if ("무주택" in c) lines += "내 이름으로 된 집이 없어야 해요"
        if ("미혼" in c) lines += "결혼하지 않았어야 해요"
        MEDIAN.find(c)?.let { lines += "가구 소득이 기준 중위소득의 ${it.groupValues[1]}% 이하여야 해요" }

        // 학교
        when {
            Regex("""대학\s*(진학|입학|생|재학)|대학에\s*(진학|입학|다니)""").containsMatchIn(c) -> lines += "대학에 들어갔거나 다니고 있어야 해요"
            Regex("""초\s*·?\s*중\s*·?\s*고|초중고""").containsMatchIn(c) -> lines += "초등학교, 중학교, 고등학교에 다니는 학생이에요"
            Regex("""중\s*·?\s*고등?\s*학생|중고생""").containsMatchIn(c) -> lines += "중학교나 고등학교에 다니는 학생이에요"
            ("재학" in c || "다니는" in c) && !evenIf -> lines += "지금 학교에 다니고 있어야 해요"
        }
        if (Regex("""성적|실적""").containsMatchIn(c)) lines += "학교 성적이나 활동 실적이 기관이 정한 기준을 넘어야 해요"

        // 일
        if ("구직단념" in c) lines += "일자리를 찾는 것을 잠시 멈춘 청년이에요"
        else if ("미취업" in c || "구직" in c) lines += "지금 일하지 않고 있어야 해요"
        if ("문답표" in c) lines += "고용센터 상담에서 구직을 멈춘 상태인지 확인받아야 해요"
        NO_HISTORY.find(c)?.let { lines += "최근 ${it.groupValues[1]}${it.groupValues[2]} 동안 일, 교육, 직업훈련을 하지 않았어야 해요" }

        // 수급 · 그 밖의 대상
        if (Regex("""기초\s*생활|기초\s*수급|수급자|수급\s*가구|급여\s*수급""").containsMatchIn(c)) lines += "기초생활보장 급여(생계, 의료, 주거, 교육)를 받는 가구여야 해요"
        if ("차상위" in c) lines += "차상위계층으로 정해져 있어야 해요"
        if ("국가유공자" in c) lines += "국가유공자이거나 그 가족이어야 해요"
        if ("건강보험" in c) lines += "건강보험에 가입돼 있어야 해요"
        if ("경계선" in c) lines += "경계선 지능이 의심되거나 진단받은 아동이에요"
        if (Regex("""심리|정서|상담\s*치료|문제\s*행동""").containsMatchIn(c)) lines += "마음이나 행동 문제로 상담이나 치료가 필요한 아동이에요"

        // 지역 · 기타
        if ("관내" in c) lines += "그 시·군·구에 사는 사람만 해당돼요"
        else if (Regex("""거주|에 사는|주소를 둔|도내|시내|군내""").containsMatchIn(c) && LIVE_YEARS.find(c) == null) lines += "그 지역에 주소가 있어야 해요"
        if ("재보호" in c) lines += "다시 보호를 받는 동안에는 멈추고, 그 보호가 끝난 달부터 다시 받아요"
        if ("원가정" in c) lines += "보호가 끝난 뒤 원래 가정으로 돌아간 경우는 빠져요"
        return Help(lines.distinct(), period(c, profile))
    }

    /**
     * '받을 수 없는 경우' 한 줄을 쉬운 말로 (정책 상세 제외 조건 아래 회색 한 줄). 풀지 못하면 null.
     */
    fun explainExclusion(text: String): String? {
        val c = text.trim()
        Regex("""(\d+)\s*(개월|년)\s*(간|안|이내)?[^,]*?(이력|참여)[^,]*?있""").find(c)?.let {
            return "최근 ${it.groupValues[1]}${it.groupValues[2]} 안에 일하거나 교육, 훈련을 받았다면 받을 수 없어요"
        }
        Regex("""(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일\s*이후\s*만?\s*(\d+)\s*세가\s*된""").find(c)?.let {
            return "${it.groupValues[1]}년 ${it.groupValues[2]}월 ${it.groupValues[3]}일 이후에 ${it.groupValues[4]}살이 된 사람부터 해당돼요"
        }
        Regex("""퇴소한\s*지\s*(\d+)\s*년이\s*지나""").find(c)?.let { return "퇴소한 지 ${it.groupValues[1]}년이 넘으면 신청할 수 없어요" }
        Regex("""만?\s*(\d+)\s*세\s*미만\s*제외""").find(c)?.let { return "만 ${it.groupValues[1]}살이 안 됐으면 받을 수 없어요" }
        return when {
            "원가정" in c -> "보호가 끝난 뒤 부모님이나 원래 가족에게 돌아갔다면 받을 수 없어요"
            Regex("""(\d+)\s*년\s*내\s*1\s*회""").containsMatchIn(c) ->
                Regex("""(\d+)\s*년""").find(c)!!.groupValues[1].let { "계약한 날부터 ${it}년 안에 한 번만 받을 수 있어요" }
            "반복" in c || Regex("""1\s*회만""").containsMatchIn(c) -> "한 번만 받을 수 있어요"
            "현장접수" in c || "현장 접수" in c -> "직접 찾아가서 신청할 수는 없어요"
            Regex("""개인\s*(신청|신청\s*절차)\s*(없|불필요)|신청\s*불필요""").containsMatchIn(c) -> "따로 신청하지 않아요. 기관이 대상자를 정해서 지원해요"
            "전문가정위탁" in c -> "전문가정위탁이 아닌 일반 위탁 아동은 빠져요"
            Regex("""생계\s*·?\s*의료\s*급여""").containsMatchIn(c) -> "생계급여나 의료급여를 받고 있으면 받을 수 없어요"
            "문화누리카드" in c -> "문화누리카드를 받을 수 있는 사람은 빠져요"
            "학원" in c -> "교과목이 아닌 학원비는 지원하지 않아요"
            "선정" in c && "미이행" in c -> "위탁가정 선정 절차를 마치지 않았으면 받을 수 없어요"
            "소년소녀가정" in c && "지정" in c -> "새로 소년소녀가정으로 지정되지는 않아요"
            Regex("""타\s*법령|다른\s*법""").containsMatchIn(c) -> "다른 법에 따른 자립 수당을 이미 받고 있으면 받을 수 없어요"
            Regex("""(.+?)\s*지원\s*불가$""").containsMatchIn(c) -> "이 제도로는 ${Regex("""(.+?)\s*지원\s*불가$""").find(c)!!.groupValues[1]}을 받을 수 없어요"
            c.endsWith("제외") -> "${c.removeSuffix("제외").trim()} 대상은 빠져요"
            else -> null
        }
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
