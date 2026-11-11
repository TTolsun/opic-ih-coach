package com.opicih.coach.data.repo

import com.opicih.coach.ai.AiCoach
import com.opicih.coach.ai.EvalInput
import com.opicih.coach.ai.ScriptInput
import com.opicih.coach.analysis.AnalysisInput
import com.opicih.coach.analysis.CompareResult
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.DiscourseMetrics
import com.opicih.coach.analysis.ErrorCounts
import com.opicih.coach.analysis.VerbatimMarkup
import com.opicih.coach.analysis.Feedback
import com.opicih.coach.analysis.FeedbackComposer
import com.opicih.coach.analysis.PauseMetrics
import com.opicih.coach.analysis.ScriptComparator
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.analysis.taskCorrection
import com.opicih.coach.analysis.withCanonicalKey
import androidx.room.withTransaction
import com.opicih.coach.data.db.AppDao
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.ErrorPatternEntity
import com.opicih.coach.data.db.ImportSourceEntity
import com.opicih.coach.data.db.MasteryState
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.RevisionOrigin
import com.opicih.coach.data.db.RevisionStatus
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptRevisionEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.importer.DuplicateLinker
import com.opicih.coach.importer.ParsedScript
import com.opicih.coach.learning.CAPTURE_CLOUD_STT
import com.opicih.coach.learning.MasteryRules
import com.opicih.coach.learning.ScoreKind
import com.opicih.coach.learning.SrsScheduler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class AttemptMetrics(
    val discourse: DiscourseMetrics,
    val pauses: PauseMetrics? = null,
    val compare: CompareResult? = null,
    /** Tense errors, abandoned sentences and self-corrections (#25). Null in rows saved before this field existed. */
    val errorCounts: ErrorCounts? = null,
    /** Local transcript score retained even when AI feedback is shown. */
    val localScore: Int? = null,
    /** This record uses the flow score. False for memorization scores and older rubric scores. */
    val speakingFlow: Boolean = false,
) {
    companion object {
        fun from(attempt: PracticeAttemptEntity): AttemptMetrics? =
            runCatching { appJson.decodeFromString(serializer(), attempt.metricsJson) }.getOrNull()
    }
}

fun PracticeAttemptEntity.measuredScore(metrics: AttemptMetrics? = AttemptMetrics.from(this)): Int? =
    if (metrics?.speakingFlow == true) metrics.localScore else score.takeIf { wordCount > 0 }

data class AnalyzedAttempt(
    val attempt: PracticeAttemptEntity,
    val feedback: Feedback,
    val metrics: AttemptMetrics,
    val previous: PracticeAttemptEntity?,
    val aiError: String? = null,
)

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

class ScriptRepository(private val dao: AppDao) {
    fun scripts() = dao.scriptsFlow()
    fun script(id: Long) = dao.scriptFlow(id)
    fun revisions(id: Long) = dao.revisionsFlow(id)

    suspend fun save(s: ScriptEntity): Long =
        if (s.id == 0L) dao.insertScript(s) else { dao.updateScript(s.copy(updatedAt = System.currentTimeMillis())); s.id }

    suspend fun delete(s: ScriptEntity) { dao.deleteRevisionsFor(s.id); dao.deleteSegmentsFor(s.id); dao.deleteScript(s) }

    /** Approving copies the revision into its version field and makes it the active study text. The original stays. */
    suspend fun approve(rev: ScriptRevisionEntity, makeActive: Boolean = true) {
        val s = dao.script(rev.scriptId) ?: return
        val v = ScriptVersion.of(rev.version)
        val updated = when (v) {
            ScriptVersion.NATURAL -> s.copy(natural = rev.text)
            ScriptVersion.IH_TARGET -> s.copy(ihTarget = rev.text)
            ScriptVersion.SHORT -> s.copy(shortVersion = rev.text)
            ScriptVersion.KEYWORDS -> s.copy(keywords = rev.text)
            ScriptVersion.ORIGINAL -> s
        }.let { if (makeActive && v != ScriptVersion.KEYWORDS && v != ScriptVersion.ORIGINAL) it.copy(activeVersion = v.name) else it }
        dao.updateScript(updated.copy(updatedAt = System.currentTimeMillis()))
        dao.updateRevision(rev.copy(status = RevisionStatus.APPROVED.name))
    }

    suspend fun reject(rev: ScriptRevisionEntity) = dao.updateRevision(rev.copy(status = RevisionStatus.REJECTED.name))

    suspend fun setActive(s: ScriptEntity, v: ScriptVersion) = dao.updateScript(s.copy(activeVersion = v.name))

