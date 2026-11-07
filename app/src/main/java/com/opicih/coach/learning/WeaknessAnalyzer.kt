package com.opicih.coach.learning

import com.opicih.coach.data.db.ErrorPatternEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.appJson

/**
 * Speaking competencies shared by OPIc and SAIL. Pronunciation is deliberately absent:
 * the app has no acoustic pronunciation model, so it never claims to measure it.
 */
enum class SharedSpeakingCompetency(val label: String, val practiceTitle: String, val practiceMode: String) {
    FLUENCY("유창성 (긴 멈춤)", "약점: 멈추지 않고 이어 말하기 (같은 질문 4/3/2)", "FREE"),
    COHERENCE("답변 길이·연결", "약점: 답 → 이유 → 예시로 길게 말하기", "FREE"),
    GRAMMAR("반복 문법 오류", "약점: 오류 노트 표현을 넣어 다시 말하기", "BLIND"),
    STORYTELLING("과거 경험 시제", "약점: 과거 경험을 과거 시제로 말하기", "VARIATION"),
    SPONTANEOUS_RESPONSE("즉답 (답변 시작)", "약점: 질문 듣고 2초 안에 시작하기", "SAIL_CONVERSATION"),
    FOLLOWUP_HANDLING("추가 질문 대응", "약점: 추가 질문을 받아 첫 문장 말하기", "SAIL_CONVERSATION"),
    VOCABULARY("표현 반복", "약점: 같은 내용을 다른 표현으로 말하기", "RANDOM");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name }
    }
}

/** One measured weakness with the evidence behind it. [severity] 0..1 ranks weaknesses; it is not a grade. */
data class Weakness(val competency: SharedSpeakingCompetency, val evidence: String, val severity: Float, val samples: Int)

/**
 * Finds the weakest competencies from real practice records of the last [windowDays] days.
 * Each rule needs a minimum number of samples, so one bad answer does not reshape the plan.
 * All numbers come from device speech recognition and pause detection; they are learning signals, not scores.
 */
object WeaknessAnalyzer {
    const val MIN_SAMPLES = 3
    private val FREE_MODES = setOf("FREE", "VARIATION", "MOCK", "RANDOM", "BLIND", "SAIL_CONVERSATION")

