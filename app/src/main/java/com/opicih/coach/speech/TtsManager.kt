package com.opicih.coach.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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

    /** Ids of the last utterance of each request. Only these end the "speaking" state or resolve a waiter. */
    private val finalIds = mutableSetOf<String>()

    /** Voice name chosen by the user ("" = automatic pick) and pitch (1.0 = engine default). */
    @Volatile private var desiredVoice: String = ""
    @Volatile private var desiredPitch: Float = DEFAULT_PITCH

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) { _state.value = State.NO_ENGINE; return@TextToSpeech }
        val r = setLanguage()
        if (r) applyVoice()
        _state.value = if (r) State.READY else State.NO_ENGLISH
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { finish(id, true) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { finish(id, false) }
            override fun onStop(id: String?, interrupted: Boolean) { finish(id, false) }
        })
    }

    private fun finish(id: String?, ok: Boolean) {
        if (id == null) return
        val last = synchronized(finalIds) { finalIds.remove(id) }
        if (last) _speaking.value = false
        pending.remove(id)?.invoke(ok)
    }

    private fun setLanguage(): Boolean {
        val r = tts.setLanguage(Locale.US)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** English voices installed on this device, best (offline, high quality) first. */
    fun englishVoices(): List<Voice> {
        if (_state.value != State.READY) return emptyList()
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        return voices
            .filter { it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.locale == Locale.US }
                .thenBy { it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
                .thenBy { it.name })
    }

    /** Applies the user's voice and pitch. Safe to call before TTS is ready; it is re-applied once ready. */
    fun configure(voiceName: String, pitch: Float) {
        desiredVoice = voiceName
        desiredPitch = pitch
        if (_state.value == State.READY) applyVoice()
    }

    private fun applyVoice() {
        val pick = englishVoicesUnchecked().firstOrNull { it.name == desiredVoice }
        if (pick != null) runCatching { tts.setVoice(pick) }
        tts.setPitch(desiredPitch)
    }

    private fun englishVoicesUnchecked(): List<Voice> =
        runCatching { tts.voices }.getOrNull().orEmpty().filter { it.locale.language == "en" }

    /** Queues [text] sentence by sentence with a short calm pause between them. Returns the last utterance id. */
    private fun enqueue(text: String, rate: Float, lastId: String) {
        tts.setSpeechRate(rate)
        tts.setPitch(desiredPitch)
        val sentences = text.trim().split(SENTENCE_BREAK).filter { it.isNotBlank() }.ifEmpty { listOf(text) }
        synchronized(finalIds) { finalIds.add(lastId) }
        sentences.forEachIndexed { i, sentence ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val isLast = i == sentences.lastIndex
            tts.speak(sentence, mode, null, if (isLast) lastId else "$lastId-$i")
            if (!isLast) tts.playSilentUtterance(SENTENCE_PAUSE_MS, TextToSpeech.QUEUE_ADD, "$lastId-gap$i")
        }
    }

    fun speak(text: String, rate: Float = 1.0f) {
        if (_state.value != State.READY) return
        enqueue(text, rate, UUID.randomUUID().toString())
    }

    /** Speaks and suspends until finished. Returns false if TTS is unavailable or interrupted. */
    suspend fun speakAndWait(text: String, rate: Float = 1.0f): Boolean {
        if (_state.value != State.READY || text.isBlank()) return false
        return suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = { ok -> if (cont.isActive) cont.resume(ok) }
            enqueue(text, rate, id)
            cont.invokeOnCancellation { stop() }
        }
    }

    fun stop() { tts.stop(); _speaking.value = false }

    fun shutdown() { tts.stop(); tts.shutdown() }

    companion object {
        /** Slightly below the engine default for a calmer, examiner-like tone. */
        const val DEFAULT_PITCH = 0.9f
        private const val SENTENCE_PAUSE_MS = 350L
        private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
    }

    fun stateMessage(): String = when (_state.value) {
        State.INITIALIZING -> "TTS 준비 중입니다."
        State.READY -> "TTS 사용 가능"
        State.NO_ENGINE -> "TTS 엔진이 없습니다. 설정 > 일반 > 텍스트 음성 변환에서 Google 또는 삼성 TTS를 설치하세요."
        State.NO_ENGLISH -> "영어 음성 데이터가 없습니다. TTS 설정에서 English(US) 음성을 다운로드하세요."
    }
}
