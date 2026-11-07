package com.opicih.coach.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opicih.coach.App
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.DayPlan
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.PlanTask
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.TaskStatus
import com.opicih.coach.learning.TodayProgress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class WeakTopic(val topic: String, val scriptId: Long?)

data class HomeState(
    val loaded: Boolean = false,
    val examDate: LocalDate = LocalDate.now(),
    val plan: DayPlan? = null,
    val tasks: List<TaskStatus> = emptyList(),
    val next: PlanTask? = null,
    val completion: Float = 0f,
    val streak: Int = 0,
    val readiness: Int = 0,
    val totalSpeakingMin: Long = 0,
    val due: List<ScriptEntity> = emptyList(),
    val weak: List<WeakTopic> = emptyList(),
    val pendingRevisions: Int = 0,
    val lastMock: MockTestEntity? = null,
    val alarmNeedsExact: Boolean = false,
)

/** Pure: everything the home screen shows, derived from stored data. No Android types, so it is unit-tested. */
fun buildHomeState(
    today: LocalDate,
    s: Settings,
    scripts: List<ScriptEntity>,
    attempts: List<PracticeAttemptEntity>,
    logs: List<DailyLogEntity>,
    mocks: List<MockTestEntity>,
    pendingRevisions: Int,
): HomeState {
    val day = today.toEpochDay()
    val due = scripts.filter { it.dueEpochDay <= day }.sortedBy { it.masteryScore }
    val mockDone = logs.any { it.mockDone && it.epochDay >= day - 7 }
    val plan = PlanGenerator.today(today, s.examDate, s.studyLevel.minutes, due.size, mockDone)
    val log = logs.firstOrNull { it.epochDay == day }
    val tasks = TodayProgress.statuses(plan, log)
    val weak = ProgressCalculator.weakTopics(attempts, s.surveyTopics).map { t ->
        // Weakest script of that topic, so a tap opens something concrete to practice.
        WeakTopic(t, scripts.filter { it.topic.equals(t, true) }.minByOrNull { it.masteryScore }?.id)
    }
    return HomeState(
        loaded = true,
        examDate = s.examDate,
        plan = plan,
        tasks = tasks,
        next = TodayProgress.next(tasks),
        completion = ProgressCalculator.todayCompletion(log, s.studyLevel.minutes, TodayProgress.reviewGoal(plan)),
        streak = ProgressCalculator.streak(logs, day),
        readiness = ProgressCalculator.ihReadiness(scripts, attempts),
        totalSpeakingMin = attempts.sumOf { if (it.speechMs > 0) it.speechMs else it.durationMs } / 60_000,
        due = due,
        weak = weak,
        pendingRevisions = pendingRevisions,
        lastMock = mocks.firstOrNull(),
        alarmNeedsExact = s.alarmEnabled && s.useExactAlarm,
    )
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container

    private val stored = combine(c.dao.scriptsFlow(), c.dao.attemptsFlow(), c.dao.dailyLogsFlow()) { s, a, l -> Triple(s, a, l) }

    val state: StateFlow<HomeState> = combine(
        c.settings.flow, stored, c.dao.mocksFlow(), c.dao.pendingRevisionsFlow(),
    ) { s, (scripts, attempts, logs), mocks, pending ->
        buildHomeState(LocalDate.now(), s, scripts, attempts, logs, mocks, pending.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
