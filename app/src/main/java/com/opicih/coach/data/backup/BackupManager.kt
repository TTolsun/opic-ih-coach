package com.opicih.coach.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.ErrorPatternEntity
import com.opicih.coach.data.db.ExamEntity
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamStatusEventEntity
import com.opicih.coach.data.db.ExamTrainingSessionEntity
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptRevisionEntity
import com.opicih.coach.data.db.WordLookupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON backup / restore through SAF. Audio files are not included (they stay on the device).
 * Settings (DataStore) and the measured study time are not included either.
 *
 * Every entity column must either be mirrored below or listed in [NOT_BACKED_UP]; BackupTest enforces it,
 * so a new column cannot silently drop out of backups.
 */
class BackupManager(private val context: Context, private val db: AppDatabase) {
    companion object {
        /** 1 = learning data only. 2 = adds the exam schedule. 3 = adds mock tests and the columns v2 dropped.
         *  4 = adds script glossary lookups. */
        const val CURRENT_VERSION = 4
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(snap: Snapshot): String = json.encodeToString(Snapshot.serializer(), snap)
        /** Reads version-1 to 4 files: columns missing from older files fall back to the entity defaults. */
        fun decode(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)
        /** Only a version-2+ file that actually carries a schedule replaces the current one. */
        fun restoresSchedule(snap: Snapshot): Boolean = snap.version >= 2 && snap.registrations.isNotEmpty()

        /**
         * Columns deliberately left out, per entity. Auto-generated ids are re-created on insert (nothing references
         * them), audio stays on the device, and import sources (raw imported files) are not backed up.
         */
        val NOT_BACKED_UP: Map<Class<*>, Set<String>> = mapOf(
            ScriptEntity::class.java to setOf("importId"),
            ScriptRevisionEntity::class.java to setOf("id"),
            PracticeAttemptEntity::class.java to setOf("id", "audioPath"),
            ErrorPatternEntity::class.java to setOf("id"),
            ChunkEntity::class.java to setOf("id"),
            ExamStatusEventEntity::class.java to setOf("id"),
            WordLookupEntity::class.java to setOf("id"),
        )

        // ---- Entity <-> mirror mapping (pure, unit tested)
        fun ScriptEntity.toBackup() = BScript(id, topic, title, question, questionType, original, natural, ihTarget, shortVersion, keywords,
            activeVersion, koreanMeaning, source, masteryState, masteryScore, srsStep, dueEpochDay, storyIds,
            difficulty, estSeconds, lastReviewedAt, linkedGroupId, createdAt, updatedAt)
        fun BScript.toEntity() = ScriptEntity(id = id, topic = topic, title = title, question = question, questionType = questionType,
            original = original, natural = natural, ihTarget = ihTarget, shortVersion = shortVersion, keywords = keywords,
            activeVersion = activeVersion, koreanMeaning = koreanMeaning, difficulty = difficulty, estSeconds = estSeconds, source = source,
            masteryState = masteryState, masteryScore = masteryScore, srsStep = srsStep, dueEpochDay = dueEpochDay,
            lastReviewedAt = lastReviewedAt, linkedGroupId = linkedGroupId, storyIds = storyIds, createdAt = createdAt, updatedAt = updatedAt)

        fun ScriptRevisionEntity.toBackup() = BRevision(scriptId, version, text, reasonKo, origin, status, createdAt)
        fun BRevision.toEntity() = ScriptRevisionEntity(scriptId = scriptId, version = version, text = text, reasonKo = reasonKo,
            origin = origin, status = status, createdAt = createdAt)

        fun PracticeAttemptEntity.toBackup() = BAttempt(scriptId, topic, questionText, questionType, mode, transcript, captureMode,
            durationMs, speechMs, longPauseCount, wordCount, wpm, coverage, score, metricsJson, feedbackJson, feedbackSource, createdAt,
            trainingSessionId, mockId)
        fun BAttempt.toEntity() = PracticeAttemptEntity(scriptId = scriptId, topic = topic, questionText = questionText,
            questionType = questionType, mode = mode, transcript = transcript, captureMode = captureMode, durationMs = durationMs,
            speechMs = speechMs, longPauseCount = longPauseCount, wordCount = wordCount, wpm = wpm, coverage = coverage, score = score,
            metricsJson = metricsJson, feedbackJson = feedbackJson, feedbackSource = feedbackSource, mockId = mockId,
            createdAt = createdAt, trainingSessionId = trainingSessionId)

        fun ErrorPatternEntity.toBackup() = BError(key, category, example, correction, count, lastSeen)
        fun BError.toEntity() = ErrorPatternEntity(key = key, category = category, example = example, correction = correction,
            count = count, lastSeen = lastSeen)

        fun ChunkEntity.toBackup() = BChunk(phrase, meaningKo, example, category, srsStep, dueEpochDay, lastResult, failCount,
            lastReviewedEpochDay, source)
        fun BChunk.toEntity() = ChunkEntity(phrase = phrase, meaningKo = meaningKo, example = example, category = category,
            srsStep = srsStep, dueEpochDay = dueEpochDay, lastResult = lastResult, lastReviewedEpochDay = lastReviewedEpochDay,
            failCount = failCount, source = source)

        fun DailyLogEntity.toBackup() = BLog(epochDay, speakingMs, attempts, scriptsReviewed, chunksReviewed, mockDone, morningDone)
        fun BLog.toEntity() = DailyLogEntity(epochDay, speakingMs, attempts, scriptsReviewed, chunksReviewed, mockDone, morningDone)

        fun MockTestEntity.toBackup() = BMock(id, startedAt, finishedAt, questionCount, selfLevel, reportJson)
        fun BMock.toEntity() = MockTestEntity(id = id, startedAt = startedAt, finishedAt = finishedAt, questionCount = questionCount,
            selfLevel = selfLevel, reportJson = reportJson)

        fun ExamEntity.toBackup() = BExam(id, epochDay, type, resultGrade, note, topicsSeen, stuckPoints, feeling, selfLevelUsed, debriefAt)
        fun BExam.toEntity() = ExamEntity(id = id, epochDay = epochDay, type = type, resultGrade = resultGrade, note = note,
            topicsSeen = topicsSeen, stuckPoints = stuckPoints, feeling = feeling, selfLevelUsed = selfLevelUsed, debriefAt = debriefAt)

        fun ExamRegistrationEntity.toBackup() = BRegistration(id, examType, dateLocal, datePrecision, startTimeLocal, endTimeLocal, zoneId,
            location, status, registrationOpenAt, registrationCloseAt, resultGrade, resultPublishedAt, waiverUsed, memo, sourceType,
            verifiedAt, needsReview, legacyExamId, createdAt, updatedAt)
        fun BRegistration.toEntity() = ExamRegistrationEntity(id = id, examType = examType, dateLocal = dateLocal,
            datePrecision = datePrecision, startTimeLocal = startTimeLocal, endTimeLocal = endTimeLocal, zoneId = zoneId,
            location = location, status = status, registrationOpenAt = registrationOpenAt, registrationCloseAt = registrationCloseAt,
            resultGrade = resultGrade, resultPublishedAt = resultPublishedAt, waiverUsed = waiverUsed, memo = memo,
            sourceType = sourceType, verifiedAt = verifiedAt, needsReview = needsReview, legacyExamId = legacyExamId,
            createdAt = createdAt, updatedAt = updatedAt)

        fun ExamStatusEventEntity.toBackup() = BStatusEvent(registrationId, fromStatus, toStatus, effectiveDate, note, createdAt)
        fun BStatusEvent.toEntity() = ExamStatusEventEntity(registrationId = registrationId, fromStatus = fromStatus,
            toStatus = toStatus, effectiveDate = effectiveDate, note = note, createdAt = createdAt)

        fun WordLookupEntity.toBackup() = BLookup(phrase, scriptId, surface, sentence, count, firstAt, lastAt)
        fun BLookup.toEntity() = WordLookupEntity(phrase = phrase, scriptId = scriptId, surface = surface, sentence = sentence,
            count = count, firstAt = firstAt, lastAt = lastAt)

        fun ExamTrainingSessionEntity.toBackup() = BTrainingSession(id, examType, kind, startedAt, finishedAt, turnCount, aiCalls, summaryJson)
        fun BTrainingSession.toEntity() = ExamTrainingSessionEntity(id = id, examType = examType, kind = kind, startedAt = startedAt,
            finishedAt = finishedAt, turnCount = turnCount, aiCalls = aiCalls, summaryJson = summaryJson)
    }

