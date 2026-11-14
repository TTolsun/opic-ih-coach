package com.opicih.coach

import com.opicih.coach.analysis.AnalysisInput
import com.opicih.coach.analysis.ArticleSlips
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.FeedbackComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleSlipsTest {
    @Test fun findsKnownOmissionsWithOriginalCasing() {
        val slips = ArticleSlips.find("When I was kid, I used to take nap after school.")
        assertEquals(listOf("When I was kid", "take nap"), slips.map { it.found })
        assertEquals(listOf("when I was a kid", "take a nap"), slips.map { it.correction })
    }

    @Test fun ignoresCorrectPhrases() {
        assertTrue(ArticleSlips.find("When I was a kid, I took a nap as a child.").isEmpty())
        // "kids" (plural) needs no article.
        assertTrue(ArticleSlips.find("I played with other kids when I was young.").isEmpty())
    }

    @Test fun localFeedbackReportsTheSlipAndLogsIt() {
        val text = "Last year I went to Busan with my family. When I was kid, we went there every summer. It was really fun."
        val fb = FeedbackComposer.compose(
            AnalysisInput("PAST_EXPERIENCE", false, text, DiscourseAnalyzer.analyze(text), null, null, transcriptCorrectedBySystem = true),
        )
        assertTrue(fb.fixes.any { it.issue.contains("When I was kid") && it.better.contains("when I was a kid") })
        assertTrue(fb.errors.any { it.key == ArticleSlips.KEY && it.example == "When I was kid" })
    }
}
