package com.opicih.coach

import com.opicih.coach.ai.AiJson
import com.opicih.coach.ai.GeminiTranscriber
import com.opicih.coach.ai.Prompts
import com.opicih.coach.analysis.ErrorCounts
import com.opicih.coach.analysis.ErrorItem
import com.opicih.coach.analysis.Feedback
import com.opicih.coach.analysis.TenseErrorDetector
import com.opicih.coach.analysis.VerbatimMarkup
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.prefs.LearnerProfile
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.learning.CapturePolicy
import com.opicih.coach.learning.ErrorTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이슈 #25: 원문 그대로 전사의 표기, 시제 오류·중단 문장·자기 수정의 측정. */
class VerbatimTest {
    // ---- 표기 파서
    @Test fun parseCountsMarksAndRemovesThemFromTheText() {
        val p = VerbatimMarkup.parse("I go to the park yesterday [self-correction] I went to the park yesterday. And then I [abandoned] We ate lunch.")
        assertEquals(1, p.selfCorrections)
        assertEquals(1, p.abandoned)
        assertFalse(p.clean.contains("["))
        assertEquals("I go to the park yesterday I went to the park yesterday. And then I We ate lunch.", p.clean)
    }

    @Test fun abandonedFragmentIsTheTextSinceTheLastSentenceEnd() {
        val p = VerbatimMarkup.parse("We went to Busan. And the food was really [abandoned] It was fun.")
        assertEquals(listOf("And the food was really"), p.abandonedFragments)
    }

    @Test fun unknownShortBracketsAreDroppedButTheTextIsKept() {
        val p = VerbatimMarkup.parse("Well [laughs] I think so.")
        assertEquals("Well I think so.", p.clean)
        assertEquals(0, p.selfCorrections + p.abandoned)
    }

    @Test fun plainTextHasNoMarks() {
        val p = VerbatimMarkup.parse("I like hiking.")
        assertEquals("I like hiking.", p.clean)
        assertEquals(0, p.abandoned)
        assertTrue(p.abandonedFragments.isEmpty())
    }

    @Test fun tagsAreCaseInsensitive() {
        assertEquals(1, VerbatimMarkup.parse("a [Self-Correction] b").selfCorrections)
    }

    @Test fun transcriptionPromptAsksForBothTags() {
        assertTrue(GeminiTranscriber.PROMPT.contains(VerbatimMarkup.SELF_CORRECTION))
        assertTrue(GeminiTranscriber.PROMPT.contains(VerbatimMarkup.ABANDONED))
    }

    // ---- 시제 오류 탐지
    @Test fun presentFormInAPastStoryIsAnError() {
        assertEquals(1, TenseErrorDetector.count("Last weekend I go to the park."))
        assertEquals(1, TenseErrorDetector.count("Yesterday I am very tired."))
        assertEquals(1, TenseErrorDetector.count("When I was young, I have a dog."))
        assertEquals(2, TenseErrorDetector.count("Yesterday I go there and I eat lunch."))
    }

    @Test fun correctPastAndNonPastSentencesAreNotFlagged() {
        assertEquals(0, TenseErrorDetector.count("Last weekend I went to the park."))
        assertEquals(0, TenseErrorDetector.count("I go to the park every day."))
        assertEquals(0, TenseErrorDetector.count("Yesterday I didn't go to school."))
        assertEquals(0, TenseErrorDetector.count("Next year I will go to Busan."))
        assertEquals(0, TenseErrorDetector.count("Three years ago I started this job, and now I work here."))
        assertEquals(0, TenseErrorDetector.count("Yesterday I have been to the park."))
        assertEquals(0, TenseErrorDetector.count(""))
    }

    @Test fun aPastMarkerDoesNotReachTheNextSentence() {
        assertEquals(0, TenseErrorDetector.count("Last year I visited Jeju. I go there again next month."))
    }

    @Test fun longUnpunctuatedSpeechIsCheckedInChunks() {
        val filler = "and we walked along the beach for a very long time with many friends from my old school"
        val text = "last weekend i went to jeju $filler $filler and then next month i plan to go again and i go with my family"
        // 과거 표지는 첫 덩어리에만 있고, 뒤쪽의 현재형은 오류로 세지 않는다.
        assertEquals(0, TenseErrorDetector.count(text))
    }