    fun analyze(
        attempts: List<PracticeAttemptEntity>,
        errors: List<ErrorPatternEntity>,
        conversations: List<ConversationReport>,
        nowMs: Long,
        windowDays: Int = 14,
    ): List<Weakness> {
        val since = nowMs - windowDays * 86_400_000L
        val recent = attempts.filter { it.createdAt >= since && it.transcript.isNotBlank() && it.mode in FREE_MODES }
        val metrics = recent.mapNotNull { a -> runCatching { appJson.decodeFromString(AttemptMetrics.serializer(), a.metricsJson) }.getOrNull()?.let { a to it } }
        val out = mutableListOf<Weakness>()

        // Fluency: 2 s+ pauses per answer (acoustic, not STT).
        val timed = recent.filter { it.captureMode != "TYPED" && it.durationMs > 0 }
        if (timed.size >= MIN_SAMPLES) {
            val avg = timed.map { it.longPauseCount }.average().toFloat()
            if (avg >= 1.5f) out += Weakness(SharedSpeakingCompetency.FLUENCY, "최근 답변 ${timed.size}개에서 2초 이상 멈춤이 답변당 평균 %.1f회".format(avg), (avg / 4f).coerceAtMost(1f), timed.size)
        }

        // Coherence: short answers or few connectors.
        if (metrics.size >= MIN_SAMPLES) {
            val avgWords = metrics.map { it.second.discourse.wordCount }.average()
            val avgConn = metrics.map { it.second.discourse.connectorCount }.average()
            if (avgWords < 60 || avgConn < 1.0) {
                val sev = maxOf(((60 - avgWords) / 60.0).coerceAtLeast(0.0), if (avgConn < 1.0) 0.5 * (1 - avgConn) else 0.0).toFloat()
                out += Weakness(SharedSpeakingCompetency.COHERENCE, "답변 평균 ${avgWords.toInt()}단어, 연결어 답변당 %.1f개".format(avgConn), sev.coerceIn(0.2f, 1f), metrics.size)
            }
        }

        // Storytelling: past-experience answers told mostly in the present tense.
        val past = metrics.filter { QuestionType.of(it.first.questionType) == QuestionType.PAST_EXPERIENCE }
        if (past.size >= MIN_SAMPLES) {
            val unstable = past.count { it.second.discourse.pastCount < it.second.discourse.presentCount }
            val ratio = unstable.toFloat() / past.size
            if (ratio >= 0.5f) out += Weakness(SharedSpeakingCompetency.STORYTELLING, "과거 경험 답변 ${past.size}개 중 ${unstable}개가 현재 시제 위주", ratio, past.size)
        }

        // Grammar: repeated error notes seen recently.
        val recentErrors = errors.filter { it.lastSeen >= since && it.category.equals("GRAMMAR", true) }
        val errorHits = recentErrors.sumOf { it.count }
        if (errorHits >= MIN_SAMPLES) {
            val top = recentErrors.maxByOrNull { it.count }!!
            out += Weakness(SharedSpeakingCompetency.GRAMMAR, "반복 문법 오류 ${errorHits}회, 가장 잦은 것: ${top.example} → ${top.correction}", (errorHits / 10f).coerceIn(0.3f, 1f), errorHits)
        }

        // Conversation signals (SAIL practice reports).
        if (conversations.isNotEmpty()) {
            val turns = conversations.sumOf { it.turns }
            val slow = conversations.sumOf { it.slowStarts }
            if (turns >= MIN_SAMPLES && slow * 3 >= turns) {
                out += Weakness(SharedSpeakingCompetency.SPONTANEOUS_RESPONSE, "대화 연습 ${turns}턴 중 ${slow}턴에서 답변 시작이 3초를 넘음", (slow.toFloat() / turns).coerceAtMost(1f), turns)
            }
            val onTopic = conversations.filter { it.turns >= 2 }
            if (onTopic.isNotEmpty() && onTopic.sumOf { it.turns } >= MIN_SAMPLES) {
                val avg = onTopic.map { it.onTopicRatio }.average().toFloat()
                if (avg < 0.6f) out += Weakness(SharedSpeakingCompetency.FOLLOWUP_HANDLING, "추가 질문에 질문 단어를 받아 답한 비율 ${(avg * 100).toInt()}%", 1f - avg, onTopic.sumOf { it.turns })
            }
            val repeated = conversations.count { it.repeatedPhrases.isNotEmpty() }
            if (repeated >= 2) {
                val ex = conversations.flatMap { it.repeatedPhrases }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                out += Weakness(SharedSpeakingCompetency.VOCABULARY, "대화 ${repeated}회에서 같은 표현 반복" + (ex?.let { " (예: \"$it\")" } ?: ""), 0.4f, repeated)
            }
        }
        return out.sortedByDescending { it.severity }
    }
}

/** Applies the measured weaknesses to a day plan without changing its length or its exam-phase focus. */
object WeaknessPlanner {
    /** Exam-day and day-before plans stay light: no weakness drills there. */
    private val keepAsIs = setOf(Focus.SAIL_WARMUP, Focus.SAIL_LIGHT, Focus.LIGHT, Focus.MOCK, Focus.SAIL_MOCK)

    fun apply(plan: DayPlan, weaknesses: List<Weakness>): DayPlan {
        val top = weaknesses.firstOrNull() ?: return plan
        if (plan.focus in keepAsIs) return plan
        // Retarget the generic weak-point slot if the plan has one, otherwise the last speaking task.
        val idx = plan.tasks.indexOfFirst { it.title.contains("취약") || it.title.contains("교정") }
            .takeIf { it >= 0 }
            ?: plan.tasks.indexOfLast { it.type == TaskType.FREE || it.type == TaskType.SPEAK }
        if (idx < 0) return plan
        val t = plan.tasks[idx]
        val c = top.competency
        val type = if (c.practiceMode == "BLIND") TaskType.SPEAK else TaskType.FREE
        val tasks = plan.tasks.toMutableList().also { it[idx] = t.copy(type = type, title = c.practiceTitle, mode = c.practiceMode) }
        return plan.copy(tasks = tasks, weakness = top)
    }

    /** With no exam registered, pick the long-cycle focus from the top weakness instead of rotating blindly. */
    fun longCycleFocus(weaknesses: List<Weakness>): Focus? = when (weaknesses.firstOrNull()?.competency) {
        SharedSpeakingCompetency.FLUENCY -> Focus.LONG_FLUENCY
        SharedSpeakingCompetency.GRAMMAR -> Focus.LONG_ERRORS
        SharedSpeakingCompetency.COHERENCE -> Focus.LONG_FREE
        SharedSpeakingCompetency.STORYTELLING, SharedSpeakingCompetency.VOCABULARY -> Focus.LONG_STRUCTURE
        SharedSpeakingCompetency.SPONTANEOUS_RESPONSE, SharedSpeakingCompetency.FOLLOWUP_HANDLING -> Focus.LONG_UNEXPECTED
        null -> null
    }
}
