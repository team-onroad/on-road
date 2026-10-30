package com.project.on_road.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.project.on_road.data.ActivityStore
import com.project.on_road.data.AppContainer
import com.project.on_road.data.AppState
import com.project.on_road.data.Links
import com.project.on_road.data.LivingStatus
import com.project.on_road.data.UserStage
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 매일 저녁 '오늘 챙길 것' 맞춤 알림 1개.
 * 휴대폰 안 기록(쓰다 만 서류 · 내보낸 서류 · 실전 연습 · 한 달 살아보기)만 보고 고른다 (서버에 묻지 않음).
 * 같은 알림은 3일 안에 다시 보내지 않고, 보낼 게 없으면 보내지 않는다.
 */
object DailyReminder {
    const val CHANNEL_ID = "today"
    private const val REQUEST = 7900
    private const val NOTIFY_ID = 7901
    private val AT: LocalTime = LocalTime.of(19, 30)
    private const val PREFS = "onroad_reminder"
    private const val DAY = 24L * 60 * 60 * 1000

    data class Pick(val key: String, val title: String, val body: String, val route: String)

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "오늘 챙길 것", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "쓰다 만 서류, 다시 해 볼 연습처럼 오늘 챙기면 좋은 것을 하루 한 번 알려 드려요."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        var at = LocalDate.now().atTime(AT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= now) at += DAY
        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_DAY, pending(context))
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, DailyReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 지금 보낼 알림을 고른다. 없으면 null */
    fun pick(context: Context): Pick? {
        if (AppState.stage == UserStage.CHILD || AppContainer.session.userId == null) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = { key: String -> now - prefs.getLong("sent_$key", 0L) < 3 * DAY }
        return candidates(now).firstOrNull { !recent(it.key) }
    }

    fun markSent(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("sent_$key", System.currentTimeMillis()).apply()
    }

    private fun candidates(now: Long): List<Pick> = buildList {
        val practices = ActivityStore.practices()
        // 1) 이틀 넘게 안 연 쓰다 만 서류
        ActivityStore.unfinishedDocs()
            .firstOrNull { now - it.at in 2 * DAY..30 * DAY }
            ?.let { d ->
                add(Pick("doc-${d.id}", "쓰다 만 서류가 있어요", "‘${d.title}’ ${d.left}칸 남았어요. 오늘 이어서 써 볼까요?", "coach/doc?documentId=${d.id}"))
            }
        // 2) 내보낸 서류: 내러 가기 전에 그 기관 연습
        ActivityStore.exportedDocs().firstOrNull { now - it.at < 14 * DAY }?.let { doc ->
            Links.practiceForDoc(doc.title)?.let { (id, name) ->
                if (practices.none { it.scenarioId == id && it.at > doc.at }) {
                    add(Pick("docpractice-$id", "내러 가기 전에 연습해 봐요", "‘${doc.title}’ 내기 전에 ‘$name’ 상황을 3분만 연습해 봐요.", "coach/roleplay/$id"))
                }
            }
        }
        // 3) 한 달 살아보기에서 아쉬웠던 것
        ActivityStore.monthLives().firstOrNull()?.let { m ->
            if ("smishing" in m.badEvents && practices.none { it.scenarioId == "scam-call" && it.at > m.at }) {
                add(Pick("month-scam", "사기 문자, 다음엔 안 속게", "지난 한 달 살아보기에서 사기 링크를 눌렀어요. 수상한 전화 대처를 연습해 봐요.", "coach/roleplay/scam-call"))
            }
            if (m.shortage > 0) {
                add(Pick("month-short", "생활비가 모자랐던 한 달", "지난 한 달 살아보기에서 ${m.shortage}만원이 모자랐어요. 받을 수 있는 생활비 지원을 찾아봐요.", "policy?q=" + android.net.Uri.encode("긴급복지지원 생활비 자립준비청년")))
            }
        }
        // 4) 지난 연습에서 놓친 것
        practices.firstOrNull()?.takeIf { it.missed > 0 && now - it.at > DAY }?.let { p ->
            add(Pick("practice-${p.scenarioId}", "‘${p.title}’ 다시 해 볼까요?", "지난번에 ${p.missed}개를 놓쳤어요. 같은 상황으로 한 번 더 연습해요.", "coach/roleplay/${p.scenarioId}"))
        }
        // 5) 아직 안 해 본 것
        if (ActivityStore.monthLives().isEmpty() && AppState.stage == UserStage.YOUNG_ADULT) {
            add(Pick("month-first", "혼자 사는 한 달, 미리 살아봐요", "매주 생기는 일에 대처하며 한 달 생활비를 버텨 보는 연습이에요.", "sim/month"))
        }
        if (practices.isEmpty()) {
            add(Pick("practice-first", "말로 하는 실전 연습", "은행·주민센터·면접 상황을 AI와 통화하듯 연습해 봐요.", "coach/roleplay"))
        }
    }
}

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppContainer.init(context)
        if (!AppContainer.session.ddayAlarm) return
        val p = DailyReminder.pick(context) ?: return
        DailyReminder.ensureChannel(context)
        DdayNotifier.show(context, 7901, p.title, p.body, p.route, DailyReminder.CHANNEL_ID)
        DailyReminder.markSent(context, p.key)
    }
}

/** 알림 한 곳에서 켜고 끄기: D-day(퇴소 전, 퇴소일이 있을 때) + 매일 저녁 '오늘 챙길 것' */
object Notifications {
    fun applyAll(context: Context) {
        val s = AppContainer.session
        if (!s.ddayAlarm) {
            DdayNotifier.cancel(context)
            DailyReminder.cancel(context)
            return
        }
        val leave = s.leaveDate
        if (leave != null && s.status != LivingStatus.LEFT) DdayNotifier.schedule(context, leave) else DdayNotifier.cancel(context)
        DailyReminder.ensureChannel(context)
        DailyReminder.schedule(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        AppContainer.session.ddayAlarm = on
        applyAll(context)
    }
}

/** 휴대폰을 껐다 켜면 예약한 알림이 사라지므로 다시 예약한다 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppContainer.init(context)
        Notifications.applyAll(context)
    }
}
