package com.opicih.coach

import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.learning.ListenCycle
import com.opicih.coach.learning.ListenCycleBuilder
import com.opicih.coach.learning.ListenLang
import com.opicih.coach.learning.ListenPart
import com.opicih.coach.learning.ListenRole
import com.opicih.coach.learning.ListenSegment
import com.opicih.coach.learning.ListenSegments
import com.opicih.coach.learning.ListenStep
import com.opicih.coach.learning.ShuffleQueue
import com.opicih.coach.playback.ListenPlayer
import com.opicih.coach.playback.ListenPrefs
import com.opicih.coach.playback.ListenSpeaker
import com.opicih.coach.playback.ListenStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private class FakeSpeaker(
    private val koreanOk: Boolean = true,
    private val englishOk: Boolean = true,
    var speakMs: Long = 100,
    var failAll: Boolean = false,
) : ListenSpeaker {
    /** 끝까지 읽은 단계만 기록한다. 중간에 끊긴 단계는 남지 않는다. */
    val spoken = mutableListOf<Triple<String, ListenLang, ListenRole>>()
    override suspend fun supports(lang: ListenLang) = if (lang == ListenLang.EN) englishOk else koreanOk
    override suspend fun speak(text: String, lang: ListenLang, role: ListenRole, rate: Float): Boolean {
        delay(speakMs)
        if (failAll) return false
        spoken += Triple(text, lang, role)
        return true
    }
    override fun stop() {}
}

private fun cycle(id: Long, withKorean: Boolean = false) = ListenCycle(
    scriptId = id, title = "t$id", topic = "topic",
    steps = buildList {
        add(ListenStep(ListenPart.QUESTION, ListenLang.EN, "q$id"))
        if (withKorean) add(ListenStep(ListenPart.QUESTION, ListenLang.KO, "질문$id"))
        add(ListenStep(ListenPart.FULL, ListenLang.EN, "a$id"))
        if (withKorean) add(ListenStep(ListenPart.FULL, ListenLang.KO, "답변$id"))
    },
)

@OptIn(ExperimentalCoroutinesApi::class)
class ListenPlayerTest {
    private fun player(
        scope: kotlinx.coroutines.CoroutineScope,
        speaker: ListenSpeaker,
        ids: List<Long> = listOf(1, 2, 3),
        withKorean: Boolean = false,
        gap: Long = 0,
    ) = ListenPlayer(
        scope = scope, speaker = speaker, ids = { ids },
        cycle = { id -> cycle(id, withKorean) },
        prefs = { ListenPrefs(gapMs = gap) },
        random = Random(7),
    )

    @Test fun cycleFollowsTheDocumentedOrder() = runTest {
        val sp = FakeSpeaker()
        val p = player(backgroundScope, sp, ids = listOf(1), withKorean = true)
        p.play()
        advanceTimeBy(450)
        assertEquals(listOf("q1", "질문1", "a1", "답변1"), sp.spoken.take(4).map { it.first })
        assertEquals(
            listOf(ListenRole.QUESTION, ListenRole.QUESTION, ListenRole.ANSWER, ListenRole.ANSWER),
            sp.spoken.take(4).map { it.third },
        )
    }

    @Test fun questionAndAnswerUseDifferentRoles() = runTest {
        val sp = FakeSpeaker()
        player(backgroundScope, sp, ids = listOf(1)).play()
        advanceTimeBy(250)
        assertEquals(ListenRole.QUESTION, sp.spoken[0].third)
        assertEquals(ListenRole.ANSWER, sp.spoken[1].third)
    }

    @Test fun noScriptRepeatsBeforeTheWholeRoundIsPlayed() = runTest {
        val sp = FakeSpeaker()
        player(backgroundScope, sp).play()
        advanceTimeBy(100 * 2 * 9 + 50)
        val order = sp.spoken.map { it.first }.filter { it.startsWith("q") }
        assertEquals(9, order.size)
        order.chunked(3).forEach { assertEquals(setOf("q1", "q2", "q3"), it.toSet()) }
    }

