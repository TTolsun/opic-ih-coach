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
        val attempts = listOf(PracticeAttemptEntity(topic = "여행", questionText = "q", mode = "FREE", wordCount = 5, score = 30))
        val st = buildHomeState(today, Settings(surveyTopics = emptySet()), scripts, attempts, emptyList(), emptyList(), 0)
        val travel = st.weak.single { it.topic == "여행" }
        assertEquals(2L, travel.scriptId)
    }

    @Test fun reviewQueueContainsOnlyDuePersonalScriptsAndResumesWithDistinctRemainingScripts() {
        val day = today.toEpochDay()
        val reviewedAt = today.atStartOfDay(com.opicih.coach.data.repo.ExamRepository.KST).toInstant().toEpochMilli()
        val scripts = listOf(
            script(1, "Osaka", 25),
            script(2, "Osaka", 4).copy(lastReviewedAt = reviewedAt),
            script(3, "Home", 2).copy(source = com.opicih.coach.data.db.ContentSource.AI_SAMPLE.name),
            script(4, "Home", 5).copy(dueEpochDay = day + 1),
            script(5, "Travel", 10),
        )
        val log = DailyLogEntity(day, scriptsReviewed = 4)
        val state = buildHomeState(today, Settings(), scripts, emptyList(), listOf(log), emptyList(), 0)
        val review = state.tasks.single { it.task.type == TaskType.REVIEW }
        assertEquals(3, review.task.targetCount)
        assertTrue(!review.done)
        assertEquals(listOf(5L, 1L), state.reviewIds)
    }

    @Test fun completedMorningMovesHighlightWhenDeviceAndExamDatesDiffer() {
        val midnightInKorea = today.atStartOfDay(com.opicih.coach.data.repo.ExamRepository.KST)
        val deviceDay = today.minusDays(1).toEpochDay()
        val state = buildHomeState(
            today, Settings(), listOf(script(1, "여행", 0).copy(dueEpochDay = deviceDay)),
            emptyList(), listOf(DailyLogEntity(deviceDay, morningDone = true)), emptyList(), 0,
            now = midnightInKorea, studyZone = java.time.ZoneOffset.UTC,
        )
        assertTrue(state.tasks.single { it.task.type == TaskType.MORNING }.done)
        assertEquals(TaskType.REVIEW, state.next?.type)
    }

    @Test fun emptyReviewQueueOffersNoReviewTask() {
        val state = buildHomeState(today, Settings(), emptyList(), emptyList(), emptyList(), emptyList(), 0)
        assertTrue(state.reviewIds.isEmpty())
        assertTrue(state.tasks.none { it.task.type == TaskType.REVIEW })
    }

    @Test fun sceneSearchAndComparisonUseTheCurrentPersonalScript() {
        val script = script(8, "Travel", 15).copy(
            sceneText = "We stayed in Osaka.", sceneMeaning = "오사카에 머물렀어요.",
            activeVersion = "SCENE", shortVersion = "We stayed in Osaka.",
        )
        assertTrue(script.matchesSearch("오사카"))
        assertEquals(listOf(com.opicih.coach.data.db.ScriptVersion.ORIGINAL), script.comparisonVersions())
        val question = com.opicih.coach.learning.scriptQuestion(script, com.opicih.coach.data.db.PracticeMode.READ_LISTEN, emptyList())
        assertEquals(script, question.script)
        assertEquals("오사카에 머물렀어요.", question.script?.studyMeaning)
    }

    @Test fun dueScriptsSortedWeakestFirst() {
        val scripts = listOf(script(1, "a", 70), script(2, "b", 10))
        val st = buildHomeState(today, Settings(), scripts, emptyList(), emptyList(), emptyList(), 0)
        assertEquals(listOf(2L, 1L), st.reviewIds)
        assertEquals(TaskType.MORNING, st.next?.type)
    }
}
