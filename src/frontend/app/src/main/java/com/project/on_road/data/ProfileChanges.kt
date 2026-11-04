package com.project.on_road.data

import com.project.on_road.data.remote.ProfileChangeDto
import java.time.LocalDate

/** 대화에서 찾은 프로필 변경 후보 (상담·문서 AI 공통). 사용자가 승인할 때만 반영한다 */
data class ProfileChange(
    val field: String,
    val label: String,
    val current: String?,
    val proposed: String,
    val proposedLabel: String,
)

fun ProfileChangeDto.toChange() = ProfileChange(field, label, current, proposed, proposedLabel)

object ProfileChanges {
    /** 승인한 변경을 한 번에 회원정보 수정(PATCH /users/{id})으로 보내고, 앱 상태(단계·D-day)도 맞춘다 */
    suspend fun apply(changes: List<ProfileChange>): UserProfile {
        // 지금 하는 일은 서버에 없는 값이라 기기에만 저장한다 (타임라인 개인화)
        changes.firstOrNull { it.field == "work" }?.let { c -> workOf(c.proposed)?.let { AppContainer.session.workStatus = it } }
        val toServer = changes.filter { it.field != "work" }
        val userId = AppContainer.session.userId.orEmpty()
        if (toServer.isEmpty()) {
            return AppContainer.api.getUser(userId) ?: error("사용자 정보를 찾지 못했어요")
        }
        val update = toServer.fold(UserUpdate()) { u, c ->
            when (c.field) {
                "name" -> u.copy(name = c.proposed)
                "region" -> u.copy(region = c.proposed)
                "phone" -> u.copy(phone = c.proposed)
                "status" -> u.copy(status = statusOf(c.proposed) ?: u.status)
                "d_date" -> u.copy(dDate = runCatching { LocalDate.parse(c.proposed) }.getOrNull() ?: u.dDate)
                else -> u
            }
        }
        val user = AppContainer.api.updateUser(userId, update)
        AppContainer.applyProfile(user)
        return user
    }

    private fun workOf(code: String): WorkStatus? = when (code) {
        "student" -> WorkStatus.STUDENT
        "working" -> WorkStatus.WORKING
        "job_seeking" -> WorkStatus.JOB_SEEKING
        "resting" -> WorkStatus.RESTING
        else -> null
    }

    private fun statusOf(code: String): LivingStatus? = when (code) {
        "in_care" -> LivingStatus.IN_FACILITY
        "leaving_soon" -> LivingStatus.LEAVING_SOON
        "left_care" -> LivingStatus.LEFT
        else -> null
    }
}
