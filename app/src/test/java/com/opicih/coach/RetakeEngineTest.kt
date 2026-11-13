package com.opicih.coach

import com.opicih.coach.data.db.DatePrecision
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.ExamPolicy
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.RetakeEngine
import com.opicih.coach.learning.RetakeSummaries
import com.opicih.coach.learning.Verification
import com.opicih.coach.learning.WaiverLedger
import com.opicih.coach.learning.WaiverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
private fun reg(type: ExamType, date: String, status: RegistrationStatus, waiver: Boolean? = null, precision: DatePrecision = DatePrecision.DAY) =
    ExamRegistrationEntity(examType = type.name, dateLocal = date, datePrecision = precision.name, status = status.name, waiverUsed = waiver)

class OfficialRetakeBoundaryTest {
    private val opic = ExamPolicy.opic()

    /** The example printed in OPIc 시험관리규정 제5조: 6/1 exam, 6/2..6/26 blocked, 6/27 allowed. */
    @Test fun officialExample() {
        assertEquals(d(6, 27), opic.earliestWithoutWaiver(d(6, 1)))
        assertEquals(Verification.OFFICIAL, opic.verification)
    }

    @Test fun boundariesAcrossMonthsAndYears() {
        assertEquals(d(11, 27), opic.earliestWithoutWaiver(d(11, 1)))
        assertEquals(d(12, 7), opic.earliestWithoutWaiver(d(11, 11)))
        assertEquals(d(1, 26, 2027), opic.earliestWithoutWaiver(d(12, 31)))   // year change
        assertEquals(d(3, 26), opic.earliestWithoutWaiver(d(2, 28)))          // 2026 is not a leap year
        assertEquals(d(3, 25, 2028), opic.earliestWithoutWaiver(d(2, 28, 2028))) // leap year: 2/29 exists
    }

    @Test fun waiverAllowsTheNextDayOnly() {
        assertEquals(d(11, 12), opic.earliestWithWaiver(d(11, 11)))
    }
}

class WaiverLedgerTest {
    private val opic = ExamPolicy.opic()
    private val declaredOn = d(11, 7)

    @Test fun declaredBalanceWithoutUse() {
        val w = WaiverLedger.state(1, declaredOn, emptyList(), opic, d(11, 8))
        assertEquals(1, w.available)
        assertNull(w.lastUsed)
    }

    @Test fun usingTheWaiverConsumesItUntilRegrant() {
        val regs = listOf(reg(ExamType.OPIC, "2026-11-20", RegistrationStatus.COMPLETED, waiver = true))
        val w = WaiverLedger.state(1, declaredOn, regs, opic, d(11, 21))
        assertEquals(0, w.available)
        assertEquals(d(11, 20), w.lastUsed)
        assertEquals(d(4, 19, 2027), w.regrantOn) // 11/20 + 150 days
        assertEquals(1, WaiverLedger.state(1, declaredOn, regs, opic, d(4, 19, 2027)).available)
    }

    @Test fun cancelledOrNoShowUseIsCountedConservatively() {
        val regs = listOf(reg(ExamType.OPIC, "2026-11-20", RegistrationStatus.NO_SHOW, waiver = true))
        val w = WaiverLedger.state(1, declaredOn, regs, opic, d(11, 21))
        assertEquals(0, w.available)
        assertTrue(w.notes.any { it.contains("복구 여부는 규정에 없어") })
    }

    @Test fun usesBeforeTheDeclarationAreAlreadyCounted() {
        val regs = listOf(reg(ExamType.OPIC, "2026-10-01", RegistrationStatus.COMPLETED, waiver = true))
        assertEquals(1, WaiverLedger.state(1, declaredOn, regs, opic, d(11, 8)).available)
    }

    @Test fun sailUseDoesNotChangeOpicBalanceAndHasNoKnownRegrant() {
        val settings = Settings(opicWaivers = 1, sailWaivers = 1, sailWaiverDeclaredOn = declaredOn)
        val regs = listOf(
            reg(ExamType.SAIL, "2026-11-20", RegistrationStatus.COMPLETED, waiver = true),
            reg(ExamType.OPIC, "2026-11-24", RegistrationStatus.PLANNED, waiver = true).copy(waiverPlanned = true),
        )
        val summary = RetakeSummaries.of(settings, regs, d(11, 25))
        assertEquals(1, summary.opicWaiver.available)
        assertEquals(0, summary.sailWaiver.available)
        assertNull(summary.sailWaiver.regrantOn)
    }

