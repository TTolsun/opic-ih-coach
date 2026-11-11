package com.opicih.coach

import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.QuestionEntity
import com.opicih.coach.learning.transferCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferQuestionsTest {
    private val origin = PracticeAttemptEntity(
        topic = "Travel", questionText = "When did you miss a flight?", questionType = "PAST_EXPERIENCE", mode = "FREE",
    )

    @Test fun picksOnlyUnusedQuestionsOfTheSameTypeOnDifferentTopics() {
        val valid = QuestionEntity(topic = "Concert", text = "What went wrong at a concert?", type = "PAST_EXPERIENCE", source = "AI_GENERATED")
        val bank = listOf(
            valid,
            valid.copy(topic = " TRAVEL "),
            valid.copy(type = "DESCRIPTION"),
            valid.copy(text = "When did you miss a flight?"),
            valid.copy(text = "What happened at the park?"),
        )
        assertEquals(listOf(valid), transferCandidates(bank, origin, listOf("What happened at the PARK!")))
    }

    @Test fun reportsNoCandidateIfTheBankHasOnlyTheOriginalQuestion() {
        val bank = listOf(QuestionEntity(topic = origin.topic, text = origin.questionText, type = origin.questionType, source = "USER_LIST"))
        assertEquals(emptyList<QuestionEntity>(), transferCandidates(bank, origin, emptyList()))
    }
}
