package com.opicih.coach

import com.opicih.coach.analysis.AnalysisInput
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.FeedbackComposer
import com.opicih.coach.analysis.PauseAnalyzer
import com.opicih.coach.analysis.ScriptComparator
import com.opicih.coach.analysis.TextNormalizer
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

    @Test fun shortAnswerGetsLengthFixAndAtMostThreeFixes() {
        val fb = FeedbackComposer.compose(input("I go to the park. It is good."))
        assertTrue(fb.fixes.size in 1..3)
        assertTrue(fb.fixes.any { it.issue.contains("짧") })
        assertEquals(2, fb.strengths.size)
        assertTrue(fb.limits.any { it.contains("보정") })
        assertTrue(fb.score in 0..100)
    }

    @Test fun richAnswerScoresHigher() {
        val rich = "I remember a trip to Gangwon-do last fall. Actually, my friend's family has an apple farm there, so I went to help them. " +
            "Everything was perfect until the afternoon, but suddenly it started pouring rain. We had to run to a small shed, and we waited for an hour. " +
            "While we were waiting, we ate apples and talked a lot, so it turned out to be really fun. Looking back, it was a bittersweet memory. " +
            "These days, I still think about it, and next fall I'm planning to go there again because I miss the view."
        val a = FeedbackComposer.compose(input(rich)).score
        val b = FeedbackComposer.compose(input("I went to a farm. It was fun.")).score
        assertTrue("rich=$a short=$b", a > b + 20)
    }

    @Test fun emptyTranscriptIsHandled() {
        val fb = FeedbackComposer.compose(input(""))
        assertEquals(0, fb.score)
        assertTrue(fb.fixes.isNotEmpty())
    }

    @Test fun comparisonWithoutPresentIsFlagged() {
        val fb = FeedbackComposer.compose(input("When I was young, our sofa was small and I jumped on it every day.", type = "COMPARISON"))
        assertTrue(fb.errors.any { it.key == "comparison_frame_missing" })
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
