package com.opicih.coach

import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.MasteryState
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.seed.SeedContent
import com.opicih.coach.data.seed.SeedScripts
import com.opicih.coach.learning.Focus
import com.opicih.coach.learning.MasteryRules
import com.opicih.coach.learning.MockTestBuilder
import com.opicih.coach.learning.MorningRoutineBuilder
import com.opicih.coach.learning.MorningStep
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.QuestionSource
import com.opicih.coach.learning.SrsScheduler
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SrsSchedulerTest {
    private val today = LocalDate.of(2026, 11, 7).toEpochDay()

    @Test fun ladderAndReset() {
        var r = SrsScheduler.next(0, true, today, null)
        assertEquals(1, r.step); assertEquals(today + 1, r.dueEpochDay)
        r = SrsScheduler.next(r.step, true, today, null)
        assertEquals(today + 2, r.dueEpochDay)
        r = SrsScheduler.next(4, true, today, null)
        assertEquals(today + 14, r.dueEpochDay)
        r = SrsScheduler.next(3, false, today, null)
        assertEquals(0, r.step); assertEquals(today + 1, r.dueEpochDay)
    }

    @Test fun clampsBeforeExam() {
        val exam = LocalDate.of(2026, 11, 14).toEpochDay()
        val r = SrsScheduler.next(4, true, today, exam)
        assertEquals(exam - 1, r.dueEpochDay)
    }

    @Test fun successRules() {
        assertTrue(SrsScheduler.isSuccess(PracticeMode.BLIND, 0.65f, 0))
        assertFalse(SrsScheduler.isSuccess(PracticeMode.READ_LISTEN, 0.65f, 0))
        assertTrue(SrsScheduler.isSuccess(PracticeMode.VARIATION, null, 70))
    }
}

class MasteryRulesTest {
    @Test fun progression() {
        var u = MasteryRules.apply(MasteryState.NEW, 0, PracticeMode.KEYWORD, 0.5f, 40)
        assertEquals(MasteryState.LEARNING, u.state)
        u = MasteryRules.apply(u.state, u.score, PracticeMode.BLIND, 0.75f, 60)
        assertEquals(MasteryState.NO_SCRIPT_OK, u.state)
        u = MasteryRules.apply(u.state, u.score, PracticeMode.VARIATION, null, 70)
        assertEquals(MasteryState.VARIATION_OK, u.state)
        u = MasteryRules.apply(u.state, u.score, PracticeMode.BLIND, 0.2f, 20)
        assertEquals(MasteryState.NEEDS_REVIEW, u.state)
        assertTrue(u.score in 0..100)
    }
}

class PlanGeneratorTest {
    private val exam = LocalDate.of(2026, 11, 14)

    @Test fun sevenDayTemplate() {
        val f = PlanGenerator.sprintFocuses(7)
        assertEquals(listOf(Focus.DIAGNOSE, Focus.CORE, Focus.PAST_COMPARE, Focus.ROLEPLAY, Focus.FREE, Focus.MOCK, Focus.LIGHT), f)
        assertEquals(Focus.DIAGNOSE, PlanGenerator.today(LocalDate.of(2026, 11, 7), exam, 30, 3, false).focus)
        assertEquals("D-7", PlanGenerator.dLabel(LocalDate.of(2026, 11, 7), exam))
    }

    @Test fun missedDaysReprioritizeInsteadOfShifting() {
        // Only 3 days left: keep the highest-priority work, mock at D-2, light review at D-1.
        val f = PlanGenerator.sprintFocuses(3)
        assertEquals(listOf(Focus.FREE, Focus.MOCK, Focus.LIGHT), f)
        // Mock already done: the slot goes to another high-priority focus.
        assertEquals(listOf(Focus.FREE, Focus.PAST_COMPARE, Focus.LIGHT).toSet(), PlanGenerator.sprintFocuses(3, mockDone = true).toSet())
        assertEquals(listOf(Focus.LIGHT), PlanGenerator.sprintFocuses(1))
    }

    @Test fun moreThanSevenDaysPadsWithCore() {
        val f = PlanGenerator.sprintFocuses(9)
        assertEquals(9, f.size)
        assertEquals(Focus.CORE, f[0]); assertEquals(Focus.LIGHT, f.last())
    }

