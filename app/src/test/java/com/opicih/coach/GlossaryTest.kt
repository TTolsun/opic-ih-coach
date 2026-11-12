package com.opicih.coach

import com.opicih.coach.data.repo.GlossaryRepository
import com.opicih.coach.data.seed.SeedScripts
import com.opicih.coach.learning.Glossary
import com.opicih.coach.learning.GlossaryEntry
import com.opicih.coach.learning.LookupCounter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class GlossaryTest {
    private fun asset(name: String): File = listOf("src/main/assets/seed", "app/src/main/assets/seed")
        .map { File(it, name) }.first { it.exists() }

    private val glossary by lazy { Glossary.parse(asset("glossary.json").readText()) }

    @Test fun assetLoadsWithUniquePhrases() {
        val entries = Json { ignoreUnknownKeys = true }.decodeFromString(com.opicih.coach.learning.GlossaryFile.serializer(), asset("glossary.json").readText()).entries
        assertTrue(entries.size >= 200)
        assertEquals(entries.size, entries.map { it.phrase.lowercase() }.toSet().size)
        assertTrue(entries.all { it.kind == "IDIOM" || it.kind == "WORD" })
        assertTrue(entries.all { it.meaningKo.isNotBlank() })
    }

    @Test fun everyEntryCanBeLookedUpIncludingPhrasesFromPreviouslyApprovedScripts() {
        val entries = Json { ignoreUnknownKeys = true }.decodeFromString(com.opicih.coach.learning.GlossaryFile.serializer(), asset("glossary.json").readText()).entries
        val missing = entries.filter { entry ->
            entry.allForms.none { form -> glossary.spans(form).any { it.entry == entry } }
        }.map { it.phrase }
        assertTrue("entries never matched: $missing", missing.isEmpty())
    }

    @Test fun pilotScriptsUseRecognizedReusablePhrases() {
        val pilot = Json.decodeFromString(SeedScripts.serializer(), asset("user_scripts_2026.json").readText())
            .scripts.filter { it.refreshRevisions }
        val found = pilot.flatMap { glossary.spans(it.ihTarget) }.map { it.entry.phrase }.toSet()
        assertTrue(found.containsAll(listOf("back then", "take a nap", "go back to", "fly (항공사)")))
    }

    @Test fun longestIdiomWinsOverItsWords() {
        val text = "The water was steaming hot, which was perfect for relieving my fatigue."
        val spans = glossary.spans(text)
        val idiom = spans.single { it.entry.phrase == "relieve my fatigue" }
        assertEquals("relieving my fatigue", text.substring(idiom.start, idiom.end))
        assertTrue(spans.none { it.entry.phrase == "fatigue" })
    }

    @Test fun tapInsideIdiomShowsIdiomAndWord() {
        val text = "I felt completely rejuvenated."
        val tap = glossary.lookupAt(text, text.indexOf("rejuvenated") + 2)!!
        assertEquals("feel rejuvenated", tap.entry!!.phrase)
        assertEquals("felt completely rejuvenated", tap.surface)
        assertEquals("rejuvenated", tap.wordEntry!!.phrase)
    }

    @Test fun curlyApostropheAndCaseMatch() {
        val text = "I COULDN’T RESIST, so I ate a lot."
        assertEquals("couldn't resist", glossary.spans(text).first().entry.phrase)
    }

    @Test fun unknownWordStillResolvesWithoutEntry() {
        val text = "Japan is close."
        val tap = glossary.lookupAt(text, 1)!!
        assertNull(tap.entry)
        assertEquals("Japan", tap.surface)
    }

    @Test fun tapOnSpaceOrPunctuationIsIgnored() {
        val text = "Hi,  there."
        assertNull(glossary.lookupAt(text, 4))
    }

    @Test fun sentenceAroundPicksTheContainingSentence() {
        val text = "First sentence. I bumped into her really hard! Then I left."
        val s = text.indexOf("bumped")
        assertEquals("I bumped into her really hard!", Glossary.sentenceAround(text, s, s + 6))
        assertEquals("First sentence.", Glossary.sentenceAround(text, 0, 5))
        assertEquals("Then I left.", Glossary.sentenceAround(text, text.indexOf("left"), text.length - 1))
    }

    @Test fun lookupCountsDifferentDaysOnly() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(LookupCounter.isNewDay(null, at(8, 9), zone))
        assertFalse(LookupCounter.isNewDay(at(8, 9), at(8, 23), zone))
        assertTrue(LookupCounter.isNewDay(at(8, 23), at(9, 0), zone))
        assertFalse(LookupCounter.shouldAutoAdd(1, alreadyInReview = false))
        assertTrue(LookupCounter.shouldAutoAdd(2, alreadyInReview = false))
        assertFalse(LookupCounter.shouldAutoAdd(5, alreadyInReview = true))
    }

    @Test fun sentenceAroundKeepsAbbreviations() {
        val text = "I lived in the U.S. for 3.5 years. Then I came back."
        val i = text.indexOf("lived")
        assertEquals("I lived in the U.S. for 3.5 years.", Glossary.sentenceAround(text, i, i + 5))
    }

    @Test fun reviewKeysCoverEveryForm() {
        val e = GlossaryEntry(phrase = "end up -ing", forms = listOf("End up breaking up", "end up"), meaningKo = "결국")
        assertEquals(listOf("end up breaking up", "end up", "end up -ing"), GlossaryRepository.reviewKeys(e))
        // A surface with a curly apostrophe or a line break matches the card saved from it.
        assertTrue("couldn't resist" in GlossaryRepository.reviewKeys(e, "Couldn’t\n resist"))
        assertNotNull(glossary.entry("END UP -ING"))
    }
}
