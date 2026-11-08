package com.opicih.coach

import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.DatePrecision
import com.opicih.coach.data.db.ErrorPatternEntity
import com.opicih.coach.data.db.ExamEntity
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamStatusEventEntity
import com.opicih.coach.data.db.ExamTrainingSessionEntity
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptRevisionEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationSource
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.learning.ExamBootstrap
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.Focus
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private val KST = ZoneId.of("Asia/Seoul")
private fun kst(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, KST)

/** The user's real data as of 2026-11-07, with ids as if stored. */
private fun userRegs(): List<ExamRegistrationEntity> =
    ExamBootstrap.userConfirmed(0).mapIndexed { i, s -> s.registration.copy(id = i + 1L) }

private fun reg(
    id: Long, type: ExamType, date: String, status: RegistrationStatus, start: String? = null, end: String? = null,
    precision: DatePrecision = DatePrecision.DAY,
) = ExamRegistrationEntity(id = id, examType = type.name, dateLocal = date, datePrecision = precision.name, startTimeLocal = start, endTimeLocal = end, status = status.name)

class ExamScheduleTest {

    @Test fun nearestExamOnNov7IsSail() {
        val now = kst(2026, 11, 7)
        val f = ExamSchedule.focus(userRegs(), now)!!
        assertEquals(ExamType.SAIL, f.type)
        assertEquals(LocalDate.of(2026, 11, 11), f.date)
        assertEquals("D-4", PlanGenerator.dLabel(now.toLocalDate(), f.date!!))
        assertEquals("2026.11.11 (수) 19:20", ExamSchedule.formatWhen(f))
    }

    @Test fun cancelledExamIsNeverFocusOrSitting() {
        val regs = userRegs()
        val cancelled = regs.first { it.dateLocal == "2026-11-18" }
        assertEquals(RegistrationStatus.CANCELLED, cancelled.state)
        // On 11/12 (after SAIL) the cancelled 11/18 slot must not become the next exam.
        assertNull(ExamSchedule.focus(regs, kst(2026, 11, 12)))
        assertNull(ExamSchedule.lastCompleted(regs, ExamType.SAIL))
    }

    @Test fun examDaySwitchesAfterEndTime() {
        val regs = userRegs()
        assertEquals(ExamType.SAIL, ExamSchedule.focus(regs, kst(2026, 11, 11, 20, 19))?.type)
        assertNull(ExamSchedule.focus(regs, kst(2026, 11, 11, 20, 21)))
        // Passed but not confirmed: the app asks instead of assuming the user sat it.
        assertEquals(1, ExamSchedule.awaitingConfirmation(regs, kst(2026, 11, 11, 20, 21)).size)
        assertTrue(ExamSchedule.awaitingConfirmation(regs, kst(2026, 11, 11, 20, 19)).isEmpty())
    }

    @Test fun focusMovesToNextRegisteredExamAfterSail() {
        val regs = userRegs() + reg(10, ExamType.OPIC, "2026-11-29", RegistrationStatus.REGISTERED, "10:00")
        assertEquals(ExamType.SAIL, ExamSchedule.focus(regs, kst(2026, 11, 7))?.type)
        assertEquals(ExamType.OPIC, ExamSchedule.next(regs, kst(2026, 11, 7))?.type)
        assertEquals(ExamType.OPIC, ExamSchedule.focus(regs, kst(2026, 11, 12))?.type)
    }

    @Test fun plannedExamIsNotABooking() {
        val regs = listOf(reg(1, ExamType.OPIC, "2026-11-20", RegistrationStatus.PLANNED))
        assertNull(ExamSchedule.focus(regs, kst(2026, 11, 7)))
        assertEquals(1L, ExamSchedule.nextOfType(regs, ExamType.OPIC, kst(2026, 11, 7))?.id)
    }

    @Test fun twoExamsOnTheSameDay() {
        val regs = listOf(
            reg(1, ExamType.SAIL, "2026-11-11", RegistrationStatus.REGISTERED, "19:20", "20:20"),
            reg(2, ExamType.OPIC, "2026-11-11", RegistrationStatus.REGISTERED, "10:00"),
        )
        assertEquals(ExamType.OPIC, ExamSchedule.focus(regs, kst(2026, 11, 11, 9))?.type)
        // OPIc without end time is assumed to take 1 hour.
        assertEquals(ExamType.SAIL, ExamSchedule.focus(regs, kst(2026, 11, 11, 11, 1))?.type)
    }

