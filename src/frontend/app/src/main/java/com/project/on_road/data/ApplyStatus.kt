package com.project.on_road.data

import androidx.compose.runtime.mutableStateMapOf

/**
 * 정책 신청 상태 (신청 관리).
 * 준비 중: 체크리스트 진행 중 → 신청함: 네 단계를 모두 끝냄 → 선정 · 미선정: 사용자가 결과를 고름.
 * 결과는 서버 API 가 없어서 휴대폰에 계정마다 저장한다 (session.applyResults).
 */
enum class ApplyStatus(val label: String) {
    PREPARING("준비 중"),
    APPLIED("신청함"),
    SELECTED("선정"),
    REJECTED("미선정"),
}

object ApplyResults {
    /** 정책 id → 결과 (선정 · 미선정만 저장. 없으면 체크리스트 진행으로 정한다) */
    private val results = mutableStateMapOf<String, ApplyStatus>()
    private var syncedUser: String? = "\u0000"

    /** 다른 계정으로 바뀌었으면 그 계정의 결과로 다시 채운다 */
    private fun sync() {
        val s = AppContainer.session
        if (syncedUser == s.userId) return
        syncedUser = s.userId
        results.clear()
        s.applyResults.forEach { (id, name) ->
            runCatching { ApplyStatus.valueOf(name) }.getOrNull()?.let { results[id] = it }
        }
    }

    fun statusOf(policyId: String, completed: Boolean): ApplyStatus {
        sync()
        if (!completed) return ApplyStatus.PREPARING
        return results[policyId] ?: ApplyStatus.APPLIED
    }

    /** 결과 고르기. 같은 결과를 다시 누르면 지운다 (신청함으로 돌아감) */
    fun pick(policyId: String, status: ApplyStatus) {
        sync()
        if (results[policyId] == status) results.remove(policyId) else results[policyId] = status
        AppContainer.session.applyResults = results.mapValues { it.value.name }
    }
}
