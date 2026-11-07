package com.opicih.coach.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opicih.coach.App
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionEntity
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.repo.AnalyzedAttempt
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.speech.AnswerCapture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

data class QuestionItem(
    val topic: String,
    val text: String,
    val type: String,
    val sourceLabel: String,
    val script: ScriptEntity? = null,
    val mission: String? = null,
    val requiredTense: String? = null,
)

enum class Phase { IDLE, RECORDING, PROCESSING, RESULT }

data class SpeakingUi(
    val mode: PracticeMode = PracticeMode.BLIND,
    val question: QuestionItem? = null,
    val scripts: List<ScriptEntity> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val liveText: String = "",
    val result: AnalyzedAttempt? = null,
    val error: String? = null,
    val showTypedFallback: Boolean = false,
    val shadowIndex: Int = 0,
    val captureMode: CaptureMode = CaptureMode.LIVE_STT,
    val aiOn: Boolean = false,
    val replays: Int = 0,
)

class SpeakingViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container
    private val capture = AnswerCapture(app, c.backup.audioDir())
    private val _ui = MutableStateFlow(SpeakingUi())
    val ui: StateFlow<SpeakingUi> = _ui
    private var bank: List<QuestionEntity> = emptyList()
    private var tick: Job? = null
    private var configured = false

    init {
        viewModelScope.launch {
            bank = c.dao.questions()
            c.dao.scriptsFlow().collect { list ->
                _ui.update { u ->
                    // Keep the current script object fresh (mastery, approved versions).
                    val q = u.question?.let { q -> q.script?.let { s -> list.firstOrNull { it.id == s.id }?.let { q.copy(script = it) } } ?: q }
                    u.copy(scripts = list, question = q)
                }
                if (_ui.value.question == null && configured) next()
            }
        }
        viewModelScope.launch {
            c.settings.flow.collect { s -> _ui.update { it.copy(captureMode = s.captureMode, aiOn = c.realAi(s) != null) } }
        }
    }

    /** Called once from navigation arguments. */
    fun configure(scriptId: Long?, mode: PracticeMode?) {
        if (configured) return
        configured = true
        viewModelScope.launch {
            val s = scriptId?.takeIf { it > 0 }?.let { c.dao.script(it) }
            val m = mode ?: if (s != null) PracticeMode.KEYWORD else PracticeMode.BLIND
            _ui.update { it.copy(mode = m) }
            if (s != null) setScript(s) else next()
        }
    }

    fun setMode(m: PracticeMode) {
        if (_ui.value.phase == Phase.RECORDING) return
        _ui.update { it.copy(mode = m, result = null, phase = Phase.IDLE, shadowIndex = 0, error = null) }
        val s = _ui.value.question?.script
        if (m.usesScript && s != null) setScript(s) else next()
    }

    fun setScript(s: ScriptEntity) {
        _ui.update { it.copy(question = questionFor(s, it.mode), result = null, phase = Phase.IDLE, shadowIndex = 0, error = null, replays = 0, showTypedFallback = false) }
    }

    /** Next question: due scripts first for script modes, Survey/focus topics first for free speaking. */
    fun next() {
        if (_ui.value.phase == Phase.RECORDING) return
        viewModelScope.launch {
            val st = c.settings.current()
            val mode = _ui.value.mode
            val current = _ui.value.question
            val q = if (mode.usesScript) {
                val scripts = _ui.value.scripts.ifEmpty { c.dao.scripts() }
                val today = LocalDate.now().toEpochDay()
                val pick = scripts.filter { it.id != current?.script?.id }
                    .sortedWith(compareBy<ScriptEntity> { it.dueEpochDay > today }.thenBy { it.masteryScore }.thenBy { it.dueEpochDay })
                    .let { list -> list.take(4).shuffled().firstOrNull() ?: scripts.firstOrNull() }
                pick?.let { questionFor(it, mode) }
            } else {
                val preferred = st.focusTopics + st.surveyTopics
                val pool = bank.filter { b -> preferred.any { p -> b.topic.contains(p.substringBefore(" "), true) } }.ifEmpty { bank }
                pool.filter { it.text != current?.text }.randomOrNull()?.let {
                    QuestionItem(it.topic, it.text, it.type, if (it.source == "USER_LIST") "내 기출 정리 자료(비공식)" else "AI 생성 연습 문제")
                }
            }
            _ui.update { it.copy(question = q, result = null, phase = Phase.IDLE, shadowIndex = 0, error = null, replays = 0, showTypedFallback = false) }
        }
    }

    private fun questionFor(s: ScriptEntity, mode: PracticeMode): QuestionItem {
        val label = ContentSource.of(s.source).label
        return when (mode) {
            PracticeMode.RANDOM -> {
                val alt = bank.filter { b -> b.type == s.questionType && (b.topic.contains(s.topic.substringBefore(" "), true) || s.topic.contains(b.topic.substringBefore(" "), true)) && b.text != s.question }
                    .randomOrNull()?.text ?: rephrase(s)
                QuestionItem(s.topic, alt, s.questionType, "다른 표현 질문", s)
            }
            PracticeMode.VARIATION -> {
                val (mission, tense) = variations(s).random()
                QuestionItem(s.topic, s.question, s.questionType, "응용 챌린지", s, mission, tense)
            }
            else -> QuestionItem(s.topic, s.question, s.questionType, label, s)
        }
    }

    private fun rephrase(s: ScriptEntity): String {
        val t = s.title.lowercase()
        return when (QuestionType.of(s.questionType)) {
            QuestionType.DESCRIPTION -> "I'd like to know more about $t. Can you describe it in as much detail as possible?"
            QuestionType.ROUTINE -> "Walk me through what you typically do. I'm curious about your $t."
            QuestionType.PAST_EXPERIENCE -> "Tell me about one specific time related to $t. What happened, and how did you feel?"
            QuestionType.COMPARISON -> "How has this changed over the years? Tell me about $t then and now."
            else -> "Tell me more about $t."
        }
    }

    private fun variations(s: ScriptEntity): List<Pair<String, String?>> = listOf(
        "같은 이야기를 미래 계획으로 바꿔 말하세요. 예: \"Next month, I'm planning to...\"" to "FUTURE",
        "같은 주제로 과거의 구체적인 경험 하나를 말하세요. 예: \"Last year, I...\"" to "PAST",
        "과거와 지금을 비교해서 말하세요. 예: \"Back then, ... These days, ...\"" to null,
        "이 답변을 다른 질문에 재활용하세요: \"" + (bank.filter { it.topic != s.topic && it.type == s.questionType }.randomOrNull()?.text ?: "Tell me about something you enjoy doing on weekends.") + "\"" to null,
    )

    fun playQuestion(rate: Float = 1.0f) {
        val q = _ui.value.question ?: return
        viewModelScope.launch {
            val r = rate * c.settings.current().ttsRate.coerceIn(0.5f, 1.5f) / 0.9f
            c.tts.speak(q.text, r)
            _ui.update { it.copy(replays = it.replays + 1) }
        }
    }

    fun speakText(text: String, rate: Float = 1.0f) = c.tts.speak(text, rate)

    fun stopAudio() { c.tts.stop(); c.player.stop() }

    fun shadowSentences(): List<String> = _ui.value.question?.script?.studyText?.let { TextNormalizer.sentences(it) }.orEmpty()

    fun shadowMove(delta: Int) {
        val n = shadowSentences().size
        _ui.update { it.copy(shadowIndex = (it.shadowIndex + delta).coerceIn(0, (n - 1).coerceAtLeast(0)), result = null, phase = Phase.IDLE) }
    }

    fun toggleMic() {
        when (_ui.value.phase) {
            Phase.RECORDING -> stop()
            Phase.PROCESSING -> Unit
            else -> start()
        }
    }

    private fun start() {
        c.tts.stop(); c.player.stop()
        val err = capture.start(viewModelScope, _ui.value.captureMode)
        if (err != null) { _ui.update { it.copy(error = err, showTypedFallback = true) }; return }
        _ui.update { it.copy(phase = Phase.RECORDING, error = null, liveText = "", elapsedMs = 0, result = null, showTypedFallback = false) }
        tick?.cancel()
        tick = viewModelScope.launch {
            while (isActive && _ui.value.phase == Phase.RECORDING) {
                val live = capture.liveState.value
                val lvl = if (_ui.value.captureMode == CaptureMode.LIVE_STT) ((live.level + 2f) / 12f) else (capture.recorderLevel.value / 6000f)
                _ui.update { it.copy(elapsedMs = capture.elapsedMs, level = lvl.coerceIn(0f, 1f), liveText = live.fullText) }
                if (live.error != null && !live.active && _ui.value.captureMode == CaptureMode.LIVE_STT) { stop(); break }
                if (capture.elapsedMs > MAX_ANSWER_MS) { stop(); break }
                delay(100)
            }
        }
    }

    fun stop() {
        if (_ui.value.phase != Phase.RECORDING) return
        tick?.cancel()
        _ui.update { it.copy(phase = Phase.PROCESSING) }
        viewModelScope.launch {
            val st = c.settings.current()
            val r = capture.stop(c.transcriber(st))
            if (r.transcript.isBlank() && r.audioPath == null) {
                _ui.update { it.copy(phase = Phase.IDLE, error = r.warning ?: "인식된 영어 문장이 없습니다. 다시 말하거나, 말한 내용을 직접 입력하세요.", showTypedFallback = true) }
                return@launch
            }
            analyze(r.transcript, r.captureMode, r.audioPath, r.durationMs, r.pauses, r.fromDeviceStt, r.warning)
        }
    }

    /** Recovery path when STT fails: the user types what they said. */
    fun submitTyped(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(phase = Phase.PROCESSING, showTypedFallback = false, error = null) }
        viewModelScope.launch { analyze(text.trim(), "TYPED", null, 0, null, false, null) }
    }

    private suspend fun analyze(transcript: String, captureMode: String, audio: String?, duration: Long, pauses: com.opicih.coach.analysis.PauseMetrics?, fromStt: Boolean, warning: String?) {
        val st = c.settings.current()
        val u = _ui.value
        val q = u.question ?: return
        val target = if (u.mode == PracticeMode.SHADOWING) shadowSentences().getOrNull(u.shadowIndex) else null
        val res = runCatching {
            c.practice.analyzeAndSave(
                PracticeRepository.Request(
                    script = q.script, topic = q.topic, question = q.text, questionType = q.type, mode = u.mode,
                    transcript = transcript, captureMode = captureMode, audioPath = audio, durationMs = duration,
                    pauses = pauses, transcriptFromDeviceStt = fromStt, requiredTense = q.requiredTense, target = target,
                ),
                ai = c.realAi(st), examEpochDay = st.examDate.toEpochDay(),
            )
        }
        res.onSuccess { a ->
            _ui.update { it.copy(phase = Phase.RESULT, result = a, error = listOfNotNull(warning, a.aiError?.let { e -> "AI 분석 실패: $e (로컬 분석으로 표시)" }).joinToString("\n").ifBlank { null }) }
        }.onFailure { e ->
            _ui.update { it.copy(phase = Phase.IDLE, error = "분석 중 오류: ${e.message}") }
        }
    }

    fun playRecording(speed: Float) {
        val path = _ui.value.result?.attempt?.audioPath ?: return
        c.player.play(File(path), speed) { msg -> _ui.update { it.copy(error = msg) } }
    }

    fun retry() = _ui.update { it.copy(phase = Phase.IDLE, result = null, error = null) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** App went to background or the screen left: never keep the mic open. */
    fun onBackground() { if (_ui.value.phase == Phase.RECORDING) stop() }

    override fun onCleared() {
        capture.release()
        c.tts.stop(); c.player.stop()
    }

    companion object { const val MAX_ANSWER_MS = 180_000L }
}
