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

    @Test fun aTrailingFutureMarkerEndsTheInheritedPastContext() {
        assertEquals(0, TenseErrorDetector.count("Last year I visited Jeju. I go there again next month."))
        assertEquals(0, TenseErrorDetector.count("Last year I visited Jeju. I'll stay there and I go hiking."))
        assertEquals(1, TenseErrorDetector.count("Yesterday I met Will at the station. I see his new bike."))
    }

    @Test fun currentTimeSwitchEndsPastContextWithoutHidingAnEarlierError() {
        val hits = TenseErrorDetector.find(
            "Yesterday I go to the library, but now I see my friends at the cafe. I have coffee here. Yesterday I eat lunch there.",
        )
        assertEquals(listOf("go", "eat"), hits.map { it.verb })
        assertTrue(hits.first().sentence.contains("I go to the library"))
        assertEquals(0, TenseErrorDetector.count("Last weekend I went to the library. Now I go to the cafe. I see my friends there."))
    }

    @Test fun directQuotesHaveTheirOwnTimeContext() {
        val text = "Yesterday I said, “Now I go home. I see the door.” Then I see my friend."
        assertEquals(listOf("Then I see my friend."), TenseErrorDetector.find(text).map { it.sentence })
        assertEquals(0, TenseErrorDetector.count("\"Yesterday I go to the library.\" I see the cafe."))
        assertEquals(0, TenseErrorDetector.count("Yesterday she said, 'I don't know where I am.'"))
    }

    @Test fun pastContextDoesNotCrossAParagraphOrCreateAnErrorAcrossSentences() {
        val newParagraph = "Yesterday I went to Jeju.\r\n\r\nI see the sea from my window."
        assertEquals(0, counts(newParagraph, verbatim = true).localTense)
        assertEquals(0, TenseErrorDetector.count("Yesterday I went to Jeju. I. See the sea!"))
    }

    @Test fun repetitionDoesNotCrossAQuotedSentenceOrLineBoundary() {
        assertEquals(0, DiscourseAnalyzer.analyze("She said, “I went yesterday.” Yesterday was sunny.").repeatedWords)
        assertEquals(0, DiscourseAnalyzer.analyze("I went yesterday.Yesterday was sunny.").repeatedWords)
        val parsed = VerbatimMarkup.parse("I went yesterday\r\nYesterday was sunny")
        assertEquals(0, DiscourseAnalyzer.analyze(parsed.clean).repeatedWords)
    }

    @Test fun futureShiftWorksInLongUnpunctuatedSpeech() {
        val filler = "and we walked along the beach for a very long time with many friends from my old school"
        val text = "last weekend i went to jeju $filler $filler and then next month i plan to go again and i go with my family"
        assertEquals(0, TenseErrorDetector.count(text))
    }

    // ---- 오류 집계
    private fun ai(tense: Int?, abandoned: Int? = null, self: Int? = null) =
        Feedback(source = "AI", engine = "Gemini", tenseErrors = tense, abandonedSentences = abandoned, selfCorrections = self)

    private fun counts(raw: String, verbatim: Boolean, ai: Feedback? = null) = ErrorCounts.analyze(raw, verbatim, ai).counts

    @Test fun verbatimTranscriptCountsMarksAndFallsBackToTheLocalTenseCount() {
        val raw = "Last weekend I go to the park. Then I [abandoned] It was fun. And I [self-correction] we went home."
        val c = counts(raw, verbatim = true)
        assertEquals(1, c.tense)
        assertEquals("LOCAL", c.tenseSource)
        assertEquals(1, c.abandoned)
        assertEquals(1, c.selfCorrection)
        assertTrue(c.verbatim)
    }

    @Test fun anErrorTheLearnerCorrectedHerselfIsNotCounted() {
        val corrected = "Last weekend I go to the park [self-correction] I went to the park."
        assertEquals(0, counts(corrected, verbatim = true).tense)
        assertEquals(1, counts(corrected, verbatim = true).selfCorrection)
        // 같은 오류를 고치지 않으면 센다.
        assertEquals(1, counts("Last weekend I go to the park.", verbatim = true).tense)
        // 고치기 전 문장이 아닌 앞선 문장의 오류는 센다.
        assertEquals(1, counts("Yesterday I eat pizza. Then I went home [self-correction] I went home.", verbatim = true).tense)
    }

    @Test fun aYearIsAPastMarker() {
        assertEquals(1, TenseErrorDetector.count("In 2019 I go to Jeju."))
    }

    @Test fun aiEstimatesDoNotReplaceTranscriptTags() {
        val c = counts("I went [abandoned] there [self-correction] here.", verbatim = true, ai = ai(tense = 3, abandoned = 2, self = 0))
        assertEquals(3, c.tense)
        assertEquals("AI", c.tenseSource)
        assertEquals(1, c.abandoned)
        assertEquals(1, c.selfCorrection)
        assertEquals(2, c.aiAbandoned)
        assertEquals(0, c.aiSelfCorrection)
    }

    @Test fun deviceRecognizerTextDoesNotClaimToMeasureSelfCorrectionsOrAbandoned() {
        val c = counts("Last weekend I go to the park.", verbatim = false)
        assertEquals(1, c.tense)
        assertNull(c.abandoned)
        assertNull(c.selfCorrection)
        assertFalse(c.verbatim)
    }

    @Test fun aLocalFeedbackDoesNotProvideTenseCounts() {
        val local = Feedback(source = "LOCAL", tenseErrors = 9)
        assertEquals("LOCAL", counts("Fine.", verbatim = true, ai = local).tenseSource)
    }

    @Test fun notebookGetsTenseSlipsAndAbandonedFragmentsOnceEach() {
        val raw = "Last weekend I go to the park. Then I want [abandoned] We ate."
        val items = ErrorCounts.analyze(raw, verbatimSource = true, ai = null).notebook
        assertEquals(listOf("tense_in_past_context", "abandoned_sentence"), items.map { it.key })
        assertEquals("Then I want", items.last().example)
        // 기기 음성 인식 텍스트는 중단 문장을 근거로 삼지 않는다.
        assertTrue(ErrorCounts.analyze(raw, verbatimSource = false, ai = null).notebook.none { it.key == "abandoned_sentence" })
    }

    @Test fun whenTheAiCountedTensesItsNotebookItemIsTheOnlyOne() {
        val raw = "Last weekend I go to the park."
        // AI가 0이라고 했으면 규칙 탐지 항목을 노트에 넣지 않는다(횟수와 노트가 어긋나지 않게).
        val zero = ErrorCounts.analyze(raw, verbatimSource = true, ai = ai(tense = 0))
        assertEquals(0, zero.counts.tense)
        assertEquals(1, zero.counts.localTense)
        assertEquals(listOf("Last weekend I go to the park."), zero.counts.localTenseExamples)
        assertTrue(zero.notebook.isEmpty())
        // AI가 센 경우에도 AI가 항목을 낸다고 보고 중복해서 넣지 않는다.
        val counted = ErrorCounts.analyze(raw, verbatimSource = true, ai = ai(tense = 1))
        assertTrue(counted.notebook.none { it.key == "tense_in_past_context" })
        // AI 응답이 아닌 로컬 평가는 규칙 탐지 항목을 쓴다.
        assertEquals(listOf("tense_in_past_context"), ErrorCounts.analyze(raw, true, Feedback(source = "LOCAL")).notebook.map { it.key })
    }

    @Test fun anItemTheAiAlreadyListedIsNotAddedTwice() {
        val raw = "Then I want [abandoned] We ate."
        val have = Feedback(source = "AI", errors = listOf(ErrorItem("abandoned_sentence", "FLUENCY", "x", "y")))
        assertTrue(ErrorCounts.analyze(raw, verbatimSource = true, ai = have).notebook.isEmpty())
    }

    @Test fun uncorrectedTextDropsOnlyTheUnfinishedAttemptBeforeACorrection() {
        val p = VerbatimMarkup.parse("We ate lunch. I go there [self-correction] I went there.")
        assertEquals("We ate lunch. I went there.", p.uncorrected)
        assertEquals("We ate lunch. I go there I went there.", p.clean)
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
        assertTrue(p.contains("next sentence when the same past event continues"))
        assertTrue(p.contains("speech inside direct quotations"))
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

    @Test fun trendOnlyScansTheMostRecentAttempts() {
        val old = attempt(ErrorCounts(tense = 9, verbatim = true), 1)
        val recentWithoutCounts = List(ErrorTrend.MAX_SCAN) { attempt(null, 100L + it) }
        assertTrue(ErrorTrend.points(recentWithoutCounts + old).isEmpty())
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
