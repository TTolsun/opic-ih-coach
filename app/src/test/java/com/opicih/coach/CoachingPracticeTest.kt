package com.opicih.coach

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.CoachingRun
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.data.repo.coachingEntries
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.learning.CoachingPrompts
import com.opicih.coach.learning.CoachingRecord
import com.opicih.coach.learning.CoachingStage
import com.opicih.coach.learning.CoachingTrack
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.WeaknessAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoachingPracticeTest {
    private val travel = CoachingPrompts.practice.first { it.type == QuestionType.PAST_EXPERIENCE }
    private val baseline = "I got a fever on my trip to Japan. I went to a pharmacy."
    private val retry = "$baseline I felt better the next day."

    @Test
    fun oneActionTargetsFirstMissingTaskAndQuotesActualPhrases() {
        val before = requireNotNull(travel.evidence(baseline, "LIVE_STT", 6_000))
        val after = requireNotNull(travel.evidence(retry, "LIVE_STT", 7_000))
        assertEquals(listOf("I got a fever on my trip to Japan.", "I went to a pharmacy.", null), before.parts)
        assertEquals(travel.actions.last(), travel.nextAction(before))
        assertEquals("I felt better the next day.", after.parts.last())
        assertNotEquals(travel.nextAction(before), travel.nextAction(after))
    }

    @Test
    fun judgmentRequiresSpeechAndTaskEvidence() {
        assertNull(travel.evidence(retry, "TYPED", 7_000))
        assertNull(travel.evidence(retry, "LIVE_STT", 0))
        assertNull(travel.evidence("", "RECORD_ONLY", 7_000))
        assertNull(travel.evidence(travel.question, "LIVE_STT", 7_000))
        assertNull(travel.evidence("I finally called my friend yesterday.", "LIVE_STT", 3_000))
        val outOfOrder = requireNotNull(travel.evidence(
            "I asked a friend to meet me. I got a fever. I felt better later.", "LIVE_STT", 7_000,
        ))
        assertEquals(listOf("I got a fever.", null, null), outOfOrder.parts)
    }

    @Test
    fun comparisonChecksBothTimeFramesAndDifference() {
        val furniture = CoachingPrompts.practice.first { it.type == QuestionType.COMPARISON }
        val evidence = requireNotNull(furniture.evidence(
            "As a child I used a wooden desk. Now my desk is made of metal. The main difference is the material.",
            "CLOUD_STT", 7_000,
        ))
        assertEquals(3, evidence.parts.count { it != null })
    }

    @Test
    fun unpunctuatedDeviceTranscriptCanShowTheEventSequence() {
        val evidence = requireNotNull(travel.evidence(
            "i got food poisoning while traveling so i went to a clinic after that i got better",
            "LIVE_STT", 7_000,
        ))
        assertEquals(listOf(
            "i got food poisoning while traveling", "so i went to a clinic", "after that i got better",
        ), evidence.parts)
    }

    @Test
    fun assessmentReservationKeepsSameSkillAndAvoidsSeenAndAssignedPrompts() {
        val type = QuestionType.PAST_EXPERIENCE
        val first = requireNotNull(CoachingPrompts.reserve(type, emptySet(), emptySet()))
        assertEquals(type, first.type)
        val second = requireNotNull(CoachingPrompts.reserve(type, emptySet(), setOf(TextNormalizer.normalize(first.question))))
        assertNotEquals(first.id, second.id)
        assertNull(CoachingPrompts.reserve(type, setOf(first.id, second.id), emptySet()))
    }

    @Test
    fun serializedLinksSurviveAttemptIdChangesAndInconclusiveChecksDoNotCompleteRun() {
        val firstTrack = CoachingTrack("stable-id", travel.id, CoachingStage.FIRST)
        val first = CoachingRecord(firstTrack, travel.evidence(baseline, "LIVE_STT", 6_000), false, 1, 92)
        val second = CoachingRecord(
            firstTrack.copy(stage = CoachingStage.RETRY), travel.evidence(retry, "LIVE_STT", 7_000), true, 0, 92,
            dueDay = 95, assessmentId = "theater", opened = 2,
        )
        val delayed = CoachingRecord(firstTrack.copy(promptId = "theater", stage = CoachingStage.DELAYED), null, false, 1, 97)
        val entries = coachingEntries(listOf(row(37, delayed), row(36, second), row(35, first)))
        val run = requireNotNull(CoachingRun.from(entries))
        assertEquals(35L, run.first.attempt.id)
        assertNotNull(run.retry)
        assertEquals(95L, run.retry?.record?.dueDay)
        assertEquals(2, run.retry?.record?.opened)
        assertNull(run.delayed)
    }

    @Test
    fun delayedRetryAfterAnInconclusiveAnswerStillCountsAsSeen() {
        val track = CoachingTrack("stable-id", travel.id, CoachingStage.RETRY)
        val reservation = CoachingRecord(track, null, true, 0, 92, dueDay = 95, assessmentId = "theater", opened = 1)
        assertEquals(1, reservation.delayedExposure(previousSubmissions = 0))
        assertEquals(2, reservation.delayedExposure(previousSubmissions = 1))
        assertEquals(2, reservation.copy(assessmentSubmitted = true).delayedExposure(previousSubmissions = 0))
        assertEquals(3, reservation.copy(opened = 3).delayedExposure(previousSubmissions = 0))
    }

    @Test
    fun inconclusiveCoachedAndRepeatExposureAnswersDoNotAffectIndependentMeasurements() {
        val track = CoachingTrack("stable-id", travel.id, CoachingStage.FIRST)
        val evidence = travel.evidence(baseline, "LIVE_STT", 6_000)
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