    @Test fun notEnteredBalance() {
        assertNull(WaiverLedger.state(-1, declaredOn, emptyList(), opic, d(11, 8)).available)
    }
}

class RetakePathTest {
    private val opic = ExamPolicy.opic()
    private val latestUseful = d(12, 21) // 12/31 deadline - 10 days result lag (assumption)
    private val oneWaiver = WaiverState(1, null, null, emptyList())

    @Test fun februaryOpicLeavesTwoChancesWithoutWaiver() {
        // Last OPIc: 2026-02 (month only, last day used). Today 11/8: the limit is long over.
        val p = RetakeEngine.paths(opic, d(2, 28), oneWaiver, d(11, 8), latestUseful)
        assertEquals(1, p.size) // waiver is pointless when the limit has already passed
        assertEquals(d(11, 9), p[0].earliest)
        assertEquals(listOf(d(11, 9), d(12, 5)), p[0].eligibleDates) // 11/9 + 26 = 12/5, + 26 = 12/31 > 12/21
        assertEquals(2, p[0].opportunities)
        assertTrue(p[0].assumptions.any { it.contains("실제 시험 회차") })
    }

    @Test fun waiverAddsAChanceAfterARecentSitting() {
        // OPIc sat on 11/20, today 11/21.
        val p = RetakeEngine.paths(opic, d(11, 20), oneWaiver, d(11, 21), latestUseful)
        val without = p.first { !it.useWaiver }
        val with = p.first { it.useWaiver }
        assertEquals(d(12, 16), without.earliest)        // 11/20 + 26
        assertEquals(1, without.opportunities)
        assertEquals(d(11, 22), with.earliest)           // tomorrow
        assertEquals(listOf(d(11, 22), d(12, 18)), with.eligibleDates)
        assertEquals(2, with.opportunities)
    }

    @Test fun noWaiverPathWhenNoneAvailable() {
        val p = RetakeEngine.paths(opic, d(11, 20), WaiverState(0, null, null, emptyList()), d(11, 21), latestUseful)
        assertFalse(p.any { it.useWaiver })
    }

    @Test fun noChanceLeftBeforeTheDeadline() {
        val p = RetakeEngine.paths(opic, d(12, 10), WaiverState(0, null, null, emptyList()), d(12, 11), latestUseful)
        assertNull(p[0].earliest)
        assertEquals(0, p[0].opportunities)
    }

    @Test fun opicAndSailAreIndependentByDefault() {
        val sail = ExamPolicy.sail(25, userConfirmed = true)
        val regs = listOf(
            reg(ExamType.OPIC, "2026-02", RegistrationStatus.COMPLETED, precision = DatePrecision.MONTH),
            reg(ExamType.SAIL, "2026-11-11", RegistrationStatus.COMPLETED),
            reg(ExamType.SAIL, "2026-11-18", RegistrationStatus.CANCELLED),
        )
        val summary = RetakeSummaries.of(Settings(retakeVerified = true), regs, d(11, 13))
        assertEquals(d(2, 28), summary.opicLast)
        assertEquals(d(12, 7), summary.sailEarliest)
        assertEquals(d(12, 7), sail.earliestWithoutWaiver(d(11, 11)))
        assertEquals(Verification.USER_CONFIRMED, sail.verification)
    }

    @Test fun cancelledAndPlannedNeverLimit() {
        val regs = listOf(
            reg(ExamType.OPIC, "2026-11-20", RegistrationStatus.CANCELLED),
            reg(ExamType.OPIC, "2026-11-25", RegistrationStatus.PLANNED),
            reg(ExamType.OPIC, "2026-11-26", RegistrationStatus.REGISTERED),
        )
        assertNull(ExamSchedule.lastCompleted(regs, ExamType.OPIC))
    }
}
