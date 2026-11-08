package com.opicih.coach.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.opicih.coach.learning.ListenLang
import com.opicih.coach.learning.ListenRole
import com.opicih.coach.playback.ListenPrefs
import com.opicih.coach.playback.ListenSpeaker
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 스크립트 플레이어 전용 TTS. 앱 전체가 쓰는 [TtsManager]와 따로 두어서, 서비스가 수명을 책임지고
 * 영어(질문 목소리·답변 목소리)와 한국어를 단계마다 바꿔 읽을 수 있게 한다.
 *
 * 한 번에 한 단계만 읽고 끝날 때까지 기다리므로, 단계마다 목소리를 바꿔도 앞 발화가 끊기지 않는다.
 */
class ListenTts(context: Context, private val prefs: suspend () -> ListenPrefs) : ListenSpeaker {
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var enOk = false
    @Volatile private var koOk = false
    @Volatile private var pending: Pending? = null

    private class Pending(val base: String, val cont: CancellableContinuation<Boolean>)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            runCatching {
                enOk = usable(Locale.US)
                koOk = usable(Locale.KOREAN)
            }
        }
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finish(utteranceId, false) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
        })
    }

    private fun usable(locale: Locale): Boolean {
        val r = tts.isLanguageAvailable(locale)
        return r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    /** 마지막 조각이 끝나면 성공, 어느 조각이든 오류가 나면 실패. 사이 무음 조각의 id는 무시한다. */
    private fun finish(id: String?, ok: Boolean) {
        val p = pending ?: return
        if (id == null || !id.startsWith(p.base)) return
        val isLast = id == "${p.base}-last"
        val isChunk = !id.contains("-gap")
        if (!ok && isChunk || ok && isLast) {
            pending = null
            if (p.cont.isActive) p.cont.resume(ok)
        }
    }

    private suspend fun awaitReady(): Boolean = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } == true

    override suspend fun supports(lang: ListenLang): Boolean {
        if (!awaitReady()) return false
        return if (lang == ListenLang.EN) enOk else koOk
    }

    /** 기기에 설치된 영어 음성. 오프라인·고품질을 앞에 둔다. */
    suspend fun englishVoices(): List<Voice> {
        if (!awaitReady() || !enOk) return emptyList()
        enVoices?.let { return it }
        return loadEnglishVoices().also { if (it.isNotEmpty()) enVoices = it }
    }

    @Volatile private var enVoices: List<Voice>? = null
    @Volatile private var koVoices: List<Voice>? = null

    private fun loadEnglishVoices(): List<Voice> =
        runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(
                compareByDescending<Voice> { it.locale == Locale.US }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )

    private fun koreanVoices(): List<Voice> = koVoices ?: loadKoreanVoices().also { if (it.isNotEmpty()) koVoices = it }

    private fun loadKoreanVoices(): List<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        .filter { it.locale.language == "ko" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })

    /** 한국어도 질문은 여성, 답변은 남성 음성을 쓴다. 해당 성별이 없으면 서로 다른 음성, 그것도 없으면 음높이로 구분한다. */
    private fun koreanVoiceFor(role: ListenRole): Pair<Voice?, Boolean> {
        val voices = koreanVoices()
        val q = voices.firstOrNull { VoiceGenders.of(it.name) == VoiceGender.FEMALE } ?: voices.firstOrNull()
        val a = voices.firstOrNull { VoiceGenders.of(it.name) == VoiceGender.MALE && it.name != q?.name }
            ?: voices.firstOrNull { it.name != q?.name } ?: q
        return (if (role == ListenRole.QUESTION) q else a) to (q != null && q.name == a?.name)
    }

    /**
     * 역할에 맞는 영어 음성. 사용자가 고른 음성이 있으면 그것을 쓰고, "자동"이면 질문에는 여성, 답변에는 남성 음성을
     * 쓴다(성별은 [VoiceGenders]의 추정). 성별을 알 수 없으면 서로 다른 음성을, 음성이 하나뿐이면 같은 음성을 쓰되
     * 음높이로 구분한다.
     */
    private suspend fun englishVoiceFor(role: ListenRole, p: ListenPrefs): Pair<Voice?, Boolean> {
        val voices = englishVoices()
        fun byName(n: String) = voices.firstOrNull { it.name == n }
        val questionVoice = byName(p.questionVoice) ?: voices.firstOrNull { VoiceGenders.of(it.name) == VoiceGender.FEMALE } ?: voices.firstOrNull()
        val answerVoice = byName(p.answerVoice)
            ?: voices.firstOrNull { VoiceGenders.of(it.name) == VoiceGender.MALE && it.name != questionVoice?.name }
            ?: voices.firstOrNull { it.name != questionVoice?.name && it.locale == questionVoice?.locale }
            ?: voices.firstOrNull { it.name != questionVoice?.name }
            ?: questionVoice
        val pick = if (role == ListenRole.QUESTION) questionVoice else answerVoice
        val sameVoice = questionVoice != null && questionVoice.name == answerVoice?.name
        return pick to sameVoice
    }

    override suspend fun speak(text: String, lang: ListenLang, role: ListenRole, rate: Float): Boolean {
        if (text.isBlank() || !supports(lang)) return false
        val p = prefs()
        val (voice, sameVoice) = if (lang == ListenLang.EN) englishVoiceFor(role, p) else koreanVoiceFor(role)
        android.util.Log.d("ListenTts", "speak lang=$lang role=$role voice=${voice?.name} same=$sameVoice")
        // 음성이 같으면 음높이를 달리해 질문과 답변을 구분한다.
        val pitch = if (!sameVoice) 1.0f else if (role == ListenRole.QUESTION) PITCH_QUESTION else PITCH_ANSWER
        runCatching {
            if (voice != null) tts.setVoice(voice) else tts.setLanguage(if (lang == ListenLang.EN) Locale.US else Locale.KOREAN)
            tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            tts.setPitch(pitch)
        }
        val chunks = split(text)
        val timeout = INIT_TIMEOUT_MS + (text.length * 160L / rate.coerceAtLeast(0.5f)).toLong()
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { cont ->
                val base = UUID.randomUUID().toString()
                pending = Pending(base, cont)
                cont.invokeOnCancellation { pending = null; tts.stop() }
                var failed = false
                chunks.forEachIndexed { i, chunk ->
                    val last = i == chunks.lastIndex
                    val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    if (tts.speak(chunk, mode, null, if (last) "$base-last" else "$base-$i") == TextToSpeech.ERROR) failed = true
                    if (!last) tts.playSilentUtterance(CHUNK_PAUSE_MS, TextToSpeech.QUEUE_ADD, "$base-gap$i")
                }
                if (failed && cont.isActive) { pending = null; cont.resume(false) }
            }
        } ?: false.also { pending = null; tts.stop() }
    }

    /** 설정 화면의 미리듣기용. 재생 중이 아닐 때만 부른다. */
    suspend fun preview(text: String, role: ListenRole, rate: Float) { speak(text, ListenLang.EN, role, rate) }

    override fun stop() {
        pending = null
        runCatching { tts.stop() }
    }

    fun shutdown() { stop(); runCatching { tts.shutdown() } }

    /** 문장 단위로 쪼개 엔진의 입력 길이 제한을 넘지 않게 한다. */
    private fun split(text: String): List<String> {
        val max = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000).coerceAtMost(3500)
        return text.trim().split(SENTENCE_BREAK).filter { it.isNotBlank() }.flatMap { it.chunked(max) }.ifEmpty { listOf(text) }
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val CHUNK_PAUSE_MS = 250L
        private const val PITCH_QUESTION = 0.85f
        private const val PITCH_ANSWER = 1.12f
        private val SENTENCE_BREAK = Regex("(?<=[.!?。])\\s+")
    }
}