    // ---- 오류 집계
    private fun ai(tense: Int?, abandoned: Int? = null, self: Int? = null) =
        Feedback(source = "AI", engine = "Gemini", tenseErrors = tense, abandonedSentences = abandoned, selfCorrections = self)

    @Test fun verbatimTranscriptCountsMarksAndFallsBackToTheLocalTenseCount() {
        val raw = "Last weekend I go to the park [self-correction] I go to the park. Then I [abandoned] It was fun."
        val c = ErrorCounts.from(raw, verbatimSource = true, ai = null)
        assertEquals(2, c.tense)
        assertEquals("LOCAL", c.tenseSource)
        assertEquals(1, c.abandoned)
        assertEquals(1, c.selfCorrection)
        assertTrue(c.verbatim)
    }

    @Test fun aiTenseCountWinsAndLargerMarkCountIsKept() {
        val c = ErrorCounts.from("I went [abandoned] there [self-correction] here.", verbatimSource = true, ai = ai(tense = 3, abandoned = 2, self = 0))
        assertEquals(3, c.tense)
        assertEquals("AI", c.tenseSource)
        assertEquals(2, c.abandoned)
        assertEquals(1, c.selfCorrection)
    }

    @Test fun deviceRecognizerTextDoesNotClaimToMeasureSelfCorrectionsOrAbandoned() {
        val c = ErrorCounts.from("Last weekend I go to the park.", verbatimSource = false, ai = null)
        assertEquals(1, c.tense)
        assertNull(c.abandoned)
        assertNull(c.selfCorrection)
        assertFalse(c.verbatim)
    }

    @Test fun aLocalFeedbackDoesNotProvideTenseCounts() {
        val local = Feedback(source = "LOCAL", tenseErrors = 9)
        assertEquals("LOCAL", ErrorCounts.from("Fine.", verbatimSource = true, ai = local).tenseSource)
    }

    @Test fun notebookGetsTenseSlipsAndAbandonedFragmentsOnceEach() {
        val raw = "Last weekend I go to the park. Then I want [abandoned] We ate."
        val items = ErrorCounts.notebookItems(raw, verbatimSource = true, existing = emptyList())
        assertEquals(listOf("tense_in_past_context", "abandoned_sentence"), items.map { it.key })
        assertEquals("Then I want", items.last().example)
        // AI가 같은 key를 이미 냈으면 중복으로 넣지 않는다.
        val withAi = ErrorCounts.notebookItems(raw, true, listOf(ErrorItem("tense_in_past_context", "GRAMMAR", "x", "y")))
        assertEquals(listOf("abandoned_sentence"), withAi.map { it.key })
        // 기기 음성 인식 텍스트는 중단 문장을 근거로 삼지 않는다.
        assertTrue(ErrorCounts.notebookItems(raw, verbatimSource = false, existing = emptyList()).none { it.key == "abandoned_sentence" })
    }

    // ---- AI 응답 파싱 (GeminiParseTest 방식)
    @Test fun feedbackParsesTheStructuredCounts() {
        val json = """{"strengths":["a","b"],"fixes":[],"betterAnswer":"x","retryMission":"y","score":70,
            "tenseErrors":2,"abandonedSentences":1,"selfCorrections":3,"errors":[]}"""
        val fb = AiJson.json.decodeFromString(Feedback.serializer(), AiJson.extractObject(json))
        assertEquals(2, fb.tenseErrors)
        assertEquals(1, fb.abandonedSentences)
        assertEquals(3, fb.selfCorrections)
    }

    @Test fun feedbackWithoutTheCountsKeepsThemNull() {
        val fb = AiJson.json.decodeFromString(Feedback.serializer(), """{"score":50}""")
        assertNull(fb.tenseErrors)
        assertNull(fb.abandonedSentences)
        assertNull(fb.selfCorrections)
    }

    @Test fun evalPromptAsksForTheCountsAndExplainsTheTags() {
        val p = Prompts.evalSystem(LearnerProfile.DEFAULT)
        listOf("\"tenseErrors\"", "\"abandonedSentences\"", "\"selfCorrections\"", "[self-correction]", "[abandoned]", "tense_in_past_context")
            .forEach { assertTrue(it, p.contains(it)) }
    }

