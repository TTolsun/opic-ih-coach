package com.opicih.coach.learning

import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.ScriptEntity

/** Pure progress math. Everything is based on recorded speaking/review activity, never on app-open time. */
object ProgressCalculator {

    fun streak(logs: List<DailyLogEntity>, todayEpochDay: Long, minSpeakingMs: Long = 60_000): Int {
        val active = logs.filter { it.speakingMs >= minSpeakingMs || it.chunksReviewed >= 5 }.map { it.epochDay }.toSet()
        var day = if (todayEpochDay in active) todayEpochDay else todayEpochDay - 1
        var n = 0
        while (day in active) { n++; day-- }
        return n
    }

    /** Today's completion 0..1: speaking time 60%, morning routine 20%, script reviews 20%. */
    fun todayCompletion(log: DailyLogEntity?, goalMinutes: Int, reviewGoal: Int): Float {
        if (log == null) return 0f
        val speakingGoalMs = (goalMinutes * 0.4f * 60_000).toLong().coerceAtLeast(3 * 60_000)
        val speak = (log.speakingMs.toFloat() / speakingGoalMs).coerceAtMost(1f)
        val morning = if (log.morningDone) 1f else (log.chunksReviewed / 10f).coerceAtMost(1f)
        val review = if (reviewGoal <= 0) 1f else (log.scriptsReviewed.toFloat() / reviewGoal).coerceAtMost(1f)
        return speak * 0.6f + morning * 0.2f + review * 0.2f
    }

    /**
     * Learning-only "IH 준비도" 0..100. Weighted: script mastery 30%, recent free/variation/mock speaking 50%,
     * activity breadth 20%. This is NOT a grade prediction.
     */
    fun ihReadiness(scripts: List<ScriptEntity>, attempts: List<PracticeAttemptEntity>): Int {
        val mastery = if (scripts.isEmpty()) 0f else scripts.map { it.masteryScore }.average().toFloat()
        val free = attempts.filter { it.mode in setOf("FREE", "VARIATION", "MOCK", "BLIND", "RANDOM") }.take(10)
        val freeScore = if (free.isEmpty()) 0f else free.map { it.score }.average().toFloat()
        val topics = attempts.map { it.topic }.toSet().size
        val breadth = (topics / 8f).coerceAtMost(1f) * 100f
        return (mastery * 0.3f + freeScore * 0.5f + breadth * 0.2f).toInt().coerceIn(0, 100)
    }

    data class TopicStat(val topic: String, val attempts: Int, val avgScore: Int, val avgWords: Int)

    fun topicStats(attempts: List<PracticeAttemptEntity>): List<TopicStat> =
        attempts.groupBy { it.topic }.map { (t, list) ->
            TopicStat(t, list.size, list.map { it.score }.average().toInt(), list.map { it.wordCount }.average().toInt())
        }.sortedBy { it.avgScore }

    /** Weak topics: lowest average score with at least one attempt, or Survey topics never practiced. */
    fun weakTopics(attempts: List<PracticeAttemptEntity>, survey: Set<String>, max: Int = 3): List<String> {
        val stats = topicStats(attempts.take(60))
        val never = survey.filter { s -> stats.none { it.topic.equals(s, true) } }
        val low = stats.filter { it.avgScore < 70 }.map { it.topic }
        return (never + low).distinct().take(max)
    }

    fun freeSpeakingRatio(attempts: List<PracticeAttemptEntity>): Float {
        if (attempts.isEmpty()) return 0f
        return attempts.count { it.mode in setOf("FREE", "VARIATION", "MOCK", "BLIND", "RANDOM") }.toFloat() / attempts.size
    }

    fun weeklyGoalRate(logs: List<DailyLogEntity>, todayEpochDay: Long, goalMinutes: Int): Float {
        val week = (todayEpochDay - 6..todayEpochDay)
        val ok = week.count { d -> logs.firstOrNull { it.epochDay == d }?.let { todayCompletion(it, goalMinutes, 1) >= 0.7f } == true }
        return ok / 7f
    }

    fun chunkDueCount(chunks: List<ChunkEntity>, today: Long) = chunks.count { it.dueEpochDay <= today }
}
