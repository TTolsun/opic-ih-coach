package com.opicih.coach.data.db

/**
 * Migration SQL kept as plain strings so JVM unit tests can run the exact same statements
 * against a real SQLite file (see MigrationTest). AppDatabase only executes these lists.
 */
object MigrationSql {
    val V1_TO_V2 = listOf(
        "ALTER TABLE exams ADD COLUMN topicsSeen TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE exams ADD COLUMN stuckPoints TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE exams ADD COLUMN feeling INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE exams ADD COLUMN selfLevelUsed INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE exams ADD COLUMN debriefAt INTEGER",
    )

    /**
     * v3: multi-exam schedule. Adds tables only; no existing row is changed or removed.
     * Old `exams` rows are copied into exam_registrations as UNKNOWN type + needsReview, because v2 never stored
     * which exam it was. A row counts as COMPLETED only if it has a result or a debrief; otherwise it stays PLANNED.
     */
    val V2_TO_V3 = listOf(
        "CREATE TABLE IF NOT EXISTS `exam_registrations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `examType` TEXT NOT NULL, " +
            "`dateLocal` TEXT NOT NULL, `datePrecision` TEXT NOT NULL, `startTimeLocal` TEXT, `endTimeLocal` TEXT, `zoneId` TEXT NOT NULL, " +
            "`location` TEXT NOT NULL, `status` TEXT NOT NULL, `registrationOpenAt` TEXT, `registrationCloseAt` TEXT, `resultGrade` TEXT, " +
            "`resultPublishedAt` TEXT, `waiverUsed` INTEGER, `memo` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `verifiedAt` INTEGER, " +
            "`needsReview` INTEGER NOT NULL, `legacyExamId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_exam_registrations_dateLocal` ON `exam_registrations` (`dateLocal`)",
        "CREATE INDEX IF NOT EXISTS `index_exam_registrations_examType` ON `exam_registrations` (`examType`)",
        "CREATE TABLE IF NOT EXISTS `exam_status_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `registrationId` INTEGER NOT NULL, " +
            "`fromStatus` TEXT, `toStatus` TEXT NOT NULL, `effectiveDate` TEXT, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_exam_status_events_registrationId` ON `exam_status_events` (`registrationId`)",
        "CREATE TABLE IF NOT EXISTS `exam_training_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `examType` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `turnCount` INTEGER NOT NULL, `aiCalls` INTEGER NOT NULL, " +
            "`summaryJson` TEXT)",
        "ALTER TABLE attempts ADD COLUMN trainingSessionId INTEGER",
        "INSERT INTO exam_registrations (examType, dateLocal, datePrecision, startTimeLocal, endTimeLocal, zoneId, location, status, " +
            "registrationOpenAt, registrationCloseAt, resultGrade, resultPublishedAt, waiverUsed, memo, sourceType, verifiedAt, needsReview, " +
            "legacyExamId, createdAt, updatedAt) " +
            "SELECT 'UNKNOWN', date(epochDay * 86400, 'unixepoch'), 'DAY', NULL, NULL, 'Asia/Seoul', '', " +
            "CASE WHEN resultGrade IS NOT NULL OR debriefAt IS NOT NULL THEN 'COMPLETED' ELSE 'PLANNED' END, " +
            "NULL, NULL, resultGrade, NULL, NULL, " +
            "'이전 버전 시험 기록에서 옮김. 시험 유형을 확인하세요.', 'MIGRATED', NULL, 1, id, " +
            "CAST(strftime('%s','now') AS INTEGER) * 1000, CAST(strftime('%s','now') AS INTEGER) * 1000 FROM exams",
    )

    /** v4: script glossary lookups. Adds one table only. */
    val V3_TO_V4 = listOf(
        "CREATE TABLE IF NOT EXISTS `word_lookups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `phrase` TEXT NOT NULL, " +
            "`scriptId` INTEGER NOT NULL, `surface` TEXT NOT NULL, `sentence` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
            "`firstAt` INTEGER NOT NULL, `lastAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_word_lookups_phrase_scriptId` ON `word_lookups` (`phrase`, `scriptId`)",
    )

    /** v5: reusable sentence frames. Adds one table only; no existing row is changed. */
    val V4_TO_V5 = listOf(
        "CREATE TABLE IF NOT EXISTS `sentence_frames` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pattern` TEXT NOT NULL, " +
            "`hintKo` TEXT NOT NULL, `example` TEXT, `sourceScriptId` INTEGER, `origin` TEXT NOT NULL, `status` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sentence_frames_pattern` ON `sentence_frames` (`pattern`)",
    )

    /**
     * v6: sentence/paragraph-aligned English-Korean segments for the script player. Adds one table.
     * It also (re)creates `sentence_frames` if it is missing: a development build of the player branch once shipped DB v5 with
     * the segments table instead of the frames table, so a device can sit at v5 without it. Both statements are IF NOT EXISTS,
     * so a normal v5 database is untouched.
     */
    val V5_TO_V6 = V4_TO_V5 + listOf(
        "CREATE TABLE IF NOT EXISTS `script_segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scriptId` INTEGER NOT NULL, " +
            "`version` TEXT NOT NULL, `sourceHash` TEXT NOT NULL, `kind` TEXT NOT NULL, `idx` INTEGER NOT NULL, `en` TEXT NOT NULL, " +
            "`ko` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_script_segments_scriptId` ON `script_segments` (`scriptId`)",
    )

    val V6_TO_V7 = listOf("ALTER TABLE `attempts` ADD COLUMN `speakingReviewDueEpochDay` INTEGER")
}
