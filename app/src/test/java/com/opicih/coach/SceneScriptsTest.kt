package com.opicih.coach

import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.seed.SeedScripts
import com.opicih.coach.data.seed.installScene
import com.opicih.coach.learning.BundledSegments
import com.opicih.coach.learning.ListenCycleBuilder
import com.opicih.coach.learning.ListenLang
import com.opicih.coach.learning.ListenPart
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SceneScriptsTest {
    private fun asset(file: String) = listOf("src/main/assets/seed", "app/src/main/assets/seed")
        .map { File(it, file) }.first { it.isFile }.readText()
    private val scenes = BundledSegments.parse(asset("scene_scripts.json")).items
    private val seed = listOf("user_scripts_2026.json", "roleplay_templates.json").flatMap {
        Json.decodeFromString(SeedScripts.serializer(), asset(it)).scripts
    }

    @Test fun allOriginalQuestionsOpenWithTheirOwnPlayableTranslatedAnswer() {
        assertEquals(26, scenes.size)
        assertEquals(seed.map { it.title to it.question }.toSet(), scenes.map { it.title to it.question }.toSet())
        for ((index, item) in seed.withIndex()) {
            val scene = scenes.single { it.question == item.question }
            val original = ScriptEntity(id = index + 1L, topic = item.topic, title = item.title, question = item.question,
                original = item.original, source = if (index < 23) ContentSource.SEED_USER.name else ContentSource.AI_SAMPLE.name,
                storyIds = item.stories, masteryScore = 61, srsStep = 3, dueEpochDay = 21000)
            val script = original.installScene(scene)
            assertEquals(original.original, script.original)
            assertEquals(original.masteryScore, script.masteryScore)
            assertEquals(original.dueEpochDay, script.dueEpochDay)
            assertEquals(ScriptVersion.SCENE, script.active)
            assertEquals(scene.answerText(), script.studyText)
            assertEquals(scene.keywords, script.keywordList())
            assertTrue(script.keywordList().isNotEmpty())
            assertNotNull(item.title, BundledSegments.rowsFor(item.question, script.studyText, scenes))
            val full = ListenCycleBuilder.forScript(script, null).steps.filter { it.part == ListenPart.FULL }
            assertEquals(listOf(ListenLang.EN to script.studyText, ListenLang.KO to script.studyMeaning), full.map { it.lang to it.text })
            with(BackupManager.Companion) { assertEquals(script, script.toBackup().toEntity()) }
        }
    }

    @Test fun installationPreservesImportsAndNeverResetsEditsOrALaterChoice() {
        val scene = scenes.first()
        val imported = ScriptEntity(topic = "Travel", title = scene.title, question = scene.question, original = "My notes.")
        assertSame(imported, imported.installScene(scene))
        val base = imported.copy(source = ContentSource.SEED_USER.name, natural = "My personal version.")
        val chosen = base.installScene(scene).copy(activeVersion = ScriptVersion.NATURAL.name)
        assertSame(chosen, chosen.installScene(scene))
        assertEquals("My personal version.", chosen.studyText)
        val edited = chosen.copy(sceneText = "My edited answer.", activeVersion = ScriptVersion.SCENE.name)
        assertSame(edited, edited.installScene(scene))
        assertEquals("My edited answer.", edited.studyText)
    }

    @Test fun osakaScenesAndRepeatedStomachachesKeepTheirOwnOutcomes() {
        fun answer(topic: String, title: String) = scenes.single {
            it.question == seed.single { s -> s.topic == topic && s.title == title }.question
        }.answerText().lowercase()
        val trip = answer("Overseas Travel", "Recent Trip")
        val meal = answer("Overseas Travel", "Memorable Experience")
        val vacation = answer("Vacation", "Memorable Vacation")
        assertTrue("hot spring" in trip && "stomachache" !in trip)
        assertTrue("takoyaki" in meal && "hot spring" !in meal)
        assertTrue("hot spring" in vacation && "drugstore" in vacation)
        assertTrue("friend's house" in answer("Domestic Travel", "Travel Experience"))
        assertTrue("final shot" in answer("Movies", "Memorable Experience"))
    }
}
