package com.opicih.coach.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow

/**
 * Long-answer speech recognition with Android SpeechRecognizer.
 * The recognizer stops after ~2 s of silence, so while a session is active it is restarted automatically and
 * segments are accumulated. onRmsChanged dB values are kept as a loudness timeline for PauseAnalyzer.
 * Must be used on the main thread.
 */
class LiveRecognizer(private val context: Context) {
    data class Snapshot(
        val active: Boolean = false,
        val finalText: String = "",
        val partial: String = "",
        val level: Float = 0f,
        val error: String? = null,
        val restarts: Int = 0,
    ) {
        val fullText: String get() = listOf(finalText, partial).filter { it.isNotBlank() }.joinToString(" ")
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionActive = false
    private val segments = mutableListOf<String>()
    private var startedAt = 0L
    private var lastLevelAt = 0L
    private var consecutiveErrors = 0

    /** (elapsed ms, rms dB) samples. */
    val levels = mutableListOf<Pair<Long, Float>>()

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    val elapsedMs: Long get() = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    fun start() {
        stop(cancelOnly = true)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = Snapshot(error = "이 기기에서 음성 인식 서비스를 찾지 못했습니다. Google 앱 또는 음성 인식 서비스를 설치하거나 '녹음 우선' 모드를 사용하세요.")
            return
        }
        segments.clear(); levels.clear(); consecutiveErrors = 0
        startedAt = SystemClock.elapsedRealtime()
        sessionActive = true
        _state.value = Snapshot(active = true)
        listenOnce()
    }

    private var finishing: CompletableDeferred<Unit>? = null

    /**
     * Ends the session gracefully: asks the engine to stop, then waits (max 2.5 s) for the last
     * onResults / onSegmentResults before destroying it. Destroying immediately drops the final text.
     */
    suspend fun finish(): String {
        val r = recognizer
        if (r != null && sessionActive) {
            sessionActive = false
            val wait = CompletableDeferred<Unit>()
            finishing = wait
            runCatching { r.stopListening() }
            withTimeoutOrNull(2500) { wait.await() }
            finishing = null
        }
        return stop()
    }

    /** Ends the session immediately and returns the text received so far. */
    fun stop(cancelOnly: Boolean = false): String {
        sessionActive = false
        recognizer?.let { r -> runCatching { if (cancelOnly) r.cancel() else r.stopListening() }; runCatching { r.destroy() } }
        recognizer = null
        val text = (segments + listOfNotNull(_state.value.partial.takeIf { it.isNotBlank() })).joinToString(" ").trim()
        if (!cancelOnly) _state.value = _state.value.copy(active = false, finalText = text, partial = "")
        return text
    }

    fun release() { stop(cancelOnly = true); main.removeCallbacksAndMessages(null) }

    private fun listenOnce() {
        if (!sessionActive) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Ask for longer silence tolerance. Many engines ignore these, which is why we also auto-restart.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+: one continuous session that delivers each sentence via onSegmentResults.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120_000) // Google engine rejects longer values
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        runCatching { r.startListening(intent) }.onFailure {
            _state.value = _state.value.copy(error = "음성 인식을 시작하지 못했습니다: ${it.message}")
        }
    }

    private fun restartSoon() {
        if (!sessionActive) return
        _state.value = _state.value.copy(restarts = _state.value.restarts + 1)
        main.postDelayed({ listenOnce() }, 150)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val now = elapsedMs
            if (now - lastLevelAt >= 50) { levels += now to rmsdB; lastLevelAt = now }
            _state.value = _state.value.copy(level = rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            finishing?.let { it.complete(Unit); return }
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT ||
                error == 11 /* ERROR_SERVER_DISCONNECTED */
            if (sessionActive && recoverable && consecutiveErrors < 20) {
                consecutiveErrors++
                restartSoon(); return
            }
            if (sessionActive && error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) sessionActive = false
            if (sessionActive && (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER)) {
                sessionActive = false
            }
            _state.value = _state.value.copy(active = sessionActive, error = errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) segments += text.trim()
            _state.value = _state.value.copy(finalText = segments.joinToString(" "), partial = "")
            finishing?.let { it.complete(Unit); return }
            restartSoon()
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            consecutiveErrors = 0
            val text = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) segments += text.trim()
            _state.value = _state.value.copy(finalText = segments.joinToString(" "), partial = "")
        }

        override fun onEndOfSegmentedSession() {
            finishing?.let { it.complete(Unit); return }
            restartSoon()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _state.value = _state.value.copy(partial = text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        fun errorMessage(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 오류입니다. 다른 앱이 마이크를 사용 중인지 확인하세요."
            SpeechRecognizer.ERROR_CLIENT -> "음성 인식 클라이언트 오류입니다. 다시 시도하세요."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없습니다. 설정에서 마이크 권한을 허용하세요."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 오류로 인식이 중단되었습니다. 지금까지 인식된 내용은 보존됩니다. 온디바이스 인식 또는 '녹음 우선' 모드를 사용하세요."
            SpeechRecognizer.ERROR_NO_MATCH -> "인식된 영어 문장이 없습니다."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기가 사용 중입니다. 잠시 후 다시 시도하세요."
            SpeechRecognizer.ERROR_SERVER -> "인식 서버 오류입니다."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 감지되지 않았습니다."
            11 -> "음성 인식 서비스 연결이 끊겼습니다. 다시 시도하세요."
            12, 13 -> "이 기기 인식기가 영어(en-US)를 지원하지 않습니다. Google 음성 인식 언어 팩을 설치하세요."
            else -> "음성 인식 오류 (코드 $code)"
        }
    }
}
