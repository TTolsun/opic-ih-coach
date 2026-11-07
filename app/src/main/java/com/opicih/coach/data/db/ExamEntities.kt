package com.opicih.coach.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** Exam families the app can plan for. UNKNOWN marks migrated rows the user still has to classify. */
enum class ExamType(val label: String) {
    OPIC("OPIc"),
    SAIL("SAIL"),
    UNKNOWN("유형 미확인");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: UNKNOWN
        val selectable = listOf(OPIC, SAIL)
    }
}

/**
 * Registration lifecycle. Only COMPLETED counts as a real sitting for retake rules;
 * CANCELLED never does. A REGISTERED exam whose time has passed stays REGISTERED until the user confirms
 * (the app never assumes the user actually sat the exam).
 */
enum class RegistrationStatus(val label: String) {
    PLANNED("접수 예정"),
    REGISTERED("접수 완료"),
    COMPLETED("응시 완료"),
    CANCELLED("취소"),
    NO_SHOW("결시");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: PLANNED
    }
}

/** How exact [ExamRegistrationEntity.dateLocal] is. MONTH = only "2026-02" is known; never invent a day. */
enum class DatePrecision { DAY, MONTH;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: DAY }
}

enum class RegistrationSource(val label: String) {
    USER("사용자 입력"),
    EMAIL_IMPORT("신청 확인 메일"),
    OFFICIAL("공식 확인"),
    MIGRATED("이전 버전에서 옮김");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: USER
    }
}

/**
 * One exam sitting (planned, booked, taken, cancelled). Facts only: recommendations are computed, never stored here.
 * Dates are stored as local wall-clock strings plus [zoneId] so a phone time-zone change cannot shift an exam.
 */
@Entity(tableName = "exam_registrations", indices = [Index("dateLocal"), Index("examType")])
data class ExamRegistrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examType: String,
    /** "2026-11-11" (DAY) or "2026-02" (MONTH). */
    val dateLocal: String,
    val datePrecision: String = DatePrecision.DAY.name,
    /** "19:20" or null when unknown. */
    val startTimeLocal: String? = null,
    val endTimeLocal: String? = null,
    val zoneId: String = DEFAULT_ZONE,
    val location: String = "",
    val status: String = RegistrationStatus.PLANNED.name,
    val registrationOpenAt: String? = null,
    val registrationCloseAt: String? = null,
    val resultGrade: String? = null,
    val resultPublishedAt: String? = null,
    /** null = not known. Waiver bookkeeping itself lives in the B-phase ledger. */
    val waiverUsed: Boolean? = null,
    val memo: String = "",
    val sourceType: String = RegistrationSource.USER.name,
    val verifiedAt: Long? = null,
    /** True for rows the user must still confirm (migrated or ambiguous). */
    val needsReview: Boolean = false,
    /** Link to the v2 `exams` row (debrief data stays there). */
    val legacyExamId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val type: ExamType get() = ExamType.of(examType)
    val state: RegistrationStatus get() = RegistrationStatus.of(status)
    val precision: DatePrecision get() = DatePrecision.of(datePrecision)
    val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))

    /** Exact day, or null when only the month is known. */
    val date: LocalDate? get() = if (precision == DatePrecision.DAY) runCatching { LocalDate.parse(dateLocal) }.getOrNull() else null
    val yearMonth: YearMonth? get() = runCatching { YearMonth.parse(dateLocal.take(7)) }.getOrNull()

    /** Earliest possible day (first of month for MONTH precision). */
    val earliestDate: LocalDate? get() = date ?: yearMonth?.atDay(1)
    /** Latest possible day (last of month for MONTH precision): the conservative value for retake rules. */
    val latestDate: LocalDate? get() = date ?: yearMonth?.atEndOfMonth()

    val startTime: LocalTime? get() = startTimeLocal?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val endTime: LocalTime? get() = endTimeLocal?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    fun startAt(): ZonedDateTime? = date?.let { d -> startTime?.let { ZonedDateTime.of(d, it, zone) } }

    /** When the sitting is over: end time, else start + 1 h, else the end of that day. */
    fun endsAt(): ZonedDateTime? {
        val d = latestDate ?: return null
        endTime?.let { return ZonedDateTime.of(d, it, zone) }
        startTime?.let { return ZonedDateTime.of(d, it, zone).plusHours(1) }
        return d.plusDays(1).atStartOfDay(zone)
    }

    companion object {
        const val DEFAULT_ZONE = "Asia/Seoul"
    }
}

/** Append-only status history, so "cancelled on 11/03" is never confused with a sitting. */
@Entity(tableName = "exam_status_events", indices = [Index("registrationId")])
data class ExamStatusEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val registrationId: Long,
    val fromStatus: String?,
    val toStatus: String,
    /** When it happened in real life, e.g. "2026-11-03". */
    val effectiveDate: String? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** A grouped practice session for an exam (e.g. a SAIL conversation). Turns are rows in `attempts`. */
@Entity(tableName = "exam_training_sessions")
data class ExamTrainingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examType: String,
    val kind: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val turnCount: Int = 0,
    val aiCalls: Int = 0,
    val summaryJson: String? = null,
)
