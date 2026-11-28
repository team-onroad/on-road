package com.project.on_road.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 화면에서 마지막으로 본 내용 (앱이 켜져 있는 동안만, 계정별).
 * 하던 화면에서 나갔다가 다시 들어오면, 불러오는 중 표시 없이 보던 내용을 바로 보여 주고
 * 뒤에서 조용히 새로 받아 바꾼다. (예전에는 들어올 때마다 비었다가 '띡' 하고 내용이 나타났다)
 */
object ScreenCache {
    private val map = ConcurrentHashMap<String, Any>()

    private fun key(name: String) = "${AppContainer.session.userId}|$name"

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(name: String): T? = map[key(name)] as? T

    fun put(name: String, value: Any?) {
        if (value == null) map.remove(key(name)) else map[key(name)] = value
    }

    fun clear() = map.clear()
}
