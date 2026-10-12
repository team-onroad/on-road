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

    /** 아동은 항상 켜짐, 청소년·자립준비청년은 토글 값 */
    val voiceOn: Boolean get() = stage == UserStage.CHILD || voiceToggle

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
