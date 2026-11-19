package com.opicih.coach.data.repo

import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.learning.CoachingRecord
import com.opicih.coach.learning.CoachingStage

data class CoachingEntry(val attempt: PracticeAttemptEntity, val record: CoachingRecord)

data class CoachingRun(
    val first: CoachingEntry,
    val retry: CoachingEntry?,
    val delayed: CoachingEntry?,
) {
    companion object {
        fun from(entries: List<CoachingEntry>): CoachingRun? {
            val usable = entries.filter { it.record.evidence != null }
            val first = usable.firstOrNull { it.record.track.stage == CoachingStage.FIRST } ?: return null
            return CoachingRun(
                first,
                usable.firstOrNull { it.record.track.stage == CoachingStage.RETRY },
                usable.firstOrNull { it.record.track.stage == CoachingStage.DELAYED },
            )
        }
    }
}

internal fun coachingEntries(attempts: List<PracticeAttemptEntity>): List<CoachingEntry> =
    attempts.mapNotNull { attempt ->
        AttemptMetrics.from(attempt)?.coaching?.let { CoachingEntry(attempt, it) }
    }

fun List<CoachingEntry>.coachingRuns(): List<CoachingRun> =
    groupBy { it.record.track.id }.values.mapNotNull(CoachingRun::from)
