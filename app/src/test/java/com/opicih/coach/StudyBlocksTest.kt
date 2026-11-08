package com.opicih.coach

import com.opicih.coach.alarm.BlockEvent
import com.opicih.coach.alarm.BlockKind
import com.opicih.coach.alarm.Decision
import com.opicih.coach.alarm.StudyBlock
import com.opicih.coach.alarm.StudyBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StudyBlocksTest {
    private val blocks = StudyBlocks.parse(StudyBlocks.DEFAULT_ENCODED)
    private val day = LocalDate.of(2026, 11, 10)
    private fun min(m: Long) = m * 60_000L
    private fun ev(i: Int, kind: BlockKind, at: LocalDateTime) = BlockEvent(at, i, kind, day)

    @Test fun studyDateBoundaryIsFourAm() {
        assertEquals(day.minusDays(1), StudyBlocks.studyDate(day.atTime(3, 59)))
        assertEquals(day, StudyBlocks.studyDate(day.atTime(4, 0)))
        assertEquals(day, StudyBlocks.studyDate(day.plusDays(1).atTime(0, 0)))
    }

    @Test fun midnightBlockBelongsToSameStudyDay() {
        val b = blocks[2]
        assertEquals(1440, b.startMinute)
        val start = StudyBlocks.startOf(day, b)
        assertEquals(day.plusDays(1).atStartOfDay(), start)
        assertEquals(day, StudyBlocks.studyDate(start))
    }

    @Test fun eventsOrderingAndNagCount() {
        val e = StudyBlocks.events(day, listOf(StudyBlock(1440, 60)))
        assertEquals(7, e.size)
        assertEquals(BlockKind.START, e.first().kind)
        assertEquals(BlockKind.END, e.last().kind)
        val nags = e.filter { it.kind == BlockKind.NAG }
        assertEquals(5, nags.size)
        assertEquals(listOf(10L, 20, 30, 40, 50).map { day.plusDays(1).atStartOfDay().plusMinutes(it) }, nags.map { it.at })
        assertEquals(day.plusDays(1).atTime(1, 0), e.last().at)
        assertEquals(e.sortedBy { it.at }, e)
    }

    @Test fun silentWhenCumulativeGoalMet() {
        val e = ev(1, BlockKind.START, day.atTime(19, 0))
        assertEquals(Decision.Silent, StudyBlocks.decide(e, blocks, min(60), 0, 0))
        assertTrue(StudyBlocks.decide(e, blocks, min(59), 0, 0) is Decision.Notify)
    }

    @Test fun carryOverRaisesLaterBlockRemaining() {
        // Block 1 studied only 10 of 40 min -> block 2 must cover 30 + 20 = 50.
        val d = StudyBlocks.decide(ev(1, BlockKind.START, day.atTime(19, 0)), blocks, min(10), 0, 10_000_000) as Decision.Notify
        assertTrue(d.text, d.text.contains("50분"))
        assertTrue(d.text, d.text.contains("10/120분"))
        assertTrue(d.loud)
        assertEquals("speaking", d.route)
        val first = StudyBlocks.decide(ev(0, BlockKind.START, day.atTime(7, 30)), blocks, 0, 0, 10_000_000) as Decision.Notify
        assertEquals("morning", first.route)
        assertTrue(first.text.contains("40분"))
    }

    @Test fun nagSilentWhenRecentlyActive() {
        val e = ev(0, BlockKind.NAG, day.atTime(7, 40))
        val now = 1_000_000_000L
        assertEquals(Decision.Silent, StudyBlocks.decide(e, blocks, min(5), now - 60_000, now))
        val n = StudyBlocks.decide(e, blocks, min(5), now - 130_000, now) as Decision.Notify
        assertTrue(n.loud)
        assertTrue(n.text, n.text.contains("35분 남았습니다"))
        assertTrue(n.text, n.text.contains("30분 뒤"))
    }

    @Test fun endOfEarlierBlockCarriesOver() {
        val d = StudyBlocks.decide(ev(0, BlockKind.END, day.atTime(8, 10)), blocks, min(25), 0, 0) as Decision.Notify
        assertTrue(!d.loud)
        assertTrue(d.text, d.text.contains("미달 15분"))
        assertTrue(d.text, d.text.contains("19:00"))
    }

    @Test fun lastBlockEndGivesRecoveryMessage() {
        val d = StudyBlocks.decide(ev(2, BlockKind.END, day.plusDays(1).atTime(1, 0)), blocks, min(90), 0, 0) as Decision.Notify
        assertEquals("오늘 목표 120분 중 90분. 지금 10분만 말하면 연속 기록이 이어집니다.", d.text)
        assertEquals("speaking", d.route)
        assertEquals(Decision.Silent, StudyBlocks.decide(ev(2, BlockKind.END, day.plusDays(1).atTime(1, 0)), blocks, min(120), 0, 0))
    }

    @Test fun nextEventRollsToNextStudyDate() {
        // After the last END (01:00, still study day D) the next event is block 1 START on D+1 at 07:30.
        val n = StudyBlocks.nextEvent(day.plusDays(1).atTime(1, 30), blocks)!!
        assertEquals(day.plusDays(1).atTime(7, 30), n.at)
        assertEquals(day, n.studyDate.minusDays(1))
        assertEquals(BlockKind.START, n.kind)
        // Strictly after: exactly at an event time yields the following event.
        val m = StudyBlocks.nextEvent(day.atTime(7, 30), blocks)!!
        assertEquals(day.atTime(7, 40), m.at)
        assertEquals(BlockKind.NAG, m.kind)
        assertNull(StudyBlocks.nextEvent(day.atTime(7, 30), emptyList()))
    }

    @Test fun parseIsDefensive() {
        assertEquals(blocks, StudyBlocks.parse("1440:60, 450:40,1140:20"))
        assertEquals(listOf(StudyBlock(450, 5), StudyBlock(600, 180)), StudyBlocks.parse("600:999,450:1,abc,:5,5:,9999:30,10:20:30,-5:10"))
        assertTrue(StudyBlocks.parse("").isEmpty())
        assertTrue(StudyBlocks.parse(null).isEmpty())
        assertEquals(StudyBlocks.DEFAULT_ENCODED, StudyBlocks.encode(blocks))
    }
}

class StudyBlocksStartWhileActiveTest {
    @org.junit.Test
    fun startIsQuietWhenAlreadyStudying() {
        val blocks = StudyBlocks.parse(StudyBlocks.DEFAULT_ENCODED)
        val day = java.time.LocalDate.of(2026, 11, 9)
        val e = BlockEvent(day.atTime(19, 0), 1, BlockKind.START, day)
        val d = StudyBlocks.decide(e, blocks, 30 * 60_000L, lastActiveAt = 1_000_000, now = 1_030_000) as Decision.Notify
        org.junit.Assert.assertFalse(d.loud)
    }
}