    @Test fun pauseThenPlayRepeatsTheInterruptedStepOnly() = runTest {
        val sp = FakeSpeaker(speakMs = 1000)
        val p = player(backgroundScope, sp, ids = listOf(1))
        p.play()
        advanceTimeBy(1500) // q1 끝, a1 읽는 중
        p.pause()
        assertEquals(ListenStatus.PAUSED, p.state.value.status)
        assertEquals(listOf("q1"), sp.spoken.map { it.first })
        p.play()
        advanceTimeBy(1001)
        assertEquals(listOf("q1", "a1"), sp.spoken.map { it.first })
    }

    @Test fun nextJumpsToAnotherScript() = runTest {
        val sp = FakeSpeaker(speakMs = 1000)
        val p = player(backgroundScope, sp)
        p.play()
        advanceTimeBy(100)
        val first = p.state.value.scriptId
        p.next()
        advanceTimeBy(100)
        assertTrue(p.state.value.scriptId != first)
        assertEquals(ListenStatus.PLAYING, p.state.value.status)
    }

    @Test fun missingKoreanVoiceSkipsKoreanStepsAndKeepsPlayingEnglish() = runTest {
        val sp = FakeSpeaker(koreanOk = false)
        val p = player(backgroundScope, sp, ids = listOf(1), withKorean = true)
        p.play()
        advanceTimeBy(250)
        assertEquals(listOf("q1", "a1"), sp.spoken.take(2).map { it.first })
        assertTrue(sp.spoken.all { it.second == ListenLang.EN })
        assertTrue(p.state.value.koreanSkipped)
        assertNotNull(p.state.value.notice)
        assertEquals(ListenStatus.PLAYING, p.state.value.status)
    }

    @Test fun repeatedSpeakFailuresPauseInsteadOfLoopingForever() = runTest {
        val p = player(backgroundScope, FakeSpeaker(failAll = true))
        p.play()
        advanceTimeBy(5_000)
        assertEquals(ListenStatus.PAUSED, p.state.value.status)
        assertNotNull(p.state.value.notice)
    }

    @Test fun noSpeakableEnglishStopsInsteadOfSpinning() = runTest {
        val p = player(backgroundScope, FakeSpeaker(englishOk = false, koreanOk = false))
        p.play()
        advanceTimeBy(5_000)
        assertEquals(ListenStatus.PAUSED, p.state.value.status)
        assertNotNull(p.state.value.notice)
    }

    @Test fun emptyLibraryExplainsWhyNothingPlays() = runTest {
        val p = player(backgroundScope, FakeSpeaker(), ids = emptyList())
        p.play()
        advanceTimeBy(5_000)
        assertEquals(ListenStatus.PAUSED, p.state.value.status)
        assertNotNull(p.state.value.notice)
    }

    @Test fun stopReturnsToIdle() = runTest {
        val p = player(backgroundScope, FakeSpeaker(speakMs = 1000))
        p.play()
        advanceTimeBy(100)
        p.stop()
        assertEquals(ListenStatus.IDLE, p.state.value.status)
        assertEquals("", p.state.value.text)
    }
}

class ListenCycleTest {
    private fun script(
        active: ScriptVersion = ScriptVersion.ORIGINAL, ihTarget: String? = null, ko: String? = "한국어 뜻",
    ) = ScriptEntity(
        id = 5, topic = "Movies", title = "영화", question = "Tell me about movies.",
        original = "I like movies. They are fun.", ihTarget = ihTarget, activeVersion = active.name, koreanMeaning = ko,
    )

