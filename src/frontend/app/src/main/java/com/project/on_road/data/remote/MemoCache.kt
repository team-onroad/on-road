package com.project.on_road.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 앱 메모리 캐시 (응답 속도). 같은 화면을 다시 열 때 서버에 또 묻지 않는다.
 * 앱을 끄면 비워지고, 사용자별로 다른 값은 키에 user_id 를 넣는다.
 */
class MemoCache<K, V>(private val ttlMillis: Long, private val maxSize: Int = 100) {
    private val data = LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true)
    private val mutex = Mutex()

    suspend fun getOrLoad(key: K, load: suspend () -> V): V {
        val now = System.currentTimeMillis()
        mutex.withLock { data[key]?.takeIf { now - it.first < ttlMillis }?.let { return it.second } }
        val value = load()
        mutex.withLock {
            data[key] = System.currentTimeMillis() to value
            while (data.size > maxSize) data.remove(data.keys.first())
        }
        return value
    }

    suspend fun invalidate(key: K) = mutex.withLock { data.remove(key) }

    suspend fun clear() = mutex.withLock { data.clear() }
}
