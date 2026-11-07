package com.opicih.coach.learning

import com.opicih.coach.data.db.DatePrecision
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationSource
import com.opicih.coach.data.db.RegistrationStatus
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure queries over exam registrations. No recommendation is produced here: only facts the user entered.
 *
 * "Focus exam" = the nearest REGISTERED exam of a known type that has not ended yet. PLANNED rows are intentions,
 * not bookings, so they never drive the daily plan or the home D-day.
 */
object ExamSchedule {

    fun focus(regs: List<ExamRegistrationEntity>, now: ZonedDateTime): ExamRegistrationEntity? =
        upcomingRegistered(regs, now).firstOrNull()

    /** Registered, known-type, exact-day exams that have not ended, nearest first. */
    fun upcomingRegistered(regs: List<ExamRegistrationEntity>, now: ZonedDateTime): List<ExamRegistrationEntity> =
        regs.filter { it.state == RegistrationStatus.REGISTERED && it.type != ExamType.UNKNOWN && it.date != null }
            .filter { r -> r.endsAt()?.isAfter(now) == true }
            .sortedWith(compareBy({ it.date }, { it.startTime }))

    /** The next exam after [focus], used for the "그다음 시험" line. */
    fun next(regs: List<ExamRegistrationEntity>, now: ZonedDateTime): ExamRegistrationEntity? =
        upcomingRegistered(regs, now).drop(1).firstOrNull()

    /** Next registered or planned exam of one type (the home "다음 OPIc 일정" line). */
    fun nextOfType(regs: List<ExamRegistrationEntity>, type: ExamType, now: ZonedDateTime): ExamRegistrationEntity? =
        regs.filter { it.type == type && (it.state == RegistrationStatus.REGISTERED || it.state == RegistrationStatus.PLANNED) }
            .filter { it.endsAt()?.isAfter(now) == true }
            .sortedWith(compareBy({ it.earliestDate }, { it.startTime }))
            .firstOrNull()

    /** REGISTERED exams whose time is over: the user has to say whether they sat it (never assumed). */
    fun awaitingConfirmation(regs: List<ExamRegistrationEntity>, now: ZonedDateTime): List<ExamRegistrationEntity> =
        regs.filter { it.state == RegistrationStatus.REGISTERED && it.endsAt()?.isAfter(now) == false }

    fun needsReview(regs: List<ExamRegistrationEntity>): List<ExamRegistrationEntity> = regs.filter { it.needsReview }

    /**
     * Last real sitting of a type. For month-only dates the last day of the month is returned, which is the
     * conservative value for a retake wait. [exact] tells the caller whether the day is real.
     */
    data class LastSitting(val date: LocalDate, val exact: Boolean, val registration: ExamRegistrationEntity)

    fun lastCompleted(regs: List<ExamRegistrationEntity>, type: ExamType): LastSitting? =
        regs.filter { it.type == type && it.state == RegistrationStatus.COMPLETED }
            .mapNotNull { r -> r.latestDate?.let { LastSitting(it, r.precision == DatePrecision.DAY, r) } }
            .maxByOrNull { it.date }

    fun daysUntil(today: LocalDate, r: ExamRegistrationEntity): Long? =
        r.date?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }

    private val KO = Locale.KOREAN

    /** "2026.11.11 (수) 19:20" / "2026.02 (일자 미입력)". */
    fun formatWhen(r: ExamRegistrationEntity): String {
        val d = r.date
        if (d == null) {
            val ym = r.yearMonth ?: return r.dateLocal
            return "%d.%02d (일자 미입력)".format(ym.year, ym.monthValue)
        }
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, KO)
        val time = r.startTimeLocal?.let { " $it" }.orEmpty()
        return "%d.%02d.%02d (%s)%s".format(d.year, d.monthValue, d.dayOfMonth, dow, time)
    }

    fun shortDate(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}"
}

/**
 * One-time conversion of pre-v3 data into registrations.
 *
 * Rules:
 *  - The user-confirmed facts (SAIL 11/11 booked, SAIL 11/18 cancelled on 11/03, OPIc sat in 2026-02) are added
 *    only if no registration of the same type and date already exists. Existing rows are never overwritten.
 *  - A migrated UNKNOWN row on the same day as a confirmed fact is absorbed into that fact (its legacy link is kept).
 *  - The old single `Settings.examDate` becomes a PLANNED, UNKNOWN, needs-review row, unless some registration
 *    already exists on that day. It is never treated as a completed sitting.
 */