    @Test fun buildsQuestionFullParagraphSentenceInOrder() {
        val seg = ListenSegments(
            paragraphs = listOf(ListenSegment("P1", "문단1")),
            sentences = listOf(ListenSegment("S1", "문장1")),
        )
        val steps = ListenCycleBuilder.build("Q", "질문", "Full", "전체", seg, Random(1))
        assertEquals(
            listOf("Q", "질문", "Full", "전체", "P1", "문단1", "S1", "문장1"),
            steps.map { it.text },
        )
        assertEquals(
            listOf(ListenPart.QUESTION, ListenPart.QUESTION, ListenPart.FULL, ListenPart.FULL, ListenPart.PARAGRAPH, ListenPart.PARAGRAPH, ListenPart.SENTENCE, ListenPart.SENTENCE),
            steps.map { it.part },
        )
    }

    @Test fun withoutSegmentsOnlyQuestionAndFullPlay() {
        val steps = ListenCycleBuilder.build("Q", null, "Full", "전체")
        assertEquals(listOf("Q", "Full", "전체"), steps.map { it.text })
    }

    @Test fun blankKoreanDropsOnlyTheKoreanStep() {
        val steps = ListenCycleBuilder.build("Q", "  ", "Full", "")
        assertEquals(listOf("Q", "Full"), steps.map { it.text })
        assertTrue(steps.all { it.lang == ListenLang.EN })
    }

    @Test fun koreanMeaningIsUsedOnlyWhenItMatchesTheStudyText() {
        // 원문이 학습본이고 IH Target 번역이 아직 없다면 사용자가 쓴 한국어 뜻이다.
        assertTrue(ListenCycleBuilder.forScript(script(), null).steps.any { it.lang == ListenLang.KO })
        // AI가 IH Target을 만들어 koreanMeaning을 덮어썼다면 원문 학습본의 번역이 아니다.
        assertFalse(ListenCycleBuilder.forScript(script(ihTarget = "I really enjoy movies."), null).steps.any { it.lang == ListenLang.KO })
        // IH Target이 학습본이면 그 번역이다.
        assertTrue(ListenCycleBuilder.forScript(script(ScriptVersion.IH_TARGET, ihTarget = "I really enjoy movies."), null).steps.any { it.lang == ListenLang.KO })
        // Natural 같은 다른 버전은 번역이 없으므로 한국어 단계를 건너뛴다.
        assertFalse(ListenCycleBuilder.forScript(script(ScriptVersion.NATURAL), null).steps.any { it.lang == ListenLang.KO })
    }

    @Test fun playsTheApprovedStudyVersion() {
        val s = script(ScriptVersion.IH_TARGET, ihTarget = "I really enjoy movies.")
        val full = ListenCycleBuilder.forScript(s, "영화 얘기를 해 주세요.").steps.first { it.part == ListenPart.FULL && it.lang == ListenLang.EN }
        assertEquals("I really enjoy movies.", full.text)
    }
}

class ShuffleQueueTest {
    @Test fun everyRoundContainsEveryIdOnceAndRoundsNeverRepeatAtTheBoundary() {
        val q = ShuffleQueue(Random(3))
        val ids = listOf(1L, 2L, 3L, 4L)
        val seq = List(ids.size * 50) { q.next(ids)!! }
        seq.chunked(ids.size).forEach { assertEquals(ids.toSet(), it.toSet()) }
        seq.zipWithNext().forEach { (a, b) -> assertTrue("연속 중복: $a, $b", a != b) }
    }

    @Test fun deletedIdsAreDroppedFromTheCurrentRound() {
        val q = ShuffleQueue(Random(1))
        val first = q.next(listOf(1, 2, 3))!!
        val rest = listOf(1L, 2L, 3L) - first
        val after = List(rest.size - 1) { q.next(rest.drop(1))!! }
        assertFalse(after.contains(rest.first()))
    }

    @Test fun singleScriptKeepsPlayingItself() {
        val q = ShuffleQueue(Random(1))
        assertEquals(9L, q.next(listOf(9)))
        assertEquals(9L, q.next(listOf(9)))
    }

    @Test fun emptyLibraryYieldsNull() {
        assertEquals(null, ShuffleQueue().next(emptyList()))
    }
}
