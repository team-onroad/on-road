package com.project.on_road.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.project.on_road.data.AppContainer
import com.project.on_road.data.ChecklistSummary
import java.time.LocalDate
import java.time.ZoneId

/**
 * 신청 예정일 알림: 하루 전 · 당일 아침 9시.
 * 예정일은 휴대폰에도 적어 두고(계정별), 신청 관리 목록을 받을 때마다 서버 값으로 맞춘다.
 * 알림 켜기(설정)를 따르고, 네 단계를 다 끝낸 신청은 알리지 않는다.
 */
object DueReminder {
    const val CHANNEL_ID = "due"
    private const val BASE_CODE = 7500
    private const val MAX_SLOTS = 40
    private const val NOTIFY_HOUR = 9
    private const val PREFS = "onroad_due"

    private data class Entry(val checklistId: Long, val policyId: String, val date: LocalDate, val name: String)

    private var app: Context? = null

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "신청 예정일 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "신청하기로 정한 날 하루 전과 당일에 알려 드려요."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 신청 관리 목록을 새로 받았을 때: 서버 값으로 통째로 맞춘다 */
    fun sync(list: List<ChecklistSummary>) {
        val entries = list.filter { it.dueDate != null && !it.completed }
            .map { Entry(it.id, it.policyId, it.dueDate!!, it.policyName) }
        write(entries)
        reschedule()
    }

    /** 한 신청의 예정일이 바뀌었을 때 (null 이면 지움) */
    fun set(checklistId: Long, policyId: String, name: String, date: LocalDate?) {
        val rest = read().filterNot { it.checklistId == checklistId }
        write(if (date == null) rest else rest + Entry(checklistId, policyId, date, name))
        reschedule()
    }

    fun remove(checklistId: Long) = set(checklistId, "", "", null)

    /** 계정을 지울 때 */
    fun forget(userId: String) {
        prefs()?.edit()?.remove(key(userId))?.apply()
        reschedule()
    }

    /** 예약을 모두 지우고 지금 계정 기준으로 다시 건다 (알림이 꺼져 있으면 지우기만) */
    fun reschedule(context: Context? = app) {
        val ctx = context?.applicationContext ?: return
        init(ctx)
        cancel(ctx)
        if (runCatching { AppContainer.session.ddayAlarm }.getOrDefault(false) != true) return
        ensureChannel(ctx)
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val slots = read().sortedBy { it.date }.flatMap { e ->
            listOf(
                e.date.minusDays(1) to Pair("내일은 신청하기로 한 날이에요", "‘${e.name}’ 신청 준비를 마무리해 봐요."),
                e.date to Pair("오늘은 신청하기로 한 날이에요", "‘${e.name}’ 신청하러 가 볼까요?"),
            ).map { (day, text) -> Triple(e, day, text) }
        }.mapNotNull { (e, day, text) ->
            val at = day.atTime(NOTIFY_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at > now) Triple(e, at, text) else null
        }.take(MAX_SLOTS)
        slots.forEachIndexed { i, (e, at, text) ->
            val intent = Intent(ctx, DdayReceiver::class.java).apply {
                putExtra(DdayNotifier.EXTRA_ID, BASE_CODE + i)
                putExtra(DdayNotifier.EXTRA_TITLE, text.first)
                putExtra(DdayNotifier.EXTRA_BODY, text.second)
                putExtra(DdayNotifier.EXTRA_ROUTE, "checklist/${e.policyId}")
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

    // ── 휴대폰에 적어 두기: 한 줄에 "checklistId\tpolicyId\tdate\tname" ──

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(userId: String) = "list_$userId"
    private fun currentKey(): String? = runCatching { AppContainer.session.userId }.getOrNull()?.let(::key)

    private fun read(): List<Entry> {
        val k = currentKey() ?: return emptyList()
        return prefs()?.getString(k, null).orEmpty().lines().mapNotNull { line ->
            val p = line.split('\t', limit = 4)
            if (p.size < 4) return@mapNotNull null
            val id = p[0].toLongOrNull() ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(p[2]) }.getOrNull() ?: return@mapNotNull null
            Entry(id, p[1], date, p[3])
        }
    }

    private fun write(entries: List<Entry>) {
        val k = currentKey() ?: return
        val text = entries.joinToString("\n") { "${it.checklistId}\t${it.policyId}\t${it.date}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" }
        prefs()?.edit()?.putString(k, text)?.apply()
    }
}
