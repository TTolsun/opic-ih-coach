package com.opicih.coach.learning

import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import java.time.LocalDate

/**
 * Retake rules per exam type, kept separate from the UI and from recommendations.
 *
 * OPIc values come from the official OPIc 시험관리규정 제5조 (effective 2026-10-29):
 *   "언어별 최근 응시일로부터 25일 경과 후의 시험에 응시할 수 있다", example D+0 = 6/1, D+1..D+25 (6/2..6/26) blocked,
 *   D+26 (6/27) allowed, "기간 계산 시 응시일 제외", Waiver "150일마다 1회 사용 가능".
 * The regulation does not say whether a waiver is applied automatically or restored after cancel / no-show, so those stay unverified.
 * SAIL is an in-house test with no public rules: everything is an assumption the user can change.
 */
enum class Verification(val label: String) { OFFICIAL("공식 규정"), USER_CONFIRMED("사용자 확인"), UNVERIFIED("미검증") }

enum class WaiverSupport { YES, NO, UNKNOWN }

data class ExamPolicy(
    val type: ExamType,
    /** Blocked days after the exam day (D+1..D+N). The first allowed day is D+N+1. */
    val blockedDays: Int,
    val waiverSupport: WaiverSupport,
    /** A used waiver comes back after this many days; null = unknown. */
    val waiverRegrantDays: Int?,
    /** Exams with the same group share the retake limit. */
    val restrictionGroup: String,
    val source: String,
    val verification: Verification,
) {
    /** First allowed sitting day after [lastSitting] without a waiver. */
    fun earliestWithoutWaiver(lastSitting: LocalDate): LocalDate = lastSitting.plusDays(blockedDays + 1L)

    /** With a waiver: the next day (the same language cannot be taken twice on one day). */
    fun earliestWithWaiver(lastSitting: LocalDate): LocalDate = lastSitting.plusDays(1)

    companion object {
        const val OPIC_RULE_SOURCE = "OPIc 시험관리규정 제5조 (2026-10-29 시행)"

        fun opic() = ExamPolicy(ExamType.OPIC, 25, WaiverSupport.YES, 150, "ACTFL", OPIC_RULE_SOURCE, Verification.OFFICIAL)

        /** SAIL: the confirmation mail mentions a 25-day rule; the exact counting is not known. */
        fun sail(blockedDays: Int, userConfirmed: Boolean, sharesWithOpic: Boolean) = ExamPolicy(
            ExamType.SAIL, blockedDays, WaiverSupport.UNKNOWN, null, if (sharesWithOpic) "ACTFL" else "SAIL",
            "신청 확인 메일의 25일 규정 안내 (응시일 제외 계산은 OPIc과 같다고 가정)",
            if (userConfirmed) Verification.USER_CONFIRMED else Verification.UNVERIFIED,
        )
    }
}

/** Waiver balance derived from facts, never decremented by hand. */
data class WaiverState(
    /** null = the user never entered a balance. */
    val available: Int?,
    val lastUsed: LocalDate?,
    /** When the most recently used waiver comes back (lastUsed + regrant days). */
    val regrantOn: LocalDate?,
    val notes: List<String>,
)

