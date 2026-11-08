package com.opicih.coach

import com.opicih.coach.alarm.ExamReminders
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ExamRemindersTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private fun at(m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(2026, m, d, h, min, 0, 0, kst)
    private val sail = ExamRegistrationEntity(id = 1, examType = ExamType.SAIL.name, dateLocal = "2026-11-11", startTimeLocal = "19:20",
        endTimeLocal = "20:20", location = "동탄", status = RegistrationStatus.REGISTERED.name)

    @Test fun sailGetsEveningBeforeAndEntryReminder() {
        val r = ExamReminders.planned(listOf(sail), at(11, 8, 9))
        assertEquals(listOf(at(11, 10, 20), at(11, 11, 18, 40)), r.map { it.at })
        assertTrue(r[1].text.contains("19:10"))
        assertEquals(2, r.map { it.requestCode }.toSet().size)
    }

    @Test fun passedRemindersAreDropped() {
        val r = ExamReminders.planned(listOf(sail), at(11, 11, 12))
        assertEquals(listOf(at(11, 11, 18, 40)), r.map { it.at })
        assertTrue(ExamReminders.planned(listOf(sail), at(11, 11, 19)).isEmpty())
    }

    @Test fun onlyRegisteredExamsWithATime() {
        val regs = listOf(
            sail.copy(id = 2, status = RegistrationStatus.CANCELLED.name),
            sail.copy(id = 3, status = RegistrationStatus.PLANNED.name),
            sail.copy(id = 4, startTimeLocal = null),
        )
        assertTrue(ExamReminders.planned(regs, at(11, 8, 9)).isEmpty())
    }

    @Test fun deletedRegistrationRemindersAreCancelled() {
        // Last sync scheduled reminders for registration 1; it was then deleted, so regs is empty.
        val previous = setOf(ExamReminders.requestCode(1, 0), ExamReminders.requestCode(1, 1))
        assertEquals(previous, ExamReminders.staleCodes(previous, emptyList(), emptySet()))
    }

    @Test fun keptRemindersAreNotCancelled() {
        val keep = ExamReminders.planned(listOf(sail), at(11, 8, 9)).map { it.requestCode }.toSet()
        assertTrue(ExamReminders.staleCodes(keep, listOf(sail), keep).isEmpty())
    }

    @Test fun firstSyncSweepsWholeRange() {
        val stale = ExamReminders.staleCodes(null, emptyList(), emptySet())
        assertTrue(ExamReminders.requestCode(899, 1) in stale)
        assertEquals(1800, stale.size)
    }

    @Test fun opicDoesNotInventAnEntryRule() {
        val opic = sail.copy(id = 5, examType = ExamType.OPIC.name, dateLocal = "2026-11-29", startTimeLocal = "10:00", endTimeLocal = null)
        val r = ExamReminders.planned(listOf(opic), at(11, 8, 9))
        assertEquals(at(11, 29, 9), r[1].at)
        assertTrue(r[1].text.contains("접수 안내를 확인"))
    }
}