object ExamBootstrap {
    data class StatusStep(val from: RegistrationStatus?, val to: RegistrationStatus, val effectiveDate: String?, val note: String)
    data class Seed(val registration: ExamRegistrationEntity, val history: List<StatusStep>)
    data class Plan(val inserts: List<Seed>, val absorbIds: List<Long>)

    fun userConfirmed(now: Long = System.currentTimeMillis()): List<Seed> = listOf(
        Seed(
            ExamRegistrationEntity(
                examType = ExamType.SAIL.name, dateLocal = "2026-11-11", startTimeLocal = "19:20", endTimeLocal = "20:20",
                location = "전자(DS) 에듀센터 동탄Ⅱ B1층 어학검정장 A", status = RegistrationStatus.REGISTERED.name,
                memo = "시험 시작 10분 전까지 입실. 신청 확인 메일에 재응시 25일 규정 확인 안내가 있음.",
                sourceType = RegistrationSource.EMAIL_IMPORT.name, verifiedAt = now, createdAt = now, updatedAt = now,
            ),
            listOf(StatusStep(null, RegistrationStatus.REGISTERED, null, "신청 확인 메일 수신")),
        ),
        Seed(
            ExamRegistrationEntity(
                examType = ExamType.SAIL.name, dateLocal = "2026-11-18", status = RegistrationStatus.CANCELLED.name,
                memo = "신청 후 2026-11-03에 취소함. 응시 이력이 아니므로 재응시 계산에 쓰지 않음.",
                sourceType = RegistrationSource.USER.name, createdAt = now, updatedAt = now,
            ),
            listOf(
                StatusStep(null, RegistrationStatus.REGISTERED, null, "신청"),
                StatusStep(RegistrationStatus.REGISTERED, RegistrationStatus.CANCELLED, "2026-11-03", "사용자 취소"),
            ),
        ),
        Seed(
            ExamRegistrationEntity(
                examType = ExamType.OPIC.name, dateLocal = "2026-02", datePrecision = DatePrecision.MONTH.name,
                status = RegistrationStatus.COMPLETED.name,
                memo = "정확한 응시일 미입력. 재응시 계산은 2월 말일을 응시일로 보고 보수적으로 합니다.",
                sourceType = RegistrationSource.USER.name, createdAt = now, updatedAt = now,
            ),
            listOf(StatusStep(null, RegistrationStatus.COMPLETED, "2026-02", "마지막 실제 응시 (월 단위 정보)")),
        ),
    )

    fun plan(existing: List<ExamRegistrationEntity>, legacyExamDate: LocalDate?, now: Long = System.currentTimeMillis()): Plan {
        val inserts = mutableListOf<Seed>()
        val absorb = mutableListOf<Long>()
        val known = existing.toMutableList()

        for (seed in userConfirmed(now)) {
            val r = seed.registration
            if (known.any { it.type == r.type && it.dateLocal == r.dateLocal }) continue
            val unknownSameDay = known.firstOrNull { it.type == ExamType.UNKNOWN && it.dateLocal == r.dateLocal && it.legacyExamId != null }
            val merged = if (unknownSameDay != null) {
                absorb += unknownSameDay.id
                known.remove(unknownSameDay)
                r.copy(legacyExamId = unknownSameDay.legacyExamId, memo = r.memo + " (이전 버전 시험 기록과 연결됨)")
            } else r
            inserts += seed.copy(registration = merged)
            known += merged
        }

        if (legacyExamDate != null) {
            val iso = legacyExamDate.toString()
            if (known.none { it.dateLocal == iso }) {
                inserts += Seed(
                    ExamRegistrationEntity(
                        examType = ExamType.UNKNOWN.name, dateLocal = iso, status = RegistrationStatus.PLANNED.name,
                        memo = "이전 버전의 '시험일' 설정에서 옮김. 시험 유형과 접수 여부를 확인하세요.",
                        sourceType = RegistrationSource.MIGRATED.name, needsReview = true, createdAt = now, updatedAt = now,
                    ),
                    listOf(StatusStep(null, RegistrationStatus.PLANNED, null, "이전 버전 설정에서 옮김")),
                )
            }
        }
        return Plan(inserts, absorb)
    }
}
