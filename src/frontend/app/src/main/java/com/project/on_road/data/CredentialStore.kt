package com.project.on_road.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 이수한 교육 · 취득한 자격증 (3.37). 설정의 '교육 · 자격증' 화면과 정책 상세 · 진로 · 상담 칩이 같이 본다.
 * 넣거나 지우면 맞춤 신청 로드맵(의무교육 이수 표시)을 다시 받는다.
 */
object CredentialStore {
    private var loaded by mutableStateOf<List<Credential>?>(null)
    private var loadedFor by mutableStateOf<String?>(null)
    /** 여러 화면이 동시에 처음 불러도 서버에는 한 번만 묻는다 */
    private val lock = Mutex()

    /** 지금 계정의 목록. null: 아직 못 받음 (계정을 바꾸면 다시 받기 전까지 null — 다른 사람 것이 보이지 않게) */
    val items: List<Credential>?
        get() = loaded.takeIf { loadedFor != null && loadedFor == AppContainer.session.userId }

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
        return lock.withLock {
            // 기다리는 동안 다른 화면이 이미 받아 왔으면 그걸 쓴다
            if (!force && loadedFor == userId && loaded != null) return@withLock loaded
            runCatching { AppContainer.api.getCredentials(userId) }.getOrNull()?.also {
                loaded = it
                loadedFor = userId
            }
        }
    }

    suspend fun add(kind: String, name: String?, code: String?, doneOn: LocalDate?): Result<Credential> {
        val userId = AppContainer.session.userId ?: return Result.failure(IllegalStateException("로그인이 필요해요"))
        val result = BackgroundWork.write { AppContainer.api.addCredential(userId, kind, name, code, doneOn) }.await()
        val c = result.getOrNull() ?: return result
        if (loadedFor == userId) {
            loaded = listOf(c) + loaded.orEmpty().filterNot { it.id == c.id }
        } else {
            refresh(force = true) // 다른 계정 목록이 남아 있었으면 새로 받는다
        }
        if (c.isEducation) PlanData.refreshRoadmap()
        return result
    }

    suspend fun delete(item: Credential): Result<Unit> {
        val userId = AppContainer.session.userId ?: return Result.failure(IllegalStateException("로그인이 필요해요"))
        val before = loaded
        loaded = loaded.orEmpty().filterNot { it.id == item.id }
        val result = BackgroundWork.write { AppContainer.api.deleteCredential(userId, item.id) }.await()
        if (result.isFailure) loaded = before
        else if (item.isEducation) PlanData.refreshRoadmap()
        return result
    }

    /** 계정을 바꾸거나 지우면 */
    fun clear() {
        loaded = null
        loadedFor = null
    }
}
