package com.opicih.coach

import com.opicih.coach.ai.DiagnosticAssessment
import com.opicih.coach.ai.GeminiTranscriber
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.learning.DiagnosticCriterion
import com.opicih.coach.learning.DiagnosticObservation
import com.opicih.coach.learning.DiagnosticReport
import com.opicih.coach.learning.DiagnosticSession
import com.opicih.coach.learning.DiagnosticStatus
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.ScoreKind
import com.opicih.coach.learning.SpeakingDiagnostic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakingDiagnosticTest {
    private val type = QuestionType.PAST_EXPERIENCE
    private val shortAnswer = "I lost my wallet. I retraced my steps and found it on the bus."
    private val offTopic = "My kitchen has a big window overlooking the garden. There is a table for six beside it, and every morning the sun lights up the whole room."

    @Test fun `new diagnostic selects five different task types and excludes exposed texts`() {
        val first = checkNotNull(SpeakingDiagnostic.remaining(emptySet(), emptyList()))
        assertEquals(SpeakingDiagnostic.types, first.map { it.type })
        val exposed = first.map { "  ${it.text.uppercase()}  " }
        val next = checkNotNull(SpeakingDiagnostic.remaining(emptySet(), exposed))
        assertEquals(first.map { it.type }, next.map { it.type })
        assertTrue(first.zip(next).all { (used, new) -> used.text != new.text })
        val rest = SpeakingDiagnostic.remaining(setOf(QuestionType.DESCRIPTION), exposed)
        assertEquals(next.drop(1), rest)
        val exhausted = SpeakingDiagnostic.questions.getValue(QuestionType.DESCRIPTION)
        assertNull(SpeakingDiagnostic.remaining(emptySet(), exhausted))
        assertEquals(next.size - 1, SpeakingDiagnostic.remaining(setOf(QuestionType.DESCRIPTION), exhausted)?.size)
    }

    @Test fun `persisted session groups answers by id and opens the first unfinished task`() {
        val first = checkNotNull(SpeakingDiagnostic.remaining(emptySet(), emptyList()))
        val rows = first.mapIndexed { index, q ->
            PracticeAttemptEntity(id = index + 1L, topic = q.type.label, questionText = q.text,
                questionType = q.type.name, mode = PracticeMode.DIAGNOSTIC.name, trainingSessionId = 9L, createdAt = 100L + index)
        }
        val partialRows = rows.take(2).map { it.copy(trainingSessionId = 12L, createdAt = 200L) }
        val sessions = DiagnosticSession.from(rows.reversed() + partialRows)
        assertEquals(listOf(12L, 9L), sessions.map { it.id })
        assertFalse(sessions.first().complete)
        assertTrue(sessions.last().complete)
        assertEquals(type, SpeakingDiagnostic.remaining(sessions.first().answers.keys, rows.map { it.questionText })?.first()?.type)
        assertFalse(PracticeMode.DIAGNOSTIC.name in ScoreKind.READINESS_MODES)
    }

    @Test fun `assessment response preserves a short complete task and a longer off topic task with exact evidence`() {
        val complete = DiagnosticAssessment.parse(response("OBSERVED", shortAnswer), type, shortAnswer, "test-model")
        val unrelated = DiagnosticAssessment.parse(response("NOT_OBSERVED", offTopic.substringBefore('.')), type, offTopic, "test-model")
        assertEquals(DiagnosticObservation(DiagnosticStatus.OBSERVED, shortAnswer), complete.observations[DiagnosticCriterion.TASK])
        assertEquals(DiagnosticStatus.NOT_OBSERVED, unrelated.observations[DiagnosticCriterion.TASK]?.status)
        val persisted = appJson.encodeToString(DiagnosticReport.serializer(), complete)
        assertEquals(complete, appJson.decodeFromString(DiagnosticReport.serializer(), persisted))
    }

    @Test fun `unclear transcript does not become an observed failure`() {
        val incomplete = "I lost my [inaudible] and called for help."
        val report = DiagnosticAssessment.parse(response("NOT_OBSERVED", "called for help"), type, incomplete, "test-model")
        assertTrue(report.observations.values.all { it.status == DiagnosticStatus.UNASSESSED && it.quote == null })
        assertFalse(SpeakingDiagnostic.hasUsableTranscript("[abandoned]"))
        assertTrue(GeminiTranscriber.requestBody("abc", "audio/wav", forDiagnostic = true).toString().contains("[inaudible]"))
    }

    @Test fun `invented evidence and inapplicable criteria are withheld independently`() {
        val answer = "The room has a wide window."
        val report = SpeakingDiagnostic.validate(QuestionType.DESCRIPTION, answer, "test-model", mapOf(
            DiagnosticCriterion.TASK to DiagnosticObservation(DiagnosticStatus.PARTIAL, "a wide window"),
            DiagnosticCriterion.CONNECTION to DiagnosticObservation(DiagnosticStatus.OBSERVED, "a sunny window"),
            DiagnosticCriterion.TIME to DiagnosticObservation(DiagnosticStatus.OBSERVED, "has a wide window"),
            DiagnosticCriterion.DELIVERY to DiagnosticObservation(DiagnosticStatus.NOT_OBSERVED, "has a wide window"),
        ))
        assertEquals(DiagnosticStatus.PARTIAL, report.observations[DiagnosticCriterion.TASK]?.status)
        assertTrue(report.observations.filterKeys { it != DiagnosticCriterion.TASK }.values.all { it.status == DiagnosticStatus.UNASSESSED })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial model response cannot masquerade as a completed report`() {
        DiagnosticAssessment.parse("""{"TASK":{"status":"OBSERVED","quote":"I lost my wallet."}}""", type, shortAnswer, "test-model")
    }

    @Test fun `comparison withholds unassessed results and different model or rubric versions`() {
        val task = DiagnosticCriterion.TASK
        val complete = DiagnosticAssessment.parse(response("OBSERVED", shortAnswer), type, shortAnswer, "test-model")
        val partial = DiagnosticAssessment.parse(response("PARTIAL", "I lost my wallet."), type, shortAnswer, "test-model")
        assertEquals(DiagnosticStatus.PARTIAL, SpeakingDiagnostic.compare(complete, partial, task))
        assertNull(SpeakingDiagnostic.compare(complete, partial, DiagnosticCriterion.TIME))
        assertNull(SpeakingDiagnostic.compare(complete, partial.copy(rubricVersion = 2), task))
        assertNull(SpeakingDiagnostic.compare(complete, partial.copy(evaluator = "another-model"), task))
    }

    @Test fun `transcribed diagnostic leaves readiness and practice topic aggregates unchanged`() {
        val practice = PracticeAttemptEntity(topic = "Library", questionText = "Describe a library.", mode = PracticeMode.FREE.name,
            transcript = "I visit my local library.", wordCount = 5, score = 65)
        val diagnostic = practice.copy(topic = "묘사", mode = PracticeMode.DIAGNOSTIC.name, captureMode = "CLOUD_STT", score = 100)
        val ordinary = listOf(practice)
        val mixed = listOf(diagnostic, practice)
        assertEquals(ProgressCalculator.ihReadiness(emptyList(), ordinary), ProgressCalculator.ihReadiness(emptyList(), mixed))
        assertEquals(ProgressCalculator.readiness(ordinary, emptyList(), 0, 0), ProgressCalculator.readiness(mixed, emptyList(), 0, 0))
        assertEquals(ProgressCalculator.topicStats(ordinary), ProgressCalculator.topicStats(mixed))
        assertEquals(ProgressCalculator.freeSpeakingRatio(ordinary), ProgressCalculator.freeSpeakingRatio(mixed))
        assertEquals(ProgressCalculator.weakTopics(ordinary, emptySet()), ProgressCalculator.weakTopics(List(60) { diagnostic } + ordinary, emptySet()))
    }

    private fun response(status: String, quote: String) = """
        {
          "TASK": {"status":"$status", "quote":"$quote"},
          "CONNECTION": {"status":"UNASSESSED", "quote":null},
          "TIME": {"status":"UNASSESSED", "quote":null},
          "DELIVERY": {"status":"UNASSESSED", "quote":null}
        }
    """.trimIndent()
}
