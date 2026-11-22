package com.opicih.coach

import com.opicih.coach.data.db.SegmentKind
import com.opicih.coach.data.seed.AppointmentSeed
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppointmentSeedTest {
    private val items = listOf("src/main/assets/seed", "app/src/main/assets/seed")
        .map { File(it, "appointments.json") }.first { it.isFile }
        .let { Json.decodeFromString<List<AppointmentSeed>>(it.readText()) }

    @Test fun freshInstallRecreatesFourOriginalsAndEveryTranslatedSentence() {
        val expected = mapOf(
            "Routine Health Checkup - IH" to ("40ca0b286004301c" to 10),
            "Childhood Visit to the Dentist - IH" to ("2a450079fb7c7144" to 8),
            "Appointment Locations - IH" to ("2d5888306042a422" to 8),
            "Appointment Routine - IH" to ("52cc150894f15f52" to 6),
        )
        assertEquals(expected.keys, items.map { it.title }.toSet())
        assertEquals(4, items.size)
        for (item in items) {
            val script = item.newScript(21000).copy(id = 42)
            val (hash, count) = expected.getValue(script.title)
            val rows = item.translationsFor(script, emptyList())
            assertEquals("ORIGINAL", script.activeVersion)
            assertTrue(rows.all { it.scriptId == script.id && it.sourceHash == hash && it.ko.isNotBlank() })
            assertEquals(item.questionKo, rows.single { it.kind == SegmentKind.QUESTION.name }.ko)
            val sentences = rows.filter { it.kind == SegmentKind.SENTENCE.name }
            assertEquals(count, sentences.size)
            assertEquals(item.sentenceKo, sentences.map { it.ko })
            assertEquals(script.studyText.lines().joinToString(" "), sentences.joinToString(" ") { it.en })
            assertEquals(item.sentenceKo.joinToString(" "), rows.single { it.kind == SegmentKind.FULL.name }.ko)
            assertTrue(item.translationsFor(script, rows).isEmpty())
        }
    }

    @Test fun upgradeRecognizesExistingImportsAndRepairsOnlyTheKnownCheckupErrors() {
        val item = items.single { it.title == "Routine Health Checkup - IH" }
        val script = item.newScript(21000).copy(id = 7)
        assertTrue(item.matches(script.copy(title = "My checkup")))
        val edited = script.copy(original = "My own answer.")
        assertTrue(item.matches(edited))
        assertTrue(item.translationsFor(edited, emptyList()).isEmpty())
        val current = item.translationsFor(script, emptyList())
        val old = current.map {
            it.copy(en = it.en.replace("again?\"", "again?"), ko = it.ko.replace("사내 의원", "회사 내 사제의원"))
        }
        assertNotEquals(current, old)
        assertEquals(current, item.translationsFor(script, old))
        val personal = current.map { it.copy(ko = "직접 작성한 뜻") }
        assertTrue(item.translationsFor(script, personal).isEmpty())
    }
}
