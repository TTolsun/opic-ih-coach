package com.opicih.coach

import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.importer.DocxTextExtractor
import com.opicih.coach.importer.DuplicateLinker
import com.opicih.coach.importer.ScriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScriptParserTest {
    private fun fixture(): String = javaClass.classLoader!!.getResource("my_scripts_2026.txt")!!.readText(Charsets.UTF_8)

    @Test fun parsesUsersTxtExport() {
        val list = ScriptParser.parse(fixture())
        assertEquals(23, list.size)
        assertEquals(7, list.map { it.topic }.distinct().size)
        val first = list.first()
        assertEquals("Home & Furniture", first.topic)
        assertEquals("Description of your house", first.title)
        assertTrue(first.question.startsWith("Could you describe your home"))
        assertTrue(first.answer.contains("6th floor"))
        // Korean label without a quoted question is kept as the question.
        assertTrue(list.any { it.question == "음악 취향 변화" && it.answer.contains("rainism") })
        // Topic with a parenthetical alias is trimmed.
        assertTrue(list.any { it.topic == "Overseas Travel" })
        // Multi-line answers keep their continuation lines.
        val comp = list.first { it.title.startsWith("Comparison") }
        assertTrue(comp.answer.contains("big change"))
        assertEquals(QuestionType.COMPARISON, comp.questionType)
    }

    @Test fun parsesGoogleDocsMarkdownExport() {
        val md = """
            # **2026년 OPIc Script**
            1.  **\#\# \*\*Topic:\*\*\*\*  &#10;\*\*\*\*Movies\*\***
                1.  **Question:****
                    ****Favorite Movies:** "What kind of movies do you like to watch?"

                      - Answer:
                        I usually enjoy romance movies.
                        It's very touching.
                2.  ​Question:
                    Tell me about a memorable vacation you took recently.
                      - Answer:
                        I took a 4-day trip to Osaka.
        """.trimIndent()
        val list = ScriptParser.parse(md)
        assertEquals(2, list.size)
        assertEquals("Movies", list[0].topic)
        assertEquals("Favorite Movies", list[0].title)
        assertEquals("What kind of movies do you like to watch?", list[0].question)
        assertEquals("I usually enjoy romance movies.\nIt's very touching.", list[0].answer)
        assertEquals("Tell me about a memorable vacation you took recently.", list[1].question)
    }

    @Test fun parsesSimpleQaFormat() {
        val text = "## Hobbies\nQ: What do you do in your free time?\nA: I usually go jogging.\nIt makes me feel alive.\nQ: Tell me about your favorite park.\nA: Central Park is near my house."
        val list = ScriptParser.parse(text)
        assertEquals(2, list.size)
        assertEquals("Hobbies", list[0].topic)
        assertTrue(list[0].answer.contains("feel alive"))
    }

    @Test fun unknownFormatFallsBackToOneScript() {
        val list = ScriptParser.parse("Just some English paragraph without any structure at all.", fallbackTitle = "note")
        assertEquals(1, list.size)
        assertEquals("note", list[0].title)
    }

    @Test fun largeInputIsHandled() {
        val block = fixture()
        val big = (1..40).joinToString("\n") { block }
        val t0 = System.currentTimeMillis()
        val list = ScriptParser.parse(big)
        assertEquals(23 * 40, list.size)
        assertTrue("parse took too long", System.currentTimeMillis() - t0 < 5000)
    }

    @Test fun duplicateLinkerGroupsNearDuplicates() {
        val a = "I live on the sixth floor of an apartment with three rooms and a big gray sofa in the living room."
        val items = listOf(
            DuplicateLinker.Item(1, "Describe your home in detail.", a),
            DuplicateLinker.Item(2, "Could you describe your home in detail?", a + " It is comfy."),
            DuplicateLinker.Item(3, "Tell me about your favorite movie.", "I like La La Land because of the music and the dancing scenes."),
        )
        val g = DuplicateLinker.groups(items)
        assertEquals(g[1L], g[2L])
        assertTrue(3L !in g)
    }
}

class DocxTextExtractorTest {
    private val xml = """<w:document><w:body><w:p><w:r><w:t>1. Movies</w:t></w:r></w:p><w:p><w:r><w:t xml:space="preserve">Favorite: "What </w:t></w:r><w:r><w:t>kind?"</w:t></w:r></w:p><w:p><w:r><w:t>* I like &amp; enjoy films.</w:t></w:r></w:p></w:body></w:document>"""

    @Test fun extractsParagraphs() {
        assertEquals("1. Movies\nFavorite: \"What kind?\"\n* I like & enjoy films.", DocxTextExtractor.fromDocumentXml(xml))
    }

    @Test fun readsZipContainer() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<x/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
        }
        val text = DocxTextExtractor.extract(ByteArrayInputStream(bos.toByteArray()))
        assertTrue(text.startsWith("1. Movies"))
        assertEquals(1, ScriptParser.parse(text).size)
    }

    @Test(expected = com.opicih.coach.importer.ImportException::class)
    fun brokenDocxThrowsReadableError() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("other.xml")); z.write("x".toByteArray()); z.closeEntry() }
        DocxTextExtractor.extract(ByteArrayInputStream(bos.toByteArray()))
    }
}