    @Test fun noRegistrations() {
        assertNull(ExamSchedule.focus(emptyList(), kst(2026, 11, 7)))
        val p = PlanGenerator.forExam(LocalDate.of(2026, 11, 7), null, null, 30, 2, false)
        assertEquals("시험 미정", p.dLabel)
        assertNull(p.examType)
    }

    @Test fun monthOnlyPastExamUsesLastDayConservatively() {
        val last = ExamSchedule.lastCompleted(userRegs(), ExamType.OPIC)!!
        assertEquals(LocalDate.of(2026, 2, 28), last.date)
        assertFalse(last.exact)
        assertEquals("2026.02 (일자 미입력)", ExamSchedule.formatWhen(last.registration))
        // OPIc and SAIL histories stay separate.
        assertNull(ExamSchedule.lastCompleted(userRegs(), ExamType.SAIL))
    }

    @Test fun timeZoneOfThePhoneDoesNotShiftTheExam() {
        val regs = userRegs()
        // 11:21 UTC = 20:21 KST: the exam is over even if the phone clock is in UTC.
        val utc = ZonedDateTime.of(2026, 11, 11, 11, 21, 0, 0, ZoneId.of("UTC"))
        assertNull(ExamSchedule.focus(regs, utc))
        val utcBefore = ZonedDateTime.of(2026, 11, 11, 11, 19, 0, 0, ZoneId.of("UTC"))
        assertEquals(ExamType.SAIL, ExamSchedule.focus(regs, utcBefore)?.type)
    }

    @Test fun yearEndBoundary() {
        val regs = listOf(reg(1, ExamType.OPIC, "2027-01-02", RegistrationStatus.REGISTERED, "10:00"))
        val f = ExamSchedule.focus(regs, kst(2026, 12, 30))!!
        assertEquals("D-3", PlanGenerator.dLabel(LocalDate.of(2026, 12, 30), f.date!!))
        val monthOnly = reg(2, ExamType.OPIC, "2026-12", RegistrationStatus.COMPLETED, precision = DatePrecision.MONTH)
        assertEquals(LocalDate.of(2026, 12, 31), monthOnly.latestDate)
    }

    @Test fun examDateChangeMovesTheFocus() {
        val before = reg(1, ExamType.OPIC, "2026-11-20", RegistrationStatus.REGISTERED)
        val after = before.copy(dateLocal = "2026-11-27")
        assertEquals(LocalDate.of(2026, 11, 20), ExamSchedule.focus(listOf(before), kst(2026, 11, 7))?.date)
        assertEquals(LocalDate.of(2026, 11, 27), ExamSchedule.focus(listOf(after), kst(2026, 11, 7))?.date)
    }

    @Test fun cancelThenReRegister() {
        val regs = listOf(
            reg(1, ExamType.SAIL, "2026-11-18", RegistrationStatus.CANCELLED),
            reg(2, ExamType.SAIL, "2026-11-25", RegistrationStatus.REGISTERED, "19:20"),
        )
        assertEquals(2L, ExamSchedule.focus(regs, kst(2026, 11, 12))?.id)
    }
}

class ExamBootstrapTest {

    @Test fun freshInstallGetsUserFacts() {
        val plan = ExamBootstrap.plan(emptyList(), legacyExamDate = LocalDate.of(2026, 11, 11), now = 0)
        assertEquals(3, plan.inserts.size)
        // The old setting was 11/11 = the SAIL booking, so no duplicate review row.
        assertTrue(plan.inserts.none { it.registration.type == ExamType.UNKNOWN })
        val cancelled = plan.inserts.first { it.registration.dateLocal == "2026-11-18" }
        assertEquals(listOf(RegistrationStatus.REGISTERED, RegistrationStatus.CANCELLED), cancelled.history.map { it.to })
        assertEquals("2026-11-03", cancelled.history.last().effectiveDate)
        val opic = plan.inserts.first { it.registration.type == ExamType.OPIC }
        assertEquals(DatePrecision.MONTH, opic.registration.precision)
        assertEquals(RegistrationSource.EMAIL_IMPORT, RegistrationSource.of(plan.inserts.first().registration.sourceType))
    }

    @Test fun legacyDateOnAnotherDayBecomesReviewPlan() {
        val plan = ExamBootstrap.plan(emptyList(), legacyExamDate = LocalDate.of(2026, 11, 14), now = 0)
        val legacy = plan.inserts.single { it.registration.type == ExamType.UNKNOWN }
        assertEquals(RegistrationStatus.PLANNED, legacy.registration.state)
        assertTrue(legacy.registration.needsReview)
    }

