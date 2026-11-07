package com.opicih.coach.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import java.time.ZonedDateTime

/**
 * Exam reminders for REGISTERED exams with a known start time: the evening before (20:00) and on the day.
 * Only facts the user entered are used; no registration or result dates are invented.
 * Two reminders per exam at most, on top of the morning alarm, to keep notifications few.
 */
object ExamReminders {
    data class Reminder(val requestCode: Int, val at: ZonedDateTime, val title: String, val text: String)

    private const val BASE = 6000
    private const val SLOTS = 2

    fun requestCode(registrationId: Long, slot: Int) = BASE + ((registrationId % 900).toInt() * SLOTS) + slot

    /** Pure: reminders that should exist now. Unit tested. */
    fun planned(regs: List<ExamRegistrationEntity>, now: ZonedDateTime): List<Reminder> =
        regs.filter { it.state == RegistrationStatus.REGISTERED && it.type != ExamType.UNKNOWN }
            .mapNotNull { r -> r.startAt()?.let { r to it } }
            .flatMap { (r, start) ->
                val where = r.location.ifBlank { null }?.let { " · $it" }.orEmpty()
                val time = r.startTimeLocal.orEmpty()
                val evening = start.toLocalDate().minusDays(1).atTime(20, 0).atZone(start.zone)
                // SAIL: the confirmation mail says to be seated 10 minutes before the start.
                val (dayAt, dayText) = if (r.type == ExamType.SAIL) {
                    val entry = start.minusMinutes(10)
                    entry.minusMinutes(30) to "입실 마감 ${entry.toLocalTime()}까지 30분 남았습니다$where. 시작 문장 3개만 소리 내어 읽고 들어가세요."
                } else {
                    start.minusMinutes(60) to "1시간 뒤 ${r.type.label} 시험이 시작됩니다($time)$where. 입실 시간은 접수 안내를 확인하세요."
                }
                listOf(
                    Reminder(requestCode(r.id, 0), evening, "내일 ${r.type.label} 시험 $time", "내일 $time 시험$where. 오늘은 가볍게 복습하고 일찍 쉬세요."),
                    Reminder(requestCode(r.id, 1), dayAt, "${r.type.label} 시험 당일", dayText),
                )
            }
            .filter { it.at.isAfter(now) }

    /** Schedules [planned] and cancels reminders of exams that are no longer registered or already passed. */
    fun sync(context: Context, regs: List<ExamRegistrationEntity>, now: ZonedDateTime) {
        val am = context.getSystemService(AlarmManager::class.java)
        val keep = planned(regs, now)
        val keepCodes = keep.map { it.requestCode }.toSet()
        regs.flatMap { r -> (0 until SLOTS).map { requestCode(r.id, it) } }.filter { it !in keepCodes }.forEach { code ->
            PendingIntent.getBroadcast(context, code, Intent(context, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_REMIND),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        keep.forEach { AlarmScheduler.scheduleReminderAt(context, it.at.toInstant().toEpochMilli(), it.title, it.text, it.requestCode, route = "home") }
    }
}
