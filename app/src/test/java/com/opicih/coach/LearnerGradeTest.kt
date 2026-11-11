package com.opicih.coach

import com.opicih.coach.ai.Prompts
import com.opicih.coach.analysis.AnalysisInput
import com.opicih.coach.analysis.CompareResult
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.FeedbackComposer
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.LearnerGrade
import com.opicih.coach.data.prefs.LearnerProfile
import com.opicih.coach.learning.GradeTargets
import com.opicih.coach.learning.LengthFit
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.ReadinessCriteria
import com.opicih.coach.learning.RetestAdvisor
import com.opicih.coach.learning.ScoreKind
import com.opicih.coach.learning.latestResultGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이슈 #23(등급 설정값), #24(점수 종류와 준비도), #27(목표 등급별 개선안 길이) 검증. */
class LearnerGradeTest {
    private val im1ToIm3 = LearnerProfile(LearnerGrade.IM1, LearnerGrade.IM3)

    // ---- #23
    @Test fun parseAcceptsOfficialStrings() {
        assertEquals(LearnerGrade.IM1, LearnerGrade.parse("IM1"))
        assertEquals(LearnerGrade.IM2, LearnerGrade.parse(" im 2 "))
        assertNull(LearnerGrade.parse("결과 대기"))
        assertNull(LearnerGrade.parse(null))
    }

    @Test fun gradeLadderIsOrdered() {
        assertTrue(LearnerGrade.IM1 < LearnerGrade.IM2)
        assertEquals(LearnerGrade.IM3, LearnerGrade.IM2.next())
        assertEquals(LearnerGrade.AL, LearnerGrade.AL.next())
    }

    @Test fun promptsUseSettingsGradesInsteadOfFixedIm2() {
        val improve = Prompts.improveSystem(im1ToIm3)
        val chat = Prompts.chatSystem(im1ToIm3)
        val summary = Prompts.convSummarySystem(im1ToIm3)
        listOf(improve, chat, summary).forEach {
            assertTrue(it, it.contains("currently at IM1"))
            assertTrue(it, it.contains("next exam target is IM3"))
            assertTrue(it, it.contains("final goal is IH"))
            assertFalse(it, it.contains("IM2 "))
        }
    }

    @Test fun evalPromptUsesTheTargetGradeAndAsksForATaskExample() {
        val eval = Prompts.evalSystem(im1ToIm3)
        assertTrue(eval.contains("Size your advice for the next exam target (IM3)"))
        assertTrue(eval.contains("a concise example for the actual task"))
        assertTrue(eval.contains("currently at IM1"))
    }

    @Test fun defaultProfileIsIm1WithIm2Target() {
        assertEquals(LearnerGrade.IM1, LearnerProfile.DEFAULT.current)
        assertEquals(LearnerGrade.IM2, LearnerProfile.DEFAULT.target)
        assertEquals(LearnerGrade.IH, LearnerProfile.DEFAULT.finalGoal)
    }

    @Test fun latestResultGradeUsesNewestCompletedOpicOnly() {
        fun reg(date: String, grade: String?, status: RegistrationStatus = RegistrationStatus.COMPLETED, type: String = "OPIC") =
            ExamRegistrationEntity(examType = type, dateLocal = date, status = status.name, resultGrade = grade)
        val regs = listOf(
            reg("2026-02-20", "IM2"),
            reg("2026-06-10", "IM1"),
            reg("2026-09-01", "IH", type = "SAIL"),
            reg("2026-11-14", "IH", status = RegistrationStatus.REGISTERED),
        )
        assertEquals(LearnerGrade.IM1, latestResultGrade(regs))
        assertNull(latestResultGrade(listOf(reg("2026-06-10", null))))
    }

    // ---- #27
    @Test fun improveRulesFollowTargetGrade() {
        assertEquals(50..80, GradeTargets.improve(LearnerGrade.IM2).let { it.minWords..it.maxWords })
        assertEquals(50..80, GradeTargets.improve(LearnerGrade.IM1).let { it.minWords..it.maxWords })
        assertEquals(70..110, GradeTargets.improve(LearnerGrade.IM3).let { it.minWords..it.maxWords })
        assertEquals(110..160, GradeTargets.improve(LearnerGrade.IH).let { it.minWords..it.maxWords })
    }

    @Test fun improvePromptCarriesTargetLengthAndNotTheOldIhRange() {
        val im3 = Prompts.improveSystem(im1ToIm3)
        assertTrue(im3.contains("70-110 words"))
        assertFalse(im3.contains("110-160"))
        val ih = Prompts.improveSystem(LearnerProfile(LearnerGrade.IM3, LearnerGrade.IH))
        assertTrue(ih.contains("110-160 words"))
        val im2 = Prompts.improveSystem(LearnerProfile.DEFAULT)
        assertTrue(im2.contains("50-80 words"))
        assertTrue(im2.contains("past tense"))
    }