    @Test fun neverOverwritesExistingRows() {
        val edited = reg(5, ExamType.SAIL, "2026-11-11", RegistrationStatus.COMPLETED, "19:20")
        val plan = ExamBootstrap.plan(listOf(edited), legacyExamDate = null, now = 0)
        assertTrue(plan.inserts.none { it.registration.type == ExamType.SAIL && it.registration.dateLocal == "2026-11-11" })
        assertTrue(plan.absorbIds.isEmpty())
    }

    @Test fun runningTwiceAddsNothing() {
        val first = ExamBootstrap.plan(emptyList(), LocalDate.of(2026, 11, 14), 0)
        val stored = first.inserts.mapIndexed { i, s -> s.registration.copy(id = i + 1L) }
        val second = ExamBootstrap.plan(stored, LocalDate.of(2026, 11, 14), 0)
        assertTrue(second.inserts.isEmpty())
    }

    @Test fun migratedUnknownRowOnSameDayIsAbsorbed() {
        val migrated = ExamRegistrationEntity(id = 7, examType = ExamType.UNKNOWN.name, dateLocal = "2026-11-11", status = RegistrationStatus.PLANNED.name,
            sourceType = RegistrationSource.MIGRATED.name, needsReview = true, legacyExamId = 3)
        val plan = ExamBootstrap.plan(listOf(migrated), null, 0)
        assertEquals(listOf(7L), plan.absorbIds)
        assertEquals(3L, plan.inserts.first { it.registration.dateLocal == "2026-11-11" }.registration.legacyExamId)
    }
}

class ExamPlanTest {
    private val sail = LocalDate.of(2026, 11, 11)
    private fun plan(today: LocalDate, minutes: Int = 30) = PlanGenerator.forExam(today, ExamType.SAIL, sail, minutes, 3, false)

    @Test fun sailCountdown() {
        assertEquals(Focus.SAIL_QUICK, plan(LocalDate.of(2026, 11, 7)).focus)       // D-4
        assertEquals(Focus.SAIL_MOCK, plan(LocalDate.of(2026, 11, 8)).focus)        // D-3
        assertEquals(Focus.SAIL_MOCK, plan(LocalDate.of(2026, 11, 9)).focus)        // D-2
        assertEquals(Focus.SAIL_LIGHT, plan(LocalDate.of(2026, 11, 10)).focus)      // D-1
        assertEquals(Focus.SAIL_WARMUP, plan(LocalDate.of(2026, 11, 11)).focus)     // D-Day
        assertEquals(Focus.SAIL_FOLLOWUP, plan(LocalDate.of(2026, 11, 6)).focus)    // D-5
        assertTrue(plan(LocalDate.of(2026, 10, 20)).focus in setOf(Focus.CORE, Focus.FREE)) // D-22: shared basics
    }

    @Test fun thirtyMinuteSailDayMatchesTheExample() {
        val p = plan(LocalDate.of(2026, 11, 7))
        assertEquals(30, p.totalMinutes)
        assertEquals(listOf(5, 5, 10, 5, 5), p.tasks.map { it.minutes })
        assertEquals(TaskType.MORNING, p.tasks[0].type)
        assertEquals("KEYWORD", p.tasks[1].mode)
        assertEquals("SAIL_CONVERSATION", p.tasks[2].mode)
        assertTrue(p.tasks.drop(3).all { it.mode == "FREE" })
    }

    @Test fun lightDayAndWarmupStayShort() {
        assertTrue(plan(LocalDate.of(2026, 11, 10), 60).totalMinutes <= 15)
        assertTrue(plan(LocalDate.of(2026, 11, 11), 60).totalMinutes <= 10)
    }

    @Test fun skippedDayDoesNotPileUp() {
        // The user skipped 11/8: the 11/9 plan is the normal D-2 plan, not D-3 + D-2 together.
        val p = plan(LocalDate.of(2026, 11, 9))
        assertEquals(Focus.SAIL_MOCK, p.focus)
        assertEquals(30, p.totalMinutes)
    }

    @Test fun opicExamKeepsTheOriginalSprint() {
        val exam = LocalDate.of(2026, 11, 29)
        val p = PlanGenerator.forExam(LocalDate.of(2026, 11, 22), ExamType.OPIC, exam, 30, 3, false)
        assertEquals(PlanGenerator.today(LocalDate.of(2026, 11, 22), exam, 30, 3, false).focus, p.focus)
        assertEquals(ExamType.OPIC, p.examType)
    }

