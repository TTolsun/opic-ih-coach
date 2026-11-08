package com.opicih.coach.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.opicih.coach.MainActivity
import com.opicih.coach.data.prefs.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** One chained alarm: always armed for the next block event; the receiver re-arms after each fire. */
object StudyBlockScheduler {
    const val ACTION_BLOCK = "com.opicih.coach.STUDY_BLOCK"
    const val EXTRA_AT = "block_at"
    const val EXTRA_INDEX = "block_index"
    const val EXTRA_KIND = "block_kind"
    const val EXTRA_STUDY_DAY = "block_study_day"
    private const val REQ = 7000
    private const val REQ_SHOW = 7001

    fun blocksOf(s: Settings): List<StudyBlock> = StudyBlocks.parse(s.studyBlocks)

    fun eventFromIntent(intent: Intent): BlockEvent? {
        val at = intent.getLongExtra(EXTRA_AT, -1L).takeIf { it > 0 } ?: return null
        val kind = runCatching { BlockKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull() ?: return null
        val day = intent.getLongExtra(EXTRA_STUDY_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
        return BlockEvent(ldt, intent.getIntExtra(EXTRA_INDEX, -1), kind, LocalDate.ofEpochDay(day))
    }

    private fun pending(context: Context, event: BlockEvent?): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).setAction(ACTION_BLOCK)
        if (event != null) {
            i.putExtra(EXTRA_AT, event.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .putExtra(EXTRA_INDEX, event.blockIndex).putExtra(EXTRA_KIND, event.kind.name)
                .putExtra(EXTRA_STUDY_DAY, event.studyDate.toEpochDay())
        }
        return PendingIntent.getBroadcast(context, REQ, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, null))
    }

    /** Re-arms the next event (or cancels everything when disabled / no blocks). Call after any settings change or fire. */
    fun sync(context: Context, s: Settings, now: LocalDateTime = LocalDateTime.now()) {
        cancel(context)
        if (!s.studyBlocksEnabled) return
        val event = StudyBlocks.nextEvent(now, blocksOf(s)) ?: return
        val at = event.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val show = PendingIntent.getActivity(
            context, REQ_SHOW, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "speaking"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        AlarmTrigger.set(context, at, pending(context, event), show = show)
    }
}
