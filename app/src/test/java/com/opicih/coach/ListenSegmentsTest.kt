package com.opicih.coach

import com.opicih.coach.ai.AiException
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.backup.BackupManager.Companion.toBackup
import com.opicih.coach.data.backup.BackupManager.Companion.toEntity
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptSegmentEntity
import com.opicih.coach.data.db.SegmentKind
import com.opicih.coach.learning.ListenCycleBuilder
import com.opicih.coach.learning.ListenLang
import com.opicih.coach.learning.ListenPart
import com.opicih.coach.learning.ListenSegment
import com.opicih.coach.learning.ListenSegments
import com.opicih.coach.learning.ScriptSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class ScriptSegmenterTest {
    private val oneBlock = "I went to Busan last year. It was sunny. I ate seafood. The food was great. I want to go again. See you."

    @Test fun singleBlockIsGroupedIntoBalancedParagraphsOfAtMostThreeSentences() {
        val plan = ScriptSegmenter.plan(oneBlock)
        assertEquals(6, plan.sentences.size)
        assertEquals(listOf(0..2, 3..5), plan.paragraphs)
        val seven = ScriptSegmenter.plan((1..7).joinToString(" ") { "Sentence $it here." })
        assertEquals(listOf(0..2, 3..4, 5..6), seven.paragraphs)
        assertTrue(seven.paragraphs.all { it.count() <= 3 })
    }

    @Test fun blankLinesDefineParagraphs() {
        val plan = ScriptSegmenter.plan("First one. First two.\n\nSecond one. Second two. Second three.\n\nThird.")
        assertEquals(6, plan.sentences.size)
        assertEquals(listOf(0..1, 2..4, 5..5), plan.paragraphs)
    }

    @Test fun aSingleParagraphIsOmittedBecauseItEqualsTheFullAnswer() {
        assertTrue(ScriptSegmenter.plan("I like movies. They are fun.").paragraphs.isEmpty())
    }

    @Test fun abbreviationsDoNotSplitSentences() {
        val plan = ScriptSegmenter.plan("I met Mr. Kim at the park. We talked for an hour.")
        assertEquals(listOf("I met Mr. Kim at the park.", "We talked for an hour."), plan.sentences)
    }

    @Test fun wordsThatMerelyEndLikeAnAbbreviationStillEndTheSentence() {
        val plan = ScriptSegmenter.plan("There are three rooms. One is my bedroom. It ended fast. I went home. I saw Dr. Lee. We left.")
        assertEquals(
            listOf("There are three rooms.", "One is my bedroom.", "It ended fast.", "I went home.", "I saw Dr. Lee.", "We left."),
            plan.sentences,
        )
    }

    @Test fun hashChangesWhenQuestionOrStudyTextChanges() {
        val h = ScriptSegmenter.sourceHash("Q", "answer text")
        assertEquals(h, ScriptSegmenter.sourceHash(" Q ", "answer text "))
        assertNotEquals(h, ScriptSegmenter.sourceHash("Q", "answer text!"))
        assertNotEquals(h, ScriptSegmenter.sourceHash("Q2", "answer text"))
    }

    @Test fun assembledKoreanCoversExactlyTheSameRangeAsTheEnglish() {
        val plan = ScriptSegmenter.plan(oneBlock)
        val ko = plan.sentences.indices.map { "한국어$it" }
        val rows = ScriptSegmenter.assemble("Q?", plan, "질문", ko)
        assertEquals("질문", rows.single { it.kind == SegmentKind.QUESTION }.ko)
        val p1 = rows.filter { it.kind == SegmentKind.PARAGRAPH }[1]
        assertEquals(plan.sentences.slice(3..5).joinToString(" "), p1.en)
        assertEquals("한국어3 한국어4 한국어5", p1.ko)
        assertEquals(plan.sentences.joinToString(" "), rows.single { it.kind == SegmentKind.FULL }.en)
        assertEquals(ko.joinToString(" "), rows.single { it.kind == SegmentKind.FULL }.ko)
        rows.filter { it.kind == SegmentKind.SENTENCE }.forEach { assertEquals("한국어${it.idx}", it.ko) }
    }

    @Test fun assembleRejectsAMismatchedTranslationCount() {
        val plan = ScriptSegmenter.plan(oneBlock)
        try { ScriptSegmenter.assemble("Q", plan, "질문", listOf("하나")); fail() } catch (_: IllegalArgumentException) {}
    }
}

