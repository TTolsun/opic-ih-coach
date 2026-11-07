package com.opicih.coach.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.opicih.coach.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** JSON backup / restore through SAF. Audio files are not included (they stay on the device). */
class BackupManager(private val context: Context, private val db: AppDatabase) {
    companion object {
        const val CURRENT_VERSION = 2
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(snap: Snapshot): String = json.encodeToString(Snapshot.serializer(), snap)
        /** Reads both version-1 (pre-schedule) and version-2 files. */
        fun decode(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)
        /** Only a version-2 file that actually carries a schedule replaces the current one. */
        fun restoresSchedule(snap: Snapshot): Boolean = snap.version >= 2 && snap.registrations.isNotEmpty()
    }

    @Serializable
    data class Snapshot(
        /** 1 = v2 app (learning data only). 2 = adds exam registrations, status history, training sessions. */
        val version: Int = CURRENT_VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        val scripts: List<BScript> = emptyList(),
        val revisions: List<BRevision> = emptyList(),
        val attempts: List<BAttempt> = emptyList(),
        val errors: List<BError> = emptyList(),
        val chunks: List<BChunk> = emptyList(),
        val logs: List<BLog> = emptyList(),
        val exams: List<BExam> = emptyList(),
        val registrations: List<BRegistration> = emptyList(),
        val statusEvents: List<BStatusEvent> = emptyList(),
        val trainingSessions: List<BTrainingSession> = emptyList(),
    )

    // Serializable mirrors of the entities (keeps Room entities free of serialization annotations).
    @Serializable data class BScript(val id: Long, val topic: String, val title: String, val question: String, val questionType: String, val original: String, val natural: String?, val ihTarget: String?, val shortVersion: String?, val keywords: String?, val activeVersion: String, val koreanMeaning: String?, val source: String, val masteryState: String, val masteryScore: Int, val srsStep: Int, val dueEpochDay: Long, val storyIds: String?)
    @Serializable data class BRevision(val scriptId: Long, val version: String, val text: String, val reasonKo: String, val origin: String, val status: String, val createdAt: Long)
    @Serializable data class BAttempt(val scriptId: Long?, val topic: String, val questionText: String, val questionType: String, val mode: String, val transcript: String, val captureMode: String, val durationMs: Long, val speechMs: Long, val longPauseCount: Int, val wordCount: Int, val wpm: Int, val coverage: Float?, val score: Int, val metricsJson: String, val feedbackJson: String, val feedbackSource: String, val createdAt: Long, val trainingSessionId: Long? = null)
    @Serializable data class BError(val key: String, val category: String, val example: String, val correction: String, val count: Int, val lastSeen: Long)
    @Serializable data class BChunk(val phrase: String, val meaningKo: String, val example: String, val category: String, val srsStep: Int, val dueEpochDay: Long, val lastResult: String, val failCount: Int)
    @Serializable data class BLog(val epochDay: Long, val speakingMs: Long, val attempts: Int, val scriptsReviewed: Int, val chunksReviewed: Int, val mockDone: Boolean, val morningDone: Boolean)
    @Serializable data class BExam(val id: Long = 0, val epochDay: Long, val type: String, val resultGrade: String?, val note: String, val topicsSeen: String = "", val stuckPoints: String = "", val feeling: Int = 0, val selfLevelUsed: Int = 0, val debriefAt: Long? = null)