    @Test fun scalesWithStudyTime() {
        assertEquals(10, plan(LocalDate.of(2026, 11, 7), 10).totalMinutes)
        assertEquals(60, plan(LocalDate.of(2026, 11, 7), 60).totalMinutes)
    }
}

class BackupCompatTest {
    @Test fun readsVersion1File() {
        val v1 = """{"version":1,"exportedAt":1,"scripts":[],"revisions":[],"attempts":[{"scriptId":null,"topic":"T","questionText":"Q","questionType":"OTHER",
            "mode":"FREE","transcript":"x","captureMode":"LIVE_STT","durationMs":1,"speechMs":1,"longPauseCount":0,"wordCount":1,"wpm":1,"coverage":null,
            "score":50,"metricsJson":"{}","feedbackJson":"{}","feedbackSource":"LOCAL","createdAt":1}],"errors":[],"chunks":[],"logs":[],
            "exams":[{"epochDay":20504,"type":"FIRST","resultGrade":"IM2","note":""}]}"""
        val snap = BackupManager.decode(v1)
        assertEquals(1, snap.version)
        assertEquals(1, snap.attempts.size)
        assertNull(snap.attempts[0].trainingSessionId)
        assertEquals(0L, snap.exams[0].id)
        // A v1 file has no schedule, so restoring it keeps the current schedule.
        assertFalse(BackupManager.restoresSchedule(snap))
    }

    @Test fun version2RoundTrip() {
        val r = BackupManager.BRegistration(1, "SAIL", "2026-11-11", "DAY", "19:20", "20:20", "Asia/Seoul", "동탄", "REGISTERED",
            null, null, null, null, null, "memo", "EMAIL_IMPORT", 1L, false, null, 1, 1)
        val snap = BackupManager.Snapshot(registrations = listOf(r), statusEvents = listOf(BackupManager.BStatusEvent(1, null, "REGISTERED", null, "메일", 1)))
        val back = BackupManager.decode(BackupManager.encode(snap))
        assertEquals(BackupManager.CURRENT_VERSION, back.version)
        assertEquals(r, back.registrations.single())
        assertEquals(1, back.statusEvents.size)
        assertTrue(BackupManager.restoresSchedule(back))
    }

    @Test fun version2FileUsesEntityDefaults() {
        val v2 = """{"version":2,"scripts":[{"id":7,"topic":"T","title":"t","question":"q","questionType":"OTHER","original":"o",
            "natural":null,"ihTarget":null,"shortVersion":null,"keywords":null,"activeVersion":"ORIGINAL","koreanMeaning":null,
            "source":"USER_IMPORT","masteryState":"NEW","masteryScore":0,"srsStep":0,"dueEpochDay":0,"storyIds":null}]}"""
        val snap = BackupManager.decode(v2)
        assertEquals(60, snap.scripts.single().estSeconds)
        assertNull(snap.scripts.single().linkedGroupId)
    }
}

/** Guards against a new entity column silently dropping out of backups (the v2 format lost several). */
class BackupCoverageTest {
    private fun fields(c: Class<*>) = c.declaredFields
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .map { it.name }.toSet()

    @Test fun everyEntityColumnIsMirroredOrExplicitlyExcluded() {
        val pairs = listOf(
            ScriptEntity::class.java to BackupManager.BScript::class.java,
            ScriptRevisionEntity::class.java to BackupManager.BRevision::class.java,
            PracticeAttemptEntity::class.java to BackupManager.BAttempt::class.java,
            ErrorPatternEntity::class.java to BackupManager.BError::class.java,
            ChunkEntity::class.java to BackupManager.BChunk::class.java,
            DailyLogEntity::class.java to BackupManager.BLog::class.java,
            MockTestEntity::class.java to BackupManager.BMock::class.java,
            ExamEntity::class.java to BackupManager.BExam::class.java,
            ExamRegistrationEntity::class.java to BackupManager.BRegistration::class.java,
            ExamStatusEventEntity::class.java to BackupManager.BStatusEvent::class.java,
            ExamTrainingSessionEntity::class.java to BackupManager.BTrainingSession::class.java,
        )
        pairs.forEach { (entity, mirror) ->
            val missing = fields(entity) - fields(mirror) - BackupManager.NOT_BACKED_UP[entity].orEmpty()
            assertTrue("${entity.simpleName} columns not in backup: $missing", missing.isEmpty())
        }
    }

