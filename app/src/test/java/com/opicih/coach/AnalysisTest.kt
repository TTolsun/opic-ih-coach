package com.opicih.coach

import com.opicih.coach.analysis.AnalysisInput
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.FeedbackComposer
import com.opicih.coach.analysis.PauseAnalyzer
import com.opicih.coach.analysis.PauseMetrics
import com.opicih.coach.analysis.ScriptComparator
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.PracticeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TextNormalizerTest {
    @Test fun normalizesContractionsAndPunctuation() {
        assertEquals("i am a homebody so i do not go out", TextNormalizer.normalize("I'm a homebody, so I don’t go out!"))
    }

    @Test fun lemmatizesAndFoldsSynonyms() {
        assertEquals(TextNormalizer.lemma("jumping"), TextNormalizer.lemma("jumped"))
        assertEquals("big", TextNormalizer.lemma("large"))
        assertEquals("comfortable", TextNormalizer.lemma("comfy"))
    }
}

class ScriptComparatorTest {
    private val target = "I live on the sixth floor of an apartment. It has three rooms. My favorite spot is the large gray sofa in the living room."

    @Test fun reorderedAnswerStillCovers() {
        val spoken = "My favorite spot is the big gray sofa in the living room. I live in an apartment on the sixth floor, and it has three rooms."
        val r = ScriptComparator.compare(target, spoken)
        assertTrue("coverage=${r.coverage}", r.coverage >= 0.9f)
        assertTrue(r.sentences.all { it.covered })
        assertTrue("order similarity should drop when reordered", r.orderSimilarity < 1f)
    }

    @Test fun detectsMissingSentence() {
        val r = ScriptComparator.compare(target, "I live on the sixth floor of an apartment. It has three rooms.")
        assertFalse(r.sentences.last().covered)
        assertTrue(r.coverage < 0.8f)
    }

    private val gangwon = "To be honest, I'm a huge fan of traveling, and my favorite spot is Ganwon-do.\n" +
        "You know, since Korea is a peninsula, there are many beautiful coasts, but Gangwon-do is special because of its stunning mountains.\n" +
        "Last fall, I went there for apple farming. The scenery was absolutely breathtaking, especially when I was up on the ladder. " +
        "It felt like I was on top of the world!"

    @Test fun paraphrasedBlindAnswerIsAccepted() {
        val spoken = "Honestly, I really love traveling, and my favorite place is Gangwon-do. " +
            "Korea is a peninsula so it has lots of lovely beaches, but Gangwon-do is unique because the mountains are gorgeous. " +
            "Last autumn I went to an apple farm there. The view was amazing and beautiful when I was on the ladder. " +
            "I felt like I was on top of the world."
        val r = ScriptComparator.compare(gangwon, spoken)
        assertTrue("coverage=${r.coverage} ${r.sentences.map { it.coverage }}", r.coverage >= 0.7f)
        assertTrue(r.sentences.count { it.covered } >= r.sentences.size - 1)
    }

    @Test fun differentStoryStaysLow() {
        // Same topic area (a trip) but different season, place and events: content is not the script's content.
        val spoken = "Last summer I went to Gangneung with my friends. We ate seafood and walked on the beach."
        val r = ScriptComparator.compare(gangwon, spoken)
        assertTrue("coverage=${r.coverage}", r.coverage < 0.3f)
    }

    @Test fun unrelatedTopicStaysLow() {
        val spoken = "I usually go to the gym after work. I lift weights for an hour and then I take a shower and cook dinner."
        val r = ScriptComparator.compare(gangwon, spoken)
        assertTrue("coverage=${r.coverage}", r.coverage < 0.15f)
        assertTrue(r.sentences.none { it.covered })
    }

    @Test fun toleratesRecognitionSpelling() {
        assertTrue(ScriptComparator.similar("ganwon", "gangwon"))
        assertFalse(ScriptComparator.similar("sea", "see"))
        assertFalse(ScriptComparator.similar("coast", "toast"))
    }

    @Test fun keywordUsage() {
        val r = ScriptComparator.compare(target, "I have a gray sofa.", listOf("sixth floor", "gray sofa"))
        assertEquals(listOf("gray sofa"), r.usedKeywords)
        assertEquals(listOf("sixth floor"), r.missingKeywords)
    }
}