    @Serializable data class BRegistration(
        val id: Long, val examType: String, val dateLocal: String, val datePrecision: String, val startTimeLocal: String?, val endTimeLocal: String?,
        val zoneId: String, val location: String, val status: String, val registrationOpenAt: String?, val registrationCloseAt: String?,
        val resultGrade: String?, val resultPublishedAt: String?, val waiverUsed: Boolean?, val memo: String, val sourceType: String,
        val verifiedAt: Long?, val needsReview: Boolean, val legacyExamId: Long?, val createdAt: Long, val updatedAt: Long,
    )
    @Serializable data class BStatusEvent(val registrationId: Long, val fromStatus: String?, val toStatus: String, val effectiveDate: String?, val note: String, val createdAt: Long)
    @Serializable data class BTrainingSession(val id: Long, val examType: String, val kind: String, val startedAt: Long, val finishedAt: Long?, val turnCount: Int, val aiCalls: Int, val summaryJson: String?)

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val d = db.dao()
        val snap = Snapshot(
            scripts = d.scripts().map { BScript(it.id, it.topic, it.title, it.question, it.questionType, it.original, it.natural, it.ihTarget, it.shortVersion, it.keywords, it.activeVersion, it.koreanMeaning, it.source, it.masteryState, it.masteryScore, it.srsStep, it.dueEpochDay, it.storyIds) },
            revisions = d.revisions().map { BRevision(it.scriptId, it.version, it.text, it.reasonKo, it.origin, it.status, it.createdAt) },
            attempts = d.attempts().map { BAttempt(it.scriptId, it.topic, it.questionText, it.questionType, it.mode, it.transcript, it.captureMode, it.durationMs, it.speechMs, it.longPauseCount, it.wordCount, it.wpm, it.coverage, it.score, it.metricsJson, it.feedbackJson, it.feedbackSource, it.createdAt, it.trainingSessionId) },
            errors = d.errors().map { BError(it.key, it.category, it.example, it.correction, it.count, it.lastSeen) },
            chunks = d.chunks().map { BChunk(it.phrase, it.meaningKo, it.example, it.category, it.srsStep, it.dueEpochDay, it.lastResult, it.failCount) },
            logs = d.dailyLogs().map { BLog(it.epochDay, it.speakingMs, it.attempts, it.scriptsReviewed, it.chunksReviewed, it.mockDone, it.morningDone) },
            exams = d.exams().map { BExam(it.id, it.epochDay, it.type, it.resultGrade, it.note, it.topicsSeen, it.stuckPoints, it.feeling, it.selfLevelUsed, it.debriefAt) },
            registrations = d.registrations().map {
                BRegistration(it.id, it.examType, it.dateLocal, it.datePrecision, it.startTimeLocal, it.endTimeLocal, it.zoneId, it.location, it.status,
                    it.registrationOpenAt, it.registrationCloseAt, it.resultGrade, it.resultPublishedAt, it.waiverUsed, it.memo, it.sourceType,
                    it.verifiedAt, it.needsReview, it.legacyExamId, it.createdAt, it.updatedAt)
            },
            statusEvents = d.statusEvents().map { BStatusEvent(it.registrationId, it.fromStatus, it.toStatus, it.effectiveDate, it.note, it.createdAt) },
            trainingSessions = d.trainingSessions().map { BTrainingSession(it.id, it.examType, it.kind, it.startedAt, it.finishedAt, it.turnCount, it.aiCalls, it.summaryJson) },
        )
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.encodeToString(Snapshot.serializer(), snap).toByteArray()) }
            ?: error("백업 파일을 만들 수 없습니다.")
        snap.scripts.size
    }

    /** Replaces all learning data with the backup. Returns the number of restored scripts. */
    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("백업 파일을 열 수 없습니다.")
        val snap = runCatching { decode(text) }.getOrElse { error("OPIc IH Coach 백업 파일이 아닙니다.") }
        val d = db.dao()
        db.withTransaction {
            d.clearScripts(); d.clearRevisions(); d.clearAttempts(); d.clearErrors(); d.clearChunks(); d.clearDailyLogs(); d.clearExams()
            // A version-1 file has no exam schedule: keep the current one instead of wiping it.
            val hasSchedule = restoresSchedule(snap)
            if (hasSchedule) { d.clearRegistrations(); d.clearStatusEvents(); d.clearTrainingSessions() }
            d.insertScriptsRaw(snap.scripts.map {
                com.opicih.coach.data.db.ScriptEntity(id = it.id, topic = it.topic, title = it.title, question = it.question, questionType = it.questionType, original = it.original, natural = it.natural, ihTarget = it.ihTarget, shortVersion = it.shortVersion, keywords = it.keywords, activeVersion = it.activeVersion, koreanMeaning = it.koreanMeaning, source = it.source, masteryState = it.masteryState, masteryScore = it.masteryScore, srsStep = it.srsStep, dueEpochDay = it.dueEpochDay, storyIds = it.storyIds)
            })
            d.insertRevisions(snap.revisions.map { com.opicih.coach.data.db.ScriptRevisionEntity(scriptId = it.scriptId, version = it.version, text = it.text, reasonKo = it.reasonKo, origin = it.origin, status = it.status, createdAt = it.createdAt) })
            d.insertAttempts(snap.attempts.map { com.opicih.coach.data.db.PracticeAttemptEntity(scriptId = it.scriptId, topic = it.topic, questionText = it.questionText, questionType = it.questionType, mode = it.mode, transcript = it.transcript, captureMode = it.captureMode, durationMs = it.durationMs, speechMs = it.speechMs, longPauseCount = it.longPauseCount, wordCount = it.wordCount, wpm = it.wpm, coverage = it.coverage, score = it.score, metricsJson = it.metricsJson, feedbackJson = it.feedbackJson, feedbackSource = it.feedbackSource, createdAt = it.createdAt, trainingSessionId = it.trainingSessionId) })
            d.insertErrors(snap.errors.map { com.opicih.coach.data.db.ErrorPatternEntity(key = it.key, category = it.category, example = it.example, correction = it.correction, count = it.count, lastSeen = it.lastSeen) })
            d.insertChunks(snap.chunks.map { com.opicih.coach.data.db.ChunkEntity(phrase = it.phrase, meaningKo = it.meaningKo, example = it.example, category = it.category, srsStep = it.srsStep, dueEpochDay = it.dueEpochDay, lastResult = it.lastResult, failCount = it.failCount) })
            d.insertDailyLogs(snap.logs.map { com.opicih.coach.data.db.DailyLogEntity(it.epochDay, it.speakingMs, it.attempts, it.scriptsReviewed, it.chunksReviewed, it.mockDone, it.morningDone) })
            d.insertExams(snap.exams.map { com.opicih.coach.data.db.ExamEntity(id = it.id, epochDay = it.epochDay, type = it.type, resultGrade = it.resultGrade, note = it.note, topicsSeen = it.topicsSeen, stuckPoints = it.stuckPoints, feeling = it.feeling, selfLevelUsed = it.selfLevelUsed, debriefAt = it.debriefAt) })
            if (hasSchedule) {
                d.insertRegistrations(snap.registrations.map {
                    com.opicih.coach.data.db.ExamRegistrationEntity(id = it.id, examType = it.examType, dateLocal = it.dateLocal, datePrecision = it.datePrecision,
                        startTimeLocal = it.startTimeLocal, endTimeLocal = it.endTimeLocal, zoneId = it.zoneId, location = it.location, status = it.status,
                        registrationOpenAt = it.registrationOpenAt, registrationCloseAt = it.registrationCloseAt, resultGrade = it.resultGrade,
                        resultPublishedAt = it.resultPublishedAt, waiverUsed = it.waiverUsed, memo = it.memo, sourceType = it.sourceType,
                        verifiedAt = it.verifiedAt, needsReview = it.needsReview, legacyExamId = it.legacyExamId, createdAt = it.createdAt, updatedAt = it.updatedAt)
                })
                d.insertStatusEvents(snap.statusEvents.map {
                    com.opicih.coach.data.db.ExamStatusEventEntity(registrationId = it.registrationId, fromStatus = it.fromStatus, toStatus = it.toStatus,
                        effectiveDate = it.effectiveDate, note = it.note, createdAt = it.createdAt)
                })
                d.insertTrainingSessions(snap.trainingSessions.map {
                    com.opicih.coach.data.db.ExamTrainingSessionEntity(id = it.id, examType = it.examType, kind = it.kind, startedAt = it.startedAt,
                        finishedAt = it.finishedAt, turnCount = it.turnCount, aiCalls = it.aiCalls, summaryJson = it.summaryJson)
                })
            }
        }
        snap.scripts.size
    }

    // ---- Audio storage management
    fun audioDir(): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun audioUsageBytes(): Long = audioDir().listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes recordings older than [days] and clears their paths. Returns deleted file count. */
    suspend fun purgeAudio(days: Int): Int = withContext(Dispatchers.IO) {
        val before = System.currentTimeMillis() - days * 86_400_000L
        val d = db.dao()
        var n = 0
        d.attemptsWithAudioBefore(before).forEach { a ->
            a.audioPath?.let { if (File(it).delete()) n++ }
            d.clearAudioPath(a.id)
        }
        // Orphan files (e.g. app killed while recording).
        audioDir().listFiles()?.filter { it.lastModified() < before }?.forEach { if (it.delete()) n++ }
        n
    }

    suspend fun deleteAllAudio() = withContext(Dispatchers.IO) {
        audioDir().listFiles()?.forEach { it.delete() }
        db.dao().attempts().filter { it.audioPath != null }.forEach { db.dao().clearAudioPath(it.id) }
    }
}
