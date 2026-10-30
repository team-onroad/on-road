package com.project.on_road.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.project.on_road.data.AppState
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadShapes
import androidx.compose.foundation.border
import java.util.Locale

/** 기기 기본 TTS로 읽어 주기. 음성은 기기 안에서만 처리하고 서버로 보내지 않는다. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    /** TTS 엔진이 없거나 초기화 실패: 읽기 끝 콜백을 바로 불러 통화 흐름이 멈추지 않게 */
    private var failed = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private var seq = 0
    /** 읽기가 끝나면 부를 콜백 (중간에 끊긴 건 부르지 않는다) */
    private val onDone = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.KOREAN)
            tts.setSpeechRate(0.95f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id -> onDone.remove(id)?.let { cb -> main.post(cb) } }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { id -> onDone.remove(id)?.let { cb -> main.post(cb) } }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let { onDone.remove(it) }
                }
            })
            ready = true
        } else {
            failed = true
        }
        pending?.let { (text, cb) -> speak(text, cb) }
        pending = null
    }

    fun speak(text: String, onFinished: (() -> Unit)? = null) {
        if (failed) {
            onFinished?.let { main.post(it) }
            return
        }
        if (!ready) {
            pending = text to onFinished
            return
        }
        onDone.clear()
        val id = "onroad-${++seq}"
        if (onFinished != null) onDone[id] = onFinished
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            onDone.remove(id)?.let { main.post(it) }
        }
    }

    fun stop() {
        pending = null
        onDone.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        onDone.clear()
        tts.stop()
        tts.shutdown()
    }
}

val LocalSpeaker = staticCompositionLocalOf<Speaker?> { null }

@Composable
fun ProvideVoice(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    // 음성을 끄면 읽던 것도 멈춘다
    LaunchedEffect(AppState.voiceOn) { if (!AppState.voiceOn) speaker.stop() }
    CompositionLocalProvider(LocalSpeaker provides speaker, content = content)
}

/** 음성이 켜져 있을 때 한 번 읽어 준다. key가 바뀌면 다시 읽는다. */
@Composable
fun SpeakOnce(text: String, key: Any = text) {
    val speaker = LocalSpeaker.current
    LaunchedEffect(key) { if (AppState.voiceOn && text.isNotBlank()) speaker?.speak(text) }
}

/** 기기 음성 인식 화면을 띄워 말한 내용을 글자로 받는다. (녹음 권한 불필요) */
@Composable
fun rememberSpeechInput(onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(callback)
        }
    }
    return remember(launcher) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "말해 주세요")
            }
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "이 기기에서는 음성 입력을 쓸 수 없어요", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** 읽어 주기 버튼. 음성이 꺼져 있으면 보이지 않는다. */
@Composable
fun SpeakButton(text: String, modifier: Modifier = Modifier, tint: Color = OnRoadColors.Primary) {
    if (!AppState.voiceOn) return
    val speaker = LocalSpeaker.current
    IconButton(onClick = { speaker?.speak(text) }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "읽어 주기", tint = tint)
    }
}

/** 말로 입력하기 버튼. 음성이 꺼져 있으면 보이지 않는다. */
@Composable
fun MicButton(onResult: (String) -> Unit, modifier: Modifier = Modifier, dim: Dp = 48.dp) {
    if (!AppState.voiceOn) return
    val listen = rememberSpeechInput(onResult)
    Box(
        modifier = modifier
            .size(dim)
            .clip(OnRoadShapes.Field)
            .background(OnRoadColors.PrimarySoft)
            .border(1.dp, OnRoadColors.PrimaryOutlineSoft, OnRoadShapes.Field)
            .clickable(onClick = listen),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = "말로 입력", tint = OnRoadColors.Primary)
    }
}
