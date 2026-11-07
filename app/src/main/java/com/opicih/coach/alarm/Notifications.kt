package com.opicih.coach.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opicih.coach.MainActivity
import com.opicih.coach.R

object Notifications {
    const val CH_ALARM_SOUND = "morning_alarm_sound"
    const val CH_ALARM_SILENT = "morning_alarm_silent"
    const val CH_REMINDER = "study_reminder"
    const val ID_MORNING = 1001
    const val ID_REMINDER = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_ALARM_SOUND, "아침 학습 알람 (소리)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "매일 아침 학습 시작 알람"
            setSound(alarmSound, attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALARM_SILENT, "아침 학습 알람 (무음)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "소리 없이 진동/알림만"
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "학습 리마인더", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "오늘 목표를 아직 채우지 못했을 때 저녁 알림"
        })
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun showMorning(context: Context, dLabel: String, mission: String, sound: Boolean, vibrate: Boolean) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context, 300, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "morning")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val speak = PendingIntent.getActivity(
            context, 301, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "speaking")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = PendingIntent.getBroadcast(
            context, 302, Intent(context, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_SNOOZE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = if (sound) CH_ALARM_SOUND else CH_ALARM_SILENT
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("$dLabel · 오늘의 OPIc 훈련")
            .setContentText(mission)
            .setStyle(NotificationCompat.BigTextStyle().bigText(mission))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "지금 시작", open)
            .addAction(0, "바로 말하기", speak)
            .addAction(0, "10분 후", snooze)
        if (!vibrate) b.setVibrate(longArrayOf(0))
        runCatching { NotificationManagerCompat.from(context).notify(ID_MORNING, b.build()) }
    }

    fun showReminder(context: Context, text: String, title: String = "오늘 말하기 목표가 남았습니다", route: String = "speaking") {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context, if (route == "speaking") 310 else 311, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(if (route == "speaking") ID_REMINDER else ID_REMINDER + 1, n) }
    }

    fun cancelMorning(context: Context) = NotificationManagerCompat.from(context).cancel(ID_MORNING)
}
