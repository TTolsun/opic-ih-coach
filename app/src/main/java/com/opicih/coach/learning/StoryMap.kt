package com.opicih.coach.learning

import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.StoryEntity

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
}
