package com.project.on_road.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 지금 계정의 회원정보를 앱 전체가 같이 쓴다.
 * 화면마다 서버에 따로 묻고 기다리면 나이 · 지역 줄이 늦게 뜨므로, 받아 둔 값을 먼저 보여 주고 뒤에서 새로 고친다.
 */
object ProfileStore {
    private var value by mutableStateOf<UserProfile?>(null)

    /** 회원정보가 바뀔 때마다 1씩 오른다 (홈 추천처럼 조건으로 고르는 화면이 다시 받게) */
    var version by mutableIntStateOf(0)
        private set

    /** 지금 계정의 받아 둔 회원정보. 다른 계정 것이면 null */
    val current: UserProfile?
        get() = value?.takeIf { it.userId == AppContainer.session.userId }

    fun set(user: UserProfile) {
        val before = current
        value = user
        if (before != null && before != user) changed()
    }

    /** 서버에서 다시 받아 맞춘다. 못 받으면 받아 둔 값 그대로 */
    suspend fun refresh(): UserProfile? {
        val id = AppContainer.session.userId ?: return null
        val user = runCatching { AppContainer.api.getUser(id) }.getOrNull() ?: return current
        if (user.userId == AppContainer.session.userId) set(user)
        return user
    }

    /** 조건이 바뀌었으니 조건으로 고른 결과를 버린다 */
    private fun changed() {
        HomeCache.clear()
        version++
    }
}

/** 홈 '나에게 맞는 지원 정책'을 계정별로 잠깐 들고 있는다 (홈에 돌아올 때 빈칸 없이 바로) */
object HomeCache {
    private val recommended = mutableMapOf<String, List<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(userId: String): List<T>? = recommended[userId] as List<T>?

    fun put(userId: String, list: List<Any>) { recommended[userId] = list }

    fun clear() = recommended.clear()
}
