package com.opicih.coach.learning

import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.Settings
import java.time.LocalDate

/** Everything the screens need about retake limits, computed from settings + registrations. */
data class RetakeSummary(
    val sailPolicy: ExamPolicy,
    val opicLast: LocalDate?,
    val opicWaiver: WaiverState,
    val sailWaiver: WaiverState,
    val opicPaths: List<RetakePath>,
    val sailEarliest: LocalDate?,
    val latestUseful: LocalDate?,
) {
    val waiverAvailable: Boolean get() = (opicWaiver.available ?: 0) > 0
}

object RetakeSummaries {
    private fun sailPolicy(s: Settings) = ExamPolicy.sail(s.retakeDays, s.retakeVerified)

    fun of(s: Settings, regs: List<ExamRegistrationEntity>, today: LocalDate): RetakeSummary {
        val opic = ExamPolicy.opic()
        val sail = sailPolicy(s)
        val opicLast = ExamSchedule.lastCompleted(regs, ExamType.OPIC)?.date
        val sailLast = ExamSchedule.lastCompleted(regs, ExamType.SAIL)?.date
        val waiver = WaiverLedger.state(s.opicWaivers, s.opicWaiverDeclaredOn, regs, opic, today)
        val latestUseful = ExamResults.latestSitting(ExamType.OPIC, s.gradeDeadline)
        return RetakeSummary(
            sail, opicLast, waiver, WaiverLedger.state(s.sailWaivers, s.sailWaiverDeclaredOn, regs, sail, today),
            RetakeEngine.paths(opic, opicLast, waiver, today, latestUseful),
            sailLast?.let { sail.earliestWithoutWaiver(it) },
            latestUseful,
        )
    }

    /** Estimates each upcoming plan in order, assuming the earlier eligible dates are taken. */
    fun plannedRetakes(s: Settings, regs: List<ExamRegistrationEntity>, today: LocalDate): Map<Long, PlannedRetake> = buildMap {
        for (policy in listOf(ExamPolicy.opic(), sailPolicy(s))) {
            val typed = regs.filter { it.type == policy.type && !it.needsReview }
            var last = ExamSchedule.lastCompleted(typed, policy.type)?.date
            val declared = if (policy.type == ExamType.OPIC) s.opicWaivers else s.sailWaivers
            val declaredOn = if (policy.type == ExamType.OPIC) s.opicWaiverDeclaredOn else s.sailWaiverDeclaredOn
            val projectedUses = mutableListOf<ExamRegistrationEntity>()
            for (r in typed.sortedBy { it.date }) {
                val date = r.date ?: continue
                if (r.state == RegistrationStatus.REGISTERED) {
                    if (last == null || date.isAfter(last)) last = date
                }
                if (r.state != RegistrationStatus.PLANNED || date.isBefore(today)) continue

                val regularFrom = last?.let(policy::earliestWithoutWaiver)
                val needsWaiver = regularFrom != null && date.isBefore(regularFrom)
                val available = WaiverLedger.state(declared, declaredOn, typed + projectedUses, policy, date).available
                val eligibility = when {
                    !needsWaiver -> PlanEligibility.REGULAR
                    last?.isBefore(date) != true -> PlanEligibility.SAME_DAY
                    r.waiverPlanned != true -> PlanEligibility.WAIVER_REQUIRED
                    available == null -> PlanEligibility.BALANCE_UNKNOWN
                    available == 0 -> PlanEligibility.NO_WAIVER_LEFT
                    else -> PlanEligibility.WITH_WAIVER
                }
                put(r.id, PlannedRetake(regularFrom, eligibility))
                if (eligibility == PlanEligibility.REGULAR || eligibility == PlanEligibility.WITH_WAIVER) last = date
                if (eligibility == PlanEligibility.WITH_WAIVER) {
                    projectedUses += r.copy(status = RegistrationStatus.REGISTERED.name, waiverUsed = true)
                }
            }
        }
    }
}

enum class PlanEligibility { REGULAR, WITH_WAIVER, SAME_DAY, WAIVER_REQUIRED, BALANCE_UNKNOWN, NO_WAIVER_LEFT }

data class PlannedRetake(val regularFrom: LocalDate?, val eligibility: PlanEligibility)
