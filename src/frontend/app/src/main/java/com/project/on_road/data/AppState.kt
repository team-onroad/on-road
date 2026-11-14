package com.project.on_road.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 화면이 바로 반응해야 하는 앱 전역 상태 (단계, 음성). SessionStore 값을 Compose 상태로 들고 있다. */
object AppState {
    var stage by mutableStateOf(UserStage.YOUNG_ADULT)
        private set
    var voiceToggle by mutableStateOf(false)
        private set

    /**
     * 쓰는 중에 서버가 '이 사용자가 없다'(USER_NOT_FOUND)고 답했는지.
     * 서버 DB 를 새로 만들면 휴대폰에 남은 계정이 서버에 없어서, 화면마다 '불러오지 못했어요'만 반복됐다.
     * → 한 번 안내하고 처음부터 다시 시작하게 한다 (OnRoadNavHost).
     */
    var accountMissing by mutableStateOf(false)

    /** 아동은 항상 켜짐, 청소년·자립준비청년은 토글 값 */
    val voiceOn: Boolean get() = voiceToggle

    fun load(session: SessionStore) {
        stage = session.stage
        voiceToggle = session.voiceToggle
    }

    fun updateStage(value: UserStage) {
        stage = value
    }

    fun updateVoice(session: SessionStore, on: Boolean) {
        session.voiceToggle = on
        voiceToggle = on
    }
}
