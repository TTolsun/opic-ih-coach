package com.opicih.coach.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One DAO keeps a single-module, single-user app easy to follow. */
@Dao
interface AppDao {
    // ---- Scripts
    @Query("SELECT * FROM scripts ORDER BY topic, id") fun scriptsFlow(): Flow<List<ScriptEntity>>
    @Query("SELECT * FROM scripts ORDER BY topic, id") suspend fun scripts(): List<ScriptEntity>
    @Query("SELECT * FROM scripts WHERE id = :id") fun scriptFlow(id: Long): Flow<ScriptEntity?>
    @Query("SELECT * FROM scripts WHERE id = :id") suspend fun script(id: Long): ScriptEntity?
    @Query("SELECT id FROM scripts ORDER BY topic, id") suspend fun scriptIds(): List<Long>
    @Query("SELECT COUNT(*) FROM scripts") suspend fun scriptCount(): Int
    @Insert suspend fun insertScript(s: ScriptEntity): Long
    @Update suspend fun updateScript(s: ScriptEntity)
    @Update suspend fun updateScripts(s: List<ScriptEntity>)
    @Delete suspend fun deleteScript(s: ScriptEntity)

    // ---- Revisions
    @Query("SELECT * FROM script_revisions WHERE scriptId = :scriptId ORDER BY createdAt DESC")
    fun revisionsFlow(scriptId: Long): Flow<List<ScriptRevisionEntity>>
    @Query("SELECT * FROM script_revisions WHERE status = 'PENDING'") fun pendingRevisionsFlow(): Flow<List<ScriptRevisionEntity>>
    @Query("SELECT * FROM script_revisions") suspend fun revisions(): List<ScriptRevisionEntity>
    @Insert suspend fun insertRevisions(r: List<ScriptRevisionEntity>)
    @Update suspend fun updateRevision(r: ScriptRevisionEntity)
    @Query("DELETE FROM script_revisions WHERE scriptId = :scriptId") suspend fun deleteRevisionsFor(scriptId: Long)

    // ---- Import sources
    @Insert suspend fun insertImport(i: ImportSourceEntity): Long

    // ---- Attempts
    @Insert suspend fun insertAttempt(a: PracticeAttemptEntity): Long
    @Query("SELECT * FROM attempts ORDER BY createdAt DESC") fun attemptsFlow(): Flow<List<PracticeAttemptEntity>>
    @Query("SELECT * FROM attempts ORDER BY createdAt DESC") suspend fun attempts(): List<PracticeAttemptEntity>
    /** Same question in the same mode only: a memorize-stage read-aloud score is not comparable to a blind answer. */
    @Query("SELECT * FROM attempts WHERE questionText = :q AND mode = :mode ORDER BY createdAt DESC LIMIT 5")
    suspend fun recentForQuestion(q: String, mode: String): List<PracticeAttemptEntity>
    @Query("SELECT * FROM attempts WHERE mockId = :mockId ORDER BY createdAt") suspend fun attemptsForMock(mockId: Long): List<PracticeAttemptEntity>
    @Query("SELECT * FROM attempts WHERE audioPath IS NOT NULL AND createdAt < :before")
    suspend fun attemptsWithAudioBefore(before: Long): List<PracticeAttemptEntity>
    @Query("UPDATE attempts SET audioPath = NULL WHERE id = :id") suspend fun clearAudioPath(id: Long)
    @Delete suspend fun deleteAttempt(a: PracticeAttemptEntity)

    // ---- Error patterns
    @Query("SELECT * FROM error_patterns ORDER BY count DESC, lastSeen DESC") fun errorsFlow(): Flow<List<ErrorPatternEntity>>
    @Query("SELECT * FROM error_patterns ORDER BY count DESC, lastSeen DESC") suspend fun errors(): List<ErrorPatternEntity>
    @Query("SELECT * FROM error_patterns WHERE `key` = :key") suspend fun error(key: String): ErrorPatternEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertError(e: ErrorPatternEntity)

    // ---- Chunks
    @Query("SELECT * FROM chunks ORDER BY id") suspend fun chunks(): List<ChunkEntity>
    @Insert suspend fun insertChunks(c: List<ChunkEntity>)
    @Update suspend fun updateChunk(c: ChunkEntity)

