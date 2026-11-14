package com.opicih.coach.analysis

import kotlinx.serialization.Serializable

/**
 * Did the learner carry a prepared story over to a question they had not rehearsed? (바뀐 질문, 응용 미션)
 *
 * Before this, 바뀐 질문 counted as a success only when ~60 % of the script sentences came back, so the drill
 * rewarded reciting the script at a different question. These four checks look at the answer as an answer:
 * it responds to the question, uses the time frame the question asks for, runs to a paragraph, and reuses the
 * story (not its wording). Thresholds are learning references, not OPIc criteria.
 */
@Serializable
data class TransferCheck(
    /** Not a repeat of the question text. */
    val answered: Boolean,
    /** The tense the question or mission asks for is present (past experience, comparison, plan). */
    val timeFrameOk: Boolean,
    /** At least [MIN_SENTENCES] sentences and [MIN_WORDS] words: a paragraph, not a one-line reply. */
    val paragraph: Boolean,
    /** Some content of the prepared story came back. Null when no script is attached. */
    val storyUsed: Boolean?,
) {
    val passed: Boolean get() = answered && timeFrameOk && paragraph && storyUsed != false

    /** 0..100 in steps of 25, one per check (a missing script counts as passed). */
    val score: Int get() = listOf(answered, timeFrameOk, paragraph, storyUsed ?: true).count { it } * 25

    /** Labels for the result screen, in the order a learner should fix them. */
    val items: List<Pair<String, Boolean>>
        get() = listOfNotNull(
            "질문에 대한 답" to answered,
            "질문이 요구한 시간대" to timeFrameOk,
            "문단 길이 (${MIN_SENTENCES}문장·${MIN_WORDS}단어 이상)" to paragraph,
            storyUsed?.let { "준비한 이야기 활용" to it },
        )

    companion object {
        const val MIN_WORDS = 60
        const val MIN_SENTENCES = 4

        /**
         * Share of the script's content that has to come back for the story to count as reused. Low on purpose:
         * the new question needs a different angle, so most script sentences should not reappear word for word.
         */
        const val STORY_REUSE = 0.25f

        internal fun of(input: AnalysisInput, storyCoverage: Float?): TransferCheck =
            of(input.answerTask(), input.transcript, input.discourse, storyCoverage)

        internal fun of(task: AnswerTask, transcript: String, discourse: DiscourseMetrics, storyCoverage: Float?): TransferCheck =
            TransferCheck(
                answered = discourse.wordCount > 0 && !task.isQuestionEcho(transcript),
                timeFrameOk = task.missingTimeFrame(transcript, discourse) == null,
                paragraph = discourse.sentenceCount >= MIN_SENTENCES && discourse.wordCount >= MIN_WORDS,
                storyUsed = storyCoverage?.let { it >= STORY_REUSE },
            )
    }
}