class DiscourseAnalyzerTest {
    @Test fun countsTensesConnectorsAndFillers() {
        val t = "Last summer, I went to Osaka. Um, I stayed at a hotel and it was great. Actually, I usually travel alone, but next year I'm planning to go to Fukuoka because I love hot springs."
        val m = DiscourseAnalyzer.analyze(t)
        assertTrue("past=${m.pastCount}", m.pastCount >= 3)
        assertTrue("future=${m.futureCount}", m.futureCount >= 1)
        assertTrue(m.presentCount >= 1)
        assertEquals(3, m.timeFrames)
        assertTrue(m.connectorsUsed.containsAll(listOf("actually", "but", "because")))
        assertTrue(m.fillerCount >= 1)
        assertTrue(m.experienceMarkers >= 1)
        assertEquals(3, m.sentenceCount)
    }

    @Test fun emptyTranscript() {
        val m = DiscourseAnalyzer.analyze("")
        assertEquals(0, m.wordCount)
        assertEquals(0, m.sentenceCount)
    }
}

class PauseAnalyzerTest {
    @Test fun findsLongPausesAndBridgesShortGaps() {
        // 50 ms frames: 2 s speech, 0.1 s gap (bridged), 2 s speech, 3 s silence, 1 s speech, 1 s trailing silence
        val levels = List(40) { 10f } + List(2) { 0f } + List(40) { 10f } + List(60) { 0f } + List(20) { 10f } + List(20) { 0f }
        val p = PauseAnalyzer.analyze(levels, 50, 5f)
        assertEquals(1, p.longPauseCount)
        assertEquals(3000, p.maxPauseMs)
        assertEquals((40 + 2 + 40 + 20) * 50L, p.speechMs)
        assertEquals(0, p.leadingSilenceMs)
        assertEquals(levels.size * 50L, p.totalMs)
    }

    @Test fun allSilence() {
        val p = PauseAnalyzer.analyze(List(100) { 0f }, 20, 1f)
        assertEquals(0, p.speechMs)
        assertEquals(2000, p.leadingSilenceMs)
    }

    @Test fun rmsOfSynthesizedPcm() {
        val tone = ShortArray(320) { i -> (10000 * sin(2 * PI * 440 * i / 16000)).toInt().toShort() }
        val silence = ShortArray(320)
        assertTrue(PauseAnalyzer.rms(tone) > 6000f)
        assertEquals(0f, PauseAnalyzer.rms(silence), 0.001f)
    }
}

class FeedbackComposerTest {
    private fun input(text: String, type: String = "PAST_EXPERIENCE", script: String? = null) = AnalysisInput(
        type, script != null, text, DiscourseAnalyzer.analyze(text), null,
        script?.let { ScriptComparator.compare(it, text) }, transcriptCorrectedBySystem = true,
    )

    @Test fun shortAnswerGetsTaskFeedbackWithoutAWordQuota() {
        val fb = FeedbackComposer.compose(input("I go to the park. It is good."))
        assertTrue(fb.fixes.size in 1..3)
        assertTrue(fb.fixes.first().issue.contains("과거 경험"))
        assertTrue(fb.fixes.none { it.issue.contains("짧") })
        assertEquals(2, fb.strengths.size)
        assertTrue(fb.limits.any { it.contains("보정") })
        assertTrue(fb.score in 0..100)
    }

    @Test fun differentTranscriptsWithTheSamePauseEvidenceGetTheSameFlowScore() {
        val rich = "I remember a trip to Gangwon-do last fall. Actually, my friend's family has an apple farm there, so I went to help them. " +
            "Everything was perfect until the afternoon, but suddenly it started pouring rain. We had to run to a small shed, and we waited for an hour. " +
            "While we were waiting, we ate apples and talked a lot, so it turned out to be really fun. Looking back, it was a bittersweet memory. " +
            "These days, I still think about it, and next fall I'm planning to go there again because I miss the view."
        val p = PauseMetrics(30_000, 20_000, 1, 2_200, 500)
        val a = FeedbackComposer.compose(input(rich).copy(pauses = p))
        val b = FeedbackComposer.compose(input("I went to a farm. It was fun.").copy(pauses = p))
        assertTrue(a.scoreMeasured && b.scoreMeasured)
        assertEquals(a.score, b.score)
        val uninterrupted = FeedbackComposer.compose(input(rich).copy(pauses = p.copy(longPauseCount = 0, maxPauseMs = 0)))
        assertTrue(uninterrupted.score > a.score)
    }

    @Test fun emptyTranscriptIsHandled() {
        val fb = FeedbackComposer.compose(input(""))
        assertEquals(0, fb.score)
        assertTrue(fb.fixes.isNotEmpty())
    }

