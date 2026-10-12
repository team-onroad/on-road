package com.project.on_road.notification

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.project.on_road.MainActivity
import com.project.on_road.R
import com.project.on_road.data.TimelineRules
import com.project.on_road.data.TimelineTask
import java.time.LocalDate
import java.time.ZoneId

object DdayNotifier {
    private const val CHANNEL_ID = "dday"
    private const val BASE_CODE = 7000
    private const val PREVIEW_ID = 6999
    private const val NOTIFY_HOUR = 9

    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "퇴소 D-day 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "퇴소 준비 할 일을 날짜에 맞춰 알려 드려요."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 퇴소일 기준으로 아직 지나지 않은 할 일마다 아침 9시 알림을 예약한다. */
    fun schedule(context: Context, leaveDate: LocalDate) {
        cancel(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        TimelineRules.tasks.forEachIndexed { i, task ->
            val at = leaveDate.plusDays(task.offsetDays.toLong())
                .atTime(NOTIFY_HOUR, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            if (at > now) {
                val pi = PendingIntent.getBroadcast(
                    context,
                    BASE_CODE + i,
                    receiverIntent(context, i, task),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        TimelineRules.tasks.indices.forEach { i ->
            PendingIntent.getBroadcast(
                context,
                BASE_CODE + i,
                Intent(context, DdayReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let {
                alarm.cancel(it)
                it.cancel()
            }
        }
    }

    /** 데모용: 다가오는 할 일 하나를 지금 바로 알림으로 보여 준다. */
    fun preview(context: Context, leaveDate: LocalDate) {
        val today = LocalDate.now()
        val task = TimelineRules.tasks.firstOrNull { !leaveDate.plusDays(it.offsetDays.toLong()).isBefore(today) }
            ?: TimelineRules.tasks.last()
        show(context, PREVIEW_ID, titleFor(task), task.description)
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, id: Int, title: String, body: String) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2E8A7C.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    private fun receiverIntent(context: Context, index: Int, task: TimelineTask) =
        Intent(context, DdayReceiver::class.java).apply {
            putExtra(EXTRA_ID, BASE_CODE + index)
            putExtra(EXTRA_TITLE, titleFor(task))
            putExtra(EXTRA_BODY, task.description)
        }

    private fun titleFor(task: TimelineTask): String {
        val label = when {
            task.offsetDays < 0 -> "퇴소 D${task.offsetDays}"
            task.offsetDays == 0 -> "오늘은 퇴소하는 날"
            else -> "퇴소 D+${task.offsetDays}"
        }
        return if (task.offsetDays == 0) label else "$label, ${task.title}"
    }
}
