package com.opicih.coach

import com.opicih.coach.learning.BundledItem
import com.opicih.coach.learning.BundledSegments
import com.opicih.coach.learning.BundledSentence
import com.opicih.coach.learning.ScriptSegmenter
import com.opicih.coach.data.db.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledSegmentsTest {
    private fun asset(): File = listOf("src/main/assets/seed/segments_ko.json", "app/src/main/assets/seed/segments_ko.json")
        .map(::File).first { it.exists() }

    @Test fun bundledFileIsCompleteAndWellFormed() {
        val items = BundledSegments.parse(asset().readText()).items
        assertEquals(23, items.size)
        assertEquals("해시가 겹치지 않아야 합니다", items.size, items.map { it.hash }.toSet().size)
        items.forEach { it ->
            assertTrue("hash ${it.hash}", it.hash.matches(Regex("[0-9a-f]{16}")))
            assertTrue("${it.title}: 질문 번역", it.questionKo.isNotBlank())
            assertTrue("${it.title}: 문장", it.sentences.isNotEmpty())
            it.sentences.forEach { s ->
                assertTrue("${it.title}: 빈 조각", s.en.isNotBlank() && s.ko.isNotBlank())
                assertTrue("${it.title}: 한국어가 한글을 포함해야 합니다", s.ko.any { c -> c in '가'..'힣' })
            }
        }
    }

    @Test fun storedSentencesSurviveTheSplitterUnchanged() {
        // 문장을 이어 붙여 다시 나눠도 같은 목록이 나와야 분할 규칙이 바뀌었을 때 어긋남을 알 수 있다.
        BundledSegments.parse(asset().readText()).items.forEach { item ->
            val again = ScriptSegmenter.plan(item.sentences.joinToString(" ") { it.en }).sentences
            assertEquals(item.title, item.sentences.map { it.en }, again)
        }
    }

    @Test fun matchingTextProducesAlignedRowsAndAnyChangeIsRejected() {
        val q = "Tell me about X?"
        val text = "I like X. It is fun. We went there. It was nice."
        val plan = ScriptSegmenter.plan(text)
        val item = BundledItem(
            hash = ScriptSegmenter.sourceHash(q, text), question = q, questionKo = "X에 대해 말해 주세요.",
            sentences = plan.sentences.mapIndexed { i, en -> BundledSentence(en, "번역$i") },
        )
        val rows = BundledSegments.rowsFor(q, text, listOf(item))
        assertNotNull(rows)
        assertEquals("X에 대해 말해 주세요.", rows!!.single { it.kind == SegmentKind.QUESTION }.ko)
        assertEquals(plan.sentences.size, rows.count { it.kind == SegmentKind.SENTENCE })
        // 학습본이 한 글자라도 바뀌면 해시가 달라져 가져오지 않는다.
        assertNull(BundledSegments.rowsFor(q, "$text!", listOf(item)))
        assertNull(BundledSegments.rowsFor("Another question?", text, listOf(item)))
        // 해시가 같아도 영어 문장 목록이 다르면(분할 규칙이 달라진 경우) 쓰지 않는다.
        val drifted = item.copy(sentences = item.sentences.dropLast(1))
        assertNull(BundledSegments.rowsFor(q, text, listOf(drifted)))
    }
}
