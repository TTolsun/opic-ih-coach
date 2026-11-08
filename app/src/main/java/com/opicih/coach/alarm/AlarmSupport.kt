package com.opicih.coach.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import com.opicih.coach.data.prefs.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The one place that decides between an exact and an inexact alarm, shared by every scheduler. */
object AlarmTrigger {
    /**
     * Schedules [operation] at [at]. Exact when [preferExact] and the permission allows it: as an alarm clock when
     * [show] is given (alarm icon, survives Doze), otherwise as an exact while-idle alarm. Falls back to an inexact
     * while-idle alarm, also when the system revokes the permission between the check and the call.
     */
    fun set(context: Context, at: Long, operation: PendingIntent, preferExact: Boolean = true, show: PendingIntent? = null): AlarmScheduler.Mode {
        val am = context.getSystemService(AlarmManager::class.java)
        return try {
            if (preferExact && AlarmScheduler.canExact(context)) {
                if (show != null) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                AlarmScheduler.Mode.EXACT
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                AlarmScheduler.Mode.INEXACT
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            AlarmScheduler.Mode.INEXACT
        }
    }
}

/** Re-applies every settings-driven alarm. Used at app start, after reboot, and after a settings change. */
object AlarmCoordinator {
    /** Returns the morning alarm mode (OFF when disabled). One failing scheduler does not stop the others. */
    fun applyAll(context: Context, s: Settings): AlarmScheduler.Mode {
        val mode = runCatching {
            if (s.alarmEnabled) AlarmScheduler.schedule(context, s.alarmHour, s.alarmMinute, s.useExactAlarm)
            else { AlarmScheduler.cancel(context); AlarmScheduler.Mode.OFF }
        }.getOrDefault(AlarmScheduler.Mode.OFF)
        runCatching { ReminderWorker.ensure(context, s.eveningReminder) }
        runCatching { StudyBlockScheduler.sync(context, s) }
        return mode
    }
}

/** Runs [block] off the main thread and keeps the receiver alive until it finishes. */
fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try { block() } finally { pending.finish() }
    }
}
