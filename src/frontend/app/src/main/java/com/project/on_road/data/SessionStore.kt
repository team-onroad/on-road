package com.project.on_road.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 이 휴대폰에서 써 본 계정 (기존 사용자로 다시 들어가기) */
data class SavedAccount(val userId: String, val name: String, val stage: UserStage, val lastUsed: Long)

/**
 * 앱 세션.
 * - 계정마다 따로 저장한다 (onroad_user_<id>). 지금 쓰는 계정 id 는 onroad_session 에 둔다.
 * - clear() 는 '로그아웃': 계정 데이터는 남겨 두고 지금 계정만 비운다 → 온보딩의 '이미 사용 중이에요'에서 다시 들어올 수 있다.
 * - 이 휴대폰에서 써 본 계정 목록은 onroad_accounts.
 */
class SessionStore(private val context: Context) {
    private val root = context.getSharedPreferences("onroad_session", Context.MODE_PRIVATE)
    private val accountPrefs = context.getSharedPreferences("onroad_accounts", Context.MODE_PRIVATE)
    private var userPrefs: SharedPreferences? = null

    /** 지금 계정 저장소 (로그인 전에는 임시로 root) */
    private val prefs: SharedPreferences get() = userPrefs ?: root

    init {
        migrateLegacy()
        root.getString(KEY_CURRENT, null)?.let { userPrefs = forUser(it) }
    }

    private fun forUser(id: String) = context.getSharedPreferences("onroad_user_$id", Context.MODE_PRIVATE)