    @Test fun oldMetricsJsonWithoutCountsStillDecodes() {
        val m = AttemptMetrics(discourse = DiscourseAnalyzer.analyze("I went home."))
        val old = appJson.encodeToString(AttemptMetrics.serializer(), m).replace(Regex(",?\"errorCounts\":\\{[^}]*}"), "")
        assertNull(appJson.decodeFromString(AttemptMetrics.serializer(), old).errorCounts)
    }

    // ---- 캡처 정책
    private val ready = Settings(cloudSttEnabled = true, audioUploadConsent = true, captureMode = CaptureMode.LIVE_STT)

    @Test fun freeSpeakingUsesRecordingPlusVerbatimTranscriptWhenAllowed() {
        for (mode in listOf(PracticeMode.FREE, PracticeMode.MOCK, PracticeMode.FRAME_ATTACH, PracticeMode.PARAGRAPH_LADDER)) {
            val choice = CapturePolicy.choose(mode, ready, hasGeminiKey = true)
            assertEquals(CaptureMode.RECORD_ONLY, choice.mode)
            assertTrue(choice.verbatim)
            assertNull(choice.note)
        }
    }

    @Test fun missingPermissionKeepsTheChosenCaptureAndExplainsWhy() {
        val off = CapturePolicy.choose(PracticeMode.FREE, ready.copy(cloudSttEnabled = false), true)
        assertEquals(CaptureMode.LIVE_STT, off.mode)
        assertFalse(off.verbatim)
        assertTrue(off.note!!.contains("녹음 전사를 켜지 않았습니다"))
        assertTrue(CapturePolicy.choose(PracticeMode.MOCK, ready.copy(audioUploadConsent = false), true).note!!.contains("동의하지 않았습니다"))
        assertTrue(CapturePolicy.choose(PracticeMode.FREE, ready, hasGeminiKey = false).note!!.contains("Gemini 키가 없습니다"))
    }

    @Test fun scriptPracticeAndSailFollowTheUsersCaptureMode() {
        for (mode in listOf(PracticeMode.BLIND, PracticeMode.READ_LISTEN, PracticeMode.VARIATION, PracticeMode.SAIL_CONVERSATION)) {
            val c = CapturePolicy.choose(mode, ready, true)
            assertEquals(CaptureMode.LIVE_STT, c.mode)
            assertNull(c.note)
        }
        assertEquals(CaptureMode.RECORD_ONLY, CapturePolicy.choose(PracticeMode.BLIND, ready.copy(captureMode = CaptureMode.RECORD_ONLY), true).mode)
    }

    // ---- 추세
    private fun attempt(counts: ErrorCounts?, at: Long): PracticeAttemptEntity {
        val m = AttemptMetrics(discourse = DiscourseAnalyzer.analyze("I went home."), errorCounts = counts)
        return PracticeAttemptEntity(topic = "T", questionText = "q", mode = "FREE", createdAt = at, metricsJson = appJson.encodeToString(AttemptMetrics.serializer(), m))
    }

    @Test fun trendUsesVerbatimAttemptsOnlyOldestFirst() {
        val attempts = listOf( // 최신순
            attempt(ErrorCounts(tense = 1, abandoned = 0, selfCorrection = 2, verbatim = true), 30),
            attempt(ErrorCounts(tense = 5, verbatim = false), 25),
            attempt(null, 20),
            attempt(ErrorCounts(tense = 3, abandoned = 1, selfCorrection = 0, verbatim = true), 10),
        )
        val t = ErrorTrend.points(attempts)
        assertEquals(listOf(10L, 30L), t.map { it.createdAt })
        assertEquals(listOf(3, 1), t.map { it.tense })
        assertEquals(listOf(1, 0), t.map { it.abandoned })
        assertEquals(listOf(0, 2), t.map { it.selfCorrection })
    }

    @Test fun trendKeepsTheNewestAttemptsAndIgnoresBrokenJson() {
        val many = List(20) { attempt(ErrorCounts(tense = it, verbatim = true), it.toLong()) }.reversed()
        val t = ErrorTrend.points(many, max = 12)
        assertEquals(12, t.size)
        assertEquals(19, t.last().tense)
        val broken = PracticeAttemptEntity(topic = "T", questionText = "q", mode = "FREE", metricsJson = "not json")
        assertTrue(ErrorTrend.points(listOf(broken)).isEmpty())
    }
}