    @Serializable
    data class Snapshot(
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
        val mocks: List<BMock> = emptyList(),
        val lookups: List<BLookup> = emptyList(),
    )

    // Serializable mirrors of the entities (keeps Room entities free of serialization annotations).
    // Fields added after version 2 carry the entity default so older files still decode.
    @Serializable data class BScript(
        val id: Long, val topic: String, val title: String, val question: String, val questionType: String, val original: String,
        val natural: String?, val ihTarget: String?, val shortVersion: String?, val keywords: String?, val activeVersion: String,
        val koreanMeaning: String?, val source: String, val masteryState: String, val masteryScore: Int, val srsStep: Int,
        val dueEpochDay: Long, val storyIds: String?,
        val difficulty: Int = 3, val estSeconds: Int = 60, val lastReviewedAt: Long? = null, val linkedGroupId: String? = null,
        val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis(),
    )
    @Serializable data class BRevision(val scriptId: Long, val version: String, val text: String, val reasonKo: String, val origin: String, val status: String, val createdAt: Long)
    @Serializable data class BAttempt(
        val scriptId: Long?, val topic: String, val questionText: String, val questionType: String, val mode: String, val transcript: String,
        val captureMode: String, val durationMs: Long, val speechMs: Long, val longPauseCount: Int, val wordCount: Int, val wpm: Int,
        val coverage: Float?, val score: Int, val metricsJson: String, val feedbackJson: String, val feedbackSource: String,
        val createdAt: Long, val trainingSessionId: Long? = null, val mockId: Long? = null,
    )
    @Serializable data class BError(val key: String, val category: String, val example: String, val correction: String, val count: Int, val lastSeen: Long)
    @Serializable data class BChunk(
        val phrase: String, val meaningKo: String, val example: String, val category: String, val srsStep: Int, val dueEpochDay: Long,
        val lastResult: String, val failCount: Int, val lastReviewedEpochDay: Long? = null, val source: String = ContentSource.AI_SAMPLE.name,
    )
    @Serializable data class BLog(val epochDay: Long, val speakingMs: Long, val attempts: Int, val scriptsReviewed: Int, val chunksReviewed: Int, val mockDone: Boolean, val morningDone: Boolean)
    @Serializable data class BMock(val id: Long, val startedAt: Long, val finishedAt: Long?, val questionCount: Int, val selfLevel: Int, val reportJson: String?)
    @Serializable data class BExam(val id: Long = 0, val epochDay: Long, val type: String, val resultGrade: String?, val note: String, val topicsSeen: String = "", val stuckPoints: String = "", val feeling: Int = 0, val selfLevelUsed: Int = 0, val debriefAt: Long? = null)