    @Test fun comparisonWithoutPresentIsFlagged() {
        val answer = input("When I was young, our sofa was small and I jumped on it every day.", type = "COMPARISON")
        listOf(
            "Compare your sofa then and now.",
            "How is your furniture now different from the furniture in your childhood home?",
        ).forEach { question ->
            val fb = FeedbackComposer.compose(answer.copy(question = question))
            assertTrue(question, fb.fixes.first().issue.contains("과거와 현재의 비교"))
            assertTrue(question, fb.errors.any { it.key == "comparison_frame_missing" })
        }
    }

    @Test fun comparingTwoCurrentPlacesDoesNotRequirePastTense() {
        val fb = FeedbackComposer.compose(
            input("My local park has lots of trees. The park near work is smaller but quieter.", type = "COMPARISON")
                .copy(question = "Compare the park near your home with the one near work."),
        )
        assertTrue(fb.errors.none { it.key == "comparison_frame_missing" })
    }

    @Test fun pastExperienceDoesNotNeedFuturePlansOrExperienceMarkers() {
        val story = "Last May I was on a trip to Rome. My bag fell off a bus. I ran to the next stop and told the driver. " +
            "He let me look in the back. I found it on a seat and got it back. I felt glad I had my bag."
        val feedback = FeedbackComposer.compose(
            input(story).copy(question = "Tell me about a time you lost something on a trip. What happened?"),
        )
        assertTrue(feedback.fixes.isEmpty())
        assertTrue(feedback.retryMission.contains("무슨 일이 있었는지"))
    }

    @Test fun explicitFutureMissionTakesPrecedenceOverThePastQuestionType() {
        val feedback = FeedbackComposer.compose(input("I had a ticket for the trip.").copy(requiredTense = "FUTURE"))
        assertTrue(feedback.errors.none { it.key == "past_tense_missing" })
        assertTrue(feedback.fixes.any { it.issue.contains("미래 계획") })
    }

    @Test fun wpm() {
        assertEquals(120, FeedbackComposer.wpm(120, 60_000, 90_000))
        assertEquals(0, FeedbackComposer.wpm(10, 0, 0))
    }
}

class AdaptiveThresholdTest {
    @Test fun followsNoiseFloorWithinBounds() {
        val quietRoom = List(80) { 100f } + List(20) { 900f }
        assertEquals(250f, PauseAnalyzer.adaptiveThreshold(quietRoom), 0.01f)
        val noisyRoom = List(80) { 400f } + List(20) { 3000f }
        assertEquals(1000f, PauseAnalyzer.adaptiveThreshold(noisyRoom), 0.01f)
        val p = PauseAnalyzer.analyze(noisyRoom, 20, PauseAnalyzer.adaptiveThreshold(noisyRoom))
        assertEquals(20 * 20L, p.speechMs)
    }

    @Test fun nearSilentRecordingGetsHonestFeedback() {
        val fb = FeedbackComposer.acousticOnly(PauseAnalyzer.analyze(List(800) { 120f }, 20, 250f))
        assertTrue(fb.fixes.single().issue.contains("감지되지"))
        assertEquals(2, fb.strengths.size)
    }
}

class PracticeModeScoringTest {
    @Test fun variationIsNotScoredAgainstScriptWording() {
        assertTrue(PracticeMode.BLIND.comparesToScript)
        assertTrue(PracticeMode.RANDOM.comparesToScript)
        assertTrue(PracticeMode.SHADOWING.comparesToScript)
        assertFalse(PracticeMode.VARIATION.comparesToScript)
        assertFalse(PracticeMode.FREE.comparesToScript)
        assertFalse(PracticeMode.MOCK.comparesToScript)
    }
}

class ResultMissionTest {
    private val fix = com.opicih.coach.analysis.FixItem("답변이 짧습니다.", "", "5~7문장을 목표로 하세요.")

    @Test fun hidesMissionThatRepeatsTheShownFix() {
        val fb = com.opicih.coach.analysis.Feedback(fixes = listOf(fix), retryMission = "다시 말하기: 5~7문장을 목표로 하세요.")
        assertEquals(null, com.opicih.coach.ui.screens.visibleMission(fb))
    }

    @Test fun keepsADifferentMission() {
        val fb = com.opicih.coach.analysis.Feedback(fixes = listOf(fix), retryMission = "빠진 문장 2개를 포함해서 같은 질문에 다시 답하세요.")
        assertEquals("빠진 문장 2개를 포함해서 같은 질문에 다시 답하세요.", com.opicih.coach.ui.screens.visibleMission(fb))
    }
}
