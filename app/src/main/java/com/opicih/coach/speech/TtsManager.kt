package com.opicih.coach.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Thin wrapper over Android TextToSpeech (free, on device). One instance for the app. */
class TtsManager(context: Context) {
    enum class State { INITIALIZING, READY, NO_ENGINE, NO_ENGLISH }

    private val _state = MutableStateFlow(State.INITIALIZING)
    val state: StateFlow<State> = _state
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val pending = mutableMapOf<String, (Boolean) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) { _state.value = State.NO_ENGINE; return@TextToSpeech }
        val r = setLanguage()
        _state.value = if (r) State.READY else State.NO_ENGLISH
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { _speaking.value = false; id?.let { pending.remove(it)?.invoke(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false; id?.let { pending.remove(it)?.invoke(false) } }
            override fun onStop(id: String?, interrupted: Boolean) { _speaking.value = false; id?.let { pending.remove(it)?.invoke(false) } }
        })
    }

    private fun setLanguage(): Boolean {
        val r = tts.setLanguage(Locale.US)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun speak(text: String, rate: Float = 1.0f) {
        if (_state.value != State.READY) return
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    /** Speaks and suspends until finished. Returns false if TTS is unavailable or interrupted. */
    suspend fun speakAndWait(text: String, rate: Float = 1.0f): Boolean {
        if (_state.value != State.READY || text.isBlank()) return false
        return suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = { ok -> if (cont.isActive) cont.resume(ok) }
            tts.setSpeechRate(rate)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { stop() }
        }
    }

    fun stop() { tts.stop(); _speaking.value = false }

    fun shutdown() { tts.stop(); tts.shutdown() }

    fun stateMessage(): String = when (_state.value) {
        State.INITIALIZING -> "TTS 준비 중입니다."
        State.READY -> "TTS 사용 가능"
        State.NO_ENGINE -> "TTS 엔진이 없습니다. 설정 > 일반 > 텍스트 음성 변환에서 Google 또는 삼성 TTS를 설치하세요."
        State.NO_ENGLISH -> "영어 음성 데이터가 없습니다. TTS 설정에서 English(US) 음성을 다운로드하세요."
    }
}
