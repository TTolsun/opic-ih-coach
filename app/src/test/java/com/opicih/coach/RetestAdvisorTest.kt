package com.opicih.coach

import com.opicih.coach.learning.ExamPolicy
import com.opicih.coach.learning.Verification
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.RetestAdvisor
import com.opicih.coach.learning.RetestAdvisor.Readiness
import com.opicih.coach.learning.RetestAdvisor.Rules
import com.opicih.coach.learning.RetestAdvisor.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RetestAdvisorTest {
    private val exam = LocalDate.of(2026, 11, 11)
    private val rules = Rules(policy = ExamPolicy.opic().copy(verification = Verification.UNVERIFIED), resultLagDays = 10, deadline = LocalDate.of(2026, 12, 31))
    private val notReady = Readiness(2, 30, 60, 2.5f, 45, 0, 0, 4)
    private val ready = Readiness(12, 200, 110, 0.5f, 72, 1, 66, 10)

    @Test fun beforeFirstExamGivesProvisionalWindow() {
        val a = RetestAdvisor.advise(LocalDate.of(2026, 11, 7), exam, rules, notReady)
        assertEquals(Verdict.WAIT_FIRST_EXAM, a.verdict)
        // Official counting: 11/11 = D+0, 11/12..12/6 = D+1..D+25 blocked, 12/7 = D+26 first allowed day.
        assertEquals(LocalDate.of(2026, 12, 7), a.earliest)
        assertEquals(LocalDate.of(2026, 12, 21), a.latestUseful)
        assertEquals(LocalDate.of(2026, 12, 7), a.recommended)
        assertEquals(LocalDate.of(2026, 11, 30), a.registerBy)
        // 12/7 + 26 = 1/2 > 12/21: only one retake fits before the deadline.
        assertFalse(a.secondChance)
        assertTrue(a.cautions.any { it.contains("확인되지 않은") })
    }

    @Test fun readyLearnerGoesAtEarliestDate() {
        val a = RetestAdvisor.advise(LocalDate.of(2026, 11, 25), exam, rules.copy(policy = ExamPolicy.opic()), ready)
        assertEquals(Verdict.REGISTER_EARLIEST, a.verdict)
        assertEquals(LocalDate.of(2026, 12, 7), a.recommended)
        assertTrue(a.cautions.none { it.contains("확인되지 않은") })
    }

    @Test fun notReadyButDeadlineForcesRegistration() {
        val a = RetestAdvisor.advise(LocalDate.of(2026, 12, 3), exam, rules, notReady)
        // Volume gate needs 8 more days: 12/3 + 8 = 12/11, still before 12/21, so wait.
        assertEquals(Verdict.REGISTER_LATER, a.verdict)
        assertEquals(LocalDate.of(2026, 12, 11), a.recommended)
        val late = RetestAdvisor.advise(LocalDate.of(2026, 12, 15), exam, rules, notReady)
        assertEquals(Verdict.REGISTER_ANYWAY, late.verdict)
        assertEquals(LocalDate.of(2026, 12, 16), late.recommended)
    }

    @Test fun waiverMovesTheEarliestDateToTheNextDay() {
        val a = RetestAdvisor.advise(LocalDate.of(2026, 11, 12), exam, rules.copy(policy = ExamPolicy.opic(), useWaiver = true), ready)
        assertEquals(LocalDate.of(2026, 11, 12), a.earliest)
        assertEquals(Verdict.REGISTER_EARLIEST, a.verdict)
        assertEquals(LocalDate.of(2026, 11, 13), a.recommended) // tomorrow at the earliest
        assertTrue(a.secondChance) // 11/13 + 26 = 12/9 <= 12/21
        assertTrue(a.cautions.any { it.contains("Waiver") })
    }

    @Test fun noWindowWhenEarliestIsAfterDeadline() {
        val a = RetestAdvisor.advise(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), rules, ready)
        assertEquals(Verdict.NO_WINDOW, a.verdict)
        assertNull(a.recommended)
    }

    @Test fun inHouseSlotsArePicked() {
        val slots = listOf(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 9), LocalDate.of(2026, 12, 16))
        val a = RetestAdvisor.advise(LocalDate.of(2026, 11, 25), exam, rules.copy(slots = slots), ready)
        assertEquals(LocalDate.of(2026, 12, 9), a.recommended)
    }

    @Test fun readinessFromRecords() {
        val since = LocalDate.of(2026, 11, 11)
        val ms = 1_794_000_000_000L
        val attempts = List(6) { i ->
            PracticeAttemptEntity(topic = "Movies", questionText = "q$i", mode = if (i < 5) "MOCK" else "FREE", wordCount = 100, score = 70,
                longPauseCount = 1, mockId = if (i < 5) 1L else null, createdAt = ms + i)
        } + PracticeAttemptEntity(topic = "Old", questionText = "old", mode = "FREE", wordCount = 10, score = 10, createdAt = ms - 1)
        val logs = listOf(DailyLogEntity(since.toEpochDay() + 1, speakingMs = 20 * 60_000), DailyLogEntity(since.toEpochDay() - 3, speakingMs = 60 * 60_000))
        val r = ProgressCalculator.readiness(attempts, logs, since.toEpochDay(), ms)
        assertEquals(1, r.practiceDays)
        assertEquals(20, r.speakingMinutes)
        assertEquals(100, r.avgWords)
        assertEquals(1, r.mocksSinceExam)
        assertEquals(70, r.bestMockScore)
        assertEquals(6, r.recentAnswers)
    }
}