class TranslationParseTest {
    @Test fun acceptsExactCountEvenWhenWrappedInFences() {
        val r = GeminiAiCoach.parseTranslations("```json\n{\"ko\":[\"하나\",\"둘\"]}\n```", 2)
        assertEquals(listOf("하나", "둘"), r)
    }

    @Test fun rejectsMergedOrSplitItemsInsteadOfGuessing() {
        try { GeminiAiCoach.parseTranslations("{\"ko\":[\"하나\"]}", 2); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("맞지 않습니다")) }
    }

    @Test fun rejectsBlankTranslationsAndMissingList() {
        try { GeminiAiCoach.parseTranslations("{\"ko\":[\"하나\",\" \"]}", 2); fail() } catch (_: AiException) {}
        try { GeminiAiCoach.parseTranslations("{\"x\":1}", 1); fail() } catch (_: AiException) {}
    }
}

class SegmentPlaybackTest {
    private fun script(active: String = "ORIGINAL", ihTarget: String? = null, ko: String? = null) = ScriptEntity(
        id = 9, topic = "T", title = "t", question = "Tell me about X.", original = "I like X. It is fun.",
        ihTarget = ihTarget, activeVersion = active, koreanMeaning = ko,
    )

    @Test fun freshSegmentsOverrideTheUntrustworthyKoreanMeaning() {
        // IH Target이 있는데 원문이 학습본이면 koreanMeaning은 믿을 수 없어 한국어를 건너뛴다. 세그먼트가 있으면 한국어가 나온다.
        val s = script(ihTarget = "I really like X.", ko = "IH 번역")
        assertFalse(ListenCycleBuilder.forScript(s, null).steps.any { it.lang == ListenLang.KO })
        val seg = ListenSegments(emptyList(), emptyList(), questionKo = "X에 대해 말해 주세요.", fullKo = "저는 X를 좋아해요. 재미있어요.")
        val steps = ListenCycleBuilder.forScript(s, null, seg).steps
        assertEquals(listOf("X에 대해 말해 주세요.", "저는 X를 좋아해요. 재미있어요."), steps.filter { it.lang == ListenLang.KO }.map { it.text })
    }

    @Test fun cycleContainsParagraphAndSentenceStepsWithTheirKorean() {
        val seg = ListenSegments(
            paragraphs = listOf(ListenSegment("P", "문단")), sentences = listOf(ListenSegment("S", "문장")),
            questionKo = "질문", fullKo = "전체",
        )
        val steps = ListenCycleBuilder.forScript(script(), null, seg, Random(1)).steps
        assertEquals(
            listOf(ListenPart.QUESTION, ListenPart.QUESTION, ListenPart.FULL, ListenPart.FULL, ListenPart.PARAGRAPH, ListenPart.PARAGRAPH, ListenPart.SENTENCE, ListenPart.SENTENCE),
            steps.map { it.part },
        )
        assertEquals(listOf("P", "문단", "S", "문장"), steps.drop(4).map { it.text })
    }
}

class SegmentBackupTest {
    @Test fun segmentsSurviveABackupRoundTrip() {
        val seg = ScriptSegmentEntity(scriptId = 4, version = "IH_TARGET", sourceHash = "abc", kind = "SENTENCE", idx = 2, en = "I ran.", ko = "달렸어요.", createdAt = 7)
        assertEquals(seg, seg.toBackup().toEntity())
        val snap = BackupManager.Snapshot(segments = listOf(seg.toBackup()))
        val back = BackupManager.decode(BackupManager.encode(snap))
        assertEquals(6, back.version)
        assertEquals(seg, back.segments.single().toEntity())
    }

    @Test fun olderBackupsWithoutSegmentsStillDecode() {
        val v4 = """{"version":4,"scripts":[],"lookups":[]}"""
        assertTrue(BackupManager.decode(v4).segments.isEmpty())
    }
}
