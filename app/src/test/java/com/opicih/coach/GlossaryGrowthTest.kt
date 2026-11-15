package com.opicih.coach

import com.opicih.coach.learning.Glossary
import com.opicih.coach.learning.GlossaryEntry
import com.opicih.coach.learning.GlossaryGrowth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryGrowthTest {
    private val seed = Glossary(listOf(GlossaryEntry("fatigue", meaningKo = "피로")))
    private val generated = listOf("I like to wind down with tea. It relieves my fatigue.", "Short answer.")
    private fun entry(phrase: String, forms: List<String> = listOf(phrase), meaning: String = "뜻", kind: String = "WORD") =
        GlossaryEntry(phrase, forms, kind, meaning)

    private fun accept(vararg c: GlossaryEntry, original: String = "I drink tea.", gen: List<String> = generated, existing: Glossary = seed) =
        GlossaryGrowth.accept(c.toList(), existing, gen, original)

    @Test fun keepsFormPresentInGeneratedText() {
        val r = accept(entry("wind down", kind = "IDIOM", meaning = " 긴장을 풀다 "))
        assertEquals(1, r.size)
        assertEquals(listOf("wind down"), r[0].forms)
        assertEquals("긴장을 풀다", r[0].meaningKo)
        assertEquals("IDIOM", r[0].kind)
    }

    @Test fun dropsFormsThatAreNotInTheText() {
        assertTrue(accept(entry("calm down")).isEmpty())
        val r = accept(entry("wind down", forms = listOf("wind down", "winding down")))
        assertEquals(listOf("wind down"), r[0].forms)
    }

    @Test fun dropsFormsTheGlossaryAlreadyKnows() {
        assertTrue(accept(entry("fatigue")).isEmpty())
        val r = accept(entry("relieve", forms = listOf("relieves", "fatigue")))
        assertEquals(listOf("relieves"), r[0].forms)
    }

    @Test fun dropsKnownHeadwordEvenWithANewForm() {
        // "relieves my fatigue" is new as a form, but the headword "fatigue" must keep the bundled meaning.
        assertTrue(accept(entry("fatigue", forms = listOf("relieves my fatigue"))).isEmpty())
    }

    @Test fun dropsEntryTheLearnerAlreadyWrote() {
        assertTrue(accept(entry("wind down"), original = "I wind down with tea.").isEmpty())
    }

    @Test fun dropsBlankMeaningOrPhrase() {
        assertTrue(accept(entry("wind down", meaning = "  "), entry("  ")).isEmpty())
    }

    @Test fun invalidKindFallsBackByLength() {
        val r = accept(entry("wind down", kind = "PHRASE"), entry("relieves", kind = "x"))
        assertEquals(listOf("IDIOM", "WORD"), r.map { it.kind })
    }

    @Test fun dedupesByPhraseAndCapsAtTwelve() {
        val words = (1..20).map { "word$it" }
        val gen = listOf(words.joinToString(" "))
        val r = GlossaryGrowth.accept(words.map { entry(it) } + entry("WORD1"), seed, gen, "")
        assertEquals(12, r.size)
        assertEquals(1, accept(entry("relieves"), entry("RELIEVES")).size)
    }
}
