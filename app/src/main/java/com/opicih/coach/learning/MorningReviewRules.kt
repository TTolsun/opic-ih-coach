package com.opicih.coach.learning

import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.MorningReviewEntity

internal data class MorningReviewChange(
    val review: MorningReviewEntity,
    val chunk: ChunkEntity,
    val isFirstRating: Boolean,
)

internal object MorningReviewRules {
    /** One review per routine position. Each expression's SRS follows the original save order. */
    fun change(
        chunk: ChunkEntity,
        history: List<MorningReviewEntity>,
        incoming: MorningReviewEntity,
    ): MorningReviewChange? {
        val previous = history.firstOrNull {
            it.routineId == incoming.routineId && it.itemIndex == incoming.itemIndex
        }
        if (previous?.success == incoming.success) return null

        val review = previous?.copy(success = incoming.success)
            ?: incoming.copy(initialStep = chunk.srsStep.takeIf { history.isEmpty() })
        val revised = if (previous == null) history + review else history.map { if (it.id == review.id) review else it }
        var srs = SrsScheduler.Result(requireNotNull(revised.first().initialStep), chunk.dueEpochDay)
        for (event in revised) {
            srs = SrsScheduler.next(srs.step, event.success, event.reviewedDay, event.examDay)
        }

        val removedFailure = if (previous?.success == false) 1 else 0
        val addedFailure = if (review.success) 0 else 1
        val latest = revised.last()
        return MorningReviewChange(
            review = review,
            chunk = chunk.copy(
                srsStep = srs.step,
                dueEpochDay = srs.dueEpochDay,
                lastResult = if (latest.success) "OK" else "FAIL",
                lastReviewedEpochDay = latest.reviewedDay,
                failCount = chunk.failCount - removedFailure + addedFailure,
            ),
            isFirstRating = previous == null,
        )
    }
}
