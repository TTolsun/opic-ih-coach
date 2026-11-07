package com.opicih.coach.learning

import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.prefs.Settings
import java.time.LocalDate

/** Everything the screens need about retake limits, computed from settings + registrations. */
data class RetakeSummary(
    val opicPolicy: ExamPolicy,
    val sailPolicy: ExamPolicy,
    val opicLast: LocalDate?,
    val sailLast: LocalDate?,
    val opicWaiver: WaiverState,
    val opicPaths: List<RetakePath>,
    /** SAIL earliest retake under the assumed rule; null before the first SAIL sitting. */
    val sailEarliest: LocalDate?,
    val latestUseful: LocalDate?,
) {
    val waiverAvailable: Boolean get() = (opicWaiver.available ?: 0) > 0
}

object RetakeSummaries {
    fun policies(s: Settings): Map<ExamType, ExamPolicy> = mapOf(
        ExamType.OPIC to ExamPolicy.opic(),
        ExamType.SAIL to ExamPolicy.sail(s.retakeDays, s.retakeVerified, s.sailSharesOpicLimit),
    )

    fun of(s: Settings, regs: List<ExamRegistrationEntity>, today: LocalDate): RetakeSummary {
        val p = policies(s)
        val opic = p.getValue(ExamType.OPIC)
        val sail = p.getValue(ExamType.SAIL)
        val opicLast = RetakeEngine.lastSitting(regs, opic, p)
        val sailLast = RetakeEngine.lastSitting(regs, sail, p)
        val waiver = WaiverLedger.state(s.opicWaivers, s.opicWaiverDeclaredOn, regs, opic, today)
        val latestUseful = s.gradeDeadline?.minusDays(s.resultLagDays.toLong())
        return RetakeSummary(
            opic, sail, opicLast, sailLast, waiver,
            RetakeEngine.paths(opic, opicLast, waiver, today, latestUseful),
            sailLast?.let { sail.earliestWithoutWaiver(it) },
            latestUseful,
        )
    }
}
