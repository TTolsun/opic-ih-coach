package com.opicih.coach.learning

import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.measuredScore

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
        val spoken = attempts.filter { it.hasSpokenTranscript }
        val free = spoken.filter { it.mode in ScoreKind.READINESS_MODES }.take(10)
        val freeScore = comparableScores(free)?.scores?.average()?.toFloat() ?: 0f
        val topics = spoken.map { it.topic }.toSet().size
        val breadth = (topics / 8f).coerceAtMost(1f) * 100f
        return (mastery * 0.3f + freeScore * 0.5f + breadth * 0.2f).toInt().coerceIn(0, 100)
    }

    data class TopicStat(val topic: String, val attempts: Int, val avgScore: Int?, val avgWords: Int, val flow: Boolean)

    fun topicStats(attempts: List<PracticeAttemptEntity>): List<TopicStat> =
        attempts.groupBy { it.topic }.map { (t, list) ->
            val scored = list.mapNotNull { attempt ->
                val metrics = AttemptMetrics.from(attempt)
                attempt.measuredScore(metrics)?.let { (metrics?.speakingFlow == true) to it }
            }
            val flow = scored.firstOrNull()?.first ?: false
            val scores = scored.filter { it.first == flow }.map { it.second }
            TopicStat(t, list.size, scores.takeIf { it.isNotEmpty() }?.average()?.toInt(), list.map { it.wordCount }.average().toInt(), flow)
        }.sortedBy { it.avgScore ?: Int.MAX_VALUE }

    /** Weak topics: lowest average score with at least one attempt, or Survey topics never practiced. */
    fun weakTopics(attempts: List<PracticeAttemptEntity>, survey: Set<String>, max: Int = 3): List<String> {
        val stats = topicStats(attempts.take(60))
        val never = survey.filter { s -> stats.none { it.topic.equals(s, true) } }
        val low = stats.filter { it.avgScore?.let { score -> score < 70 } == true }.map { it.topic }
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

    /** Readiness signals since [sinceEpochMs]. [attempts] are ordered newest first. */
    fun readiness(
        attempts: List<PracticeAttemptEntity>,
        logs: List<DailyLogEntity>,
        sinceEpochDay: Long,
        sinceEpochMs: Long,
        mocks: List<MockTestEntity> = emptyList(),
    ): RetestAdvisor.Readiness {
        val after = attempts.filter { it.createdAt >= sinceEpochMs }
        val speaking = after.filter { it.mode in ScoreKind.READINESS_MODES && it.captureMode in speechCaptures }
        val transcribed = speaking.filter { it.hasSpokenTranscript }.take(15)
        val pauseCounts = speaking.take(15).mapNotNull { attempt ->
            AttemptMetrics.from(attempt)?.pauses?.takeIf { it.speechMs > 0 }?.longPauseCount
        }
        val local = comparableScores(transcribed)
        val mockAttempts = after.filter { it.mode == PracticeMode.MOCK.name && it.hasSpokenTranscript }.groupBy { it.mockId }
        val completedMocks = mocks.filter { it.finishedAt?.let { time -> time >= sinceEpochMs } == true }
        val scoredMocks = completedMocks.mapNotNull { comparableScores(mockAttempts[it.id].orEmpty()) }
        val recentLogs = logs.filter { it.epochDay >= sinceEpochDay }
        return RetestAdvisor.Readiness(
            practiceDays = recentLogs.count { it.speakingMs >= 10 * 60_000 },
            speakingMinutes = (recentLogs.sumOf { it.speakingMs } / 60_000).toInt(),
            avgWords = if (transcribed.isEmpty()) 0 else transcribed.map { it.wordCount }.average().toInt(),
            avgLongPauses = pauseCounts.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            avgScore = local?.scores?.average()?.toInt(),
            mocksSinceExam = completedMocks.size,
            bestMockScore = scoredMocks.filter { it.scores.size >= 5 }.maxOfOrNull { it.scores.average().toInt() },
            recentAnswers = transcribed.size,
            scoredAnswers = local?.scores?.size ?: 0,
            scoreCapture = local?.capture,
            scoreIsFlow = local?.flow == true,
            scorableMockAnswers = scoredMocks.maxOfOrNull { it.scores.size } ?: 0,
        )
    }

    private val speechCaptures = setOf(CaptureMode.LIVE_STT.name, CAPTURE_CLOUD_STT, CaptureMode.RECORD_ONLY.name)
    private val PracticeAttemptEntity.hasSpokenTranscript: Boolean
        get() = wordCount > 0 && (captureMode == CaptureMode.LIVE_STT.name || captureMode == CAPTURE_CLOUD_STT)

    private data class ComparableScores(val capture: String, val flow: Boolean, val scores: List<Int>)

    /** Use the most populated local scoring group. The newest capture wins a tie. */
    private fun comparableScores(attempts: List<PracticeAttemptEntity>): ComparableScores? {
        val scoresByCapture = attempts.mapNotNull { attempt ->
            val metrics = AttemptMetrics.from(attempt)
            val flow = metrics?.speakingFlow == true
            val score = if (!flow && attempt.feedbackSource == "LOCAL") attempt.score else metrics?.localScore
            score?.takeIf { it in 0..100 }?.let { (attempt.captureMode to flow) to it }
        }.groupBy({ it.first }, { it.second })
        val group = scoresByCapture.maxByOrNull { it.value.size } ?: return null
        return ComparableScores(group.key.first, group.key.second, group.value)
    }
}