    suspend fun requestImprovement(s: ScriptEntity, coach: AiCoach, storyHints: String): Result<Int> {
        val r = coach.improveScript(ScriptInput(s.topic, s.question, s.original, storyHints))
        return r.map { v ->
            val origin = if (coach.isRealAi) RevisionOrigin.AI_CLAUDE.name else RevisionOrigin.LOCAL.name
            val revs = buildList {
                if (v.natural.isNotBlank()) add(ScriptRevisionEntity(scriptId = s.id, version = ScriptVersion.NATURAL.name, text = v.natural, reasonKo = v.reasonKo, origin = origin))
                if (v.ihTarget.isNotBlank()) add(ScriptRevisionEntity(scriptId = s.id, version = ScriptVersion.IH_TARGET.name, text = v.ihTarget, reasonKo = v.reasonKo, origin = origin))
                if (v.short.isNotBlank()) add(ScriptRevisionEntity(scriptId = s.id, version = ScriptVersion.SHORT.name, text = v.short, reasonKo = "긴급 상황용 핵심 답변", origin = origin))
                if (v.keywords.isNotEmpty()) add(ScriptRevisionEntity(scriptId = s.id, version = ScriptVersion.KEYWORDS.name, text = v.keywords.joinToString("\n"), reasonKo = "말하는 순서대로 정리한 키워드", origin = origin))
            }
            dao.insertRevisions(revs)
            if (v.koreanMeaning.isNotBlank()) dao.updateScript(s.copy(koreanMeaning = v.koreanMeaning))
            revs.size
        }
    }

    /** Saves parsed scripts, keeps the raw import text, and links near-duplicates. */
    suspend fun importParsed(fileName: String, mime: String, raw: String, parsed: List<ParsedScript>): Int {
        val importId = dao.insertImport(ImportSourceEntity(fileName = fileName, mime = mime, rawText = raw.take(2_000_000), parsedCount = parsed.size))
        val today = LocalDate.now().toEpochDay()
        parsed.forEach { p ->
            dao.insertScript(
                ScriptEntity(
                    topic = p.topic, title = p.title, question = p.question, questionType = p.questionType.name,
                    original = p.answer, estSeconds = p.estSeconds, source = ContentSource.USER_IMPORT.name,
                    importId = importId, dueEpochDay = today,
                )
            )
        }
        relink()
        return parsed.size
    }

    suspend fun relink() {
        val all = dao.scripts()
        val groups = DuplicateLinker.groups(all.map { DuplicateLinker.Item(it.id, it.question, it.original) })
        val changed = all.filter { groups[it.id] != it.linkedGroupId }.map { it.copy(linkedGroupId = groups[it.id]) }
        if (changed.isNotEmpty()) dao.updateScripts(changed)
    }
}

class PracticeRepository(private val db: AppDatabase) {
    private val dao = db.dao()

    data class Request(
        val script: ScriptEntity?,
        val topic: String,
        val question: String,
        val questionType: String,
        val mode: PracticeMode,
        val transcript: String,
        val captureMode: String,
        val audioPath: String?,
        val durationMs: Long,
        val pauses: PauseMetrics?,
        val transcriptFromDeviceStt: Boolean,
        val requiredTense: String? = null,
        /** Variation drill instruction shown with the question; passed to AI evaluation. */
        val mission: String? = null,
        val mockId: Long? = null,
        /** Target text used for comparison (study text, or a single sentence in shadowing). */
        val target: String? = null,
        /** v3: turn of an exam training session (SAIL conversation). */
        val trainingSessionId: Long? = null,
        /** Why the verbatim transcript was not used for an answer that should have had one (#25). Shown as a limit. */
        val captureNote: String? = null,
        val speakingReviewId: Long? = null,
        val missionQuestion: String? = null,
    )

    /**
     * Analyses the answer, then saves the attempt, daily log, script mastery/SRS and error notebook in one
     * transaction so a crash cannot leave a half-written practice record.
     */
    suspend fun analyzeAndSave(req: Request, ai: AiCoach?, examEpochDay: Long?): AnalyzedAttempt {
        // The network call stays outside the transaction.
        val result = analyze(req, ai)
        return db.withTransaction { persist(req, result, examEpochDay) }
    }

