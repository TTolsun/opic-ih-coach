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
import com.opicih.coach.data.repo.ExamRepository
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.analysis.VerbatimMarkup
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.CaptureChoice
import com.opicih.coach.learning.CapturePolicy
import com.opicih.coach.learning.FrameMatcher
import com.opicih.coach.learning.LadderLevel
import com.opicih.coach.learning.LadderResult
import com.opicih.coach.learning.ParagraphLadder
import com.opicih.coach.learning.transferCandidates
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
    /** FRAME_ATTACH: the approved frames the learner should use in this answer. */
    val frames: List<FrameChip> = emptyList(),
    val reviewId: Long? = null,
    val missionQuestion: String? = null,
)

private data class Variation(val instruction: String, val tense: String? = null, val question: String? = null)

/** A frame shown on the speaking screen. */
data class FrameChip(val pattern: String, val hintKo: String)

/** How an answer did against the mode's own goal (frame use, ladder level). Shown under the result card. */
data class PracticeCheck(
    val frameUsage: List<Pair<FrameChip, Boolean>> = emptyList(),
    val ladder: LadderResult? = null,
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
    val replays: Int = 0,
    /** PARAGRAPH_LADDER: the level the learner is trying now. Null in every other mode. */
    val ladder: LadderLevel? = null,
    val check: PracticeCheck? = null,
    val reviewDueEpochDay: Long? = null,
    val transferUnavailable: Boolean = false,
)

class SpeakingViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container
    private val capture = AnswerCapture(app, c.backup.audioDir())
    private val _ui = MutableStateFlow(SpeakingUi())
    val ui: StateFlow<SpeakingUi> = _ui
    private var bank: List<QuestionEntity> = emptyList()
    private var tick: Job? = null
    private var questionLoad: Job? = null
    private var configured = false
    private var openingReview = false
    private var reviewRoute = false

    init {
        viewModelScope.launch {
            bank = c.dao.questions()
            c.dao.scriptsFlow().collect { list ->
                _ui.update { u ->
                    // Keep the current script object fresh (mastery, approved versions).
                    val q = u.question?.let { q -> q.script?.let { s -> list.firstOrNull { it.id == s.id }?.let { q.copy(script = it) } } ?: q }
                    u.copy(scripts = list, question = q)
                }
                if (_ui.value.question == null && configured && !reviewRoute) next()
            }
        }
        viewModelScope.launch {
            c.settings.flow.collect { s ->
                latestSettings = s
                _ui.update { it.copy(captureMode = s.captureMode) }
            }
        }
    }

    private var latestSettings = Settings()

    /** How the answer being recorded is captured (see [CapturePolicy]); null before the first recording. */
    private var activeChoice: CaptureChoice? = null

    /** Called once from navigation arguments. */
    fun configure(scriptId: Long?, mode: PracticeMode?, reviewId: Long?) {
        if (configured) return
        configured = true
        reviewRoute = reviewId != null
        openingReview = reviewRoute
        viewModelScope.launch {
            if (reviewId != null) {
                val review = c.dao.dueSpeakingReview(reviewId, ExamRepository.nowKst().toLocalDate().toEpochDay())
                openingReview = false
                if (review == null) {
                    _ui.update { it.copy(mode = PracticeMode.FREE, error = "다시 말할 질문이 없습니다.") }
                } else {
                    _ui.update { it.copy(mode = PracticeMode.FREE) }
                    val source = bank.firstOrNull { it.text == review.questionText }?.sourceLabel ?: "다시 말하기"
                    showQuestion(QuestionItem(review.topic, review.questionText, review.questionType, source, reviewId = review.id))
                }
                return@launch
            }
            val s = scriptId?.takeIf { it > 0 }?.let { c.dao.script(it) }
            val m = mode ?: if (s != null) PracticeMode.KEYWORD else PracticeMode.BLIND
            _ui.update { it.copy(mode = m) }
            if (s != null) setScript(s) else next()
        }
    }

    fun setMode(m: PracticeMode) {
        if (openingReview || _ui.value.phase in setOf(Phase.RECORDING, Phase.PROCESSING)) return
        _ui.update { it.copy(mode = m, result = null, phase = Phase.IDLE, shadowIndex = 0, error = null, ladder = null, check = null) }
        val s = _ui.value.question?.script
        if (m.usesScript && s != null) setScript(s) else next()
    }

    fun setScript(s: ScriptEntity) {
        questionLoad?.cancel()
        showQuestion(questionFor(s, _ui.value.mode))
    }

    /** Next question: due scripts first for script modes, Survey/focus topics first for free speaking. */
    fun next() {
        if (openingReview || _ui.value.phase in setOf(Phase.RECORDING, Phase.PROCESSING)) return
        questionLoad?.cancel()
        questionLoad = viewModelScope.launch {
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
                if (bank.isEmpty()) bank = c.dao.questions()
                val preferred = st.focusTopics + st.surveyTopics
                val all = bank.filter { b -> preferred.any { p -> b.topic.contains(p.substringBefore(" "), true) } }.ifEmpty { bank }
                // The ladder needs a question that invites a past story; fall back to any question if none matches.
                val pool = if (mode == PracticeMode.PARAGRAPH_LADDER) all.filter { it.type == QuestionType.PAST_EXPERIENCE.name }.ifEmpty { all } else all
                pool.filter { it.text != current?.text }.randomOrNull()?.let {
                    when (mode) {
                        PracticeMode.FRAME_ATTACH -> frameQuestion(it, it.sourceLabel)
                        PracticeMode.PARAGRAPH_LADDER -> QuestionItem(it.topic, it.text, it.type, it.sourceLabel, mission = LadderLevel.ONE.missionKo)
                        else -> QuestionItem(it.topic, it.text, it.type, it.sourceLabel)
                    }
                }
            }
            val ladder = if (mode == PracticeMode.PARAGRAPH_LADDER && q != null) LadderLevel.ONE else null
            showQuestion(q, ladder)
        }
    }

    fun nextSameType() {
        val attempt = _ui.value.result?.attempt?.takeIf { it.mode == PracticeMode.FREE.name } ?: return
        questionLoad?.cancel()
        questionLoad = viewModelScope.launch {
            val candidate = transferCandidates(bank, attempt, c.dao.answeredQuestionTexts()).randomOrNull()
            if (_ui.value.result?.attempt?.id != attempt.id) return@launch
            if (candidate == null) {
                _ui.update { it.copy(transferUnavailable = true) }
            } else {
                stopAudio()
                showQuestion(QuestionItem(candidate.topic, candidate.text, candidate.type, candidate.sourceLabel))
            }
        }
    }

    fun scheduleReview() {
        val attempt = _ui.value.result?.attempt?.takeIf { it.mode == PracticeMode.FREE.name } ?: return
        viewModelScope.launch {
            val due = ExamRepository.nowKst().toLocalDate().plusDays(3).toEpochDay()
            c.dao.scheduleSpeakingReview(attempt.id, due)
            val scheduled = c.dao.speakingReviewDate(attempt.questionText)
            _ui.update { if (it.result?.attempt?.id == attempt.id) it.copy(reviewDueEpochDay = scheduled) else it }
        }
    }

    private fun showQuestion(question: QuestionItem?, ladder: LadderLevel? = null) {
        _ui.update {
            it.copy(
                question = question, result = null, phase = Phase.IDLE, shadowIndex = 0, error = null,
                replays = 0, showTypedFallback = false, ladder = ladder, check = null,
                reviewDueEpochDay = null, transferUnavailable = false,
            )
        }
    }

    private val QuestionEntity.sourceLabel: String
        get() = if (source == "USER_LIST") "내 기출 정리 자료(비공식)" else "AI 생성 연습 문제"

    /** A new question plus one or two approved frames to use in the answer (none when the learner has approved none). */
    private suspend fun frameQuestion(b: QuestionEntity, label: String): QuestionItem {
        val chips = pickFrames()
        return QuestionItem(b.topic, b.text, b.type, label, mission = frameMission(chips), frames = chips)
    }

    private suspend fun pickFrames(): List<FrameChip> =
        c.frames.approved().shuffled().take(FRAMES_PER_QUESTION).map { FrameChip(it.pattern, it.hintKo) }

    private fun frameMission(chips: List<FrameChip>): String? =
        chips.takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.pattern }?.let { "문장 틀을 써서 답하세요: $it" }

    /**
     * The frames screen can approve frames while this question is open. When the current question was built with none,
     * pick them now instead of making the learner skip the question to see them.
     */
    fun refreshFrames() {
        val u = _ui.value
        val q = u.question ?: return
        if (u.mode != PracticeMode.FRAME_ATTACH || q.frames.isNotEmpty() || u.phase == Phase.RECORDING || u.phase == Phase.PROCESSING) return
        viewModelScope.launch {
            val chips = pickFrames()
            if (chips.isEmpty()) return@launch
            _ui.update { cur ->
                val now = cur.question
                if (now == null || now.text != q.text || now.frames.isNotEmpty()) cur
                else cur.copy(question = now.copy(frames = chips, mission = frameMission(chips)))
            }
        }
    }

    /** Ladder: keep the same question and try the next level after passing one. */
    fun ladderAdvance() {
        val u = _ui.value
        val next = u.ladder?.next() ?: return
        if (u.phase == Phase.RECORDING) return
        _ui.update { it.copy(ladder = next, question = it.question?.copy(mission = next.missionKo), result = null, check = null, phase = Phase.IDLE, error = null, replays = 0, showTypedFallback = false) }
    }

    private fun questionFor(s: ScriptEntity, mode: PracticeMode): QuestionItem {
        val label = ContentSource.of(s.source).label
        return when (mode) {
            PracticeMode.RANDOM -> {
                val alt = bank.filter { b -> b.type == s.questionType && (b.topic.contains(s.topic.substringBefore(" "), true) || s.topic.contains(b.topic.substringBefore(" "), true)) && b.text != s.question }
                    .randomOrNull()?.text ?: rephrase(s)
                QuestionItem(s.topic, alt, s.questionType, PracticeMode.RANDOM.label, s)
            }
            PracticeMode.VARIATION -> {
                val (mission, tense, question) = variations(s).random()
                QuestionItem(s.topic, s.question, s.questionType, PracticeMode.VARIATION.label, s, mission, tense, missionQuestion = question)
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

    private fun variations(s: ScriptEntity): List<Variation> {
        val replacement = bank.filter { it.topic != s.topic && it.type == s.questionType }.randomOrNull()?.text
            ?: "Tell me about something you enjoy doing on weekends."
        return listOf(
            Variation("같은 이야기를 미래 계획으로 바꿔 말하세요. 예: \"Next month, I'm planning to...\"", "FUTURE"),
            Variation("같은 주제로 과거의 구체적인 경험 하나를 말하세요. 예: \"Last year, I...\"", "PAST"),
            Variation("과거와 지금을 비교해서 말하세요. 예: \"Back then, ... These days, ...\"", "PAST_AND_PRESENT"),
            Variation("이 답변을 다른 질문에 재활용하세요: \"$replacement\"", question = replacement),
        )
    }

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
        questionLoad?.cancel()
        c.tts.stop(); c.player.stop()
        // Free-speaking answers are recorded and transcribed verbatim when the learner allowed it; otherwise the
        // chosen capture mode is kept and the reason is shown with the result.
        val choice = CapturePolicy.choose(_ui.value.mode, latestSettings, c.hasGeminiKey())
        activeChoice = choice
        val err = capture.start(viewModelScope, choice.mode)
        if (err != null) { _ui.update { it.copy(error = err, showTypedFallback = true) }; return }
        _ui.update { it.copy(phase = Phase.RECORDING, error = null, liveText = "", elapsedMs = 0, result = null, showTypedFallback = false) }
        tick?.cancel()
        tick = viewModelScope.launch {
            while (isActive && _ui.value.phase == Phase.RECORDING) {
                val live = capture.liveState.value
                val lvl = if (choice.mode == CaptureMode.LIVE_STT) ((live.level + 2f) / 12f) else (capture.recorderLevel.value / 6000f)
                _ui.update { it.copy(elapsedMs = capture.elapsedMs, level = lvl.coerceIn(0f, 1f), liveText = live.fullText) }
                if (live.error != null && !live.active && choice.mode == CaptureMode.LIVE_STT) { stop(); break }
                if (capture.elapsedMs > MAX_ANSWER_MS) { stop(); break }
                delay(100)
            }
        }
    }

    fun stop(completeReview: Boolean = true) {
        if (_ui.value.phase != Phase.RECORDING) return
        tick?.cancel()
        _ui.update { it.copy(phase = Phase.PROCESSING) }
        viewModelScope.launch {
            val st = c.settings.current()
            val r = capture.stop(c.transcriber(st))
            // A verbatim transcription that failed (network, quota) leaves a recording but no text. Offer the typed
            // fallback instead of saving a score-less answer, as for a failed live recognizer.
            if (r.transcript.isBlank() && (r.audioPath == null || activeChoice?.verbatim == true)) {
                _ui.update { it.copy(phase = Phase.IDLE, error = r.warning ?: "인식된 영어 문장이 없습니다. 다시 말하거나, 말한 내용을 직접 입력하세요.", showTypedFallback = true) }
                return@launch
            }
            analyze(r.transcript, r.captureMode, r.audioPath, r.durationMs, r.pauses, r.fromDeviceStt, r.warning, activeChoice?.note, completeReview)
        }
    }

    /** Recovery path when STT fails: the user types what they said. */
    fun submitTyped(text: String) {
        if (text.isBlank() || _ui.value.phase in setOf(Phase.RECORDING, Phase.PROCESSING)) return
        questionLoad?.cancel()
        _ui.update { it.copy(phase = Phase.PROCESSING, showTypedFallback = false, error = null) }
        viewModelScope.launch { analyze(text.trim(), "TYPED", null, 0, null, false, null, null) }
    }

    private suspend fun analyze(transcript: String, captureMode: String, audio: String?, duration: Long, pauses: com.opicih.coach.analysis.PauseMetrics?, fromStt: Boolean, warning: String?, captureNote: String?, completeReview: Boolean = true) {
        val st = c.settings.current()
        val u = _ui.value
        val q = u.question ?: return
        val target = if (u.mode == PracticeMode.SHADOWING) shadowSentences().getOrNull(u.shadowIndex) else null
        val res = runCatching {
            c.practice.analyzeAndSave(
                PracticeRepository.Request(
                    script = q.script, topic = q.topic, question = q.text, questionType = q.type, mode = u.mode,
                    transcript = transcript, captureMode = captureMode, audioPath = audio, durationMs = duration,
                    pauses = pauses, transcriptFromDeviceStt = fromStt, requiredTense = q.requiredTense, mission = q.mission, target = target, captureNote = captureNote,
                    speakingReviewId = q.reviewId.takeIf { completeReview },
                    missionQuestion = q.missionQuestion,
                ),
                ai = c.realAi(st), examEpochDay = c.exams.focusEpochDay(),
            )
        }
        res.onSuccess { a ->
            val check = checkFor(u, q, transcript, a)
            val reviewDate = if (u.mode == PracticeMode.FREE) c.dao.speakingReviewDate(q.text) else null
            _ui.update {
                it.copy(
                    phase = Phase.RESULT, result = a, check = check, reviewDueEpochDay = reviewDate, transferUnavailable = false,
                    error = listOfNotNull(warning, a.aiError?.let { e -> "AI 분석 실패: $e (로컬 분석으로 표시)" }).joinToString("\n").ifBlank { null },
                )
            }
        }.onFailure { e ->
            _ui.update { it.copy(phase = Phase.IDLE, error = "분석 중 오류: ${e.message}") }
        }
    }

    /** The mode's own goal: which frames were used, and whether the ladder level was reached. Null for other modes. */
    private fun checkFor(u: SpeakingUi, q: QuestionItem, rawTranscript: String, a: AnalyzedAttempt): PracticeCheck? {
        // A verbatim transcript carries [self-correction]/[abandoned] marks that are not part of what was said.
        val transcript = VerbatimMarkup.parse(rawTranscript).clean
        return when {
            u.mode == PracticeMode.FRAME_ATTACH && q.frames.isNotEmpty() ->
                PracticeCheck(frameUsage = q.frames.map { it to FrameMatcher.used(it.pattern, transcript) })
            u.mode == PracticeMode.PARAGRAPH_LADDER && u.ladder != null ->
                PracticeCheck(ladder = ParagraphLadder.judge(u.ladder, a.metrics.discourse, SENTENCE_PUNCTUATION.containsMatchIn(transcript)))
            else -> null
        }
    }

    fun playRecording(speed: Float) {
        val path = _ui.value.result?.attempt?.audioPath ?: return
        c.player.play(File(path), speed) { msg -> _ui.update { it.copy(error = msg) } }
    }

    fun retry() = _ui.update { it.copy(phase = Phase.IDLE, result = null, error = null) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** App went to background or the screen left: never keep the mic open. */
    fun onBackground() { if (_ui.value.phase == Phase.RECORDING) stop(completeReview = false) }

    override fun onCleared() {
        capture.release()
        c.tts.stop(); c.player.stop()
    }

    companion object {
        const val MAX_ANSWER_MS = 180_000L
        const val FRAMES_PER_QUESTION = 2
        private val SENTENCE_PUNCTUATION = Regex("[.!?]")
    }
}
