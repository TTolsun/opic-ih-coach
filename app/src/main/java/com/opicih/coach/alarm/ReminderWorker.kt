package com.opicih.coach.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opicih.coach.App
import com.opicih.coach.learning.ProgressCalculator
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Evening reminder (study notification, not an exact alarm): only fires if today's goal is not met. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val s = app.container.settings.current()
        if (!s.eveningReminder) return Result.success()
        // Study blocks replace the evening reminder to avoid double nagging.
        if (s.studyBlocksEnabled) return Result.success()
        val hour = LocalDateTime.now().hour
        if (hour < 19 || hour > 22) return Result.success()
        val log = app.container.dao.dailyLog(LocalDate.now().toEpochDay())
        val done = ProgressCalculator.todayCompletion(log, s.studyLevel.minutes, 2)
        if (done < 0.7f) {
            Notifications.showReminder(applicationContext, "오늘 완료율 ${(done * 100).toInt()}%. 질문 하나만 2분 말해도 연속 학습이 이어집니다.")
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "evening_reminder"

        fun ensure(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val now = LocalDateTime.now()
            var target = now.withHour(20).withMinute(30).withSecond(0)
            if (!target.isAfter(now)) target = target.plusDays(1)
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, target).toMinutes(), TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
