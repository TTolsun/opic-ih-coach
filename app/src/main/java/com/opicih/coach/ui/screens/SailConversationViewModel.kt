package com.opicih.coach.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opicih.coach.App
import com.opicih.coach.ai.ConvExchange
import com.opicih.coach.data.db.ExamTrainingSessionEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.learning.AiBudget
import com.opicih.coach.learning.ConvTurn
import com.opicih.coach.learning.ConversationReport
import com.opicih.coach.learning.ConversationScoring
import com.opicih.coach.learning.LocalFollowUp
import com.opicih.coach.learning.QuestionOrigin
import com.opicih.coach.learning.SailQuestionBank
import com.opicih.coach.learning.TurnMetrics
import com.opicih.coach.speech.AnswerCapture
import com.opicih.coach.speech.CaptureResult
import com.opicih.coach.speech.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate

enum class ConvPhase { SETUP, ASKING, READY, LISTENING, THINKING, DONE }

data class ConvUi(
    val phase: ConvPhase = ConvPhase.SETUP,
    val targetTurns: Int = 4,
    val turns: List<ConvTurn> = emptyList(),
    val question: String? = null,
    val origin: QuestionOrigin = QuestionOrigin.SEED,
    val showQuestionText: Boolean = false,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val liveText: String = "",
    val error: String? = null,
    val showTyped: Boolean = false,
    val aiAvailable: Boolean = false,
    val aiCalls: Int = 0,
    val aiNote: String? = null,
    val report: ConversationReport? = null,
    val ttsMessage: String? = null,
)

/**
 * SAIL conversation practice: TTS question -> live STT answer -> follow-up built from that answer -> 3..5 turns -> report.
 * Paid AI is used only when enabled + consented + key stored, and within AiBudget limits; otherwise LocalFollowUp.
 * Always LIVE_STT: follow-ups need a transcript, and record-only mode has none without cloud transcription.
 */
class SailConversationViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container
    private val capture = AnswerCapture(app, c.backup.audioDir())
    private val _ui = MutableStateFlow(ConvUi())
    val ui: StateFlow<ConvUi> = _ui
    private val usedTemplates = mutableSetOf<String>()
    private var sessionId: Long? = null
    private var firstQuestion: Pair<String, QuestionOrigin>? = null
    private var tick: Job? = null
    private var askJob: Job? = null

    init {
        c.tts.state // touch the lazy TTS so the engine starts initializing while the user reads the setup card
        viewModelScope.launch {
            c.settings.flow.collect { s -> _ui.update { it.copy(aiAvailable = c.realAi(s) != null) } }
        }
    }

    fun setTurns(n: Int) = _ui.update { it.copy(targetTurns = n.coerceIn(3, 5)) }

    /** @param repeatFirst re-run the same opening question ("같은 질문 다시 답하기"). */
    fun start(repeatFirst: Boolean = false) {
        usedTemplates.clear()
        _ui.update { ConvUi(targetTurns = it.targetTurns, aiAvailable = it.aiAvailable, phase = ConvPhase.THINKING) }
        viewModelScope.launch {
            sessionId = c.dao.insertTrainingSession(ExamTrainingSessionEntity(examType = ExamType.SAIL.name, kind = KIND))
            val opener = if (repeatFirst && firstQuestion != null) firstQuestion!! else {
                val debrief = c.dao.exams().flatMap { SailQuestionBank.fromDebriefNote(it.note) }
                val recent = c.dao.attempts().filter { it.mode == PracticeMode.SAIL_CONVERSATION.name }.take(30).map { it.questionText }.toSet()
                SailQuestionBank.pick(debrief, recent)
            }
            firstQuestion = opener
            ask(opener.first, opener.second)
        }
    }

    private fun ask(q: String, origin: QuestionOrigin) {
        _ui.update { it.copy(question = q, origin = origin, phase = ConvPhase.ASKING, liveText = "", error = null, showTyped = false, elapsedMs = 0) }
        askJob?.cancel()
        askJob = viewModelScope.launch {
            val rate = c.settings.current().ttsRate
            // TTS is created lazily; on the first question the engine may still be starting.
            withTimeoutOrNull(4000) { c.tts.state.first { it != TtsManager.State.INITIALIZING } }
            val spoke = c.tts.speakAndWait(q, rate)
            if (!spoke) {
                // No TTS: show the text and let the user start when ready.
                _ui.update { it.copy(phase = ConvPhase.READY, showQuestionText = true, ttsMessage = if (c.tts.state.value != TtsManager.State.READY) c.tts.stateMessage() else null) }
                return@launch
            }
            listen()
        }
    }

    fun replayQuestion() {
        val q = _ui.value.question ?: return
        if (_ui.value.phase == ConvPhase.LISTENING) capture.cancel()
        tick?.cancel()
        ask(q, _ui.value.origin)
    }

    fun toggleQuestionText() = _ui.update { it.copy(showQuestionText = !it.showQuestionText) }

    /** Starts the mic right after the question audio, so leading silence = response latency. */
    fun listen() {
        val err = capture.start(viewModelScope, CaptureMode.LIVE_STT)
        if (err != null) { _ui.update { it.copy(phase = ConvPhase.READY, error = err, showTyped = true) }; return }
        _ui.update { it.copy(phase = ConvPhase.LISTENING, error = null, liveText = "", elapsedMs = 0) }
        tick?.cancel()
        tick = viewModelScope.launch {
            while (isActive && _ui.value.phase == ConvPhase.LISTENING) {
                val live = capture.liveState.value
                _ui.update { it.copy(elapsedMs = capture.elapsedMs, level = ((live.level + 2f) / 12f).coerceIn(0f, 1f), liveText = live.fullText) }
                if (live.error != null && !live.active) { finishAnswer(); break }
                if (capture.elapsedMs > MAX_ANSWER_MS) { finishAnswer(); break }
                delay(100)
            }
        }
    }

    fun finishAnswer() {
        if (_ui.value.phase != ConvPhase.LISTENING) return
        tick?.cancel()
        _ui.update { it.copy(phase = ConvPhase.THINKING) }
        viewModelScope.launch {
            val r = capture.stop(null)
            if (r.transcript.isBlank()) {
                _ui.update { it.copy(phase = ConvPhase.READY, error = r.warning ?: "인식된 영어 문장이 없습니다. 다시 답하거나, 말한 내용을 직접 입력하거나, 건너뛰세요.", showTyped = true) }
                return@launch
            }
            handleAnswer(r.transcript, r, typed = false)
        }
    }

    /** Recovery after a recognition failure. Typed answers carry no timing data. */
    fun submitTyped(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(phase = ConvPhase.THINKING, showTyped = false, error = null) }
        viewModelScope.launch { handleAnswer(text.trim(), null, typed = true) }
    }

    /** No answer: counts as a turn with an empty answer, the next question becomes simpler. */
    fun skip() {
        if (_ui.value.phase == ConvPhase.LISTENING) capture.cancel()
        tick?.cancel()
        _ui.update { it.copy(phase = ConvPhase.THINKING, showTyped = false, error = null) }
        viewModelScope.launch { handleAnswer("", null, typed = true) }
    }

    private suspend fun handleAnswer(answer: String, r: CaptureResult?, typed: Boolean) {
        val u = _ui.value
        val q = u.question ?: return
        var metrics: TurnMetrics? = null
        if (answer.isNotBlank()) {
            val saved = runCatching {
                c.practice.analyzeAndSave(
                    PracticeRepository.Request(
                        script = null, topic = TOPIC, question = q, questionType = QuestionType.OTHER.name, mode = PracticeMode.SAIL_CONVERSATION,
                        transcript = answer, captureMode = r?.captureMode ?: "TYPED", audioPath = null, durationMs = r?.durationMs ?: 0,
                        pauses = r?.pauses, transcriptFromDeviceStt = r?.fromDeviceStt ?: false, trainingSessionId = sessionId,
                    ),
                    ai = null, // per-turn AI evaluation would double the cost; the conversation gets one summary instead
                    examEpochDay = c.exams.focusEpochDay(),
                )
            }.getOrNull()
            if (!typed && r != null) {
                metrics = TurnMetrics(
                    responseLatencyMs = r.pauses?.leadingSilenceMs, speechMs = r.pauses?.speechMs ?: 0, durationMs = r.durationMs,
                    longPauses = r.pauses?.longPauseCount ?: 0, wordCount = saved?.attempt?.wordCount ?: 0,
                    connectors = saved?.metrics?.discourse?.connectorCount ?: 0,
                )
            }
        }
        val turns = u.turns + ConvTurn(q, u.origin, answer, metrics, typed)
        _ui.update { it.copy(turns = turns, liveText = "") }
        if (turns.size >= u.targetTurns) finish(turns) else nextQuestion(turns)
    }

    private suspend fun nextQuestion(turns: List<ConvTurn>) {
        val last = turns.last()
        val history = turns.map { ConvExchange(it.question, it.answer) }
        var note: String? = null
        if (useAi()) {
            val ai = c.realAi(c.settings.current())
            if (ai != null) {
                countAiCall()
                val res = ai.followUp(history)
                val q = res.getOrNull()?.takeIf { it.length in 8..300 }
                if (q != null) { ask(q, QuestionOrigin.AI); return }
                note = "AI 추가 질문 생성 실패: ${res.exceptionOrNull()?.message ?: "응답 형식 오류"} 이번 질문은 로컬 규칙으로 만들었습니다."
            }
        } else if (_ui.value.aiAvailable) {
            note = "AI 호출 상한에 도달해 로컬 규칙으로 질문을 만듭니다."
        }
        val f = LocalFollowUp.next(last.answer, usedTemplates, last.question)
        usedTemplates += f.templateId
        _ui.update { it.copy(aiNote = note ?: it.aiNote) }
        ask(f.text, QuestionOrigin.LOCAL_RULE)
    }

    private suspend fun finish(turns: List<ConvTurn>) {
        var report = ConversationScoring.report(turns)
        if (useAi() && turns.any { it.answer.isNotBlank() }) {
            val ai = c.realAi(c.settings.current())
            if (ai != null) {
                countAiCall()
                val findings = (report.strengths + report.fixes).joinToString("\n")
                ai.summarizeConversation(turns.map { ConvExchange(it.question, it.answer) }, findings)
                    .onSuccess { report = report.copy(aiSummary = it.take(2500)) }
                    .onFailure { e -> _ui.update { it.copy(aiNote = "AI 종합 피드백 실패: ${e.message}") } }
            }
        }
        sessionId?.let { id ->
            c.dao.trainingSessions().firstOrNull { it.id == id }?.let { s ->
                c.dao.updateTrainingSession(s.copy(finishedAt = System.currentTimeMillis(), turnCount = turns.size, aiCalls = _ui.value.aiCalls,
                    summaryJson = json.encodeToString(ConversationReport.serializer(), report)))
            }
        }
        _ui.update { it.copy(phase = ConvPhase.DONE, report = report, question = null) }
    }

    /** Ends early with the turns answered so far. */
    fun endNow() {
        if (_ui.value.phase == ConvPhase.LISTENING) capture.cancel()
        tick?.cancel(); askJob?.cancel(); c.tts.stop()
        val turns = _ui.value.turns
        if (turns.isEmpty()) { _ui.update { ConvUi(targetTurns = it.targetTurns, aiAvailable = it.aiAvailable) }; return }
        _ui.update { it.copy(phase = ConvPhase.THINKING) }
        viewModelScope.launch { finish(turns) }
    }

    fun reset() = _ui.update { ConvUi(targetTurns = it.targetTurns, aiAvailable = it.aiAvailable) }

    private suspend fun useAi(): Boolean {
        val s = c.settings.current()
        if (c.realAi(s) == null) return false
        return AiBudget.canCall(_ui.value.aiCalls, s.aiCallsToday(LocalDate.now().toEpochDay()), s.aiDailyCap)
    }

    private suspend fun countAiCall() {
        val today = LocalDate.now().toEpochDay()
        c.settings.update { it.copy(aiCallDay = today, aiCallCount = it.aiCallsToday(today) + 1) }
        _ui.update { it.copy(aiCalls = it.aiCalls + 1) }
    }

    /** Never keep the mic open in the background. */
    fun onBackground() {
        if (_ui.value.phase == ConvPhase.LISTENING || _ui.value.phase == ConvPhase.ASKING) {
            capture.cancel(); tick?.cancel(); askJob?.cancel(); c.tts.stop()
            _ui.update { it.copy(phase = ConvPhase.READY, error = "앱이 화면에서 벗어나 답변을 멈췄습니다. 질문 다시 듣기나 답하기를 누르세요.") }
        }
    }

    override fun onCleared() { capture.release(); c.tts.stop() }

    companion object {
        const val KIND = "CONVERSATION"
        const val TOPIC = "SAIL 대화"
        const val MAX_ANSWER_MS = 90_000L
        private val json = Json { encodeDefaults = true }
    }
}
