package com.opicih.coach.ui.screens

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opicih.coach.App
import com.opicih.coach.data.db.MorningReviewEntity
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.learning.MorningItem
import com.opicih.coach.learning.MorningRating
import com.opicih.coach.learning.MorningRoutineBuilder
import com.opicih.coach.learning.MorningRoutineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

class MorningViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container
    private val routineId = UUID.randomUUID().toString()
    private val startedDay = LocalDate.now().toEpochDay()

    internal var items by mutableStateOf<List<MorningItem>>(emptyList())
        private set
    internal var dueScripts by mutableStateOf<List<ScriptEntity>>(emptyList())
        private set
    internal var state by mutableStateOf<MorningRoutineState?>(null)
        private set
    internal var loadFailed by mutableStateOf(false)
        private set

    init { load() }

    internal fun load() {
        state = null
        loadFailed = false
        viewModelScope.launch {
            try {
                items = MorningRoutineBuilder.build(c.dao.chunks(), startedDay)
                dueScripts = c.dao.scripts().filter { it.dueEpochDay <= startedDay }.sortedBy { it.masteryScore }.take(3)
                state = MorningRoutineState(items.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadFailed = true
            }
        }
    }

    internal fun select(index: Int, rating: MorningRating) {
        state = state?.select(index, rating)
    }

    internal fun previous(index: Int) {
        state = state?.previous(index)
    }

    internal fun next(index: Int) {
        val current = state ?: return
        val saving = current.beginSave(index, SystemClock.elapsedRealtime())
        if (saving === current) return
        val request = checkNotNull(saving.pending)
        state = saving

        viewModelScope.launch {
            val succeeded = try {
                c.practice.saveMorningReview(
                    MorningReviewEntity(
                        routineId = routineId,
                        itemIndex = request.index,
                        chunkId = items[request.index].chunk.id,
                        reviewedDay = LocalDate.now().toEpochDay(),
                        examDay = c.exams.focusEpochDay(),
                        success = request.rating == MorningRating.SUCCESS,
                        initialStep = null,
                    ),
                    finishesRoutine = request.finishesRoutine,
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            state = state?.finishSave(request, succeeded, SystemClock.elapsedRealtime())
        }
    }
}
