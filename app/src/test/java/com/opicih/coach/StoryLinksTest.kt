package com.opicih.coach

import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.seed.SeedContent
import com.opicih.coach.data.seed.SeedScripts
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
        assertEquals(8, back.version)
        assertEquals(listOf(own), back.stories.map { it.toEntity() })
        // A version-7 file has no stories field; it still decodes and restores none.
        assertTrue(decode("""{"version":7}""").stories.isEmpty())
    }
}