    @Serializable data class BRegistration(
        val id: Long, val examType: String, val dateLocal: String, val datePrecision: String, val startTimeLocal: String?, val endTimeLocal: String?,
        val zoneId: String, val location: String, val status: String, val registrationOpenAt: String?, val registrationCloseAt: String?,
        val resultGrade: String?, val resultPublishedAt: String?, val waiverUsed: Boolean?, val memo: String, val sourceType: String,
        val verifiedAt: Long?, val needsReview: Boolean, val legacyExamId: Long?, val createdAt: Long, val updatedAt: Long,
    )
    @Serializable data class BStatusEvent(val registrationId: Long, val fromStatus: String?, val toStatus: String, val effectiveDate: String?, val note: String, val createdAt: Long)
    @Serializable data class BLookup(val phrase: String, val scriptId: Long, val surface: String, val sentence: String, val count: Int, val firstAt: Long, val lastAt: Long)
    @Serializable data class BTrainingSession(val id: Long, val examType: String, val kind: String, val startedAt: Long, val finishedAt: Long?, val turnCount: Int, val aiCalls: Int, val summaryJson: String?)

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val d = db.dao()
        val snap = Snapshot(
            scripts = d.scripts().map { it.toBackup() },
            revisions = d.revisions().map { it.toBackup() },
            attempts = d.attempts().map { it.toBackup() },
            errors = d.errors().map { it.toBackup() },
            chunks = d.chunks().map { it.toBackup() },
            logs = d.dailyLogs().map { it.toBackup() },
            exams = d.exams().map { it.toBackup() },
            registrations = d.registrations().map { it.toBackup() },
            statusEvents = d.statusEvents().map { it.toBackup() },
            trainingSessions = d.trainingSessions().map { it.toBackup() },
            mocks = d.mocks().map { it.toBackup() },
            lookups = d.lookups().map { it.toBackup() },
        )
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encode(snap).toByteArray()) }
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
            // Mocks are always replaced: attempts are, and kept mocks would lose their answers (v1/v2 files have none).
            d.clearMocks()
            d.insertScriptsRaw(snap.scripts.map { it.toEntity() })
            d.insertRevisions(snap.revisions.map { it.toEntity() })
            d.insertAttempts(snap.attempts.map { it.toEntity() })
            d.insertErrors(snap.errors.map { it.toEntity() })
            d.insertChunks(snap.chunks.map { it.toEntity() })
            d.insertDailyLogs(snap.logs.map { it.toEntity() })
            d.insertExams(snap.exams.map { it.toEntity() })
            d.insertMocks(snap.mocks.map { it.toEntity() })
            // Always replaced: lookups point at script ids, and the scripts were just replaced too.
            d.clearLookups()
            d.insertLookups(snap.lookups.map { it.toEntity() })
            if (hasSchedule) {
                d.insertRegistrations(snap.registrations.map { it.toEntity() })
                d.insertStatusEvents(snap.statusEvents.map { it.toEntity() })
                d.insertTrainingSessions(snap.trainingSessions.map { it.toEntity() })
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
