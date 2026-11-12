package com.opicih.coach.learning

private const val NEXT_TAP_GUARD_MILLIS = 300L

internal enum class MorningRating(val label: String) {
    SUCCESS("말할 수 있어요"), FAILURE("헷갈려요"),
}

internal data class MorningSave(val index: Int, val rating: MorningRating, val finishesRoutine: Boolean)

internal data class MorningRoutineState(
    val itemCount: Int,
    val index: Int = 0,
    val selections: Map<Int, MorningRating> = emptyMap(),
    val pending: MorningSave? = null,
    val saveFailed: Boolean = false,
    private val nextTapAllowedAt: Long = 0,
) {
    val isSummary get() = itemCount > 0 && index == itemCount
    val isLastItem get() = index == itemCount - 1
    val selection get() = selections[index]

    fun select(expectedIndex: Int, rating: MorningRating): MorningRoutineState =
        if (pending == null && index == expectedIndex && index < itemCount) {
            copy(selections = selections + (index to rating), saveFailed = false, nextTapAllowedAt = 0)
        } else this

    fun previous(expectedIndex: Int): MorningRoutineState =
        if (pending == null && index == expectedIndex && index > 0) {
            copy(index = index - 1, saveFailed = false, nextTapAllowedAt = 0)
        } else this

    fun beginSave(expectedIndex: Int, nowMillis: Long): MorningRoutineState {
        val rating = selection ?: return this
        if (pending != null || index != expectedIndex || isSummary || nowMillis < nextTapAllowedAt) return this
        return copy(pending = MorningSave(index, rating, isLastItem), saveFailed = false)
    }

    fun finishSave(save: MorningSave, succeeded: Boolean, nowMillis: Long): MorningRoutineState {
        if (pending !== save) return this
        // The next position may already have a selection when revisiting several questions.
        return if (succeeded) copy(index = index + 1, pending = null, nextTapAllowedAt = nowMillis + NEXT_TAP_GUARD_MILLIS)
        else copy(pending = null, saveFailed = true)
    }
}
