package com.opicih.coach

import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.MorningReviewEntity
import com.opicih.coach.learning.MorningReviewRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorningReviewRulesTest {
    private val original = ChunkEntity(
        id = 8, phrase = "to be honest", meaningKo = "솔직히", example = "To be honest, it was fun.",
        category = "opener", srsStep = 2, dueEpochDay = 95, lastResult = "OK", failCount = 3, lastReviewedEpochDay = 91,
    )

    @Test fun repeatedCorrectionRestoresEveryFieldAndOnlyFirstRatingCounts() {
        val event = review(index = 0, success = true)
        val first = checkNotNull(MorningReviewRules.change(original, emptyList(), event))
        assertTrue(first.isFirstRating)
        assertEquals(original.copy(srsStep = 3, dueEpochDay = 104, lastReviewedEpochDay = 100), first.chunk)
        assertNull(MorningReviewRules.change(first.chunk, listOf(first.review), event))

        val failure = checkNotNull(MorningReviewRules.change(first.chunk, listOf(first.review), event.copy(success = false)))
        assertFalse(failure.isFirstRating)
        assertEquals(original.copy(srsStep = 0, dueEpochDay = 101, lastResult = "FAIL", failCount = 4, lastReviewedEpochDay = 100), failure.chunk)
        assertNull(MorningReviewRules.change(failure.chunk, listOf(failure.review), event.copy(success = false)))

        val restored = checkNotNull(MorningReviewRules.change(failure.chunk, listOf(failure.review), event))
        assertFalse(restored.isFirstRating)
        assertEquals(first, restored.copy(isFirstRating = true))
    }

    @Test fun correctingEarlierStageReplaysLaterStageAndUsesItsOriginalDateAndExam() {
        val early = review(index = 0, success = true)
        val first = checkNotNull(MorningReviewRules.change(original, emptyList(), early))
        val later = review(index = 5, success = true).copy(reviewedDay = 101, examDay = 105)
        val second = checkNotNull(MorningReviewRules.change(first.chunk, listOf(first.review), later))
        assertTrue(second.isFirstRating)
        assertEquals(original.copy(srsStep = 4, dueEpochDay = 104, lastReviewedEpochDay = 101), second.chunk)

        val corrected = checkNotNull(MorningReviewRules.change(
            second.chunk, listOf(first.review, second.review),
            early.copy(success = false, reviewedDay = 111, examDay = 112),
        ))
        assertEquals(first.review.copy(success = false), corrected.review)
        assertEquals(original.copy(srsStep = 1, dueEpochDay = 102, failCount = 4, lastReviewedEpochDay = 101), corrected.chunk)

        val restored = checkNotNull(MorningReviewRules.change(corrected.chunk, listOf(corrected.review, second.review), early))
        assertEquals(second.chunk, restored.chunk)
    }

    @Test fun laterFailureFromAnotherRoutineStaysEffectiveAfterEarlierFailureIsRemoved() {
        val early = review(index = 0, success = false)
        val first = checkNotNull(MorningReviewRules.change(original, emptyList(), early))
        val later = early.copy(id = 2, routineId = "other", reviewedDay = 103)
        val second = checkNotNull(MorningReviewRules.change(first.chunk, listOf(first.review), later))
        val corrected = checkNotNull(MorningReviewRules.change(second.chunk, listOf(first.review, second.review), early.copy(success = true)))
        assertEquals(original.copy(srsStep = 0, dueEpochDay = 104, lastResult = "FAIL", failCount = 4, lastReviewedEpochDay = 103), corrected.chunk)
    }

    private fun review(index: Int, success: Boolean) = MorningReviewEntity(
        id = index + 1L, routineId = "routine", itemIndex = index, chunkId = original.id,
        reviewedDay = 100, examDay = null, success = success, initialStep = null,
    )
}
