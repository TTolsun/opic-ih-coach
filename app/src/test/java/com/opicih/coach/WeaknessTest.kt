package com.opicih.coach

import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.data.db.ErrorPatternEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.learning.ConversationReport
import com.opicih.coach.learning.Focus
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.SharedSpeakingCompetency
import com.opicih.coach.learning.WeaknessAnalyzer
import com.opicih.coach.learning.WeaknessPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeaknessAnalyzerTest {
    private val now = 1_794_100_000_000L
    private val day = 86_400_000L

    private fun attempt(text: String, pauses: Int = 0, type: QuestionType = QuestionType.OTHER, mode: String = "FREE", ageDays: Int = 1, capture: String = "LIVE_STT") =
        PracticeAttemptEntity(
            topic = "T", questionText = "Q", questionType = type.name, mode = mode, transcript = text, captureMode = capture,
            durationMs = 60_000, longPauseCount = pauses, createdAt = now - ageDays * day,
            metricsJson = appJson.encodeToString(AttemptMetrics.serializer(), AttemptMetrics(DiscourseAnalyzer.analyze(text))),
        )

    private val longAnswer = "I usually go hiking on weekends because it helps me relax. For example, last month I climbed Bukhansan with my friends, " +
        "and after that we had a big lunch. So I think hiking is the best way to spend my weekend. Also, it is good for my health, " +
        "and I can talk with my friends a lot. Actually, I want to climb Hallasan next year because it is the highest mountain in Korea."

    @Test fun frequentLongPausesMeanFluency() {
        val w = WeaknessAnalyzer.analyze(List(4) { attempt(longAnswer, pauses = 2) }, emptyList(), emptyList(), now)
        assertEquals(SharedSpeakingCompetency.FLUENCY, w.first().competency)
        assertTrue(w.first().evidence.contains("2.0회"))
    }

    @Test fun tooFewSamplesChangeNothing() {
        assertTrue(WeaknessAnalyzer.analyze(List(2) { attempt("short answer", pauses = 4) }, emptyList(), emptyList(), now).isEmpty())
    }

    @Test fun oldAndTypedRecordsAreIgnoredForTiming() {
        val old = List(4) { attempt(longAnswer, pauses = 3, ageDays = 30) }
        val typed = List(4) { attempt(longAnswer, pauses = 3, capture = "TYPED") }
        assertTrue(WeaknessAnalyzer.analyze(old + typed, emptyList(), emptyList(), now).none { it.competency == SharedSpeakingCompetency.FLUENCY })
    }

    @Test fun shortAnswersMeanCoherence() {
        val w = WeaknessAnalyzer.analyze(List(3) { attempt("I like movies. It is fun.") }, emptyList(), emptyList(), now)
        assertTrue(w.any { it.competency == SharedSpeakingCompetency.COHERENCE })
    }

    @Test fun presentTensePastStoriesMeanStorytelling() {
        val text = "I go to Jeju with my family. We eat a lot of fish and we swim every day. It is really fun and I like it so much because the sea is beautiful."
        val w = WeaknessAnalyzer.analyze(List(3) { attempt(text, type = QuestionType.PAST_EXPERIENCE) }, emptyList(), emptyList(), now)
        assertTrue(w.any { it.competency == SharedSpeakingCompetency.STORYTELLING })
    }

    @Test fun repeatedGrammarErrors() {
        val errors = listOf(ErrorPatternEntity(key = "go-went", category = "GRAMMAR", example = "I go yesterday", correction = "I went yesterday", count = 4, lastSeen = now - day))
        val w = WeaknessAnalyzer.analyze(emptyList(), errors, emptyList(), now)
        assertEquals(SharedSpeakingCompetency.GRAMMAR, w.single().competency)
        assertTrue(w.single().evidence.contains("I went yesterday"))
    }

    @Test fun slowStartsInConversationsMeanSpontaneousResponse() {
        val report = ConversationReport(turns = 4, avgLatencyMs = 3500, slowStarts = 3, totalSpeechMs = 0, avgWords = 50, longPauses = 0,
            connectorsPerAnswer = 2f, repeatedPhrases = emptyList(), onTopicRatio = 0.4f, strengths = emptyList(), fixes = emptyList(), weakCompetencies = emptyList())
        val w = WeaknessAnalyzer.analyze(emptyList(), emptyList(), listOf(report), now)
        assertTrue(w.any { it.competency == SharedSpeakingCompetency.SPONTANEOUS_RESPONSE })
        assertTrue(w.any { it.competency == SharedSpeakingCompetency.FOLLOWUP_HANDLING })
    }
}

class WeaknessPlannerTest {
    private val now = 1_794_100_000_000L
    private val fluency = WeaknessAnalyzer.analyze(
        List(4) { PracticeAttemptEntity(topic = "T", questionText = "Q", mode = "FREE", transcript = "x", durationMs = 60_000, longPauseCount = 3, createdAt = now) },
        emptyList(), emptyList(), now,
    )

    @Test fun sailDayRetargetsTheWeakPointSlot() {
        val p = PlanGenerator.forExam(LocalDate.of(2026, 11, 7), ExamType.SAIL, LocalDate.of(2026, 11, 11), 30, 3, false, fluency)
        assertEquals(30, p.totalMinutes)
        assertEquals(SharedSpeakingCompetency.FLUENCY.practiceTitle, p.tasks[3].title)
        assertEquals(SharedSpeakingCompetency.FLUENCY, p.weakness?.competency)
        assertEquals("SAIL_CONVERSATION", p.tasks[2].mode) // the SAIL core task is untouched
    }

    @Test fun dayBeforeAndExamDayStayLight() {
        val d1 = PlanGenerator.forExam(LocalDate.of(2026, 11, 10), ExamType.SAIL, LocalDate.of(2026, 11, 11), 30, 3, false, fluency)
        assertNull(d1.weakness)
        assertNull(PlanGenerator.forExam(LocalDate.of(2026, 11, 11), ExamType.SAIL, LocalDate.of(2026, 11, 11), 30, 3, false, fluency).weakness)
    }

    @Test fun noExamPicksTheFocusFromTheWeakness() {
        val p = PlanGenerator.forExam(LocalDate.of(2026, 11, 12), null, null, 30, 3, false, fluency)
        assertEquals(Focus.LONG_FLUENCY, p.focus)
        assertEquals(WeaknessPlanner.longCycleFocus(fluency), Focus.LONG_FLUENCY)
    }

    @Test fun withoutDataThePlanIsUnchanged() {
        val a = PlanGenerator.forExam(LocalDate.of(2026, 11, 7), ExamType.SAIL, LocalDate.of(2026, 11, 11), 30, 3, false)
        val b = PlanGenerator.forExam(LocalDate.of(2026, 11, 7), ExamType.SAIL, LocalDate.of(2026, 11, 11), 30, 3, false, emptyList())
        assertEquals(a, b)
        assertNull(a.weakness)
    }
}
