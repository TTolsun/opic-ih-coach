package com.opicih.coach.learning

import com.opicih.coach.data.db.MasteryState
import com.opicih.coach.analysis.TransferCheck
import com.opicih.coach.data.db.PracticeMode

/**
 * Simple, predictable spaced repetition (SM-2 simplified, see HANDOFF 10).
 * Fixed ladder [1,2,4,7,14] days; success moves one step up, failure resets to 1 day.
 * Before the exam the due date is clamped so everything is seen at least once by D-1.
 */
object SrsScheduler {
    val LADDER = intArrayOf(1, 2, 4, 7, 14)

    data class Result(val step: Int, val dueEpochDay: Long)

    fun next(step: Int, success: Boolean, todayEpochDay: Long, examEpochDay: Long?): Result {
        val newStep = if (success) (step + 1).coerceAtMost(LADDER.size) else 0
        val interval = if (success) LADDER[(newStep - 1).coerceIn(0, LADDER.lastIndex)] else 1
        var due = todayEpochDay + interval
        if (examEpochDay != null && todayEpochDay < examEpochDay - 1 && due > examEpochDay - 1) due = examEpochDay - 1
        return Result(newStep, due)
    }

    /**
     * Grade an attempt: success when the user conveyed most of the content for that mode. Transfer drills
     * (바뀐 질문, 응용 미션) pass on the [TransferCheck] when one was made, not on how much script came back.
     */
    fun isSuccess(mode: PracticeMode, coverage: Float?, score: Int, transfer: TransferCheck? = null): Boolean = when {
        transfer != null && (mode == PracticeMode.RANDOM || mode == PracticeMode.VARIATION) -> transferPassed(mode, transfer, score)
        else -> isSuccessByContent(mode, coverage, score)
    }

    /**
     * 바뀐 질문 passes on the check alone ([score] there is the pause-based flow score, unrelated to transfer).
     * 응용 미션 also needs [score] ≥ 60: with AI that is the relevance score, without AI it is the check's own score.
     */
    internal fun transferPassed(mode: PracticeMode, transfer: TransferCheck, score: Int): Boolean =
        transfer.passed && (mode != PracticeMode.VARIATION || score >= 60)

    private fun isSuccessByContent(mode: PracticeMode, coverage: Float?, score: Int): Boolean = when (mode) {
        PracticeMode.READ_LISTEN, PracticeMode.SHADOWING -> (coverage ?: 0f) >= 0.8f
        PracticeMode.FILL_BLANK, PracticeMode.KEYWORD -> (coverage ?: 0f) >= 0.6f
        PracticeMode.BLIND, PracticeMode.RANDOM -> (coverage ?: 0f) >= 0.6f
        PracticeMode.VARIATION, PracticeMode.FREE, PracticeMode.MOCK, PracticeMode.SAIL_CONVERSATION,
        PracticeMode.FRAME_ATTACH, PracticeMode.PARAGRAPH_LADDER -> score >= 60
    }
}

/**
 * Mastery state machine: NEW → LEARNING → NO_SCRIPT_OK (안 보고 말하기 ≥70%) → VARIATION_OK (a passed transfer
 * check on 바뀐 질문 or 응용 미션); drop → NEEDS_REVIEW.
 */
object MasteryRules {
    data class Update(val state: MasteryState, val score: Int)

    fun apply(current: MasteryState, currentScore: Int, mode: PracticeMode, coverage: Float?, score: Int, transfer: TransferCheck? = null): Update {
        val cov = coverage ?: 0f
        val transferDrill = transfer != null && (mode == PracticeMode.RANDOM || mode == PracticeMode.VARIATION)
        val newScore = ((currentScore * 0.6f) + (when {
            transferDrill -> requireNotNull(transfer).score.toFloat()
            else -> contentPoints(mode, cov, score)
        } * 0.4f)).toInt().coerceIn(0, 100)

        val state = when {
            // Using the story on a new question is the step after recalling it; it never demotes recall.
            transferDrill -> when {
                SrsScheduler.transferPassed(mode, requireNotNull(transfer), score) && current in setOf(MasteryState.NO_SCRIPT_OK, MasteryState.VARIATION_OK) -> MasteryState.VARIATION_OK
                current == MasteryState.NEW -> MasteryState.LEARNING
                else -> current
            }
            else -> contentState(current, mode, cov, score)
        }
        return Update(state, newScore)
    }

    private fun contentPoints(mode: PracticeMode, cov: Float, score: Int): Float = when (mode) {
        PracticeMode.BLIND, PracticeMode.RANDOM -> cov * 100f
        PracticeMode.VARIATION -> score.toFloat()
        PracticeMode.KEYWORD, PracticeMode.FILL_BLANK -> cov * 85f
        else -> cov * 60f
    }

    private fun contentState(current: MasteryState, mode: PracticeMode, cov: Float, score: Int): MasteryState =
        when {
            mode == PracticeMode.VARIATION && score >= 60 && current in setOf(MasteryState.NO_SCRIPT_OK, MasteryState.VARIATION_OK) -> MasteryState.VARIATION_OK
            (mode == PracticeMode.BLIND || mode == PracticeMode.RANDOM) && cov >= 0.7f -> if (current == MasteryState.VARIATION_OK) current else MasteryState.NO_SCRIPT_OK
            (mode == PracticeMode.BLIND || mode == PracticeMode.RANDOM) && cov < 0.4f && current in setOf(MasteryState.NO_SCRIPT_OK, MasteryState.VARIATION_OK) -> MasteryState.NEEDS_REVIEW
            current == MasteryState.NEW -> MasteryState.LEARNING
            current == MasteryState.NEEDS_REVIEW && cov >= 0.7f -> MasteryState.LEARNING
            else -> current
        }
}
