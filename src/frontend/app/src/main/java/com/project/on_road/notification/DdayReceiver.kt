package com.project.on_road.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DdayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DdayNotifier.show(
            context,
            intent.getIntExtra(DdayNotifier.EXTRA_ID, 0),
            intent.getStringExtra(DdayNotifier.EXTRA_TITLE) ?: "퇴소 준비 알림",
            intent.getStringExtra(DdayNotifier.EXTRA_BODY) ?: "오늘 챙길 일이 있어요."
        )
    }
}