    // ---- Daily logs
    @Query("SELECT * FROM daily_logs WHERE epochDay = :day") suspend fun dailyLog(day: Long): DailyLogEntity?
    @Query("SELECT * FROM daily_logs ORDER BY epochDay DESC") fun dailyLogsFlow(): Flow<List<DailyLogEntity>>
    @Query("SELECT * FROM daily_logs ORDER BY epochDay DESC") suspend fun dailyLogs(): List<DailyLogEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDailyLog(d: DailyLogEntity)

    // ---- Mock tests
    @Insert suspend fun insertMock(m: MockTestEntity): Long
    @Update suspend fun updateMock(m: MockTestEntity)
    @Query("SELECT * FROM mock_tests WHERE id = :id") suspend fun mock(id: Long): MockTestEntity?
    @Query("SELECT * FROM mock_tests ORDER BY startedAt DESC") fun mocksFlow(): Flow<List<MockTestEntity>>
    @Query("SELECT * FROM mock_tests ORDER BY startedAt DESC") suspend fun mocks(): List<MockTestEntity>

    // ---- Exams
    @Query("SELECT * FROM exams ORDER BY epochDay DESC") fun examsFlow(): Flow<List<ExamEntity>>
    @Query("SELECT * FROM exams ORDER BY epochDay DESC") suspend fun exams(): List<ExamEntity>
    @Insert suspend fun insertExam(e: ExamEntity): Long
    @Update suspend fun updateExam(e: ExamEntity)

    // ---- Exam registrations (v3)
    @Query("SELECT * FROM exam_registrations ORDER BY dateLocal, startTimeLocal") fun registrationsFlow(): Flow<List<ExamRegistrationEntity>>
    @Query("SELECT * FROM exam_registrations ORDER BY dateLocal, startTimeLocal") suspend fun registrations(): List<ExamRegistrationEntity>
    @Query("SELECT * FROM exam_registrations WHERE id = :id") suspend fun registration(id: Long): ExamRegistrationEntity?
    @Insert suspend fun insertRegistration(r: ExamRegistrationEntity): Long
    @Insert suspend fun insertRegistrations(r: List<ExamRegistrationEntity>)
    @Update suspend fun updateRegistration(r: ExamRegistrationEntity)
    @Query("DELETE FROM exam_registrations WHERE id = :id") suspend fun deleteRegistration(id: Long)
    @Query("SELECT * FROM exam_status_events ORDER BY createdAt") suspend fun statusEvents(): List<ExamStatusEventEntity>
    @Insert suspend fun insertStatusEvent(e: ExamStatusEventEntity): Long
    @Insert suspend fun insertStatusEvents(e: List<ExamStatusEventEntity>)
    @Query("DELETE FROM exam_status_events WHERE registrationId = :id") suspend fun deleteStatusEventsFor(id: Long)
    @Query("SELECT * FROM exam_training_sessions ORDER BY startedAt DESC") suspend fun trainingSessions(): List<ExamTrainingSessionEntity>
    @Query("SELECT * FROM exam_training_sessions ORDER BY startedAt DESC") fun trainingSessionsFlow(): Flow<List<ExamTrainingSessionEntity>>
    @Insert suspend fun insertTrainingSession(s: ExamTrainingSessionEntity): Long
    @Insert suspend fun insertTrainingSessions(s: List<ExamTrainingSessionEntity>)
    @Update suspend fun updateTrainingSession(s: ExamTrainingSessionEntity)

    // ---- Stories / questions
    @Query("SELECT * FROM stories ORDER BY id") suspend fun stories(): List<StoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStories(s: List<StoryEntity>)
    @Query("SELECT * FROM questions ORDER BY topic, id") suspend fun questions(): List<QuestionEntity>
    @Insert suspend fun insertQuestions(q: List<QuestionEntity>)

