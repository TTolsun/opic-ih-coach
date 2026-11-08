package com.opicih.coach

import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.learning.FRAME_BLANK
import com.opicih.coach.learning.FrameBank
import com.opicih.coach.learning.FrameExtractor
import com.opicih.coach.learning.FrameMatcher
import com.opicih.coach.learning.FrameOrigin
import com.opicih.coach.learning.LadderLevel
import com.opicih.coach.learning.ParagraphLadder
import com.opicih.coach.learning.ScoreKind
import com.opicih.coach.learning.ScriptText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이슈 #28: 문장 틀 추출·매칭과 1→3→5문장 문단 확장 사다리의 판정. */
class SentenceFramesTest {
    private val like = "The thing I like most about $FRAME_BLANK is $FRAME_BLANK because $FRAME_BLANK."
    private val planning = "I'm planning to $FRAME_BLANK next $FRAME_BLANK."

    // ---- 틀 은행
    @Test fun bankHasTwentyDistinctFramesWithBlanks() {
        val patterns = FrameBank.ALL.map { it.pattern }
        assertEquals(patterns.size, patterns.toSet().size)
        assertTrue(patterns.size in FrameExtractor.MIN_TOTAL..FrameExtractor.MAX_TOTAL)
        assertTrue(patterns.all { it.contains(FRAME_BLANK) })
        assertTrue(FrameBank.ALL.all { it.hintKo.isNotBlank() })
    }

    // ---- 매칭
    @Test fun matcherAcceptsAFilledFrameIgnoringCaseAndPunctuation() {
        assertTrue(FrameMatcher.used(like, "the thing i like most about hiking is the view, because it relaxes me"))
        assertTrue(FrameMatcher.used(like, "The thing I like most about my town is its parks. Because they are quiet."))
    }

    @Test fun matcherTreatsContractionsAsEqual() {
        assertTrue(FrameMatcher.used(planning, "I am planning to travel next month"))
        assertTrue(FrameMatcher.used(planning, "I’m planning to travel next month"))
    }

    @Test fun matcherRejectsMissingOrReorderedParts() {
        assertFalse(FrameMatcher.used(like, "The thing I like most about hiking is the view"))
        assertFalse(FrameMatcher.used(like, "Because it relaxes me, hiking is the thing I like most about it"))
        assertFalse(FrameMatcher.used(planning, "I plan to travel"))
        assertFalse(FrameMatcher.used(like, ""))
    }

    @Test fun matcherRequiresTheBlanksToBeFilled() {
        // 고정 문구만 이어 말하고 빈칸을 비운 문장
        assertFalse(FrameMatcher.used(like, "The thing I like most about is because it"))
        assertFalse(FrameMatcher.used(like, "The thing I like most about hiking is because"))
        assertFalse(FrameMatcher.used(planning, "I am planning to next month"))
        // 끝 빈칸에 단어가 없는 경우
        assertFalse(FrameMatcher.used("To be honest, I $FRAME_BLANK.", "To be honest I"))
        assertTrue(FrameMatcher.used("To be honest, I $FRAME_BLANK.", "To be honest I love cooking"))
    }

    @Test fun matcherDoesNotMatchInsideLongerWords() {
        assertFalse(FrameMatcher.used("It was a $FRAME_BLANK experience because $FRAME_BLANK.", "It wasn't a fun experience because of rain"))
    }

    // ---- 추출
    @Test fun extractorFindsFramesLearnersAlreadyUseAndKeepsTheirSentence() {
        val scripts = listOf(
            ScriptText(7, "I live in Seoul. Compared to the past, the city is much cleaner these days. I like it."),
            ScriptText(9, "To be honest, I love cooking."),
        )
        val out = FrameExtractor.extract(scripts)
        val compared = out.single { it.pattern.startsWith("Compared to") }
        assertEquals(FrameOrigin.SCRIPT, compared.origin)
        assertEquals(7L, compared.sourceScriptId)
        assertTrue(compared.example!!.startsWith("Compared to the past"))
        assertEquals(FrameOrigin.SCRIPT, out.single { it.pattern.startsWith("To be honest") }.origin)
    }

    @Test fun extractorFillsUpToTheMinimumWithBuiltInFramesAndNeverExceedsTheMax() {
        val out = FrameExtractor.extract(emptyList())
        assertEquals(FrameExtractor.MIN_TOTAL, out.size)
        assertTrue(out.all { it.origin == FrameOrigin.BUILTIN && it.example == null })
        assertTrue(FrameExtractor.extract(emptyList(), max = 5).size <= 5)
        assertTrue(out.size <= FrameExtractor.MAX_TOTAL)
    }

    @Test fun extractorSkipsFramesThatAlreadyExist() {
        val existing = FrameBank.ALL.map { it.pattern }.toSet()
        assertTrue(FrameExtractor.extract(listOf(ScriptText(1, "To be honest, I love cooking.")), existing).isEmpty())
        val some = FrameExtractor.extract(emptyList()).map { it.pattern }.toSet()
        val more = FrameExtractor.extract(emptyList(), some).map { it.pattern }
        assertTrue(more.none { it in some })
    }

    // ---- 사다리
    private fun d(text: String) = DiscourseAnalyzer.analyze(text)

    @Test fun levelOneNeedsOnlyOneSentence() {
        assertTrue(ParagraphLadder.judge(LadderLevel.ONE, d("I like hiking.")).passed)
        assertFalse(ParagraphLadder.judge(LadderLevel.ONE, d("")).passed)
    }

