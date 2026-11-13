package com.opicih.coach.learning

import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.Settings
import java.time.LocalDate

/** The user's confirmed result and the plan adopted on 2026-11-13. */
internal object YearEndExamUpdate {
    const val VERSION = 2
    private val confirmedOn = LocalDate.of(2026, 11, 13)

    fun updateSettings(current: Settings): Settings = current.copy(
        examBootstrapVersion = VERSION,
        retakeDays = 25,
        retakeVerified = true,
        opicWaivers = 1,
        opicWaiverDeclaredOn = confirmedOn,
        sailWaivers = 1,
        sailWaiverDeclaredOn = confirmedOn,
        gradeDeadline = LocalDate.of(2026, 12, 31),
    )

    fun completion(existing: List<ExamRegistrationEntity>): ExamRegistrationEntity? = existing.firstOrNull {
        it.type == ExamType.SAIL && it.dateLocal == "2026-11-11"
    }?.copy(status = RegistrationStatus.COMPLETED.name, resultGrade = "IL", needsReview = false)

    fun plans(): List<ExamRegistrationEntity> = listOf(
        planned(ExamType.OPIC, "2026-11-22", false),
        planned(ExamType.SAIL, "2026-12-09", false),
        planned(ExamType.OPIC, "2026-12-18", false),
        planned(ExamType.SAIL, "2026-12-23", true),
        planned(ExamType.OPIC, "2026-12-24", true),
    )

    private fun planned(type: ExamType, date: String, waiver: Boolean) = ExamRegistrationEntity(
        examType = type.name,
        dateLocal = date,
        status = RegistrationStatus.PLANNED.name,
        waiverPlanned = waiver,
    )
}