    // ---- Glossary lookups (v4)
    @Query("SELECT * FROM word_lookups WHERE phrase = :phrase AND scriptId = :scriptId") suspend fun lookup(phrase: String, scriptId: Long): WordLookupEntity?
    /** IGNORE: a second insert for the same (phrase, script) after a cancelled write must not crash. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLookup(l: WordLookupEntity)
    @Query("SELECT MAX(lastAt) FROM word_lookups WHERE phrase = :phrase") suspend fun lastLookupAt(phrase: String): Long?
    @Update suspend fun updateLookup(l: WordLookupEntity)
    @Query("SELECT COALESCE(SUM(count), 0) FROM word_lookups WHERE phrase = :phrase") suspend fun lookupDays(phrase: String): Int
    @Query("SELECT * FROM word_lookups WHERE scriptId = :scriptId ORDER BY count DESC, lastAt DESC") fun lookupsFlow(scriptId: Long): Flow<List<WordLookupEntity>>
    @Query("SELECT * FROM word_lookups ORDER BY id") suspend fun lookups(): List<WordLookupEntity>
    @Insert suspend fun insertLookups(l: List<WordLookupEntity>)
    @Query("SELECT COUNT(*) FROM chunks WHERE lower(phrase) IN (:phrases)") suspend fun chunkCount(phrases: List<String>): Int

    // ---- Sentence frames (v5)
    /** All frames, pending first. REJECTED rows stay so a rejected frame is not suggested again; the screen can restore them. */
    @Query("SELECT * FROM sentence_frames ORDER BY status DESC, id") fun framesFlow(): Flow<List<SentenceFrameEntity>>
    @Query("SELECT * FROM sentence_frames ORDER BY id") suspend fun frames(): List<SentenceFrameEntity>
    @Query("SELECT * FROM sentence_frames WHERE status = 'APPROVED' ORDER BY id") suspend fun approvedFrames(): List<SentenceFrameEntity>
    /** IGNORE: the unique pattern index makes a repeated extraction a no-op for frames that already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFrames(f: List<SentenceFrameEntity>)
    @Query("UPDATE sentence_frames SET status = :status WHERE id = :id") suspend fun setFrameStatus(id: Long, status: String)

    // ---- Script segments (v6)
    @Query("SELECT * FROM script_segments WHERE scriptId = :scriptId ORDER BY kind, idx") suspend fun segmentsFor(scriptId: Long): List<ScriptSegmentEntity>
    @Query("SELECT * FROM script_segments ORDER BY scriptId, kind, idx") suspend fun segments(): List<ScriptSegmentEntity>
    @Query("DELETE FROM script_segments WHERE scriptId = :scriptId") suspend fun deleteSegmentsFor(scriptId: Long)
    @Insert suspend fun insertSegments(s: List<ScriptSegmentEntity>)

    // ---- Wipe / restore
    @Query("DELETE FROM scripts") suspend fun clearScripts()
    @Query("DELETE FROM script_revisions") suspend fun clearRevisions()
    @Query("DELETE FROM import_sources") suspend fun clearImports()
    @Query("DELETE FROM attempts") suspend fun clearAttempts()
    @Query("DELETE FROM error_patterns") suspend fun clearErrors()
    @Query("DELETE FROM chunks") suspend fun clearChunks()
    @Query("DELETE FROM daily_logs") suspend fun clearDailyLogs()
    @Query("DELETE FROM mock_tests") suspend fun clearMocks()
    @Query("DELETE FROM exams") suspend fun clearExams()
    @Query("DELETE FROM stories") suspend fun clearStories()
    @Query("DELETE FROM questions") suspend fun clearQuestions()
    @Query("DELETE FROM exam_registrations") suspend fun clearRegistrations()
    @Query("DELETE FROM exam_status_events") suspend fun clearStatusEvents()
    @Query("DELETE FROM exam_training_sessions") suspend fun clearTrainingSessions()
    @Query("DELETE FROM word_lookups") suspend fun clearLookups()
    @Query("DELETE FROM sentence_frames") suspend fun clearFrames()
    @Query("DELETE FROM script_segments") suspend fun clearSegments()

    @Insert suspend fun insertScriptsRaw(s: List<ScriptEntity>)
    @Insert suspend fun insertAttempts(a: List<PracticeAttemptEntity>)
    @Insert suspend fun insertErrors(e: List<ErrorPatternEntity>)
    @Insert suspend fun insertDailyLogs(d: List<DailyLogEntity>)
    @Insert suspend fun insertMocks(m: List<MockTestEntity>)
    @Insert suspend fun insertExams(e: List<ExamEntity>)

    @Transaction
    suspend fun wipeAll() {
        clearScripts(); clearRevisions(); clearImports(); clearAttempts(); clearErrors(); clearChunks()
        clearDailyLogs(); clearMocks(); clearExams(); clearStories(); clearQuestions()
        clearRegistrations(); clearStatusEvents(); clearTrainingSessions(); clearLookups(); clearFrames(); clearSegments()
    }
}
