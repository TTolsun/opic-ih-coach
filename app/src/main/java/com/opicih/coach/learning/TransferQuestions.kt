package com.opicih.coach.learning

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.QuestionEntity

/** Keeps the speaking task comparable while changing the topic and excluding practiced prompts. */
internal fun transferCandidates(
    bank: List<QuestionEntity>,
    from: PracticeAttemptEntity,
    answeredTexts: List<String>,
): List<QuestionEntity> {
    val used = answeredTexts.mapTo(mutableSetOf(), TextNormalizer::normalize)
    used += TextNormalizer.normalize(from.questionText)
    return bank.filter {
        it.type == from.questionType &&
            !it.topic.trim().equals(from.topic.trim(), ignoreCase = true) &&
            TextNormalizer.normalize(it.text) !in used
    }
}