object WaiverLedger {
    /**
     * @param declared balance the user reported (Settings.opicWaivers, -1 = not entered), taken as of [declaredOn]
     * Uses are registrations of the type with waiverUsed = true and a date after [declaredOn].
     * A use made before the declaration is already reflected in the declared number.
     */
    fun state(declared: Int, declaredOn: LocalDate, regs: List<ExamRegistrationEntity>, policy: ExamPolicy, today: LocalDate): WaiverState {
        val notes = mutableListOf<String>()
        if (policy.waiverSupport == WaiverSupport.UNKNOWN) {
            return WaiverState(null, null, null, listOf("${policy.type.label} Waiver 규정을 알 수 없어 사용 가능하다고 가정하지 않습니다."))
        }
        if (policy.waiverSupport == WaiverSupport.NO) return WaiverState(0, null, null, emptyList())
        if (declared < 0) return WaiverState(null, null, null, listOf("보유 Waiver 수를 입력하지 않았습니다."))

        val uses = regs.filter { it.type == policy.type && it.waiverUsed == true }
            .mapNotNull { r -> r.latestDate?.let { d -> r to d } }
            .filter { (_, d) -> d.isAfter(declaredOn) }
        var active = 0
        var lastUsed: LocalDate? = null
        for ((r, d) in uses) {
            if (r.state == RegistrationStatus.CANCELLED || r.state == RegistrationStatus.NO_SHOW) {
                notes += "${d} ${r.state.label} 시험의 Waiver 복구 여부는 규정에 없어, 사용한 것으로 보수적으로 계산합니다."
            }
            val back = policy.waiverRegrantDays?.let { d.plusDays(it.toLong()) }
            if (back == null || back.isAfter(today)) active++
            if (lastUsed == null || d.isAfter(lastUsed)) lastUsed = d
        }
        val regrant = lastUsed?.let { u -> policy.waiverRegrantDays?.let { u.plusDays(it.toLong()) } }
        notes += "보유 수는 ${declaredOn} 기준 사용자 입력값입니다."
        notes += "접수할 때 Waiver가 자동으로 적용되는지는 시험관리규정에 없습니다. 접수 화면에서 확인하세요."
        return WaiverState((declared - active).coerceAtLeast(0), lastUsed, regrant, notes)
    }
}

/** One way to use the remaining time before the deadline. Dates are rule-eligible days, not confirmed sessions. */
data class RetakePath(
    val useWaiver: Boolean,
    val earliest: LocalDate?,
    /** Sittings whose result can still arrive by the deadline, taking each one as early as the rules allow. */
    val opportunities: Int,
    val eligibleDates: List<LocalDate>,
    val assumptions: List<String>,
)

object RetakeEngine {
    /** Last real sitting that limits [policy]'s type (shared groups included). Cancelled exams never count. */
    fun lastSitting(regs: List<ExamRegistrationEntity>, policy: ExamPolicy, policies: Map<ExamType, ExamPolicy>): LocalDate? =
        regs.filter { r -> r.state == RegistrationStatus.COMPLETED && policies[r.type]?.restrictionGroup == policy.restrictionGroup }
            .mapNotNull { it.latestDate }
            .maxOrNull()

    /**
     * Compares the path without a waiver and (if one is available) the path that spends it on the next sitting.
     * @param latestUseful last sitting day whose result is out before the deadline (deadline - result lag)
     */
    fun paths(policy: ExamPolicy, lastSitting: LocalDate?, waiver: WaiverState, today: LocalDate, latestUseful: LocalDate?): List<RetakePath> {
        val step = policy.blockedDays + 1L
        val base = mutableListOf("재응시 제한: 응시일 다음 날부터 ${policy.blockedDays}일 (${policy.source}, ${policy.verification.label})")
        latestUseful?.let { base += "결과 발표 기간을 고려한 마지막 유효 응시일 ${it}" }
        base += "날짜는 규정상 응시 가능한 날이며, 실제 시험 회차와 접수 가능 여부는 확인하지 않았습니다."

        fun chain(first: LocalDate): List<LocalDate> {
            val out = mutableListOf<LocalDate>()
            var d = first
            while (latestUseful == null || !d.isAfter(latestUseful)) {
                out += d
                if (latestUseful == null && out.size >= 3) break
                d = d.plusDays(step)
            }
            return out
        }

        val tomorrow = today.plusDays(1)
        val noWaiverFirst = maxOf(lastSitting?.let { policy.earliestWithoutWaiver(it) } ?: tomorrow, tomorrow)
        val noWaiverDates = chain(noWaiverFirst)
        val result = mutableListOf(RetakePath(false, noWaiverDates.firstOrNull(), noWaiverDates.size, noWaiverDates, base))

        val canWaive = (waiver.available ?: 0) > 0 && lastSitting != null && policy.earliestWithoutWaiver(lastSitting).isAfter(tomorrow)
        if (canWaive) {
            val first = maxOf(policy.earliestWithWaiver(lastSitting!!), tomorrow)
            val dates = if (latestUseful != null && first.isAfter(latestUseful)) emptyList() else listOf(first) + chain(first.plusDays(step)).filter { it.isAfter(first) }
            result += RetakePath(true, dates.firstOrNull(), dates.size, dates,
                base + "Waiver 1회를 다음 응시에 쓴다고 가정했습니다." + waiver.notes)
        }
        return result
    }
}
