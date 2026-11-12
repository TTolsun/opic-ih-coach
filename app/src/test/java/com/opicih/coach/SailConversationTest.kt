package com.opicih.coach

import com.opicih.coach.learning.AiBudget
import com.opicih.coach.learning.ConvTurn
import com.opicih.coach.learning.ConversationScoring
import com.opicih.coach.learning.LocalFollowUp
import com.opicih.coach.learning.QuestionOrigin
import com.opicih.coach.learning.SailQuestionBank
import com.opicih.coach.learning.TurnMetrics
import com.opicih.coach.ui.screens.SailDebriefNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LocalFollowUpTest {

    @Test fun followUpUsesWhatTheUserSaid() {
        val f = LocalFollowUp.next("I enjoy running marathons.", emptySet())
        assertEquals("How did you first get into running marathons?", f.text)
    }

    @Test fun pastExperienceKeepsTheNameAndCase() {
        val f = LocalFollowUp.next("Last year I went to Jeju with my family. It was really fun and the weather was great.", emptySet())
        assertTrue(f.text, f.text.contains("Jeju"))
    }

    @Test fun pronounsAreTurnedAround() {
        assertEquals("your team project", LocalFollowUp.toSecondPerson("my team project"))
        assertEquals("your family", LocalFollowUp.toSecondPerson("My family"))
    }

    @Test fun neverRepeatsATemplateInOneConversation() {
        val answer = "I like cooking pasta at home with my wife."
        val used = mutableSetOf<String>()
        val texts = (1..5).map { LocalFollowUp.next(answer, used).also { used += it.templateId }.text }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test fun emptyOrShortAnswerGetsASimplerQuestion() {
        assertEquals("retry_simple", LocalFollowUp.next("", emptySet()).templateId)
        assertEquals("retry_simple", LocalFollowUp.next("Um, I don't know.", emptySet()).templateId)
    }

    @Test fun problemStoryAsksHowItWasSolved() {
        val f = LocalFollowUp.next("Our server had a big problem before the release and the whole team stayed late.", emptySet())
        assertEquals("problem_solve", f.templateId)
    }

    @Test fun problemBeatsPastExperience() {
        // Found on the emulator: "we had a problem" used to produce "most memorable moment of a problem".
        val f = LocalFollowUp.next("I listen to my colleague first. Last month we had a problem with a project deadline, so I suggested a compromise.", emptySet())
        assertEquals("problem_solve", f.templateId)
        val used = mutableSetOf<String>()
        repeat(6) { used += LocalFollowUp.next("Last month we had a problem with a project deadline.", used).also { assertFalse(it.text, it.text.contains("moment of a problem")) }.templateId }
    }

    @Test fun tripUsesThePlaceName() {
        val f = LocalFollowUp.next("Last summer I visited Jeju with my family and we ate a lot of seafood there.", emptySet())
        assertEquals("What was the most memorable moment of your trip to Jeju?", f.text)
    }

    @Test fun planAsksWhy() {
        val f = LocalFollowUp.next("This year I want to learn Spanish for my next trip to Spain.", emptySet())
        assertEquals("Why do you want to learn Spanish for your next trip to Spain?", f.text)
    }

    @Test fun doesNotRepeatThePreviousQuestion() {
        val prev = "Can you tell me more about why you think so?"
        val used = setOf("retry_simple")
        val f = LocalFollowUp.next("", used, prev)
        assertFalse(f.text.equals(prev, ignoreCase = true))
    }
}

class ConversationScoringTest {
    private fun turn(q: String, a: String, latency: Long?, pauses: Int = 0, typed: Boolean = false) =
        ConvTurn(q, QuestionOrigin.LOCAL_RULE, a, if (typed) null else TurnMetrics(latency, 20_000, 30_000, pauses, a.split(" ").size, 1), typed)

    @Test fun slowStartsAndShortAnswersBecomeWeaknesses() {
        val r = ConversationScoring.report(listOf(
            turn("Tell me about your hobby.", "I like running.", 4500, 1),
            turn("How did you first get into running?", "My friend told me running is good.", 3800, 2),
            turn("How often do you run?", "Twice a week.", 1000, 0),
        ))
        assertEquals(2, r.slowStarts)
        assertEquals(3100L, r.avgLatencyMs)
        assertTrue(r.weakCompetencies.contains("SPONTANEOUS_RESPONSE"))
        assertTrue(r.weakCompetencies.contains("COHERENCE"))
        assertTrue(r.fixes.size <= 3)
        assertTrue(r.limits.any { it.contains("공식 등급 예측이 아닙니다") })
    }

    @Test fun typedAnswersHaveNoTiming() {
        val r = ConversationScoring.report(listOf(turn("Q one?", "I went to Busan last week with friends.", null, typed = true)))
        assertNull(r.avgLatencyMs)
        assertTrue(r.limits.any { it.contains("직접 입력") })
    }

    @Test fun skippedTurnsAreCountedWithoutDilutingAnswerMetrics() {
        val answer = turn("Why do you like running?", "I like running because it is relaxing.", 1200, pauses = 1)
        val skipped = turn("Where do you run?", "", null, typed = true)
        val baseline = ConversationScoring.report(listOf(answer))
        val mixed = ConversationScoring.report(listOf(answer, skipped))

        assertEquals(baseline.copy(turns = 2, skippedTurns = 1), mixed)
        assertEquals(1, mixed.answeredTurns)
    }

    @Test fun skipOnlyConversationHasNoAnswerFeedback() {
        val r = ConversationScoring.report(listOf(turn("Q one?", "", null, typed = true)))

        assertEquals(0, r.answeredTurns)
        assertEquals(1, r.skippedTurns)
        assertNull(r.avgLatencyMs)
        assertTrue(r.strengths.isEmpty())
        assertTrue(r.fixes.isEmpty())
        assertTrue(r.weakCompetencies.isEmpty())
    }

    @Test fun repeatedPhrasesAreFound() {
        val rep = ConversationScoring.repeatedPhrases(listOf(
            "it was very fun and it was very good",
            "the trip was very fun",
            "my job was very fun too",
        ))
        assertTrue(rep.toString(), rep.contains("was very fun"))
    }

    @Test fun onTopicMeansReusingTheQuestionWords() {
        assertTrue(ConversationScoring.overlaps("What was the most difficult marathon?", "The most difficult marathon was in Seoul."))
        assertFalse(ConversationScoring.overlaps("What was the most difficult marathon?", "I like pizza."))
    }
}

class AiBudgetTest {
    @Test fun capsPerConversationAndPerDay() {
        assertTrue(AiBudget.canCall(0, 0, 30))
        assertFalse(AiBudget.canCall(AiBudget.PER_CONVERSATION, 0, 30))
        assertFalse(AiBudget.canCall(0, 30, 30))
        assertFalse(AiBudget.canCall(0, 0, 0))
    }
}

class SailQuestionBankTest {
    @Test fun debriefQuestionsRoundTripIntoTheBank() {
        val note = SailDebriefNote.encode("What is your role in your team?\n\nHow do you handle stress?", "준비 시간 없이 바로 답, 추가 질문 2개")
        assertEquals("What is your role in your team?\nHow do you handle stress?", SailDebriefNote.questions(note))
        assertEquals("준비 시간 없이 바로 답, 추가 질문 2개", SailDebriefNote.tempo(note))
        assertEquals(listOf("What is your role in your team?", "How do you handle stress?"), SailQuestionBank.fromDebriefNote(note))
    }

    @Test fun pickAvoidsRecentlyUsedQuestions() {
        val recent = SailQuestionBank.openers.drop(1).toSet()
        repeat(5) { assertEquals(SailQuestionBank.openers.first(), SailQuestionBank.pick(emptyList(), recent, Random(it)).first) }
    }

    @Test fun pickCanUseDebriefQuestions() {
        val picks = (0 until 20).map { SailQuestionBank.pick(listOf("What is your role in your team?"), emptySet(), Random(it)).second }
        assertTrue(picks.contains(QuestionOrigin.DEBRIEF))
        assertTrue(picks.contains(QuestionOrigin.SEED))
    }
}
