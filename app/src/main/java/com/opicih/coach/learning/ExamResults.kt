package com.opicih.coach.learning

import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import java.time.LocalDate

object ExamResults {
    const val OPIC_LAG_DAYS = 5
    const val SAIL_LAG_DAYS = 1

    private fun lagDays(type: ExamType): Int? = when (type) {
        ExamType.OPIC -> OPIC_LAG_DAYS
        ExamType.SAIL -> SAIL_LAG_DAYS
        ExamType.UNKNOWN -> null
    }

    fun expectedOn(registration: ExamRegistrationEntity): LocalDate? =
        lagDays(registration.type)?.let { registration.date?.plusDays(it.toLong()) }

    fun latestSitting(type: ExamType, deadline: LocalDate?): LocalDate? =
        lagDays(type)?.let { deadline?.minusDays(it.toLong()) }
}