    @Test fun afterExamLongCycleAndLabels() {
        val p = PlanGenerator.today(LocalDate.of(2026, 11, 16), exam, 60, 0, true)
        assertEquals("D+2", p.dLabel)
        assertTrue(p.focus.name.startsWith("LONG") || p.focus == Focus.FREE || p.focus == Focus.MOCK)
        assertEquals("D-Day", PlanGenerator.dLabel(exam, exam))
    }

    @Test fun taskMinutesScaleWithLevel() {
        val quick = PlanGenerator.tasksFor(Focus.FREE, 10, 5).sumOf { it.minutes }
        val intense = PlanGenerator.tasksFor(Focus.FREE, 60, 5).sumOf { it.minutes }
        assertTrue("quick=$quick", quick in 8..14)
        assertTrue("intense=$intense", intense in 50..70)
    }

}

class AlarmSchedulerTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test fun nextTriggerTodayOrTomorrow() {
        val early = ZonedDateTime.of(2026, 11, 7, 6, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 7, 7, 0, 0, 0, zone), AlarmScheduler.nextTrigger(early, 7, 0))
        val late = ZonedDateTime.of(2026, 11, 7, 7, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 7, 0, 0, 0, zone), AlarmScheduler.nextTrigger(late, 7, 0))
        val endOfMonth = ZonedDateTime.of(2026, 11, 30, 23, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 12, 1, 7, 0, 0, 0, zone), AlarmScheduler.nextTrigger(endOfMonth, 7, 0))
    }
}

class ProgressCalculatorTest {
    private val today = 20_000L

    @Test fun streakCountsConsecutiveSpeakingDays() {
        val logs = listOf(
            DailyLogEntity(today, speakingMs = 120_000), DailyLogEntity(today - 1, speakingMs = 90_000),
            DailyLogEntity(today - 2, speakingMs = 10_000), DailyLogEntity(today - 3, speakingMs = 200_000),
        )
        assertEquals(2, ProgressCalculator.streak(logs, today))
        // Today not done yet: streak continues from yesterday.
        assertEquals(1, ProgressCalculator.streak(logs.drop(1), today))
    }

    @Test fun completionIsBasedOnRecordedWork() {
        assertEquals(0f, ProgressCalculator.todayCompletion(null, 30, 2), 0.001f)
        val full = DailyLogEntity(today, speakingMs = 20 * 60_000, scriptsReviewed = 3, morningDone = true)
        assertEquals(1f, ProgressCalculator.todayCompletion(full, 30, 2), 0.001f)
        val half = DailyLogEntity(today, speakingMs = 6 * 60_000)
        assertEquals(0.3f, ProgressCalculator.todayCompletion(half, 30, 2), 0.01f)
    }
}

class MorningRoutineBuilderTest {
    private fun chunk(id: Long, result: String = "NONE") = ChunkEntity(id = id, phrase = "Looking back,", meaningKo = "돌이켜 보면", example = "Looking back, it was fun.", category = "x", lastResult = result, lastReviewedEpochDay = 1)

    @Test fun buildsStepsInOrder() {
        val chunks = (1L..3L).map { chunk(it, "FAIL") } + (4L..20L).map { chunk(it) }
        val items = MorningRoutineBuilder.build(chunks, 100, seed = 1)
        assertEquals(3, items.count { it.step == MorningStep.WRONG })
        assertEquals(5, items.count { it.step == MorningStep.NEW })
        assertEquals(3, items.count { it.step == MorningStep.COMPLETE })
        assertEquals(2, items.count { it.step == MorningStep.PRONOUNCE })
    }

    @Test fun clozeBlanksThePhrase() {
        assertEquals("_____ it was fun.", MorningRoutineBuilder.cloze(chunk(1)))
    }
}

