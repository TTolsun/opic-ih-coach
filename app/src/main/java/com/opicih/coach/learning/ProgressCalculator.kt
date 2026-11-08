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
     * Learning-only "IH 준비도" 0..100. Weighted: script mastery 30%, recent 말하기 점수 attempts 50%,
     * activity breadth 20%. This is NOT a grade prediction.
     * 암기 점수 모드(Blind 포함)는 말하기 항목에 섞지 않습니다. 암송 결과가 즉석 말하기 실력처럼 보이면 안 되기 때문입니다.
     */
    fun ihReadiness(scripts: List<ScriptEntity>, attempts: List<PracticeAttemptEntity>): Int {
        val mastery = if (scripts.isEmpty()) 0f else scripts.map { it.masteryScore }.average().toFloat()
        val free = attempts.filter { it.mode in ScoreKind.SPEAKING_MODES }.take(10)
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
        return attempts.count { it.mode in ScoreKind.SPEAKING_MODES }.toFloat() / attempts.size
    }

    fun weeklyGoalRate(logs: List<DailyLogEntity>, todayEpochDay: Long, goalMinutes: Int): Float {
        val week = (todayEpochDay - 6..todayEpochDay)
        val ok = week.count { d -> logs.firstOrNull { it.epochDay == d }?.let { todayCompletion(it, goalMinutes, 1) >= 0.7f } == true }
        return ok / 7f
    }

    /** Pure: the daily log after removing one attempt that was recorded on that day. Never goes below zero. */
    fun withoutAttempt(log: DailyLogEntity, a: PracticeAttemptEntity): DailyLogEntity = log.copy(
        speakingMs = (log.speakingMs - a.effectiveSpeechMs).coerceAtLeast(0),
        attempts = (log.attempts - 1).coerceAtLeast(0),
        scriptsReviewed = (log.scriptsReviewed - if (a.scriptId != null) 1 else 0).coerceAtLeast(0),
    )

    /** Readiness signals since [sinceEpochMs] (normally the last exam), for RetestAdvisor. */
    fun readiness(attempts: List<PracticeAttemptEntity>, logs: List<DailyLogEntity>, sinceEpochDay: Long, sinceEpochMs: Long): RetestAdvisor.Readiness {
        val after = attempts.filter { it.createdAt >= sinceEpochMs }
        val noScript = after.filter { it.mode in ScoreKind.SPEAKING_MODES && it.wordCount > 0 }.take(15)
        val withPauses = noScript.filter { it.captureMode != "TYPED" }
        val mocks = after.filter { it.mockId != null }.groupBy { it.mockId }
            .filter { (_, l) -> l.size >= 5 }.mapValues { (_, l) -> l.map { it.score }.average().toInt() }
        val recentLogs = logs.filter { it.epochDay >= sinceEpochDay }
        return RetestAdvisor.Readiness(
            practiceDays = recentLogs.count { it.speakingMs >= 10 * 60_000 },
            speakingMinutes = (recentLogs.sumOf { it.speakingMs } / 60_000).toInt(),
            avgWords = if (noScript.isEmpty()) 0 else noScript.map { it.wordCount }.average().toInt(),
            avgLongPauses = if (withPauses.isEmpty()) null else withPauses.map { it.longPauseCount }.average().toFloat(),
            avgScore = if (noScript.isEmpty()) 0 else noScript.map { it.score }.average().toInt(),
            mocksSinceExam = mocks.size,
            bestMockScore = mocks.values.maxOrNull() ?: 0,
            recentAnswers = noScript.size,
        )
    }
}
