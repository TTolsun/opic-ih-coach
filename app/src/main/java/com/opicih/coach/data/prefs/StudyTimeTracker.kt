package com.opicih.coach.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opicih.coach.alarm.StudyBlocks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

private val Context.studyStore by preferencesDataStore(name = "study_time")

/** Measured study time for the current study day (04:00 boundary). Own DataStore: no Room migration needed. */
class StudyTimeTracker(private val context: Context) {
    data class Snapshot(val studyDay: Long, val studyMs: Long, val lastActiveAt: Long) {
        fun msFor(day: Long) = if (studyDay == day) studyMs else 0L
    }

    private object K {
        val day = longPreferencesKey("study_day")
        val ms = longPreferencesKey("study_ms")
        val active = longPreferencesKey("last_active_at")
    }

    val snapshots: Flow<Snapshot> = context.studyStore.data.map { Snapshot(it[K.day] ?: -1L, it[K.ms] ?: 0L, it[K.active] ?: 0L) }

    /** Today's measured milliseconds, live. */
    val flow: Flow<Long> get() = snapshots.map { it.msFor(StudyBlocks.studyDate(LocalDateTime.now()).toEpochDay()) }

    suspend fun snapshot(): Snapshot = snapshots.first()

    suspend fun today(now: LocalDateTime = LocalDateTime.now()): Long = snapshot().msFor(StudyBlocks.studyDate(now).toEpochDay())

    /** Adds [ms] (capped at 60 s per call against clock jumps) to the study day containing [now]. */
    suspend fun add(ms: Long, now: LocalDateTime = LocalDateTime.now()) {
        val delta = ms.coerceIn(0L, 60_000L)
        if (delta == 0L) return
        val day = StudyBlocks.studyDate(now).toEpochDay()
        context.studyStore.edit { p ->
            val cur = if ((p[K.day] ?: -1L) == day) p[K.ms] ?: 0L else 0L
            p[K.day] = day
            p[K.ms] = cur + delta
            p[K.active] = System.currentTimeMillis()
        }
    }
}
