package com.opicih.coach

import com.opicih.coach.analysis.ErrorItem
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.learning.BundledSegments
import com.opicih.coach.learning.Glossary
import com.opicih.coach.learning.GlossaryEntry
import com.opicih.coach.learning.SceneCoaching
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SceneDrillTest {
    private val storyFile = listOf("src/main/assets/seed", "app/src/main/assets/seed")
        .map { File(it, "scene_scripts.json") }.first { it.isFile }
    private val catalog = BundledSegments.parse(storyFile.readText())
    private val drill = catalog.drills.getValue("거실의 회색 소파")

    @Test fun aRetryAfterFeedbackIsSavedAsGuidedPractice() = runBlocking {
        val request = PracticeRepository.Request(script = null, topic = "Furniture", question = drill.second.text,
            questionType = drill.second.type.name, mode = PracticeMode.FREE, transcript = "Back then I used to jump on the sofa.",
            captureMode = "TYPED", audioPath = null, durationMs = 0, pauses = null, transcriptFromDeviceStt = false)
        fun eligible(attempt: com.opicih.coach.data.db.PracticeAttemptEntity) =
            requireNotNull(AttemptMetrics.from(attempt)).usableForIndependentAssessment
        assertTrue(eligible(PracticeRepository.analyze(request, null).attempt))
        assertFalse(eligible(PracticeRepository.analyze(request.copy(guidedRetry = true), null).attempt))
    }

    @Test fun everyScenePairsTwoDistinctQuestionsWithItsOwnRetryHint() {
        assertEquals(catalog.sharedScenes.keys, catalog.drills.keys)
        catalog.drills.values.forEach {
            assertTrue(it.first.text.isNotBlank() && it.second.text.isNotBlank() && it.retryHint.isNotBlank())
            assertNotEquals(it.first.text, it.second.text)
        }
    }

    @Test fun strengthQuotesTheLearnerAndOnlyOneSlipIsSelected() {
        val answer = "Back then I used to jump on it. Now I take nap on it."
        val kept = requireNotNull(SceneCoaching.expression(drill, "", answer))
        assertEquals("Back then", kept.words)
        assertTrue(kept.fromSecond)
        assertTrue(answer.contains(kept.words))
        val fix = SceneCoaching.correction(drill, "", answer)
        assertTrue(fix.contains("take a nap"))
        assertFalse(fix.contains(drill.retryHint))
    }

    @Test fun echoOrMissingEvidenceNeverBecomesPraise() {
        assertNull(SceneCoaching.expression(drill, drill.first.text, drill.second.text))
        assertNull(SceneCoaching.expression(drill, "", "um"))
        val kept = requireNotNull(SceneCoaching.expression(drill, "I sit on it with my family.", drill.second.text))
        assertFalse(kept.fromSecond)
        assertEquals("with my family", kept.words)
        assertTrue(SceneCoaching.correction(drill, "", drill.second.text).contains(drill.retryHint))
    }

    @Test fun repeatedAnswerGetsTheChangedQuestionsFocus() {
        val answer = "I like my gray sofa because it is comfortable."
        assertTrue(SceneCoaching.correction(drill, answer, answer).contains("첫 답변과 같은"))
        assertTrue(SceneCoaching.correction(drill, answer, answer).contains(drill.retryHint))
    }

    @Test fun ungroundedAiCorrectionNeverOverridesTheLocalAdvice() {
        val answer = "Back then I jumped on it. Now I just rest on it."
        val hallucination = ErrorItem("e", "GRAMMAR", "I sleeped", "I slept")
        assertFalse(SceneCoaching.correction(drill, "", answer, listOf(hallucination)).contains("sleeped"))
        val grounded = SceneCoaching.correction(drill, "", "I sleeped on my sofa.", listOf(hallucination))
        assertTrue(grounded.contains("I sleeped") && grounded.contains("I slept"))
    }

    @Test fun aCompleteComparisonGetsARefinementAnchoredInTheActualAnswer() {
        val answer = "Back then I jumped on it. Now I sleep on it after work."
        val retry = SceneCoaching.correction(drill, "", answer)
        assertTrue(retry.contains("Now I sleep on it after work."))
        assertTrue(retry.contains("차이"))
    }

    @Test fun glossaryReturnsTheSpokenFormOfAnIdiom() {
        val glossary = Glossary(listOf(GlossaryEntry("stretch out", listOf("stretched out"), "IDIOM", "쭉 뻗다")))
        val kept = requireNotNull(SceneCoaching.expression(drill, "", "We stretched out on the couch.", glossary))
        assertEquals("stretched out", kept.words)
    }
}
