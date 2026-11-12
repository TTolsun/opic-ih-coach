package com.opicih.coach

import com.opicih.coach.learning.MorningRating.FAILURE
import com.opicih.coach.learning.MorningRating.SUCCESS
import com.opicih.coach.learning.MorningRoutineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MorningRoutineStateTest {
    @Test fun firstQuestionNeedsASelectionAndSelectionDoesNotMove() {
        val initial = MorningRoutineState(itemCount = 3)
        assertSame(initial, initial.previous(0))
        assertSame(initial, initial.beginSave(0, nowMillis = 1_000))

        val corrected = initial.select(0, FAILURE).select(0, SUCCESS)
        assertEquals(0, corrected.index)
        assertEquals(SUCCESS, corrected.selection)
        assertNull(corrected.pending)
    }

    @Test fun draftsAndSavedSelectionsSurviveMovingBackAndForward() {
        val first = MorningRoutineState(itemCount = 3).select(0, SUCCESS)
        val second = first.advance().select(1, FAILURE)
        val back = second.previous(1)
        assertEquals(SUCCESS, back.selection)
        assertNull(back.pending)
        val returned = back.advance()
        assertEquals(1, returned.index)
        assertEquals(FAILURE, returned.selection)

        val third = returned.advance(nowMillis = 1_500)
        val firstAgain = third.previous(2).previous(1)
        assertEquals(SUCCESS, firstAgain.selection)
        assertEquals(FAILURE, firstAgain.advance().selection)
    }

    @Test fun saveLocksNavigationAndInputUntilSuccessOrRetry() {
        val second = MorningRoutineState(itemCount = 2).select(0, SUCCESS).advance().select(1, FAILURE)
        val saving = second.beginSave(1, nowMillis = 1_000)
        val request = checkNotNull(saving.pending)
        assertSame(saving, saving.beginSave(1, nowMillis = 1_001))
        assertSame(saving, saving.previous(1))
        assertSame(saving, saving.select(1, SUCCESS))

        val failed = saving.finishSave(request, succeeded = false, nowMillis = 1_010)
        assertTrue(failed.saveFailed)
        assertEquals(1, failed.index)
        assertEquals(FAILURE, failed.selection)
        assertSame(failed, failed.finishSave(request, succeeded = true, nowMillis = 1_011))
        assertSame(failed, failed.beginSave(0, nowMillis = 1_011))

        val retry = failed.beginSave(1, nowMillis = 1_011)
        assertFalse(retry.saveFailed)
        assertEquals(request, retry.pending)
        assertSame(retry, retry.finishSave(request, succeeded = true, nowMillis = 1_012))
        assertTrue(retry.finishSave(checkNotNull(retry.pending), succeeded = true, nowMillis = 1_012).isSummary)
    }

    @Test fun completionCanReopenAndCorrectTheLastQuestion() {
        val first = MorningRoutineState(itemCount = 1).select(0, FAILURE)
        val summary = first.advance()
        assertTrue(summary.isSummary)
        assertSame(summary, summary.beginSave(1, nowMillis = 2_000))
        val reopened = summary.previous(1)
        assertEquals(FAILURE, reopened.selection)
        val saving = reopened.select(0, SUCCESS).beginSave(0, nowMillis = 2_000)
        val request = checkNotNull(saving.pending)
        assertTrue(request.finishesRoutine)
        assertEquals(SUCCESS, request.rating)
        assertTrue(saving.finishSave(request, succeeded = true, nowMillis = 2_001).isSummary)
    }

    @Test fun repeatedNextTapCannotSkipAnAlreadyRatedQuestion() {
        val first = MorningRoutineState(itemCount = 3).select(0, SUCCESS)
        val third = first.advance().select(1, FAILURE).advance()
        val reopened = third.previous(2).previous(1)
        val second = reopened.advance()
        assertEquals(FAILURE, second.selection)
        assertSame(second, second.beginSave(1, nowMillis = 1_050))
        assertEquals(1, second.beginSave(1, nowMillis = 1_500).pending?.index)
    }

    private fun MorningRoutineState.advance(nowMillis: Long = 1_000): MorningRoutineState {
        val saving = beginSave(index, nowMillis)
        return saving.finishSave(checkNotNull(saving.pending), succeeded = true, nowMillis = nowMillis + 10)
    }
}
