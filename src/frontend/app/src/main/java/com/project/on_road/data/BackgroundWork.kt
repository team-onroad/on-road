package com.project.on_road.data

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 화면을 나가도 끝까지 하는 일 (서류 내보내기 · 받은 양식 읽기처럼 오래 걸리는 것).
 * 화면의 ViewModel 이 아니라 앱 전체에서 돌아서, 다른 화면으로 가도 멈추지 않는다 (앱이 꺼지면 멈춤).
 *
 * - 같은 key 의 일이 돌고 있으면 새로 시작하지 않고 그 일을 돌려준다 (두 번 누르거나 다시 들어와도 한 번만)
 * - 화면은 watch / unwatch 로 '보고 있음'을 알린다. 아무도 안 보는 동안 끝나면 onAwayDone 으로 알린다 (알림 문구)
 * - 화면이 결과를 받아 가면 consume 으로 지운다. 안 받아 간 결과는 다음에 그 화면을 열 때 받을 수 있다
 */
object BackgroundWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Deferred<Result<Any?>>>()
    private val watchers = mutableMapOf<String, Int>()
    private val doneAway = mutableSetOf<String>()

    /** 지금 도는 일의 key (화면에 '진행 중'을 보여 줄 때) */
    val running = mutableStateListOf<String>()

    @Suppress("UNCHECKED_CAST")
    fun <T> start(key: String, onAwayDone: ((Result<T>) -> Unit)? = null, block: suspend () -> T): Deferred<Result<T>> {
        jobs[key]?.takeIf { it.isActive }?.let { return it as Deferred<Result<T>> }
        doneAway.remove(key)
        running.add(key)
        // 바로 실행(immediate)하지 않고 다음 차례에 시작한다: 파일이 너무 커서 곧바로 실패하는 경우에도
        // 화면이 watch 를 먼저 걸어서, '나가 있는 동안 끝남'(알림 문구)으로 잘못 처리되지 않게
        val job = scope.async(Dispatchers.Main) {
            val result = runCatching { block() }
            running.remove(key)
            if ((watchers[key] ?: 0) == 0) {
                doneAway.add(key)
                onAwayDone?.invoke(result)
            }
            result
        }
        jobs[key] = job as Deferred<Result<Any?>>
        return job
    }

    /**
     * 저장 · 체크 · 지우기처럼 서버에 꼭 들어가야 하는 짧은 요청.
     * 화면을 바로 나가도 취소되지 않고 끝까지 보낸다. 화면은 await() 로 결과(Result)를 받아 반영한다
     * (화면이 이미 없으면 결과만 버려진다)
     */
    fun <T> write(block: suspend () -> T): Deferred<Result<T>> = scope.async { runCatching { block() } }

    /** 돌고 있거나, 끝났지만 아직 화면이 받아 가지 않은 일 */
    @Suppress("UNCHECKED_CAST")
    fun <T> find(key: String): Deferred<Result<T>>? = jobs[key] as Deferred<Result<T>>?

    /** 아무도 안 보는 동안 끝났는지 (이미 알림으로 알려 줬으면 화면이 또 알리지 않게) */
    fun finishedAway(key: String): Boolean = key in doneAway

    fun watch(key: String) {
        watchers[key] = (watchers[key] ?: 0) + 1
    }

    fun unwatch(key: String) {
        val n = (watchers[key] ?: 0) - 1
        if (n <= 0) watchers.remove(key) else watchers[key] = n
    }

    /** 화면이 결과를 받아 갔으면 지운다 */
    fun consume(key: String) {
        if (jobs[key]?.isActive == true) return
        jobs.remove(key)
        doneAway.remove(key)
    }
}