    /** 예전 버전(계정 하나를 onroad_session 에 저장)이면 계정 저장소로 옮긴다 */
    private fun migrateLegacy() {
        if (root.contains(KEY_CURRENT)) return
        val id = root.getString(KEY_USER_ID, null) ?: return
        val target = forUser(id).edit()
        root.all.forEach { (k, v) ->
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> target.putString(k, v)
                is Int -> target.putInt(k, v)
                is Long -> target.putLong(k, v)
                is Boolean -> target.putBoolean(k, v)
                is Float -> target.putFloat(k, v)
                is Set<*> -> target.putStringSet(k, v as Set<String>)
            }
        }
        target.apply()
        val name = root.getString(KEY_NICKNAME, null).orEmpty()
        val st = root.getString(KEY_STAGE, null)?.let { runCatching { UserStage.valueOf(it) }.getOrNull() } ?: UserStage.YOUNG_ADULT
        root.edit().clear().putString(KEY_CURRENT, id).apply()
        rememberAccount(id, name, st)
    }

    val userId: String? get() = userPrefs?.getString(KEY_USER_ID, null)
    val nickname: String get() = prefs.getString(KEY_NICKNAME, null).orEmpty()

    /** 저장된 단계가 없으면(이전 버전 사용자) 자립준비청년으로 본다. */
    val stage: UserStage
        get() = prefs.getString(KEY_STAGE, null)
            ?.let { runCatching { UserStage.valueOf(it) }.getOrNull() }
            ?: UserStage.YOUNG_ADULT

    /** 가입·로그인·정보 갱신. 다른 계정이면 그 계정으로 바꾼다 */
    fun save(userId: String, nickname: String, stage: UserStage) {
        if (root.getString(KEY_CURRENT, null) != userId) {
            root.edit().clear().putString(KEY_CURRENT, userId).apply()
            userPrefs = forUser(userId)
        }
        ActivityStore.useAccount(userId)
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_NICKNAME, nickname)
            .putString(KEY_STAGE, stage.name)
            .apply()
        rememberAccount(userId, nickname, stage)
    }

    // ───── 계정 목록 ─────

    /** 이 휴대폰에서 써 본 계정 (최근 사용 순) */
    val accounts: List<SavedAccount>
        get() {
            val arr = accountPrefs.getString(KEY_ACCOUNTS, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedAccount(
                    userId = o.optString("id"),
                    name = o.optString("name"),
                    stage = runCatching { UserStage.valueOf(o.optString("stage")) }.getOrDefault(UserStage.YOUNG_ADULT),
                    lastUsed = o.optLong("used"),
                )
            }.filter { it.userId.isNotBlank() }.sortedByDescending { it.lastUsed }
        }

    private fun writeAccounts(list: List<SavedAccount>) {
        val arr = JSONArray()
        list.forEach { a -> arr.put(JSONObject().put("id", a.userId).put("name", a.name).put("stage", a.stage.name).put("used", a.lastUsed)) }
        accountPrefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun rememberAccount(id: String, name: String, stage: UserStage) {
        writeAccounts(accounts.filter { it.userId != id } + SavedAccount(id, name, stage, System.currentTimeMillis()))
    }

    /** 이 휴대폰에서 계정 지우기 (서버 계정은 그대로) */
    fun removeAccount(id: String) {
        writeAccounts(accounts.filter { it.userId != id })
        forUser(id).edit().clear().apply()
        ActivityStore.deleteAccount(id)
        if (root.getString(KEY_CURRENT, null) == id) clear()
    }

    /** 음성 토글(청소년·자립준비청년용). 아동은 이 값과 관계없이 항상 켜짐. */
    var voiceToggle: Boolean
        get() = prefs.getBoolean(KEY_VOICE, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    /** 흥미 탐색 결과 상위 유형 (예: "S,A") */
    var interestTypes: List<Riasec>
        get() = prefs.getString(KEY_INTEREST, null).orEmpty()
            .split(",").mapNotNull { runCatching { Riasec.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_INTEREST, value.joinToString(",") { it.name }).apply()

    /** 아동 미션: 마지막으로 완료한 날(epochDay)과 누적 완료 수 */
    var missionDoneDay: Long
        get() = prefs.getLong(KEY_MISSION_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_MISSION_DAY, value).apply()

    var missionCount: Int
        get() = prefs.getInt(KEY_MISSION_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_MISSION_COUNT, value).apply()

    /** 지금 상황 (시설 생활 / 퇴소 예정 / 퇴소 후). 서버 값과 맞춰 둔다 */
    var status: LivingStatus?
        get() = prefs.getString(KEY_STATUS, null)?.let { runCatching { LivingStatus.valueOf(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_STATUS) else putString(KEY_STATUS, value.name)
            }.apply()
        }

    /** 퇴소 예정일 (퇴소 후면 목표일). 서버 d_date 와 맞춰 둔다 */
    var leaveDate: LocalDate?
        get() = prefs.getLong(KEY_LEAVE_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LEAVE_DATE) else putLong(KEY_LEAVE_DATE, value.toEpochDay())
            }.apply()
        }

    /** 퇴소 후 목표 이름 (예: 첫 출근, 이사). 목표일 자체는 leaveDate(= 서버 d_date) */
    var goalTitle: String?
        get() = prefs.getString(KEY_GOAL_TITLE, null)
        set(value) {
            prefs.edit().apply { if (value.isNullOrBlank()) remove(KEY_GOAL_TITLE) else putString(KEY_GOAL_TITLE, value.trim()) }.apply()
        }

    /** 퇴소 후 내가 잡은 일정 */
    var goalTasks: List<GoalTask>
        get() = GoalTaskCodec.decode(prefs.getString(KEY_GOAL_TASKS, null))
        set(value) = prefs.edit().putString(KEY_GOAL_TASKS, GoalTaskCodec.encode(value)).apply()

    /** D-day 알림 사용 여부 */
    var ddayAlarm: Boolean
        get() = prefs.getBoolean(KEY_DDAY_ALARM, false)
        set(value) = prefs.edit().putBoolean(KEY_DDAY_ALARM, value).apply()

    /** 타임라인에서 완료 체크한 할 일 id */
    var timelineDone: Set<String>
        get() = prefs.getStringSet(KEY_TIMELINE_DONE, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_TIMELINE_DONE, value).apply()

    /** 홈 바로가기 (HomeFeature 이름, 순서대로). null = 아직 편집 안 함 → 기본값 사용 */
    var homeShortcuts: List<String>?
        get() = prefs.getString(KEY_SHORTCUTS, null)?.split(",")?.filter { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SHORTCUTS) else putString(KEY_SHORTCUTS, value.joinToString(","))
            }.apply()
        }

    /** 저장한 정책(찜) id. 서버 API가 없어서 앱에 저장한다 (초안 3.22 app_records 에도 사용) */
    var savedPolicyIds: Set<String>
        get() = prefs.getStringSet(KEY_SAVED_POLICIES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_SAVED_POLICIES, value).apply()

    /** 아동: 스티커를 사느라 쓴 별 (가진 별 = missionCount - starsSpent) */
    var starsSpent: Int
        get() = prefs.getInt(KEY_STARS_SPENT, 0)
        set(value) = prefs.edit().putInt(KEY_STARS_SPENT, value).apply()

    val starBalance: Int get() = (missionCount - starsSpent).coerceAtLeast(0)

    /** 아동: 산 스티커 id */
    var ownedStickers: Set<String>
        get() = prefs.getStringSet(KEY_OWNED_STICKERS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_OWNED_STICKERS, value).apply()

    /** 아동: 홈 꾸미기 판에 붙인 스티커 ("id,x,y;id,x,y" — x·y는 판 크기 대비 0~1) */
    var placedStickersRaw: String
        get() = prefs.getString(KEY_PLACED_STICKERS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PLACED_STICKERS, value).apply()

    /**
     * 로그아웃 (처음으로 돌아가기 · 프로필 다시 설정).
     * 계정 데이터는 지우지 않는다 → 온보딩의 '이미 사용 중이에요'에서 다시 들어올 수 있다.
     * 앱 안 활동 기록(ActivityStore)은 계정마다 따로 남아 있다.
     */
    fun clear() {
        root.edit().clear().apply()
        userPrefs = null
        ActivityStore.useAccount(null)
    }

    private companion object {
        const val KEY_CURRENT = "current_user"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_GOAL_TITLE = "goal_title"
        const val KEY_GOAL_TASKS = "goal_tasks"
        const val KEY_USER_ID = "user_id"
        const val KEY_NICKNAME = "nickname"
        const val KEY_STAGE = "stage"
        const val KEY_VOICE = "voice_toggle"
        const val KEY_INTEREST = "interest_types"
        const val KEY_MISSION_DAY = "mission_day"
        const val KEY_MISSION_COUNT = "mission_count"
        const val KEY_LEAVE_DATE = "leave_date"
        const val KEY_STATUS = "living_status"
        const val KEY_DDAY_ALARM = "dday_alarm"
        const val KEY_TIMELINE_DONE = "timeline_done"
        const val KEY_SHORTCUTS = "home_shortcuts"
        const val KEY_STARS_SPENT = "stars_spent"
        const val KEY_OWNED_STICKERS = "owned_stickers"
        const val KEY_PLACED_STICKERS = "placed_stickers"
        const val KEY_SAVED_POLICIES = "saved_policies"
    }
}
