package com.project.on_road.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Deadlines
import java.time.LocalDate
import java.time.ZoneId

/**
 * 마감 알림: 30일 전 · 7일 전 · 당일 아침 9시 (Deadlines 의 날짜).
 * 로드맵을 새로 받거나 시험을 일정에 넣고 뺄 때마다 통째로 다시 건다. 알림 켜기(설정)를 따른다.
 */
object DeadlineReminder {
    const val CHANNEL_ID = "deadline"
    private const val BASE_CODE = 7600
    private const val MAX_SLOTS = 40
    private const val NOTIFY_HOUR = 9

    private var app: Context? = null

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "마감 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "신청 마감, 미리 신청이 열리는 날, 일정에 담은 시험을 30일 전, 7일 전, 당일에 알려 드려요."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun reschedule(context: Context? = app) {
        val ctx = context?.applicationContext ?: return
        init(ctx)
        cancel(ctx)
        if (runCatching { AppContainer.session.ddayAlarm }.getOrDefault(false) != true) return
        ensureChannel(ctx)
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val slots = Deadlines.all(today).flatMap { d ->
            Deadlines.NOTIFY_BEFORE.map { before -> d to d.date.minusDays(before) }
        }.mapNotNull { (d, day) ->
            val at = day.atTime(NOTIFY_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at > now) Triple(d, day, at) else null
        }.sortedBy { it.third }.take(MAX_SLOTS)
        slots.forEachIndexed { i, (d, day, at) ->
            val intent = Intent(ctx, DdayReceiver::class.java).apply {
                putExtra(DdayNotifier.EXTRA_ID, BASE_CODE + i)
                // 알림이 뜨는 날 기준 남은 날로 문구를 만든다
                putExtra(DdayNotifier.EXTRA_TITLE, d.title(day))
                putExtra(DdayNotifier.EXTRA_BODY, d.note.orEmpty())
                putExtra(DdayNotifier.EXTRA_ROUTE, d.route)
                putExtra(DdayNotifier.EXTRA_CHANNEL, CHANNEL_ID)
            }
            val pi = PendingIntent.getBroadcast(ctx, BASE_CODE + i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        (0 until MAX_SLOTS).forEach { i ->
            PendingIntent.getBroadcast(
                context, BASE_CODE + i, Intent(context, DdayReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                alarm.cancel(it)
                it.cancel()
            }
        }
    }
}
