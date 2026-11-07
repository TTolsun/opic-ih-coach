package com.opicih.coach

import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.TaskType
import com.opicih.coach.learning.TodayProgress
import com.opicih.coach.ui.screens.buildHomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TodayProgressTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val plan = PlanGenerator.today(today, LocalDate.of(2026, 11, 14), 30, 3, false)

    @Test fun nothingDoneMeansMorningFirst() {
        val st = TodayProgress.statuses(plan, null)
        assertTrue(st.none { it.done })
        assertEquals(TaskType.MORNING, TodayProgress.next(st)?.type)
    }

    @Test fun nextSkipsFinishedTasksInPlanOrder() {
        val goal = TodayProgress.reviewGoal(plan)
        val log = DailyLogEntity(today.toEpochDay(), morningDone = true, scriptsReviewed = goal, attempts = goal)
        val st = TodayProgress.statuses(plan, log)
        assertTrue(st.first { it.task.type == TaskType.MORNING }.done)
        assertTrue(st.first { it.task.type == TaskType.REVIEW }.done)
        // Review attempts must not also count toward the speaking task.
        assertEquals(plan.tasks[2], TodayProgress.next(st))
    }

    @Test fun allDoneHasNoNext() {
        val log = DailyLogEntity(today.toEpochDay(), morningDone = true, scriptsReviewed = 99, attempts = 99, mockDone = true)
        assertNull(TodayProgress.next(TodayProgress.statuses(plan, log)))
    }
}

class HomeStateTest {
    private val today = LocalDate.of(2026, 11, 7)
    private fun script(id: Long, topic: String, mastery: Int) =
        ScriptEntity(id = id, topic = topic, title = "t$id", question = "q", original = "o", masteryScore = mastery, dueEpochDay = today.toEpochDay())

    @Test fun weakTopicOpensWeakestScriptOfThatTopic() {
        val scripts = listOf(script(1, "여행", 80), script(2, "여행", 20), script(3, "음악", 50))
        val attempts = listOf(PracticeAttemptEntity(topic = "여행", questionText = "q", mode = "FREE", score = 30))
        val st = buildHomeState(today, Settings(surveyTopics = emptySet()), scripts, attempts, emptyList(), emptyList(), 0)
        val travel = st.weak.single { it.topic == "여행" }
        assertEquals(2L, travel.scriptId)
    }

    @Test fun dueScriptsSortedWeakestFirst() {
        val scripts = listOf(script(1, "a", 70), script(2, "b", 10))
        val st = buildHomeState(today, Settings(), scripts, emptyList(), emptyList(), emptyList(), 0)
        assertEquals(listOf(2L, 1L), st.due.map { it.id })
        assertEquals(TaskType.MORNING, st.next?.type)
    }
}
