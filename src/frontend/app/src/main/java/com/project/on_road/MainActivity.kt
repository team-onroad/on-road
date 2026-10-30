package com.project.on_road

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.project.on_road.data.AppContainer
import com.project.on_road.notification.Notifications
import com.project.on_road.notification.PendingRoute
import com.project.on_road.ui.screens.navigation.OnRoadNavHost
import com.project.on_road.ui.theme.OnRoadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(this)
        // 알림을 눌러 열었으면 그 화면으로, 예약한 알림은 다시 맞춘다
        PendingRoute.from(intent)
        runCatching { Notifications.applyAll(this) }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            OnRoadTheme {
                OnRoadNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PendingRoute.from(intent)
    }
}
