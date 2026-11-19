package com.opicih.coach

import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.seed.SeedContent
import com.opicih.coach.data.seed.SeedScripts
import com.opicih.coach.data.seed.curatedReuseQuestions
import com.opicih.coach.data.seed.planStoryLinks
import com.opicih.coach.data.db.StoryEntity
import com.opicih.coach.learning.StoryMap
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoryLinksTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun asset(name: String) = File("src/main/assets/seed/$name").takeIf { it.exists() } ?: File("app/src/main/assets/seed/$name")
    private val seed = json.decodeFromString(SeedScripts.serializer(), asset("user_scripts_2026.json").readText()).scripts
    private val content = json.decodeFromString(SeedContent.serializer(), asset("content.json").readText())

    private fun seeded(title: String, topic: String, storyIds: String?, source: ContentSource = ContentSource.SEED_USER) =
        ScriptEntity(id = title.hashCode().toLong(), topic = topic, title = title, question = "Q", original = "o", source = source.name, storyIds = storyIds)

    @Test fun everyBundledScriptHasAStoryThatExists() {
        val ids = content.stories.map { it.id }.toSet()
        val missing = seed.filter { s -> s.stories.split(',').map { it.trim() }.none { it in ids } }
        assertEquals(emptyList<String>(), missing.map { "${it.topic} / ${it.title}" })
    }

    @Test fun linksOnlyBundledScriptsThatWereNeverLinked() {
        val scripts = listOf(
            seeded("Favorite Movies", "Movies", null),                                  // never linked → S8
            seeded("Music Taste Change", "Park & Music", ""),                          // learner chose "no story" → kept
            seeded("Movie Routine", "Movies", "S7"),                                   // already linked → kept
            seeded("Favorite Movies", "Movies", null, ContentSource.USER_IMPORT),      // learner's own script → kept
        )
        val updated = planStoryLinks(seed, scripts)
        assertEquals(listOf("S8"), updated.map { it.storyIds })
        assertEquals(ContentSource.SEED_USER.name, updated.single().source)
    }

    @Test fun roleplayTemplatesParseAndStayOutOfTheNoStoryList() {
        val templates = json.decodeFromString(SeedScripts.serializer(), asset("roleplay_templates.json").readText()).scripts
        assertEquals(3, templates.size)
        assertTrue(templates.all { it.original.isNotBlank() && it.keywords.isNotEmpty() && it.question.isNotBlank() })
        val roleplay = ScriptEntity(id = 1, topic = templates[0].topic, title = templates[0].title, question = "Q", original = "o",
            questionType = QuestionType.ROLEPLAY.name)
        val stories = content.stories.map { StoryEntity(it.id, it.titleKo, it.summary, it.tags, it.reusableFor) }
        assertTrue(StoryMap.build(stories, listOf(roleplay)).withoutStory.isEmpty())
    }

    @Test fun learnerMadeStoriesSurviveABackupRoundTrip() = with(BackupManager.Companion) {
        val own = StoryEntity("U1731560000000", "어릴 적 치과", "충치 치료가 무서웠음", "user", "병원, 어린 시절")
        val back = decode(encode(BackupManager.Snapshot(stories = listOf(own.toBackup()))))
        assertEquals(9, back.version)
        assertEquals(listOf(own), back.stories.map { it.toEntity() })
        // A version-7 file has no stories field; it still decodes and restores none.
        assertTrue(decode("""{"version":7}""").stories.isEmpty())
    }

    @Test fun everyBundledScriptHasTwoDistinctPromptsForItsOriginalAndBundledStudyVersions() {
        val stories = content.stories.map { StoryEntity(it.id, it.titleKo, it.summary, it.tags, it.reusableFor) }
        seed.forEach { item ->
            val original = seeded(item.title, item.topic, item.stories).copy(
                question = item.question, original = item.original,
                questionType = item.type, ihTarget = item.ihTarget, shortVersion = item.short,
            )
            ScriptVersion.entries.forEach { version ->
                val script = original.copy(activeVersion = version.name)
                val prompts = curatedReuseQuestions(script, seed, stories)
                assertEquals(item.title, 2, StoryMap.reuseQuestions(script, stories, listOf(script), prompts).size)
            }
        }
    }

    @Test fun oldSavedScriptsGetPromptsAfterStoryLinkBackfillWithoutLosingUserEdits() {
        val stories = content.stories.map { StoryEntity(it.id, it.titleKo, it.summary, it.tags, it.reusableFor) }
        val item = seed.first { it.topic == "Overseas Travel" && it.title == "Memorable Experience" }
        val old = seeded(item.title, item.topic, null).copy(question = item.question, original = item.original)
        val linked = planStoryLinks(seed, listOf(old)).single()
        assertEquals(item.reuseQuestions, curatedReuseQuestions(linked, seed, stories))
        assertTrue(curatedReuseQuestions(linked.copy(storyIds = ""), seed, stories).isEmpty())
        assertTrue(curatedReuseQuestions(linked.copy(storyIds = "S2"), seed, stories).isEmpty())
        assertTrue(curatedReuseQuestions(linked, seed, emptyList()).isEmpty())
        val edited = linked.copy(activeVersion = ScriptVersion.NATURAL.name, natural = "I traveled to a different city.")
        assertTrue(curatedReuseQuestions(edited, seed, stories).isEmpty())
    }

    @Test fun sameStoryFallbackRequiresTheSameAnswerAndIgnoresDuplicateQuestions() {
        val story = StoryEntity("U1", "여행", "여행 경험", "user", "여행")
        val current = seeded("Current", "Travel", "U1", ContentSource.USER_IMPORT).copy(
            id = 1, question = "What happened on the trip?", original = "I missed my train and took the bus.",
            questionType = QuestionType.PAST_EXPERIENCE.name,
        )
        val first = current.copy(id = 2, question = "Tell me about a time you missed public transportation.")
        val second = current.copy(id = 3, question = "How did you get to your destination after missing your train?")
        val candidates = listOf(
            current, second, first.copy(id = 6, question = first.question.lowercase()), first,
            current.copy(id = 7, question = current.question.uppercase()),
            current.copy(id = 8, question = "Where did you walk?", original = "I went for a walk downtown."),
            current.copy(id = 9, question = "Where would you travel next?", storyIds = "U2"),
            current.copy(id = 10, question = "Where do you usually go?", questionType = QuestionType.ROUTINE.name),
        )
        assertEquals(
            listOf(first.question, second.question),
            StoryMap.reuseQuestions(current, listOf(story), candidates, listOf(current.question.lowercase())),
        )
        assertTrue(StoryMap.reuseQuestions(current, emptyList(), candidates, emptyList()).isEmpty())
        assertEquals(first.question, StoryMap.reuseQuestions(current, listOf(story), listOf(current, first), emptyList()).first())
    }

    @Test fun linkedPersonalScriptGetsTwoAnglesWithoutOtherScriptsOrLocationAssumptions() {
        val story = StoryEntity("U1", "나의 경험", "", "", "")
        val script = ScriptEntity(
            id = 1, topic = "Personal", title = "My story", questionType = QuestionType.PAST_EXPERIENCE.name,
            question = "What part of that experience stands out most to you? Tell me why.",
            original = "I offered to help and learned a new skill.", storyIds = story.id,
            activeVersion = ScriptVersion.NATURAL.name, natural = "I volunteered to help. I learned a new skill.",
        )
        val questions = StoryMap.reuseQuestions(script, listOf(story), emptyList(), emptyList())
        assertEquals(
            listOf(
                "Think back to that experience. What happened first, and what did you do next?",
                "How did the experience turn out? Tell me what you remember about the ending.",
            ),
            questions,
        )
        assertTrue(StoryMap.reuseQuestions(script.copy(storyIds = ""), listOf(story), emptyList(), emptyList()).isEmpty())
    }
}
