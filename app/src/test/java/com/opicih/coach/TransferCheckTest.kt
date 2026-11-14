package com.opicih.coach

import com.opicih.coach.analysis.TransferCheck
import com.opicih.coach.data.db.MasteryState
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.learning.MasteryRules
import com.opicih.coach.learning.SrsScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCheckTest {
    private val script = ScriptEntity(
        id = 1, topic = "Overseas Travel", title = "Memorable Vacation", questionType = "PAST_EXPERIENCE",
        question = "Tell me about a memorable vacation you took recently.",
        original = "I went to Osaka last summer. My hotel had a hot spring. I ate too much takoyaki and got a stomachache. " +
            "I had to run to a drugstore for medicine. Looking back, it was a memorable trip.",
        activeVersion = ScriptVersion.ORIGINAL.name,
    )

    private fun request(mode: PracticeMode, question: String, transcript: String) = PracticeRepository.Request(
        script = script, topic = script.topic, question = question, questionType = "PAST_EXPERIENCE", mode = mode,
        transcript = transcript, captureMode = "LIVE_STT", audioPath = null, durationMs = 60_000, pauses = null,
        transcriptFromDeviceStt = true,
    )

    /**
     * A new question about food, answered with only the relevant part of the Osaka story (no hotel, no hot spring,
     * no "looking back"). That is what a good transfer answer looks like, and it is exactly what 바뀐 질문 used to
     * fail, because it required ~60 % of all script content.
     */
    private val newQuestion = "Tell me about a time when you had a problem with food while traveling."
    private val transferAnswer =
        "One night in Japan, my family went to a busy street full of food stalls. Everything smelled amazing, " +
            "so I bought three boxes of takoyaki and ate all of them in ten minutes. About an hour later, " +
            "my stomach started to hurt really badly. I could not sleep, so my brother walked with me to a pharmacy. " +
            "The pharmacist gave me some pills, and I felt okay the next morning. Since then, I never eat that fast."

    @Test fun storyRetoldForANewQuestionPassesWithoutReciting() = runBlocking {
        val r = PracticeRepository.analyze(request(PracticeMode.RANDOM, newQuestion, transferAnswer), ai = null)
        val t = requireNotNull(r.metrics.transfer)
        assertTrue(t.answered); assertTrue(t.timeFrameOk); assertTrue(t.paragraph)
        assertEquals(true, t.storyUsed)
        assertTrue(t.passed)
        // Retelling in new words brings back well under the 60 % of script sentences that 바뀐 질문 used to require.
        val coverage = requireNotNull(r.metrics.compare).coverage
        assertTrue("coverage $coverage", coverage < 0.6f)
        assertFalse(SrsScheduler.isSuccess(PracticeMode.RANDOM, coverage, r.feedback.score))
        assertTrue(SrsScheduler.isSuccess(PracticeMode.RANDOM, coverage, r.feedback.score, t))
    }

    @Test fun shortOrUnrelatedAnswersFailTheMatchingChecks() = runBlocking {
        val short = PracticeRepository.analyze(request(PracticeMode.RANDOM, newQuestion, "I like food. Japan is good."), ai = null)
        val t = requireNotNull(short.metrics.transfer)
        assertFalse(t.paragraph); assertFalse(t.timeFrameOk); assertEquals(false, t.storyUsed)
        assertFalse(t.passed)
        assertEquals(25, t.score)

        val echo = PracticeRepository.analyze(request(PracticeMode.RANDOM, newQuestion, newQuestion), ai = null)
        assertFalse(requireNotNull(echo.metrics.transfer).answered)
    }

    @Test fun recallDrillsHaveNoTransferCheck() = runBlocking {
        val r = PracticeRepository.analyze(request(PracticeMode.BLIND, script.question, script.original), ai = null)
        assertNull(r.metrics.transfer)
    }

    @Test fun passedTransferMovesRecalledScriptsToVariationOkAndNeverDemotes() {
        val pass = TransferCheck(answered = true, timeFrameOk = true, paragraph = true, storyUsed = true)
        val fail = pass.copy(paragraph = false)
        // Recalled script + passed transfer → 응용 가능, even though little of the script wording came back.
        assertEquals(MasteryState.VARIATION_OK, MasteryRules.apply(MasteryState.NO_SCRIPT_OK, 70, PracticeMode.RANDOM, 0.3f, 40, pass).state)
        // Not recalled yet: transfer alone does not skip the recall step.
        assertEquals(MasteryState.LEARNING, MasteryRules.apply(MasteryState.LEARNING, 30, PracticeMode.RANDOM, 0.3f, 40, pass).state)
        // A failed transfer leaves recall as it was (it used to drop to 재복습 필요 on low coverage).
        assertEquals(MasteryState.NO_SCRIPT_OK, MasteryRules.apply(MasteryState.NO_SCRIPT_OK, 70, PracticeMode.RANDOM, 0.2f, 40, fail).state)
        // 응용 미션 without AI: the check's own score; with AI, low relevance still blocks.
        assertEquals(MasteryState.VARIATION_OK, MasteryRules.apply(MasteryState.NO_SCRIPT_OK, 70, PracticeMode.VARIATION, null, pass.score, pass).state)
        assertEquals(MasteryState.NO_SCRIPT_OK, MasteryRules.apply(MasteryState.NO_SCRIPT_OK, 70, PracticeMode.VARIATION, null, 40, pass).state)
        assertFalse(SrsScheduler.isSuccess(PracticeMode.VARIATION, null, 40, pass))
    }
}
