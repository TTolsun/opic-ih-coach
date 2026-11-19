package com.opicih.coach.learning

import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.StoryEntity
import com.opicih.coach.analysis.TextNormalizer

/**
 * Groups scripts by the personal story they tell (ScriptEntity.storyIds → StoryEntity).
 *
 * IH answers are built from a few real stories reused across questions, not from one memorized text per question.
 * This map shows which story each script uses, which topics share a story, and which scripts have no story yet.
 */
object StoryMap {
    data class StoryUse(
        val story: StoryEntity,
        val scripts: List<ScriptEntity>,
    ) {
        /** Survey topics whose scripts tell this story, in script order. */
        val topics: List<String> get() = scripts.map { it.topic }.distinct()

        /**
         * Two or more topics share this story. In one exam each topic gets its own question set, so if both topics
         * come up the same story would be told twice; one of them needs another scene ready.
         */
        val sharedAcrossTopics: Boolean get() = topics.size >= 2
    }

    data class Result(val uses: List<StoryUse>, val withoutStory: List<ScriptEntity>)

    fun storyIdsOf(script: ScriptEntity): List<String> =
        script.storyIds.orEmpty().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun build(stories: List<StoryEntity>, scripts: List<ScriptEntity>): Result {
        val known = stories.associateBy { it.id }
        val uses = stories.mapNotNull { story ->
            val linked = scripts.filter { story.id in storyIdsOf(it) }
            if (linked.isEmpty()) null else StoryUse(story, linked)
        }
        // A script whose ids point only at missing stories counts as "no story" so it is not silently dropped.
        // Roleplay answers follow a call template (ask, solve, …) rather than a personal story, so they are not listed.
        val withoutStory = scripts.filter { s -> s.questionType != QuestionType.ROLEPLAY.name && storyIdsOf(s).none { it in known } }
        return Result(uses, withoutStory)
    }

    /** Other scripts that tell the same story as [script], for the "이 이야기를 쓰는 다른 답변" line. */
    fun siblings(script: ScriptEntity, stories: List<StoryEntity>, scripts: List<ScriptEntity>): List<Pair<StoryEntity, List<ScriptEntity>>> {
        val ids = storyIdsOf(script)
        return stories.filter { it.id in ids }.map { story ->
            story to scripts.filter { it.id != script.id && story.id in storyIdsOf(it) }
        }
    }

    fun reuseQuestions(
        script: ScriptEntity,
        stories: List<StoryEntity>,
        scripts: List<ScriptEntity>,
        curated: List<String>,
    ): List<String> {
        val links = siblings(script, stories, scripts)
        if (links.isEmpty() || script.type == QuestionType.ROLEPLAY) return emptyList()
        val originalQuestion = TextNormalizer.normalize(script.question)
        val answer = TextNormalizer.normalize(script.studyText)
        val reusedAnswerQuestions = links.flatMap { it.second }
            .filter { it.questionType == script.questionType && answer.isNotEmpty() && TextNormalizer.normalize(it.studyText) == answer }
            .sortedBy { it.id }
            .map { it.question }
        return (curated + reusedAnswerQuestions + reuseAngles(script.type))
            .map { it.trim() }
            .filter { TextNormalizer.normalize(it).let { question -> question.isNotEmpty() && question != originalQuestion } }
            .distinctBy(TextNormalizer::normalize)
            .take(2)
    }

    // The learner's explicit story link grounds these angles. They make no assumptions about its setting or events.
    private fun reuseAngles(type: QuestionType): List<String> = when (type) {
        QuestionType.PAST_EXPERIENCE -> listOf(
            "Think back to that experience. What happened first, and what did you do next?",
            "What part of that experience stands out most to you? Tell me why.",
            "How did the experience turn out? Tell me what you remember about the ending.",
        )
        QuestionType.DESCRIPTION -> listOf(
            "How would you describe it to someone who has never seen or experienced it?",
            "Which detail would you point out first? What makes that detail stand out?",
            "What do you remember most clearly about it? Describe that detail.",
        )
        QuestionType.ROUTINE -> listOf(
            "Walk me through what you usually do, in the order you do it.",
            "Which part of your routine matters most to you? What makes it important?",
            "What do you usually do to get started? Tell me what happens next.",
        )
        QuestionType.COMPARISON -> listOf(
            "What is the biggest difference between the two things you described?",
            "What is one thing the two sides have in common? What sets them apart?",
            "If you had to explain the contrast in one example, which example would you choose?",
        )
        QuestionType.ISSUE -> listOf(
            "How has this issue affected you or what you have seen firsthand?",
            "Which part of the issue do you think deserves the most attention? Why?",
            "How would you explain the issue to someone who has never heard about it?",
        )
        QuestionType.SELF_INTRO, QuestionType.UNEXPECTED, QuestionType.OTHER -> listOf(
            "Which detail from this story would you tell someone first? Why?",
            "What do you remember most clearly about this story? Tell me a little more about it.",
            "What made this story worth sharing with someone else?",
        )
        QuestionType.ROLEPLAY -> emptyList()
    }
}
