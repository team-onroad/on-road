package com.project.on_road.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

/**
 * 서버 스트리밍 응답(text/event-stream) 읽기. 서버가 문장을 만들 때마다 보내 준다.
 * - delta: {"text": "문장"} 여러 번 → [onDelta] 에 지금까지 받은 글 전체를 넘긴다 (화면 스레드)
 * - done: 보통 응답과 같은 JSON (+ replace) → 돌려준다
 * - error: {"error": {code, message}} → ApiException
 * 요청 형식 · 사용자 오류처럼 답을 만들기 전 오류는 보통 HTTP 오류로 와서 Network.call 이 처리한다.
 */
internal object Sse {
    private val gson = Gson()

    private data class Delta(val text: String?)

    suspend fun <T> read(body: ResponseBody, doneType: Class<T>, onDelta: ((String) -> Unit)?): Pair<T, String> =
        withContext(Dispatchers.IO) {
            val shown = StringBuilder()
            var done: T? = null
            body.use { b ->
                val src = b.source()
                var name = "message"
                val data = StringBuilder()
                suspend fun dispatch() {
                    if (data.isEmpty()) return
                    val json = data.toString()
                    data.clear()
                    when (name) {
                        "delta" -> {
                            val t = gson.fromJson(json, Delta::class.java)?.text.orEmpty().trim()
                            if (t.isNotEmpty()) {
                                if (shown.isNotEmpty()) shown.append(' ')
                                shown.append(t)
                                val now = shown.toString()
                                onDelta?.let { cb -> withContext(Dispatchers.Main) { cb(now) } }
                            }
                        }
                        "done" -> done = gson.fromJson(json, doneType)
                        "error" -> {
                            val e = runCatching { gson.fromJson(json, ErrorBodyDto::class.java) }.getOrNull()?.error
                            throw ApiException(503, e?.code ?: "AI_UNAVAILABLE", e?.message ?: "AI 답변 중 오류가 발생했습니다.")
                        }
                    }
                    name = "message"
                }
                while (done == null) {
                    val line = src.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> dispatch()
                        line.startsWith("event:") -> name = line.substringAfter(":").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(":").removePrefix(" "))
                        }
                    }
                }
                if (done == null) dispatch()
            }
            val result = done ?: throw java.io.IOException("스트림이 중간에 끊겼어요")
            result to shown.toString()
        }
}

/** 서버에 스트리밍 주소가 아직 없을 때 (새 서버를 올리기 전) → 보통 요청으로 */
internal fun ApiException.noStreamRoute(): Boolean = (httpStatus == 404 || httpStatus == 405) && code.startsWith("HTTP_")
