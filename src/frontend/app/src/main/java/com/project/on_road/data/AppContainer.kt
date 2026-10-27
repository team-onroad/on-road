package com.project.on_road.data

import android.content.Context
import com.project.on_road.BuildConfig
import com.project.on_road.data.remote.Network
import com.project.on_road.data.remote.RemoteChatApi
import com.project.on_road.data.remote.RemoteCoachApi
import com.project.on_road.data.remote.RemoteOnRoadApi

object AppContainer {
    /** true: 백엔드 연결 / false: 가짜 데이터로 화면 확인 */
    private const val USE_REMOTE = true

    lateinit var session: SessionStore
        private set

    private val service by lazy { Network.create(BuildConfig.BASE_URL) }

    val api: OnRoadApi by lazy {
        if (USE_REMOTE) RemoteOnRoadApi(service) { if (::session.isInitialized) session else null }
        else FakeOnRoadApi()
    }
    val chatApi: ChatApi by lazy { if (USE_REMOTE) RemoteChatApi(service) else FakeChatApi() }

    /** 문서 AI (서버 전용): 서류 작성 화면·체크리스트 신청서 쓰기·상담 핸드오프 */
    val documents: DocumentRepository by lazy { DocumentRepository(service) { session.userId.orEmpty() } }

    /** 코치: 롤플레잉·서류 양식·초안은 서버, 흥미 탐색·직업 추천·로드맵·양식 올리기는 앱에서 처리 */
    val coachApi: CoachApi by lazy { if (USE_REMOTE) RemoteCoachApi(service) else FakeCoachApi() }

    fun init(context: Context) {
        if (!::session.isInitialized) {
            session = SessionStore(context.applicationContext)
        }
        ActivityStore.init(context)
        ActivityStore.useAccount(session.userId)
        FormParser.init(context)
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
            // 서버에 없는 계정 → 이 휴대폰 목록에서도 지운다
            session.removeAccount(id)
            AppState.updateStage(UserStage.YOUNG_ADULT)
        } else {
            applyProfile(user)
        }
    }

    /**
     * 기존 사용자로 들어가기 (GET /users/{id}).
     * - 성공: 그 계정으로 바꾸고 true
     * - 서버에 없는 계정(404): 이 휴대폰 목록에서 지우고 false
     * - 연결 실패: 예외를 그대로 던진다 (화면에서 안내)
     */
    suspend fun loginExisting(userId: String): Boolean {
        val user = api.getUser(userId.trim())
        if (user == null) {
            session.removeAccount(userId.trim())
            return false
        }
        applyProfile(user)
        return true
    }

    /**
     * 서버에서 받은 사용자 정보를 앱에 반영한다 (가입, 앱 실행, 회원정보 수정 뒤).
     * 퇴소일(d_date)도 서버 값으로 맞춰서 홈·타임라인 D-day 가 서버와 같게 한다.
     */
    fun applyProfile(user: UserProfile) {
        session.save(user.userId, user.name, user.stage)
        session.status = user.status
        if (USE_REMOTE) session.leaveDate = user.dDate
        AppState.updateStage(user.stage)
        // 계정을 바꾸면 그 계정의 음성 설정도 다시 읽는다
        AppState.load(session)
    }

    /**
     * 홈(3.4)에서 받은 사용자 정보를 앱에 반영한다. 서버가 단계를 다시 계산하므로 단계·퇴소일도 맞춘다.
     */
    fun applyDashboardUser(user: DashboardUser) {
        val id = session.userId ?: return
        session.save(id, user.name, user.stage)
        session.status = user.status
        if (USE_REMOTE) session.leaveDate = user.dDate
        AppState.updateStage(user.stage)
    }
}
