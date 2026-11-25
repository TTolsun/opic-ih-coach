package com.opicih.coach

import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.seed.AppointmentSeed
import com.opicih.coach.data.seed.SeedScript
import com.opicih.coach.data.seed.SeedScripts
import com.opicih.coach.data.seed.installScene
import com.opicih.coach.learning.BundledSegments
import com.opicih.coach.learning.ListenCycleBuilder
import com.opicih.coach.learning.ListenLang
import com.opicih.coach.learning.ListenPart
import com.opicih.coach.learning.ScriptSegmenter
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
    } + Json.decodeFromString<List<AppointmentSeed>>(asset("appointments.json")).map {
        SeedScript("Appointments", it.title, it.type, question = it.question, original = it.original)
    }

    @Test fun allOriginalQuestionsOpenWithTheirOwnPlayableTranslatedAnswer() {
        assertEquals(30, scenes.size)
        assertEquals(seed.map { it.title to it.question }.toSet(), scenes.map { it.title to it.question }.toSet())
        for ((index, item) in seed.withIndex()) {
            val scene = scenes.single { it.question == item.question }
            val original = ScriptEntity(id = index + 1L, topic = item.topic, title = item.title, question = item.question,
                original = item.original, source = if (index in 23..25) ContentSource.AI_SAMPLE.name else ContentSource.SEED_USER.name,
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
            assertEquals(listOf(ListenLang.KO to script.studyMeaning, ListenLang.EN to script.studyText), full.map { it.lang to it.text })
            with(BackupManager.Companion) { assertEquals(script, script.toBackup().toEntity()) }
        }
    }

    @Test fun installationPreservesImportsAndNeverResetsEditsOrALaterChoice() {
        val scene = scenes.first()
        val imported = ScriptEntity(topic = "Travel", title = scene.title, question = scene.question, original = "My notes.")
        assertSame(imported, imported.installScene(scene))
        val base = imported.copy(source = ContentSource.SEED_USER.name, original = seed.first().original, natural = "My personal version.")
        listOf(base.copy(original = "Personal original."), base.copy(koreanMeaning = "내 번역"),
            base.copy(activeVersion = ScriptVersion.NATURAL.name)).forEach {
            assertSame(it, it.installScene(scene))
        }
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

    @Test fun knownShippedSceneRefreshesOnceAndPreservesPersonalVersionsAndEdits() {
        val item = scenes.first()
        val old = ScriptEntity(topic = "Travel", title = item.title, question = item.question,
            source = ContentSource.SEED_USER.name, original = "Original.", sceneText = "Previous.",
            sceneMeaning = "이전 번역", sceneKeywords = "old", natural = "My answer.",
            activeVersion = ScriptVersion.NATURAL.name, masteryScore = 58, dueEpochDay = 21001)
        val update = item.copy(previousSignatures = listOf("earlier release", ScriptSegmenter.sourceHash(item.question, "Previous.\u0000이전 번역\u0000old")))
        val refreshed = old.installScene(update)
        assertEquals(item.answerText(), refreshed.sceneText)
        assertEquals("My answer.", refreshed.studyText)
        assertEquals(old.masteryScore, refreshed.masteryScore)
        assertEquals(old.dueEpochDay, refreshed.dueEpochDay)
        assertSame(refreshed, refreshed.installScene(update))
        listOf(old.copy(sceneText = "Edited."), old.copy(sceneMeaning = "내 번역"), old.copy(sceneKeywords = "mine")).forEach {
            assertSame(it, it.installScene(update))
        }
    }

    @Test fun everySharedSceneIsUsedIntactByMultipleQuestions() {
        val file = BundledSegments.parse(asset("scene_scripts.json"))
        val uses = scenes.flatMap { item -> item.parts.mapNotNull { part -> part.scene?.let { it to item } } }
        for ((name, sentences) in file.sharedScenes) {
            val questions = uses.filter { it.first == name }.map { it.second }.distinct()
            assertTrue(name, questions.size >= 2)
            assertTrue(name, sentences.isNotEmpty() && sentences.all { it.en.isNotBlank() && it.ko.isNotBlank() })
            questions.forEach { item -> assertEquals(sentences, item.parts.single { it.scene == name }.sentences) }
        }
    }
}
