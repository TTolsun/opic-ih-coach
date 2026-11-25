package com.opicih.coach

import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.learning.BundledSegments
import com.opicih.coach.learning.Glossary
import com.opicih.coach.learning.MorningRoutineBuilder
import com.opicih.coach.learning.MorningStep
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MorningScriptCardsTest {
    private val assets = listOf("src/main/assets/seed", "app/src/main/assets/seed").map(::File).first { it.isDirectory }
    private val glossary = Glossary.parse(assets.resolve("glossary.json").readText())
    private val scripts = BundledSegments.parse(assets.resolve("scene_scripts.json").readText()).items.map {
        ScriptEntity(topic = "Test", title = it.title, question = it.question, original = "",
            sceneText = it.answerText(), activeVersion = ScriptVersion.SCENE.name)
    }

    @Test fun adjacentDaysOfferDifferentPhrasesFromTheActiveBundledAnswersEvenWithoutRatings() {
        val pool = MorningRoutineBuilder.pool(scripts, glossary, emptyList())
        assertTrue(pool.all { card -> scripts.any { it.studyText.contains(card.example) } && card.example.contains(card.phrase) })
        assertEquals(pool.size, pool.distinctBy { it.phrase.lowercase() }.size)
        val days = (100L..106L).map { day ->
            val items = MorningRoutineBuilder.build(pool, day)
            assertEquals(items, MorningRoutineBuilder.build(pool.reversed(), day))
            items.filter { it.step == MorningStep.NEW }.map { it.chunk.phrase }.toSet()
        }
        assertTrue(days.all { it.size == 5 })
        assertTrue(days.zipWithNext().all { (a, b) -> a.intersect(b).isEmpty() })
        assertTrue(days.flatten().distinct().size >= 25)
    }

    @Test fun changingOrRemovingAScriptUpdatesTheExampleAndKeepsExistingReviews() {
        val script = scripts.first().copy(original = "I lose track of time at work.", activeVersion = ScriptVersion.ORIGINAL.name)
        val card = MorningRoutineBuilder.pool(listOf(script), glossary, emptyList()).single { it.phrase == "lose track of time" }
            .copy(id = 83, lastResult = "FAIL", dueEpochDay = 200)
        val edited = script.copy(original = "At home I lose track of time.")
        val pool = MorningRoutineBuilder.pool(listOf(edited), glossary, listOf(card))
        val review = MorningRoutineBuilder.build(pool, 200).first()
        assertEquals(MorningStep.WRONG, review.step)
        assertEquals(83L, review.chunk.id)
        assertEquals(edited.original, review.chunk.example)
        assertFalse(MorningRoutineBuilder.build(pool, 199).any { it.chunk.id == card.id })
        assertTrue(MorningRoutineBuilder.pool(emptyList(), glossary, listOf(card)).isEmpty())
        val saved = card.copy(source = ContentSource.SCRIPT_LOOKUP.name)
        assertEquals(listOf(saved), MorningRoutineBuilder.pool(emptyList(), glossary, listOf(saved)))
    }

    @Test fun dueCardsAndUserSavedCardsHaveRoomBesideNewScriptPhrases() {
        val active = MorningRoutineBuilder.pool(scripts, glossary, emptyList())
        val saved = ChunkEntity(id = 91, phrase = "my own phrase", example = "my own phrase", meaningKo = "내 표현",
            category = "", source = ContentSource.SCRIPT_LOOKUP.name)
        val due = saved.copy(id = 92, phrase = "due phrase", lastResult = "OK", dueEpochDay = 90)
        val chosen = MorningRoutineBuilder.build(active + listOf(saved, due), 100).filter { it.step == MorningStep.NEW }
        assertTrue(chosen.any { it.chunk == saved } && chosen.any { it.chunk == due })
        assertEquals(3, chosen.count { it.chunk.source == ContentSource.ACTIVE_SCRIPT.name })
    }
}
