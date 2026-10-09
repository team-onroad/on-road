package com.project.on_road.data

import android.content.Context
import com.project.on_road.BuildConfig
import com.project.on_road.data.remote.Network
import com.project.on_road.data.remote.RemoteChatApi
import com.project.on_road.data.remote.RemoteOnRoadApi

object AppContainer {
    /** true: 백엔드 연결 / false: 가짜 데이터로 화면 확인 */
    private const val USE_REMOTE = true

    lateinit var session: SessionStore
        private set

    private val service by lazy { Network.create(BuildConfig.BASE_URL) }

    val api: OnRoadApi by lazy {
        if (USE_REMOTE) RemoteOnRoadApi(service) { if (::session.isInitialized) session.userId else null }
        else FakeOnRoadApi()
    }
    val chatApi: ChatApi by lazy { if (USE_REMOTE) RemoteChatApi(service) else FakeChatApi() }

    /** 코치(흥미 탐색·롤플레이·서류) API는 백엔드에 아직 없어서 가짜 유지 */
    val coachApi: CoachApi = FakeCoachApi()

    fun init(context: Context) {
        if (!::session.isInitialized) {
            session = SessionStore(context.applicationContext)
        }
        AppState.load(session)
    }

    /**
     * 앱 실행 시 저장된 user_id 확인 (GET /users/{id}).
     * - 404: 세션 삭제 → 온보딩으로
     * - 성공: 서버가 다시 계산한 단계(stage) 반영
     * - 서버 연결 실패: 저장된 값 그대로 진행
     */
    suspend fun verifySession() {
        if (!USE_REMOTE) return
        val id = session.userId ?: return
        val user = runCatching { api.getUser(id) }.getOrElse { return }
        if (user == null) {
            session.clear()
            AppState.updateStage(UserStage.YOUNG_ADULT)
        } else {
            session.save(user.userId, user.name, user.stage)
            AppState.updateStage(user.stage)
        }
    }
}
