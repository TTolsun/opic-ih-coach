package com.opicih.coach

import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.ExamBootstrap
import com.opicih.coach.learning.ExamResults
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.PlanEligibility
import com.opicih.coach.learning.RetakeSummaries
import com.opicih.coach.learning.YearEndExamUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class YearEndExamUpdateTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val settings = YearEndExamUpdate.updateSettings(Settings(examBootstrapVersion = 1))
    private val original = ExamBootstrap.userConfirmed().map { it.registration }

    @Test fun completionChangesTheExistingRegistrationWithoutInventingFacts() {
        val old = original.first().copy(id = 42, legacyExamId = 17, memo = "기존 메모")
        val updated = YearEndExamUpdate.completion(listOf(old) + original.drop(1))
        assertEquals(old.copy(status = RegistrationStatus.COMPLETED.name, resultGrade = "IL"), updated)
        assertNull(updated?.waiverUsed)
        assertNull(updated?.resultPublishedAt)
    }

    @Test fun yearEndPlanMeetsSeparateLimitsAndDoesNotSpendActualWaivers() {
        val completed = requireNotNull(YearEndExamUpdate.completion(original))
        val plans = YearEndExamUpdate.plans().mapIndexed { index, plan -> plan.copy(id = index + 10L) }
        val rows = listOf(completed) + original.drop(1) + plans
        assertEquals(listOf("2026-11-22", "2026-12-09", "2026-12-18", "2026-12-23", "2026-12-24"), plans.map { it.dateLocal })
        assertEquals(listOf(ExamType.OPIC, ExamType.SAIL, ExamType.OPIC, ExamType.SAIL, ExamType.OPIC), plans.map { it.type })
        assertEquals(listOf("2026-11-27", "2026-12-10", "2026-12-23", "2026-12-24", "2026-12-29"), plans.map { ExamResults.expectedOn(it).toString() })
        assertTrue(plans.all { it.state == RegistrationStatus.PLANNED && it.waiverUsed == null && it.resultGrade == null &&
            it.resultPublishedAt == null && it.startAt() == null && it.location.isEmpty() && it.registrationCloseAt == null })
        assertNull(ExamSchedule.focus(rows, today.atStartOfDay(ZoneId.of("Asia/Seoul"))))
        val retake = RetakeSummaries.of(settings, rows, today)
        assertEquals(LocalDate.of(2026, 12, 7), retake.sailEarliest)
        assertEquals(LocalDate.of(2026, 12, 26), retake.latestUseful)
        assertEquals(LocalDate.of(2026, 12, 30), ExamResults.latestSitting(ExamType.SAIL, settings.gradeDeadline))
        assertEquals(1, retake.opicWaiver.available)
        assertEquals(1, retake.sailWaiver.available)
        val eligibility = RetakeSummaries.plannedRetakes(settings, rows, today)
        assertEquals(listOf(PlanEligibility.REGULAR, PlanEligibility.REGULAR, PlanEligibility.REGULAR,
            PlanEligibility.WITH_WAIVER, PlanEligibility.WITH_WAIVER), plans.map { eligibility[it.id]?.eligibility })
        assertEquals(LocalDate.of(2026, 12, 18), eligibility[plans[2].id]?.regularFrom)
    }

    @Test fun unconfirmedEarlierBookingStillAffectsLaterPlanProjection() {
        val earlier = YearEndExamUpdate.plans().first().copy(id = 1, status = RegistrationStatus.REGISTERED.name)
        val later = earlier.copy(id = 2, dateLocal = "2026-11-26", status = RegistrationStatus.PLANNED.name, waiverPlanned = false)
        val rows = listOf(earlier, later)
        val plan = RetakeSummaries.plannedRetakes(settings, rows, LocalDate.of(2026, 11, 23))
        assertNull(ExamSchedule.lastCompleted(rows, ExamType.OPIC))
        assertEquals(PlanEligibility.WAIVER_REQUIRED, plan[later.id]?.eligibility)
    }
}