    companion object {
        /** Local analysis always runs; AI evaluation is attempted only when [ai] is a real AI and falls back on failure. */
        internal suspend fun analyze(req: Request, ai: AiCoach?): AnalyzedAttempt {
            // A verbatim transcript carries [self-correction]/[abandoned] marks. Word counts, tenses and script comparison
            // use the text without them; the stored transcript and the AI keep the marks.
            val text = VerbatimMarkup.parse(req.transcript).clean
            val scoreKind = ScoreKind.of(req.mode)
            val discourse = DiscourseAnalyzer.analyze(text)
            val verbatim = req.captureMode == CAPTURE_CLOUD_STT && discourse.wordCount > 0
            val target = req.target ?: req.script?.studyText?.takeIf { req.mode.comparesToScript }
            val compare = target?.takeIf { text.isNotBlank() }?.let { ScriptComparator.compare(it, text, req.script?.keywordList().orEmpty()) }
            val composed = if (text.isBlank() && req.pauses != null && !req.transcriptFromDeviceStt) {
                FeedbackComposer.acousticOnly(req.pauses)
            } else {
                FeedbackComposer.compose(
                    AnalysisInput(
                        req.questionType, target != null, text, discourse, req.pauses, compare, req.transcriptFromDeviceStt,
                        req.requiredTense, scoreKind, req.question, req.mission, req.missionQuestion,
                    )
                )
            }
            val local = composed.copy(limits = composed.limits + listOfNotNull(req.captureNote))
            var feedback = local
            var aiError: String? = null
            if (ai != null && ai.isRealAi && discourse.wordCount >= 5 && local.errors.none { it.key == "question_echo" }) {
                // 말하기 점수 모드에는 스크립트를 넘기지 않습니다. 넘기면 AI가 원문과 비슷한 답변에 높은 점수를 줄 수 있습니다.
                val aiTarget = target.takeIf { scoreKind == ScoreKind.MEMORIZATION }
                ai.evaluate(
                    EvalInput(
                        req.question, req.questionType, req.transcript, discourse, req.pauses, aiTarget, req.transcriptFromDeviceStt,
                        req.mission, req.captureMode, req.requiredTense, req.missionQuestion,
                    ),
                ).onSuccess { fb ->
                    val speaking = scoreKind == ScoreKind.SPEAKING
                    val errors = fb.errors.map { it.withCanonicalKey() }.filterNot { it.key == "abandoned_sentence" }
                    val taskFix = errors.firstNotNullOfOrNull { it.taskCorrection(req.transcript) }.takeIf { speaking }
                    feedback = fb.copy(
                        score = if (speaking) local.score else fb.score,
                        scoreMeasured = if (speaking) local.scoreMeasured else fb.scoreMeasured,
                        fixes = if (taskFix == null) fb.fixes else
                            (listOf(taskFix) + fb.fixes.filterNot { it.better == taskFix.better }).take(3),
                        retryMission = taskFix?.better ?: fb.retryMission,
                        limits = local.limits.filterNot { it == FeedbackComposer.LOCAL_RELEVANCE_LIMIT },
                        errors = errors,
                        abandonedSentences = fb.abandonedSentences.takeIf { verbatim },
                        selfCorrections = fb.selfCorrections.takeIf { verbatim },
                    )
                }.onFailure { aiError = it.message }
            }
            // The error notebook is built from the same read of the transcript as the counts.
            val errorAnalysis = ErrorCounts.analyze(req.transcript, verbatim, feedback)
            feedback = feedback.copy(errors = (feedback.errors + errorAnalysis.notebook).distinctBy { it.key })
            val metrics = AttemptMetrics(
                discourse, req.pauses, compare, errorAnalysis.counts,
                local.score.takeIf { local.scoreMeasured }, speakingFlow = scoreKind == ScoreKind.SPEAKING,
            )
            val speechMs = req.pauses?.speechMs ?: 0L
            val attempt = PracticeAttemptEntity(
                scriptId = req.script?.id, topic = req.topic, questionText = req.question, questionType = req.questionType,
                mode = req.mode.name, transcript = req.transcript, captureMode = req.captureMode, audioPath = req.audioPath,
                durationMs = req.durationMs, speechMs = speechMs, longPauseCount = req.pauses?.longPauseCount ?: 0,
                wordCount = discourse.wordCount, wpm = FeedbackComposer.wpm(discourse.wordCount, speechMs, req.durationMs),
                coverage = compare?.coverage, score = feedback.score,
                metricsJson = appJson.encodeToString(AttemptMetrics.serializer(), metrics),
                feedbackJson = appJson.encodeToString(Feedback.serializer(), feedback),
                feedbackSource = feedback.source, mockId = req.mockId, trainingSessionId = req.trainingSessionId,
            )
            return AnalyzedAttempt(attempt, feedback, metrics, previous = null, aiError = aiError)
        }
    }