    @Test fun roundTripKeepsColumnsThatV2Dropped() = with(BackupManager.Companion) {
        val script = ScriptEntity(id = 3, topic = "T", title = "t", question = "q", original = "o", difficulty = 5, estSeconds = 95,
            lastReviewedAt = 11, linkedGroupId = "G1", createdAt = 1, updatedAt = 2)
        assertEquals(script, script.toBackup().toEntity())
        val chunk = ChunkEntity(phrase = "p", meaningKo = "m", example = "e", category = "c", lastReviewedEpochDay = 9, source = "USER_IMPORT")
        assertEquals(chunk, chunk.toBackup().toEntity())
        val attempt = PracticeAttemptEntity(topic = "T", questionText = "Q", mode = "MOCK", mockId = 4, trainingSessionId = 2, createdAt = 1)
        assertEquals(attempt, attempt.toBackup().toEntity())
        val mock = MockTestEntity(id = 4, startedAt = 1, finishedAt = 2, questionCount = 12, selfLevel = 5, reportJson = "{}")
        assertEquals(mock, mock.toBackup().toEntity())
    }

    /** Distinct values per column so a swapped positional argument in toBackup() fails the test. */
    @Test fun roundTripKeepsEveryColumnInPlace() = with(BackupManager.Companion) {
        val attempt = PracticeAttemptEntity(scriptId = 1, topic = "a", questionText = "b", questionType = "c", mode = "d", transcript = "e",
            captureMode = "f", durationMs = 2, speechMs = 3, longPauseCount = 4, wordCount = 5, wpm = 6, coverage = 0.7f, score = 8,
            metricsJson = "g", feedbackJson = "h", feedbackSource = "i", mockId = 9, createdAt = 10, trainingSessionId = 11)
        assertEquals(attempt, attempt.toBackup().toEntity())
        val script = ScriptEntity(id = 1, topic = "a", title = "b", question = "c", questionType = "d", original = "e", natural = "f",
            ihTarget = "g", shortVersion = "h", keywords = "i", activeVersion = "j", koreanMeaning = "k", difficulty = 2, estSeconds = 3,
            source = "l", masteryState = "m", masteryScore = 4, srsStep = 5, dueEpochDay = 6, lastReviewedAt = 7, linkedGroupId = "n",
            storyIds = "o", createdAt = 8, updatedAt = 9)
        assertEquals(script, script.toBackup().toEntity())
        val revision = ScriptRevisionEntity(scriptId = 1, version = "a", text = "b", reasonKo = "c", origin = "d", status = "e", createdAt = 2)
        assertEquals(revision, revision.toBackup().toEntity())
        val error = ErrorPatternEntity(key = "a", category = "b", example = "c", correction = "d", count = 2, lastSeen = 3)
        assertEquals(error, error.toBackup().toEntity())
        val chunk = ChunkEntity(phrase = "a", meaningKo = "b", example = "c", category = "d", srsStep = 2, dueEpochDay = 3, lastResult = "e",
            lastReviewedEpochDay = 4, failCount = 5, source = "f")
        assertEquals(chunk, chunk.toBackup().toEntity())
        val log = DailyLogEntity(1, 2, 3, 4, 5, mockDone = true, morningDone = false)
        assertEquals(log, log.toBackup().toEntity())
        val exam = ExamEntity(id = 1, epochDay = 2, type = "a", resultGrade = "b", note = "c", topicsSeen = "d", stuckPoints = "e",
            feeling = 3, selfLevelUsed = 4, debriefAt = 5)
        assertEquals(exam, exam.toBackup().toEntity())
        val reg = ExamRegistrationEntity(id = 1, examType = "a", dateLocal = "b", datePrecision = "c", startTimeLocal = "d", endTimeLocal = "e",
            zoneId = "f", location = "g", status = "h", registrationOpenAt = "i", registrationCloseAt = "j", resultGrade = "k",
            resultPublishedAt = "l", waiverUsed = true, memo = "m", sourceType = "n", verifiedAt = 2, needsReview = true, legacyExamId = 3,
            createdAt = 4, updatedAt = 5)
        assertEquals(reg, reg.toBackup().toEntity())
        val event = ExamStatusEventEntity(registrationId = 1, fromStatus = "a", toStatus = "b", effectiveDate = "c", note = "d", createdAt = 2)
        assertEquals(event, event.toBackup().toEntity())
        val session = ExamTrainingSessionEntity(id = 1, examType = "a", kind = "b", startedAt = 2, finishedAt = 3, turnCount = 4, aiCalls = 5,
            summaryJson = "c")
        assertEquals(session, session.toBackup().toEntity())
    }
}
