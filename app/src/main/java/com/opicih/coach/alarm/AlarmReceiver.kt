package com.opicih.coach.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opicih.coach.App
import com.opicih.coach.learning.PlanGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val s = app.container.settings.current()
                when (intent.action) {
                    AlarmScheduler.ACTION_REMIND -> Notifications.showReminder(
                        context, intent.getStringExtra(AlarmScheduler.EXTRA_TEXT).orEmpty(),
                        intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "OPIc IH Coach",
                        route = intent.getStringExtra(AlarmScheduler.EXTRA_ROUTE) ?: "nextExam",
                    )
                    AlarmScheduler.ACTION_SNOOZE -> {
                        Notifications.cancelMorning(context)
                        AlarmScheduler.snooze(context, 10)
                    }
                    AlarmScheduler.ACTION_FIRE -> {
                        val today = LocalDate.now()
                        val due = app.container.dao.scripts().count { it.dueEpochDay <= today.toEpochDay() }
                        val focus = app.container.exams.focus()
                        val plan = PlanGenerator.forExam(today, focus?.type, focus?.date, s.studyLevel.minutes, due, false)
                        val mission = "${plan.focus.titleKo} · 약 ${plan.totalMinutes}분\n아침 표현 5분부터 시작하세요. 복습할 스크립트 ${due}개"
                        Notifications.showMorning(context, plan.dLabel, mission, s.alarmSound, s.alarmVibrate)
                        // Re-arm for tomorrow (alarms are one-shot).
                        if (s.alarmEnabled) AlarmScheduler.schedule(context, s.alarmHour, s.alarmMinute, s.useExactAlarm)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Restores the alarm after reboot, app update, time change, or exact-alarm permission change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val s = app.container.settings.current()
                if (s.alarmEnabled) AlarmScheduler.schedule(context, s.alarmHour, s.alarmMinute, s.useExactAlarm)
                ReminderWorker.ensure(context, s.eveningReminder)
            } finally {
                pending.finish()
            }
        }
    }
}