class MockTestBuilderTest {
    private val pool = buildList {
        add(QuestionSource("Self Introduction", "Tell me about yourself.", "SELF_INTRO", "AI"))
        listOf("Movies", "Park & Music", "Overseas Travel", "Banks", "Technology").forEach { t ->
            listOf("DESCRIPTION", "ROUTINE", "PAST_EXPERIENCE", "COMPARISON").forEach { ty -> add(QuestionSource(t, "$t $ty question?", ty, "AI")) }
        }
        listOf("ROLEPLAY", "ROLEPLAY", "PAST_EXPERIENCE").forEachIndexed { i, ty -> add(QuestionSource("Role-play: Tickets", "Role-play $i?", ty, "AI")) }
        repeat(3) { add(QuestionSource("Internet", "Issue $it?", "ISSUE", "AI")) }
    }

    @Test fun fifteenQuestionStructure() {
        val q = MockTestBuilder.build(pool, setOf("Movies", "Park & Music", "Overseas Travel"), 15, 5, seed = 7)
        assertEquals(15, q.size)
        assertEquals("SELF_INTRO", q.first().type)
        assertEquals(q.size, q.map { it.text }.distinct().size)
        assertTrue(q.any { it.stage == "롤플레이" })
        assertEquals((1..15).toList(), q.map { it.no })
    }

    @Test fun twelveQuestionsAndReadjust() {
        val q = MockTestBuilder.build(pool, setOf("Movies", "Park & Music"), 12, 4, seed = 3)
        assertEquals(12, q.size)
        val r = MockTestBuilder.readjust(q, 7, pool, setOf("Movies", "Park & Music"), 4, MockTestBuilder.Adjust.HARDER)
        assertEquals(12, r.size)
        assertEquals(q.take(7), r.take(7))
    }

    @Test fun miniMockBuildsSevenUniqueQuestions() {
        val questions = MockTestBuilder.build(pool, setOf("Movies", "Park & Music"), MockTestBuilder.MINI_QUESTION_COUNT, 4, seed = 3)

        assertEquals((1..7).toList(), questions.map { it.no })
        assertEquals(7, questions.map { it.text }.distinct().size)
        assertEquals("SELF_INTRO", questions.first().type)
    }
}

/** The bundled seed files must stay parseable (they are loaded on first launch). */
class SeedAssetsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun asset(name: String) = File("src/main/assets/seed/$name").takeIf { it.exists() } ?: File("app/src/main/assets/seed/$name")

    @Test fun userScriptsSeed() {
        val s = json.decodeFromString(SeedScripts.serializer(), asset("user_scripts_2026.json").readText())
        assertEquals(23, s.scripts.size)
        assertTrue(s.scripts.all { it.original.isNotBlank() && it.ihTarget.isNotBlank() && it.keywords.size >= 4 && it.reasonKo.isNotBlank() })
        // The pilot deliberately uses shorter targets based on the original experience.
        s.scripts.filterNot { it.refreshRevisions }.forEach { sc ->
            val n = sc.ihTarget.split(Regex("\\s+")).size
            assertTrue("${sc.title} ihTarget words=$n", n in 90..170)
        }
    }

    @Test fun contentSeed() {
        val c = json.decodeFromString(SeedContent.serializer(), asset("content.json").readText())
        assertEquals(7, c.stories.size)
        assertTrue(c.chunks.size >= 40)
        assertTrue(c.questions.size >= 70)
        assertTrue(c.questions.all { it.source == "USER_LIST" || it.source == "AI_GENERATED" })
    }
}

class DeleteAttemptLogTest {
    @Test fun removesSpeakingTimeAndCounts() {
        val log = DailyLogEntity(100, speakingMs = 90_000, attempts = 3, scriptsReviewed = 1)
        val a = com.opicih.coach.data.db.PracticeAttemptEntity(scriptId = 5, topic = "t", questionText = "q", mode = "BLIND", speechMs = 30_000, durationMs = 40_000)
        val r = ProgressCalculator.withoutAttempt(log, a)
        assertEquals(60_000, r.speakingMs); assertEquals(2, r.attempts); assertEquals(0, r.scriptsReviewed)
        // Typed answers have no speech time; never below zero.
        val typed = a.copy(scriptId = null, speechMs = 0, durationMs = 0)
        assertEquals(0, ProgressCalculator.withoutAttempt(DailyLogEntity(100), typed).attempts)
    }
}
