package com.project.on_road

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        hideSystemBars()
        setContent {
            OnRoadTheme {
                OnRoadNavHost()
            }
        }
    }

    /**
     * 위 상태바(시간 · 배터리)는 보이고, 아래 시스템 바만 숨긴다.
     * 아래 끝에서 쓸어 올리면 잠깐 나타났다가 다시 숨는다 (필요할 때만 보이게).
     * 숨긴 바 자리만큼 앱 아래 여백은 OnRoadNavHost 에서 띄운다.
     */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.show(WindowInsetsCompat.Type.statusBars())
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    // 다른 앱에 다녀오거나 키보드를 닫은 뒤에도 다시 숨긴다
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PendingRoute.from(intent)
    }
}
