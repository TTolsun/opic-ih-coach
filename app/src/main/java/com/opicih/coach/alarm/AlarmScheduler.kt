package com.opicih.coach.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.opicih.coach.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Morning alarm scheduling. Exact alarms use setAlarmClock (shows the alarm icon and survives Doze);
 * without permission the app falls back to an inexact while-idle alarm and the Settings screen warns the user.
 */
object AlarmScheduler {
    const val ACTION_FIRE = "com.opicih.coach.ALARM_FIRE"
    const val ACTION_SNOOZE = "com.opicih.coach.ALARM_SNOOZE"
    const val ACTION_DISMISS = "com.opicih.coach.ALARM_DISMISS"
    const val ACTION_REMIND = "com.opicih.coach.REMIND"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    private const val REQ_MAIN = 100
    private const val REQ_SNOOZE = 101

    enum class Mode { EXACT, INEXACT, OFF }

    /** Pure: the next trigger time strictly after [now] for hh:mm in [zone]. Unit tested. */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t
    }

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun schedule(context: Context, hour: Int, minute: Int, preferExact: Boolean): Mode {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val at = nextTrigger(now, hour, minute).toInstant().toEpochMilli()
        return scheduleAt(context, at, preferExact, REQ_MAIN)
    }

    fun snooze(context: Context, minutes: Int = 10): Mode {
        val at = System.currentTimeMillis() + minutes * 60_000L
        return scheduleAt(context, at, true, REQ_SNOOZE)
    }

    /** One-off reminder (e.g. "register for the next exam") at 09:00 on [date]. Inexact is fine here. */
    fun scheduleReminder(context: Context, date: java.time.LocalDate, title: String, text: String, requestCode: Int = 400): Boolean {
        val at = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) return false
        val pi = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_REMIND).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return true
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(firePending(context, REQ_MAIN))
        am.cancel(firePending(context, REQ_SNOOZE))
    }

    private fun scheduleAt(context: Context, at: Long, preferExact: Boolean, req: Int): Mode {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = firePending(context, req)
        return try {
            if (preferExact && canExact(context)) {
                val show = PendingIntent.getActivity(
                    context, 200, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "morning"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                Mode.EXACT
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Mode.INEXACT
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Mode.INEXACT
        }
    }

    private fun firePending(context: Context, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, req, Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun describe(now: LocalDateTime, hour: Int, minute: Int): String {
        val next = nextTrigger(now.atZone(ZoneId.systemDefault()), hour, minute)
        val day = if (next.toLocalDate() == now.toLocalDate()) "오늘" else "내일"
        return "%s %02d:%02d".format(day, hour, minute)
    }
}
