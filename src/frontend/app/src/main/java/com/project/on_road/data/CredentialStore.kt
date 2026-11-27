package com.project.on_road.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * 이수한 교육 · 취득한 자격증 (3.37). 설정의 '교육 · 자격증' 화면과 정책 상세가 같이 본다.
 * 넣거나 지우면 맞춤 신청 로드맵(의무교육 이수 표시)을 다시 받는다.
 */
object CredentialStore {
    /** null: 아직 못 받음 */
    var items by mutableStateOf<List<Credential>?>(null)
        private set
    private var loadedFor: String? = null

    val educations: List<Credential> get() = items.orEmpty().filter { it.isEducation }
    val certificates: List<Credential> get() = items.orEmpty().filter { !it.isEducation }

    /** 이수했다고 넣은 정해진 교육인지 (settlement · finance) */
    fun hasEducation(code: String): Boolean = items.orEmpty().any { it.isEducation && it.code == code }

    /** 이 자격증을 이미 땄는지 (띄어쓰기 무시, "컴퓨터활용능력" ↔ "컴퓨터활용능력 2급"처럼 한쪽이 다른 쪽을 품으면 같다고 본다) */
    fun holds(certificate: String): Boolean {
        val want = certificate.replace(" ", "")
        if (want.isEmpty()) return false
        return certificates.any { c -> c.name.replace(" ", "").let { it.contains(want) || want.contains(it) } }
    }

    suspend fun refresh(force: Boolean = false): List<Credential>? {
        val userId = AppContainer.session.userId ?: return null
        if (!force && loadedFor == userId && items != null) return items
        return runCatching { AppContainer.api.getCredentials(userId) }.getOrNull()?.also {
            items = it
            loadedFor = userId
        }
    }

    suspend fun add(kind: String, name: String?, code: String?, doneOn: LocalDate?): Result<Credential> {
        val userId = AppContainer.session.userId ?: return Result.failure(IllegalStateException("로그인이 필요해요"))
        val result = BackgroundWork.write { AppContainer.api.addCredential(userId, kind, name, code, doneOn) }.await()
        val c = result.getOrNull() ?: return result
        items = listOf(c) + items.orEmpty().filterNot { it.id == c.id }
        loadedFor = userId
        if (c.isEducation) PlanData.refreshRoadmap()
        return result
    }

    suspend fun delete(item: Credential): Result<Unit> {
        val userId = AppContainer.session.userId ?: return Result.failure(IllegalStateException("로그인이 필요해요"))
        val before = items
        items = items.orEmpty().filterNot { it.id == item.id }
        val result = BackgroundWork.write { AppContainer.api.deleteCredential(userId, item.id) }.await()
        if (result.isFailure) items = before
        else if (item.isEducation) PlanData.refreshRoadmap()
        return result
    }

    /** 계정을 바꾸거나 지우면 */
    fun clear() {
        items = null
        loadedFor = null
    }
}
