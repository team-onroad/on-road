package com.project.on_road.notification

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 알림을 눌러 앱을 열 때 갈 화면. MainActivity 가 넣고, 내비게이션이 꺼내 이동한다 */
object PendingRoute {
    var route by mutableStateOf<String?>(null)

    fun from(intent: Intent?) {
        intent?.getStringExtra(DdayNotifier.EXTRA_ROUTE)?.takeIf { it.isNotBlank() }?.let { route = it }
        intent?.removeExtra(DdayNotifier.EXTRA_ROUTE)
    }

    fun take(): String? = route.also { route = null }
}
