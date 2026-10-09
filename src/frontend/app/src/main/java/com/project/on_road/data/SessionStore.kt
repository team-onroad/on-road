package com.project.on_road.data

import android.content.Context
import java.time.LocalDate

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("onroad_session", Context.MODE_PRIVATE)

    val userId: String? get() = prefs.getString(KEY_USER_ID, null)
    val nickname: String get() = prefs.getString(KEY_NICKNAME, null).orEmpty()

    /** 저장된 단계가 없으면(이전 버전 사용자) 자립준비청년으로 본다. */
    val stage: UserStage
        get() = prefs.getString(KEY_STAGE, null)
            ?.let { runCatching { UserStage.valueOf(it) }.getOrNull() }
            ?: UserStage.YOUNG_ADULT

    fun save(userId: String, nickname: String, stage: UserStage) {
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_NICKNAME, nickname)
            .putString(KEY_STAGE, stage.name)
            .apply()
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

    /** 퇴소 예정일 */
    var leaveDate: LocalDate?
        get() = prefs.getLong(KEY_LEAVE_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LEAVE_DATE) else putLong(KEY_LEAVE_DATE, value.toEpochDay())
            }.apply()
        }

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

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_NICKNAME = "nickname"
        const val KEY_STAGE = "stage"
        const val KEY_VOICE = "voice_toggle"
        const val KEY_INTEREST = "interest_types"
        const val KEY_MISSION_DAY = "mission_day"
        const val KEY_MISSION_COUNT = "mission_count"
        const val KEY_LEAVE_DATE = "leave_date"
        const val KEY_DDAY_ALARM = "dday_alarm"
        const val KEY_TIMELINE_DONE = "timeline_done"
        const val KEY_SHORTCUTS = "home_shortcuts"
        const val KEY_STARS_SPENT = "stars_spent"
        const val KEY_OWNED_STICKERS = "owned_stickers"
        const val KEY_PLACED_STICKERS = "placed_stickers"
    }
}
