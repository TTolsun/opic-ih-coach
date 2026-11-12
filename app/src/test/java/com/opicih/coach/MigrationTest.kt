package com.opicih.coach

import com.opicih.coach.data.db.MigrationSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Runs the production migration SQL (MigrationSql) against a real SQLite database built from Room's exported
 * v1 schema, then checks the result against Room's exported v8 schema and that no user data was lost.
 * This replaces an instrumented MigrationTestHelper run, which needs a device.
 */
class MigrationTest {
    private lateinit var conn: Connection
    private lateinit var dbFile: File

    private fun schemaFile(v: Int): File = listOf("schemas", "app/schemas")
        .map { File(it, "com.opicih.coach.data.db.AppDatabase/$v.json") }.first { it.exists() }

    private fun entities(v: Int): List<JsonObject> =
        Json.parseToJsonElement(schemaFile(v).readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }
    private fun count(table: String, where: String = "1=1"): Int =
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { it.next(); it.getInt(1) } }

    @Before fun setUp() {
        dbFile = File.createTempFile("migration", ".db")
        conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        for (e in entities(1)) {
            val table = e["tableName"]!!.jsonPrimitive.content
            exec(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { exec(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        seedV1()
    }

    @After fun tearDown() { conn.close(); dbFile.delete() }

    /** A realistic v1 user: 23 scripts, pending + approved revisions, attempts, errors, chunks with SRS, two exams. */
    private fun seedV1() {
        repeat(23) { i ->
            exec("INSERT INTO scripts (topic, title, question, questionType, original, activeVersion, difficulty, estSeconds, source, masteryState, masteryScore, srsStep, dueEpochDay, createdAt, updatedAt) " +
                "VALUES ('T$i', 'title $i', 'Q$i', 'OTHER', 'original text $i', 'ORIGINAL', 3, 60, 'USER_IMPORT', 'LEARNING', ${i * 3}, ${i % 5}, ${20400 + i}, 1, 1)")
        }
        exec("INSERT INTO script_revisions (scriptId, version, text, reasonKo, origin, status, createdAt) VALUES (1, 'IH_TARGET', 'better', '이유', 'PREBUILT', 'PENDING', 1)")
        exec("INSERT INTO script_revisions (scriptId, version, text, reasonKo, origin, status, createdAt) VALUES (2, 'NATURAL', 'nat', '이유', 'AI', 'APPROVED', 1)")
        repeat(5) { exec("INSERT INTO attempts (topic, questionText, questionType, mode, transcript, captureMode, durationMs, speechMs, longPauseCount, wordCount, wpm, score, metricsJson, feedbackJson, feedbackSource, createdAt) " +
            "VALUES ('T', 'Q', 'OTHER', 'FREE', 'I went to Busan', 'LIVE_STT', 60000, 50000, 1, 90, 100, 70, '{}', '{}', 'LOCAL', $it)") }
        exec("INSERT INTO error_patterns (`key`, category, example, correction, count, lastSeen) VALUES ('go-went', 'GRAMMAR', 'I go', 'I went', 4, 1)")
        exec("INSERT INTO chunks (phrase, meaningKo, example, category, srsStep, dueEpochDay, lastResult, failCount, source) VALUES ('to be honest', '솔직히', 'ex', 'opener', 3, 20410, 'OK', 1, 'AI_SAMPLE')")
        exec("INSERT INTO daily_logs VALUES (20409, 10000, 3, 2, 5, 1, 1)")
        // 2026-02-20 with a result (a real sitting) and 2026-11-14 without anything (only a plan in v1).
        exec("INSERT INTO exams (epochDay, type, resultGrade, note) VALUES (${java.time.LocalDate.of(2026, 2, 20).toEpochDay()}, 'FIRST', 'IM2', '')")
        exec("INSERT INTO exams (epochDay, type, resultGrade, note) VALUES (${java.time.LocalDate.of(2026, 11, 14).toEpochDay()}, 'RETAKE', NULL, '')")
    }

    private fun migrateToV8() {
        MigrationSql.V1_TO_V2.forEach(::exec)
        MigrationSql.V2_TO_V3.forEach(::exec)
        MigrationSql.V3_TO_V4.forEach(::exec)
        MigrationSql.V4_TO_V5.forEach(::exec)
        MigrationSql.V5_TO_V6.forEach(::exec)
        MigrationSql.V6_TO_V7.forEach(::exec)
        MigrationSql.V7_TO_V8.forEach(::exec)
    }

    @Test fun v1ToV8MatchesRoomSchema() {
        migrateToV8()
        for (e in entities(8)) {
            val table = e["tableName"]!!.jsonPrimitive.content
            val expected = e["fields"]!!.jsonArray.associate { f ->
                val o = f.jsonObject
                o["columnName"]!!.jsonPrimitive.content to (o["affinity"]!!.jsonPrimitive.content to (o["notNull"]?.jsonPrimitive?.boolean ?: false))
            }
            val actual = mutableMapOf<String, Pair<String, Boolean>>()
            conn.createStatement().use { st -> st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) actual[rs.getString("name")] = rs.getString("type") to (rs.getInt("notnull") == 1 || rs.getInt("pk") == 1)
            } }
            assertEquals("columns of $table", expected, actual)
            val expectedIdx = e["indices"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet().orEmpty()
            val actualIdx = mutableSetOf<String>()
            conn.createStatement().use { st -> st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                while (rs.next()) rs.getString("name").takeIf { it.startsWith("index_") }?.let(actualIdx::add)
            } }
            assertEquals("indices of $table", expectedIdx, actualIdx)
        }
    }

    @Test fun keepsAllUserData() {
        migrateToV8()
        assertEquals(23, count("scripts"))
        assertEquals(1, count("script_revisions", "status = 'PENDING'"))
        assertEquals(1, count("script_revisions", "status = 'APPROVED'"))
        assertEquals(5, count("attempts"))
        assertEquals(5, count("attempts", "trainingSessionId IS NULL"))
        assertEquals(5, count("attempts", "speakingReviewDueEpochDay IS NULL AND transcript = 'I went to Busan'"))
        assertEquals(1, count("error_patterns", "count = 4"))
        assertEquals(1, count("chunks", "srsStep = 3 AND dueEpochDay = 20410"))
        assertEquals(1, count("daily_logs", "epochDay = 20409 AND speakingMs = 10000 AND attempts = 3 AND scriptsReviewed = 2 AND chunksReviewed = 5 AND mockDone = 1 AND morningDone = 1"))
        assertEquals(0, count("morning_reviews"))
        assertEquals(2, count("exams"))
        assertEquals(0, count("word_lookups"))
        assertEquals(0, count("sentence_frames"))
        assertEquals(0, count("script_segments"))
        // SRS progress of every script is unchanged.
        assertEquals(23, count("scripts", "srsStep = (id - 1) % 5 AND dueEpochDay = 20400 + id - 1"))
    }

    @Test fun oldExamsBecomeReviewRowsNotFacts() {
        migrateToV8()
        assertEquals(2, count("exam_registrations"))
        assertEquals(2, count("exam_registrations", "examType = 'UNKNOWN' AND needsReview = 1 AND sourceType = 'MIGRATED' AND legacyExamId IS NOT NULL"))
        // Only the row with a result is treated as a sitting; the bare v1 date stays a plan.
        assertEquals(1, count("exam_registrations", "dateLocal = '2026-02-20' AND status = 'COMPLETED' AND resultGrade = 'IM2'"))
        assertEquals(1, count("exam_registrations", "dateLocal = '2026-11-14' AND status = 'PLANNED'"))
    }

    @Test fun divergedDevelopmentV5DatabaseIsHealedByV5ToV6() {
        // 개발 중 빌드가 v5로 sentence_frames 대신 script_segments를 만들었던 기기: v4까지 올린 뒤 그 v5 상태를 흉내 낸다.
        MigrationSql.V1_TO_V2.forEach(::exec)
        MigrationSql.V2_TO_V3.forEach(::exec)
        MigrationSql.V3_TO_V4.forEach(::exec)
        MigrationSql.V5_TO_V6.filter { it.contains("script_segments") }.forEach(::exec)
        assertEquals(0, count("sqlite_master", "name = 'sentence_frames'"))
        MigrationSql.V5_TO_V6.forEach(::exec)
        assertEquals(1, count("sqlite_master", "name = 'sentence_frames'"))
        assertEquals(1, count("sqlite_master", "name = 'script_segments'"))
        assertEquals(23, count("scripts"))
    }

    @Test fun emptyExamsTableMigrates() {
        exec("DELETE FROM exams")
        migrateToV8()
        assertEquals(0, count("exam_registrations"))
        assertTrue(count("exam_status_events") == 0 && count("exam_training_sessions") == 0)
    }

    @Test fun routinePositionIsUniqueAndRestoringChunksClearsTheirReviewHistory() {
        migrateToV8()
        exec("PRAGMA foreign_keys = ON")
        val insert = "INSERT INTO morning_reviews (routineId, itemIndex, chunkId, reviewedDay, success, initialStep)"
        exec("$insert VALUES ('routine', 0, 1, 20409, 1, 3)")
        exec("$insert VALUES ('routine', 5, 1, 20409, 0, NULL)")
        assertThrows(SQLException::class.java) { exec("$insert VALUES ('routine', 5, 1, 20409, 1, NULL)") }
        assertEquals(2, count("morning_reviews"))
        exec("DELETE FROM chunks")
        assertEquals(0, count("morning_reviews"))
        assertEquals(1, count("daily_logs", "epochDay = 20409 AND chunksReviewed = 5 AND morningDone = 1"))
    }
}