    @Test fun levelThreeNeedsThreeSentencesAndAConnector() {
        val ok = d("I like hiking. It is fun because I can see nature. So I go every weekend.")
        assertTrue(ParagraphLadder.judge(LadderLevel.THREE, ok).passed)
        val noConnector = d("I like hiking. I go often. I see trees.")
        val r = ParagraphLadder.judge(LadderLevel.THREE, noConnector)
        assertFalse(r.passed)
        assertTrue(r.missing.single().contains("연결어"))
    }

    @Test fun levelFiveNeedsFiveSentencesTwoConnectorsAndPastForm() {
        val ok = d("Last summer I went to Jeju. It was great because the sea was clear. I stayed with my friends. So we swam every day. Then we ate seafood.")
        assertTrue(ParagraphLadder.judge(LadderLevel.FIVE, ok).passed)
        val present = d("I go to Jeju. It is great because the sea is clear. I stay with friends. We swim a lot. We eat seafood.")
        val r = ParagraphLadder.judge(LadderLevel.FIVE, present)
        assertFalse(r.passed)
        assertTrue(r.missing.any { it.contains("과거형") })
    }

    @Test fun unpunctuatedSpeechRecognitionIsJudgedByWordCountEstimate() {
        val text = "last summer i went to jeju with my friends and it was great because the sea was clear so we swam every day then we ate seafood and relaxed and then we went home happy"
        val m = d(text)
        assertEquals(1, m.sentenceCount)
        assertEquals(m.wordCount / ParagraphLadder.WORDS_PER_SENTENCE, ParagraphLadder.sentenceCount(m, hasSentencePunctuation = false))
        assertTrue(ParagraphLadder.judge(LadderLevel.THREE, m, hasSentencePunctuation = false).passed)
    }

    @Test fun aSingleTrailingPeriodOnALongAnswerIsStillEstimated() {
        val text = "last summer i went to jeju with my friends and it was great because the sea was clear so we swam every day then we ate seafood and relaxed and then we went home happy."
        val m = d(text)
        assertEquals(1, m.sentenceCount)
        assertTrue(ParagraphLadder.judge(LadderLevel.THREE, m, hasSentencePunctuation = true).passed)
    }

    @Test fun properlyPunctuatedShortSentencesKeepTheirRealCount() {
        val two = d("I went to Jeju last summer. It was great because the sea was clear.")
        assertEquals(2, ParagraphLadder.sentenceCount(two, hasSentencePunctuation = true))
        assertFalse(ParagraphLadder.judge(LadderLevel.THREE, two, hasSentencePunctuation = true).passed)
    }

    @Test fun ladderStepsAreOrderedAndEndAtFive() {
        assertEquals(LadderLevel.THREE, LadderLevel.ONE.next())
        assertEquals(LadderLevel.FIVE, LadderLevel.THREE.next())
        assertNull(LadderLevel.FIVE.next())
        assertTrue(LadderLevel.FIVE.missionKo.contains("연결어 2개") && LadderLevel.FIVE.missionKo.contains("과거형"))
    }

    // ---- 준비도와 백업 규칙
    @Test fun ladderAttemptsAreExcludedFromReadinessButNotFromSpeakingScore() {
        assertTrue("PARAGRAPH_LADDER" in ScoreKind.SPEAKING_MODES)
        assertFalse("PARAGRAPH_LADDER" in ScoreKind.READINESS_MODES)
        assertTrue("FRAME_ATTACH" in ScoreKind.READINESS_MODES && "FREE" in ScoreKind.READINESS_MODES)
        assertFalse("BLIND" in ScoreKind.READINESS_MODES)
    }

    @Test fun shortLadderAnswersDoNotLowerReadiness() {
        fun a(mode: String, words: Int, score: Int) =
            com.opicih.coach.data.db.PracticeAttemptEntity(topic = "T", questionText = "q", mode = mode, wordCount = words, score = score, createdAt = 2_000_000_000_000L)
        val attempts = List(3) { a("FREE", 100, 70) } + List(10) { a("PARAGRAPH_LADDER", 10, 20) }
        val r = com.opicih.coach.learning.ProgressCalculator.readiness(attempts, emptyList(), 0, 0)
        assertEquals(3, r.recentAnswers)
        assertEquals(100, r.avgWords)
        assertEquals(70, r.avgScore)
    }

    @Test fun olderBackupsKeepTheCurrentFrames() {
        val v4 = com.opicih.coach.data.backup.BackupManager.Snapshot(version = 4)
        val v5 = com.opicih.coach.data.backup.BackupManager.Snapshot(version = 5)
        assertFalse(com.opicih.coach.data.backup.BackupManager.restoresFrames(v4))
        assertTrue(com.opicih.coach.data.backup.BackupManager.restoresFrames(v5))
    }

    // ---- 점수 종류 (#24 연결)
    @Test fun newModesCountAsSpeakingScore() {
        assertEquals(ScoreKind.SPEAKING, ScoreKind.of(PracticeMode.FRAME_ATTACH))
        assertEquals(ScoreKind.SPEAKING, ScoreKind.of(PracticeMode.PARAGRAPH_LADDER))
        assertTrue("FRAME_ATTACH" in ScoreKind.SPEAKING_MODES && "PARAGRAPH_LADDER" in ScoreKind.SPEAKING_MODES)
    }
}