    @Test fun lengthFitLabelTellsHowTheVersionCompares() {
        assertEquals(LengthFit.LONGER, GradeTargets.fit(130, LearnerGrade.IM2))
        assertEquals(LengthFit.WITHIN, GradeTargets.fit(65, LearnerGrade.IM2))
        assertEquals(LengthFit.SHORTER, GradeTargets.fit(40, LearnerGrade.IM3))
        assertTrue(GradeTargets.fitLabel(130, LearnerGrade.IM2).contains("권장보다 김"))
    }

    // ---- #24
    @Test fun everyModeHasAScoreKindAndBlindIsMemorization() {
        assertEquals(PracticeMode.entries.size, PracticeMode.entries.map { ScoreKind.of(it) }.size)
        listOf("READ_LISTEN", "SHADOWING", "FILL_BLANK", "KEYWORD", "BLIND").forEach { assertEquals(ScoreKind.MEMORIZATION, ScoreKind.of(it)) }
        listOf("RANDOM", "VARIATION", "FREE", "MOCK", "SAIL_CONVERSATION").forEach { assertEquals(ScoreKind.SPEAKING, ScoreKind.of(it)) }
        assertFalse("BLIND" in ScoreKind.SPEAKING_MODES)
    }

    private fun input(coverage: Float, kind: ScoreKind): AnalysisInput {
        val text = "Last weekend I went to the park and it was really fun because the weather was nice. So I want to go again next week."
        val compare = CompareResult(coverage = coverage, orderSimilarity = 1f, sentences = emptyList(), missingKeywords = emptyList(), usedKeywords = emptyList())
        return AnalysisInput("PAST_EXPERIENCE", true, text, DiscourseAnalyzer.analyze(text), null, compare, false, scoreKind = kind)
    }

    @Test fun speakingScoreIgnoresCoverageButMemorizationScoreRewardsIt() {
        val speakLow = FeedbackComposer.score(input(0.1f, ScoreKind.SPEAKING))
        val speakHigh = FeedbackComposer.score(input(1.0f, ScoreKind.SPEAKING))
        assertEquals(speakLow, speakHigh)
        val memLow = FeedbackComposer.score(input(0.1f, ScoreKind.MEMORIZATION))
        val memHigh = FeedbackComposer.score(input(1.0f, ScoreKind.MEMORIZATION))
        assertTrue(memHigh > memLow)
    }

    private fun attempt(mode: String, score: Int, words: Int = 100) =
        PracticeAttemptEntity(topic = "Movies", questionText = "q", mode = mode, wordCount = words, score = score, createdAt = 2_000_000_000_000L)

    @Test fun blindAttemptsDoNotEnterReadiness() {
        val attempts = List(5) { attempt("BLIND", 95, 150) } + List(3) { attempt("FREE", 40, 60) }
        val r = ProgressCalculator.readiness(attempts, emptyList(), 0, 0)
        assertEquals(3, r.recentAnswers)
        assertEquals(40, r.avgScore)
        assertEquals(60, r.avgWords)
        val onlyBlind = ProgressCalculator.ihReadiness(emptyList(), List(5) { attempt("BLIND", 95) })
        val withFree = ProgressCalculator.ihReadiness(emptyList(), List(5) { attempt("FREE", 95) })
        assertTrue(withFree > onlyBlind)
    }

    @Test fun readinessCriteriaScaleWithTargetGrade() {
        val im2 = ReadinessCriteria.of(LearnerGrade.IM2)
        val im3 = ReadinessCriteria.of(LearnerGrade.IM3)
        val ih = ReadinessCriteria.of(LearnerGrade.IH)
        assertTrue(im2.minWords < im3.minWords && im3.minWords < ih.minWords)
        assertTrue(im2.minScore < im3.minScore && im3.minScore < ih.minScore)
        assertEquals(90, ih.minWords)
        assertEquals(65, ih.minScore)
        assertEquals(60, ih.minMockScore)
    }

    @Test fun sameReadinessPassesLowerTargetButNotIh() {
        val r = RetestAdvisor.Readiness(12, 200, 70, 0.5f, 60, 1, 55, 6)
        fun passed(g: LearnerGrade) = RetestAdvisor.gates(r, ReadinessCriteria.of(g)).count { it.passed }
        assertEquals(5, passed(LearnerGrade.IM2))
        assertTrue(passed(LearnerGrade.IH) < 5)
    }
}
