package com.opicih.coach.alarm

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Study block contract: fixed daily blocks whose quota is measured; the app re-alarms until it is met.
 * [startMinute] counts from the study date's midnight and may exceed 1440 (24:00 = 1440) so that a
 * midnight block still belongs to the same study day.
 */
data class StudyBlock(val startMinute: Int, val minutes: Int)

enum class BlockKind { START, NAG, END }

/** [studyDate] is the study day the block belongs to (not necessarily the calendar date of [at]). */
data class BlockEvent(val at: LocalDateTime, val blockIndex: Int, val kind: BlockKind, val studyDate: LocalDate)

sealed interface Decision {
    data object Silent : Decision
    data class Notify(val title: String, val text: String, val loud: Boolean, val route: String) : Decision
}

object StudyBlocks {
    const val DAY_BOUNDARY_HOUR = 4
    const val MAX_START_MINUTE = 1679
    const val NAG_INTERVAL_MIN = 10
    const val ACTIVE_WINDOW_MS = 120_000L
    const val MAX_BLOCKS = 5
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 180
    const val DEFAULT_ENCODED = "450:40,1140:20,1440:60"

    /** A study day runs 04:00 to 03:59 the next morning. */
    fun studyDate(now: LocalDateTime): LocalDate = now.minusHours(DAY_BOUNDARY_HOUR.toLong()).toLocalDate()

    fun startOf(studyDate: LocalDate, block: StudyBlock): LocalDateTime =
        studyDate.atStartOfDay().plusMinutes(block.startMinute.toLong())

    fun endOf(studyDate: LocalDate, block: StudyBlock): LocalDateTime = startOf(studyDate, block).plusMinutes(block.minutes.toLong())

    fun dailyGoalMinutes(blocks: List<StudyBlock>): Int = blocks.sumOf { it.minutes }

    fun cumulativeGoalMs(blocks: List<StudyBlock>, index: Int): Long =
        blocks.take(index + 1).sumOf { it.minutes } * 60_000L

    /** Defensive parse of "450:40,1140:20,1440:60": bad entries dropped, sorted by start, minutes clamped. */
    fun parse(encoded: String?): List<StudyBlock> =
        encoded.orEmpty().split(',').mapNotNull { part ->
            val p = part.trim().split(':')
            if (p.size != 2) return@mapNotNull null
            val start = p[0].trim().toIntOrNull() ?: return@mapNotNull null
            val min = p[1].trim().toIntOrNull() ?: return@mapNotNull null
            if (start !in 0..MAX_START_MINUTE) return@mapNotNull null
            StudyBlock(start, min.coerceIn(MIN_MINUTES, MAX_MINUTES))
        }.sortedBy { it.startMinute }.take(MAX_BLOCKS)

    fun encode(blocks: List<StudyBlock>): String = blocks.joinToString(",") { "${it.startMinute}:${it.minutes}" }

    /** "07:30"; blocks past midnight read "24:00", "24:30" so the label stays tied to the same study day. */
    fun label(startMinute: Int): String = "%02d:%02d".format(startMinute / 60, startMinute % 60)

    fun events(studyDate: LocalDate, blocks: List<StudyBlock>): List<BlockEvent> =
        blocks.flatMapIndexed { i, b ->
            val start = startOf(studyDate, b)
            val end = endOf(studyDate, b)
            buildList {
                add(BlockEvent(start, i, BlockKind.START, studyDate))
                var t = start.plusMinutes(NAG_INTERVAL_MIN.toLong())
                while (t.isBefore(end)) {
                    add(BlockEvent(t, i, BlockKind.NAG, studyDate))
                    t = t.plusMinutes(NAG_INTERVAL_MIN.toLong())
                }
                add(BlockEvent(end, i, BlockKind.END, studyDate))
            }
        }.sortedBy { it.at }

    /** First event strictly after [now], looking at the current study date and the next one. */
    fun nextEvent(now: LocalDateTime, blocks: List<StudyBlock>): BlockEvent? {
        if (blocks.isEmpty()) return null
        val d = studyDate(now)
        return (events(d, blocks) + events(d.plusDays(1), blocks)).filter { it.at.isAfter(now) }.minByOrNull { it.at }
    }

    private fun ceilMin(ms: Long) = ((ms + 59_999L) / 60_000L).toInt()

    fun decide(event: BlockEvent, blocks: List<StudyBlock>, studiedMs: Long, lastActiveAt: Long, now: Long): Decision {
        val block = blocks.getOrNull(event.blockIndex) ?: return Decision.Silent
        val goalMs = cumulativeGoalMs(blocks, event.blockIndex)
        if (studiedMs >= goalMs) return Decision.Silent
        val remain = ceilMin(goalMs - studiedMs)
        val done = (studiedMs / 60_000L).toInt()
        val daily = dailyGoalMinutes(blocks)
        val route = if (event.blockIndex == 0) "morning" else "speaking"
        val n = event.blockIndex + 1
        return when (event.kind) {
            BlockKind.START -> Decision.Notify(
                "학습 블록 $n 시작 · ${label(block.startMinute)}",
                "지금 ${remain}분이 필요합니다(이월 포함). 오늘 $done/${daily}분",
                // Already studying (started early): show it without ringing.
                now - lastActiveAt >= ACTIVE_WINDOW_MS, route,
            )
            BlockKind.NAG ->
                if (now - lastActiveAt < ACTIVE_WINDOW_MS) Decision.Silent
                else {
                    val left = ceilMin(Duration.between(event.at, endOf(event.studyDate, block)).toMillis())
                    Decision.Notify("학습 블록 $n · 아직 시작 전입니다", "아직 ${remain}분 남았습니다. 블록이 ${left}분 뒤 끝납니다.", true, route)
                }
            BlockKind.END ->
                if (event.blockIndex < blocks.lastIndex) {
                    val next = blocks[event.blockIndex + 1]
                    Decision.Notify("학습 블록 $n 미달", "블록 $n 미달 ${remain}분. 다음 블록(${label(next.startMinute)})으로 이월됩니다.", false, route)
                } else Decision.Notify(
                    "오늘 학습 목표 미달", "오늘 목표 ${daily}분 중 ${done}분. 지금 10분만 말하면 연속 기록이 이어집니다.", false, "speaking",
                )
        }
    }
}