    private suspend fun persist(req: Request, result: AnalyzedAttempt, examEpochDay: Long?): AnalyzedAttempt {
        val (attempt, feedback, metrics) = result
        val compare = metrics.compare
        val previous = dao.recentForQuestion(req.question, req.mode.name).firstOrNull()
        val id = dao.insertAttempt(attempt)
        if (req.mode == PracticeMode.FREE && req.speakingReviewId != null && (attempt.wordCount > 0 || attempt.speechMs > 0)) {
            dao.finishSpeakingReview(req.speakingReviewId, req.question, ExamRepository.nowKst().toLocalDate().toEpochDay())
        }

        // Daily log: real speaking time, not app-open time.
        val today = LocalDate.now().toEpochDay()
        val log = dao.dailyLog(today) ?: DailyLogEntity(today)
        dao.upsertDailyLog(
            log.copy(
                speakingMs = log.speakingMs + attempt.effectiveSpeechMs,
                attempts = log.attempts + 1,
                scriptsReviewed = log.scriptsReviewed + if (req.script != null) 1 else 0,
                mockDone = log.mockDone || req.mode == PracticeMode.MOCK,
            )
        )

        // Script mastery + SRS
        val masteryScore = if (req.mode == PracticeMode.VARIATION) {
            feedback.relevance.takeIf { feedback.source == "AI" }?.let {
                if (feedback.errors.any { error -> error.key in setOf("task_incomplete", "off_topic_response", "question_echo") }) 0 else it
            }
        } else {
            feedback.score
        }
        req.script?.takeIf { req.transcript.isNotBlank() && masteryScore != null }?.let { s ->
            val evaluatedScore = requireNotNull(masteryScore)
            val m = MasteryRules.apply(s.mastery, s.masteryScore, req.mode, compare?.coverage, evaluatedScore)
            val srs = SrsScheduler.next(s.srsStep, SrsScheduler.isSuccess(req.mode, compare?.coverage, evaluatedScore), today, examEpochDay)
            dao.updateScript(s.copy(masteryState = m.state.name, masteryScore = m.score, srsStep = srs.step, dueEpochDay = srs.dueEpochDay, lastReviewedAt = System.currentTimeMillis()))
        }

        // Repeated error notebook
        feedback.errors.forEach { e ->
            val key = e.key.ifBlank { TextNormalizer.normalize(e.example).take(40) }
            val existing = dao.error(key)
            dao.upsertError(
                existing?.copy(count = existing.count + 1, lastSeen = System.currentTimeMillis(), example = e.example.ifBlank { existing.example })
                    ?: ErrorPatternEntity(key = key, category = e.category.ifBlank { "GRAMMAR" }, example = e.example, correction = e.correction)
            )
        }
        return result.copy(attempt = attempt.copy(id = id), previous = previous)
    }

    suspend fun reviewChunk(c: ChunkEntity, ok: Boolean, examEpochDay: Long?) {
        val today = LocalDate.now().toEpochDay()
        val srs = SrsScheduler.next(c.srsStep, ok, today, examEpochDay)
        dao.updateChunk(c.copy(srsStep = srs.step, dueEpochDay = srs.dueEpochDay, lastResult = if (ok) "OK" else "FAIL", lastReviewedEpochDay = today, failCount = c.failCount + if (ok) 0 else 1))
        val log = dao.dailyLog(today) ?: DailyLogEntity(today)
        dao.upsertDailyLog(log.copy(chunksReviewed = log.chunksReviewed + 1))
    }

    /**
     * Deletes one practice record (e.g. a test recording) with its audio file and takes its speaking time out of
     * that day's log. Script mastery / review dates already changed by it are left as they are.
     */
    suspend fun deleteAttempt(a: PracticeAttemptEntity) {
        a.audioPath?.let { runCatching { java.io.File(it).delete() } }
        dao.deleteAttempt(a)
        val day = java.time.Instant.ofEpochMilli(a.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        dao.dailyLog(day)?.let { dao.upsertDailyLog(com.opicih.coach.learning.ProgressCalculator.withoutAttempt(it, a)) }
    }

    suspend fun markMorningDone() {
        val today = LocalDate.now().toEpochDay()
        val log = dao.dailyLog(today) ?: DailyLogEntity(today)
        dao.upsertDailyLog(log.copy(morningDone = true))
    }
    fun decodeMetrics(a: PracticeAttemptEntity): AttemptMetrics? = AttemptMetrics.from(a)
}
