package com.project.on_road.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

enum class ListenState { IDLE, LISTENING, ERROR }

/**
 * 통화형 연습용 음성 인식. 기기 음성 인식 창을 띄우지 않고 앱 안에서 듣는다.
 * 말하는 동안 글자가 바로 보이고(partial), 말이 끝나면 onText 로 넘긴다.
 * 기기 인식기는 잠깐만 쉬어도 말을 끝낸 것으로 본다. 그래서 한 마디를 들은 뒤 바로 한 번 더 듣고,
 * 이어서 말하면 붙이고(최대 MAX_PIECES 번), 조용하면 그때 지금까지 들은 말을 넘긴다. '말 다 했어요'를 누르면 바로 넘긴다.
 * 녹음 권한(RECORD_AUDIO)이 필요하다.
 */
class CallListener(context: Context, private val onText: (text: String, others: List<String>) -> Unit) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** 아무 말도 못 알아들었을 때 조용히 한 번 더 듣는다 */
    private var retries = 0
    /** 지금까지 들은 말 (쉬었다 이어 말한 것까지) */
    private var heard = ""
    private var pieces = 0
    /** '말 다 했어요'를 눌렀다: 이번 결과를 받으면 더 듣지 않고 넘긴다 */
    private var stopping = false
    /** 이번 듣기에서 들리는 중인 글자 (heard 를 뺀 것) */
    private var current = ""
    private val app = context.applicationContext
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(app)) SpeechRecognizer.createSpeechRecognizer(app) else null

    var state by mutableStateOf(ListenState.IDLE)
        private set
    /** 말하는 중 미리 보이는 글자 (앞서 들은 말 + 지금 들리는 말) */
    var partial by mutableStateOf("")
        private set
    /** 한 마디를 들은 뒤 이어서 듣는 중 (화면에 '이어서 말해도 돼요') */
    var continuing by mutableStateOf(false)
        private set
    /** 목소리 크기 0~1 (마이크 버튼 테두리 크기) */
    var level by mutableStateOf(0f)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val available: Boolean get() = recognizer != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state = ListenState.LISTENING
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                level = 0f
            }
            override fun onError(error: Int) {
                level = 0f
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                // 이어서 듣는 중에 조용했으면: 지금까지 들은 말로 끝낸다
                if (quiet && heard.isNotEmpty() && clean(current).isEmpty()) {
                    finishWith(heard)
                    return
                }
                // 말을 시작하기 전에 끊긴 거면 한 번은 다시 듣는다 (말하기 전 뜸 들이는 경우)
                if (quiet && current.isBlank() && retries > 0) {
                    retries--
                    main.postDelayed({ listen() }, 250)
                    return
                }
                // 인식기가 아직 바쁘면 잠깐 뒤 다시
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && retries > 0) {
                    retries--
                    recognizer?.cancel()
                    main.postDelayed({ listen() }, 400)
                    return
                }
                // 말하던 중 끊겼으면 들은 데까지라도 쓴다
                val got = join(heard, clean(current))
                if (quiet && got.isNotEmpty()) {
                    finishWith(got)
                    return
                }
                heard = ""
                current = ""
                partial = ""
                continuing = false
                errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "잘 듣지 못했어요. 마이크를 누르고 다시 말해 주세요."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "음성 인식이 연결되지 않아요. 글로 써 주세요."
                    else -> "음성 인식이 멈췄어요. 마이크를 다시 눌러 주세요."
                }
                state = ListenState.ERROR
            }
            override fun onResults(results: Bundle?) {
                level = 0f
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    .map(::clean).filter { it.isNotEmpty() }.distinct()
                val text = all.firstOrNull() ?: clean(current)
                current = ""
                if (text.isEmpty() && heard.isEmpty()) {
                    partial = ""
                    state = ListenState.IDLE
                    errorMessage = "잘 듣지 못했어요. 마이크를 누르고 다시 말해 주세요."
                    state = ListenState.ERROR
                    return
                }
                // 인식기가 앞서 들은 말을 다시 보내는 경우가 있어 겹치면 붙이지 않는다
                heard = when {
                    text.isEmpty() || heard.endsWith(text) -> heard
                    heard.isNotEmpty() && text.startsWith(heard) -> text
                    else -> join(heard, text)
                }
                pieces++
                // 다른 후보는 한 마디로 끝났을 때만 (이어 말한 문장은 후보를 합칠 수 없음)
                val others = if (pieces == 1) all.drop(1).take(2) else emptyList()
                if (stopping || pieces >= MAX_PIECES) {
                    finishWith(heard, others)
                } else {
                    // 잠깐 쉰 것일 수 있으니 한 번 더 듣는다
                    partial = heard
                    continuing = true
                    pendingOthers = others
                    main.postDelayed({ listen(keep = true) }, 150)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    current = it
                    partial = join(heard, clean(it))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private var pendingOthers: List<String> = emptyList()

    private fun finishWith(text: String, others: List<String> = pendingOthers) {
        main.removeCallbacksAndMessages(null)
        heard = ""
        current = ""
        pieces = 0
        stopping = false
        continuing = false
        pendingOthers = emptyList()
        partial = ""
        level = 0f
        state = ListenState.IDLE
        onText(text, others)
    }

    fun start() {
        retries = 1
        heard = ""
        pieces = 0
        stopping = false
        continuing = false
        pendingOthers = emptyList()
        listen()
    }

    private fun listen(keep: Boolean = false) {
        val r = recognizer ?: return
        errorMessage = null
        current = ""
        if (!keep) partial = ""
        state = ListenState.LISTENING
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // 생각하며 말하는 사람도 끊기지 않게 침묵을 넉넉히 기다린다
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        runCatching { r.startListening(intent) }.onFailure {
            errorMessage = "음성 인식을 시작하지 못했어요. 글로 써 주세요."
            state = ListenState.ERROR
        }
    }

    /** 말 다 했어요: 지금까지 들은 걸로 끝낸다 */
    fun stop() {
        // 이어 듣기만 기다리는 중(새로 들린 말 없음)이면 바로 넘긴다
        if (continuing && clean(current).isEmpty() && heard.isNotEmpty()) {
            recognizer?.cancel()
            finishWith(heard)
            return
        }
        stopping = true
        recognizer?.stopListening()
    }

    /** 듣기 취소 (결과 버림) */
    fun cancel() {
        main.removeCallbacksAndMessages(null)
        retries = 0
        recognizer?.cancel()
        level = 0f
        heard = ""
        current = ""
        pieces = 0
        stopping = false
        continuing = false
        pendingOthers = emptyList()
        partial = ""
        state = ListenState.IDLE
    }

    fun clearError() {
        errorMessage = null
        if (state == ListenState.ERROR) state = ListenState.IDLE
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
    }

    companion object {
        /** 쉬었다 이어 말한 것을 몇 마디까지 붙일지 */
        private const val MAX_PIECES = 4

        private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

        /** 말 앞 군말("음", "어") 정리, 띄어쓰기 정리 */
        private val FILLER = Regex("""^((음+|어+|으음|흠)[,.\s…]+)+""")

        fun clean(text: String): String =
            text.trim().replace(Regex("""\s+"""), " ").replace(FILLER, "").trim()
    }
}

@Composable
fun rememberCallListener(onText: (text: String, others: List<String>) -> Unit): CallListener {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onText)
    val listener = remember { CallListener(context) { t, o -> callback(t, o) } }
    DisposableEffect(listener) { onDispose { listener.destroy() } }
    return listener
}
