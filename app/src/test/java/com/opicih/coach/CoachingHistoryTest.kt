package com.opicih.coach

import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.learning.CoachingEvidence
import com.opicih.coach.learning.CoachingRecord
import com.opicih.coach.learning.CoachingStage
import com.opicih.coach.learning.CoachingTrack
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.WeaknessAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachingHistoryTest {
    private val baseline = "I got a fever on my trip to Japan."

    @Test
    fun inconclusiveCoachedAndRepeatExposureAnswersDoNotAffectIndependentMeasurements() {
        val track = CoachingTrack("stable-id", "travel", CoachingStage.FIRST)
        val evidence = CoachingEvidence(listOf(baseline, null, null), baseline)
        val inconclusive = CoachingRecord(track, null, false, 0, 92)
        val coached = CoachingRecord(track.copy(stage = CoachingStage.RETRY), evidence, true, 0, 92)
        val repeated = CoachingRecord(track.copy(stage = CoachingStage.DELAYED), evidence, false, 1, 95, opened = 2)
        val attempts = listOf(inconclusive, coached, repeated).mapIndexed { index, record ->
            row(index.toLong(), record).copy(
                createdAt = 100L, transcript = baseline, wordCount = 13, durationMs = 6_000, longPauseCount = 3, score = 50,
                questionType = QuestionType.PAST_EXPERIENCE.name,
            )
        }
        assertTrue(WeaknessAnalyzer.analyze(attempts, emptyList(), emptyList(), 100L).isEmpty())
        assertEquals(0, ProgressCalculator.ihReadiness(emptyList(), attempts))
        assertEquals(0, ProgressCalculator.readiness(attempts, emptyList(), 0, 0).recentAnswers)
        assertTrue(ProgressCalculator.weakTopics(attempts, emptySet()).isEmpty())
        val original = attempts.mapIndexed { index, attempt ->
            attempt.copy(metricsJson = row(index.toLong(), inconclusive.copy(evidence = evidence)).metricsJson)
        }
        assertTrue(WeaknessAnalyzer.analyze(original, emptyList(), emptyList(), 100L).isNotEmpty())
        assertEquals(3, ProgressCalculator.readiness(original, emptyList(), 0, 0).recentAnswers)
    }

    private fun row(id: Long, record: CoachingRecord): PracticeAttemptEntity {
        val metrics = AttemptMetrics(DiscourseAnalyzer.analyze(""), coaching = record)
        return PracticeAttemptEntity(
            id = id, topic = "test", questionText = "test", mode = "FREE",
            metricsJson = appJson.encodeToString(AttemptMetrics.serializer(), metrics),
        )
    }
}
