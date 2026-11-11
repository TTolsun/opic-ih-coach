package com.opicih.coach

import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.PauseMetrics
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.RetestAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessEvidenceTest {
    private val speech = PracticeAttemptEntity(
        topic = "Movies", questionText = "Tell me about a movie.", mode = "FREE",
        captureMode = "LIVE_STT", wordCount = 110, score = 75, createdAt = 10,
    )

    @Test fun typedAnswersDoNotCountTowardHomeOrExamReadiness() {
        val typed = List(3) { speech.copy(captureMode = "TYPED", score = 99) }
        val readiness = readiness(typed)
        assertEquals(0, readiness.recentAnswers)
        assertEquals(0, readiness.avgWords)
        assertNull(readiness.avgScore)
        assertNull(readiness.avgLongPauses)
        assertEquals(0, ProgressCalculator.ihReadiness(emptyList(), typed))
        assertTrue(RetestAdvisor.gates(readiness).filter { it.id in setOf("length", "score", "fluency") }.none { it.passed })
    }

    @Test fun missingAcousticsStayMissingAndMeasuredZeroPausesCount() {
        val missing = listOf(speech, speech.copy(metricsJson = metrics()), speech.copy(metricsJson = "{"))
        val silent = speech.copy(captureMode = "RECORD_ONLY", wordCount = 0, metricsJson = metrics(pauses = PauseMetrics(5_000, 0, 0, 0, 5_000)))
        val noMeasurement = readiness(missing + silent)
        assertEquals(3, noMeasurement.recentAnswers)
        assertEquals(110, noMeasurement.avgWords)
        assertNull(noMeasurement.avgLongPauses)
        assertFalse(RetestAdvisor.gates(noMeasurement).first { it.id == "fluency" }.passed)

        val measured = speech.copy(longPauseCount = 0, metricsJson = metrics(pauses = PauseMetrics(5_000, 4_000, 0, 0, 0)))
        val zeroPauses = readiness(missing + silent + measured)
        assertEquals(0f, zeroPauses.avgLongPauses)
        assertTrue(RetestAdvisor.gates(zeroPauses).first { it.id == "fluency" }.passed)

        val audioOnly = silent.copy(longPauseCount = 3, metricsJson = metrics(pauses = PauseMetrics(10_000, 4_000, 3, 2_000, 0)))
        val withAudioOnly = readiness(missing + measured + audioOnly)
        assertEquals(4, withAudioOnly.recentAnswers)
        assertEquals(1.5f, withAudioOnly.avgLongPauses)
        assertFalse(RetestAdvisor.gates(withAudioOnly).first { it.id == "fluency" }.passed)
    }

    @Test fun readinessUsesOnlyComparableLocalScoresAndHandlesOldAiRows() {
        val cloud = speech.copy(captureMode = "CLOUD_STT", score = 99)
        val oldAi = speech.copy(feedbackSource = "AI", score = 100)
        val newAi = oldAi.copy(metricsJson = metrics(localScore = 45))
        val live = speech.copy(score = 60)
        val recent = listOf(cloud, cloud, oldAi, newAi, live, live)
        val r = readiness(recent)
        assertEquals(6, r.recentAnswers)
        assertEquals(3, r.scoredAnswers)
        assertEquals("LIVE_STT", r.scoreCapture)
        assertEquals(55, r.avgScore)
        assertFalse(RetestAdvisor.gates(r).first { it.id == "score" }.passed)
        assertEquals(30, ProgressCalculator.ihReadiness(emptyList(), recent))
        assertNull(readiness(List(3) { oldAi }).avgScore)
    }

    @Test fun completedMocksWithoutEnoughScorableAnswersHaveNoScore() {
        val mock = MockTestEntity(id = 1, startedAt = 1, finishedAt = 20, questionCount = 5, selfLevel = 4)
        val unfinished = mock.copy(id = 2, finishedAt = null)
        val answer = speech.copy(mode = "MOCK", mockId = 1)
        val audioOnly = answer.copy(captureMode = "RECORD_ONLY", wordCount = 0, score = 0)
        val partial = List(4) { answer } + audioOnly + List(5) { answer.copy(mockId = 2) }
        val r = readiness(partial, listOf(mock, unfinished))
        assertEquals(1, r.mocksSinceExam)
        assertEquals(4, r.scorableMockAnswers)
        assertNull(r.bestMockScore)
        assertFalse(RetestAdvisor.gates(r).first { it.id == "mock" }.passed)
        assertEquals(75, readiness(partial + answer, listOf(mock, unfinished)).bestMockScore)
        assertEquals(0, readiness(List(5) { answer.copy(score = 0) }, listOf(mock)).bestMockScore)
    }

    @Test fun flowScoreDoesNotAlsoPassTheTaskScoreGate() {
        val noPauses = PauseMetrics(5_000, 4_000, 0, 0, 0)
        val flow = speech.copy(score = 100, metricsJson = metrics(pauses = noPauses, localScore = 100, speakingFlow = true))
        val r = readiness(List(3) { flow } + List(2) { speech })
        assertTrue(r.scoreIsFlow)
        assertEquals(3, r.scoredAnswers)
        assertEquals(100, r.avgScore)
        val gates = RetestAdvisor.gates(r)
        assertTrue(gates.first { it.id == "fluency" }.passed)
        assertFalse(gates.first { it.id == "score" }.passed)
    }

    private fun readiness(attempts: List<PracticeAttemptEntity>, mocks: List<MockTestEntity> = emptyList()) =
        ProgressCalculator.readiness(attempts, emptyList(), 0, 0, mocks)

    private fun metrics(pauses: PauseMetrics? = null, localScore: Int? = null, speakingFlow: Boolean = false): String =
        appJson.encodeToString(AttemptMetrics.serializer(), AttemptMetrics(DiscourseAnalyzer.analyze("I watched a movie."), pauses,
            localScore = localScore, speakingFlow = speakingFlow))
}
