package com.opicih.coach.learning

import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionEntity
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.db.ScriptEntity

data class QuestionItem(
    val topic: String,
    val text: String,
    val type: String,
    val sourceLabel: String,
    val script: ScriptEntity? = null,
    val mission: String? = null,
    val requiredTense: String? = null,
    /** FRAME_ATTACH: the approved frames the learner should use in this answer. */
    val frames: List<FrameChip> = emptyList(),
    val reviewId: Long? = null,
    val missionQuestion: String? = null,
    val recallScript: ScriptEntity? = null,
)

private data class Variation(val instruction: String, val tense: String? = null, val question: String? = null)

/** A frame shown on the speaking screen. */
data class FrameChip(val pattern: String, val hintKo: String)

fun scriptQuestion(s: ScriptEntity, mode: PracticeMode, bank: List<QuestionEntity>): QuestionItem {
    val label = ContentSource.of(s.source).label
    return when (mode) {
        PracticeMode.RANDOM -> {
            val alt = bank.filter { b -> b.type == s.questionType && (b.topic.contains(s.topic.substringBefore(" "), true) || s.topic.contains(b.topic.substringBefore(" "), true)) && b.text != s.question }
                .randomOrNull()?.text ?: rephrase(s)
            QuestionItem(s.topic, alt, s.questionType, PracticeMode.RANDOM.label, s)
        }
        PracticeMode.VARIATION -> {
            val (mission, tense, question) = variations(s, bank).random()
            QuestionItem(s.topic, s.question, s.questionType, PracticeMode.VARIATION.label, s, mission, tense, missionQuestion = question)
        }
        else -> QuestionItem(s.topic, s.question, s.questionType, label, s)
    }
}

private fun rephrase(s: ScriptEntity): String {
    val t = s.title.lowercase()
    return when (QuestionType.of(s.questionType)) {
        QuestionType.DESCRIPTION -> "I'd like to know more about $t. Can you describe it in as much detail as possible?"
        QuestionType.ROUTINE -> "Walk me through what you typically do. I'm curious about your $t."
        QuestionType.PAST_EXPERIENCE -> "Tell me about one specific time related to $t. What happened, and how did you feel?"
        QuestionType.COMPARISON -> "How has this changed over the years? Tell me about $t then and now."
        else -> "Tell me more about $t."
    }
}

private fun variations(s: ScriptEntity, bank: List<QuestionEntity>): List<Variation> {
    val replacement = bank.filter { it.topic != s.topic && it.type == s.questionType }.randomOrNull()?.text
        ?: "Tell me about something you enjoy doing on weekends."
    return listOf(
        Variation("같은 이야기를 미래 계획으로 바꿔 말하세요. 예: \"Next month, I'm planning to...\"", "FUTURE"),
        Variation("같은 주제로 과거의 구체적인 경험 하나를 말하세요. 예: \"Last year, I...\"", "PAST"),
        Variation("과거와 지금을 비교해서 말하세요. 예: \"Back then, ... These days, ...\"", "PAST_AND_PRESENT"),
        Variation("이 답변을 다른 질문에 재활용하세요: \"$replacement\"", question = replacement),
    )
}
