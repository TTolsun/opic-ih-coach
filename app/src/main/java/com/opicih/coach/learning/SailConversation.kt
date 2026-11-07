package com.opicih.coach.learning

import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.TextNormalizer
import kotlinx.serialization.Serializable

/**
 * SAIL conversation practice (learning simulation).
 * The official SAIL item format is not public, so nothing here claims to reproduce the real test:
 * it trains quick answers and follow-up handling, which any interview-style speaking test needs.
 */

/** Where a question came from; shown on every turn so AI and offline rules are never confused. */
enum class QuestionOrigin(val label: String) {
    SEED("기본 질문"),
    DEBRIEF("내 시험 복기"),
    AI("Claude AI"),
    LOCAL_RULE("로컬 규칙 (AI 아님)"),
}

@Serializable
data class TurnMetrics(
    /** Silence between the end of the question audio and the first detected speech; null = not measured. */
    val responseLatencyMs: Long?,
    val speechMs: Long,
    val durationMs: Long,
    val longPauses: Int,
    val wordCount: Int,
    val connectors: Int,
)

data class ConvTurn(
    val question: String,
    val origin: QuestionOrigin,
    val answer: String,
    val metrics: TurnMetrics?,
    /** True when the answer was typed after a recognition failure (no timing data). */
    val typed: Boolean = false,
)

object SailQuestionBank {
    /** Openers written for this app (AI-sample style). Not official SAIL items. */
    val openers = listOf(
        "Tell me about your favorite hobby.",
        "Please describe your typical workday.",
        "Tell me about a recent problem you had at work and how you handled it.",
        "What do you usually do on weekends?",
        "Tell me about a trip that you remember well.",
        "If a colleague disagreed with your idea in a meeting, what would you do?",
        "Describe a skill you would like to learn this year.",
        "Tell me about a time you had to finish something under a tight deadline.",
        "What is something you bought recently that you are happy with?",
        "Tell me about your team at work. What does each person do?",
        "How do you usually stay healthy?",
        "Tell me about a place in your neighborhood that you like.",
        "What would you recommend to a foreign visitor coming to Korea for the first time?",
        "Tell me about a change in your company or school that affected you.",
        "Describe how you prepare for an important presentation.",
    )

    /** Picks an opener, preferring questions the user wrote down after a real exam. */
    fun pick(debriefQuestions: List<String>, recentlyUsed: Set<String>, random: kotlin.random.Random = kotlin.random.Random): Pair<String, QuestionOrigin> {
        val fresh = debriefQuestions.filter { it !in recentlyUsed }
        if (fresh.isNotEmpty() && random.nextInt(2) == 0) return fresh.random(random) to QuestionOrigin.DEBRIEF
        val pool = openers.filter { it !in recentlyUsed }.ifEmpty { openers }
        return pool.random(random) to QuestionOrigin.SEED
    }

    /** "Q: ..." lines saved in a SAIL debrief note. */
    fun fromDebriefNote(note: String): List<String> =
        note.lines().map { it.trim() }.filter { it.startsWith("Q:", ignoreCase = true) }.map { it.drop(2).trim() }.filter { it.length >= 8 }
}

/**
 * Offline follow-up generator. Keyword based, not real understanding: it reuses a phrase from the last answer
 * so the next question is tied to what the user actually said, and never repeats a template in one conversation.
 */
object LocalFollowUp {
    data class FollowUp(val text: String, val templateId: String)

    private val likeRx = Regex("""\b(?:i|we)\s+(?:really\s+|usually\s+|also\s+)?(?:like|love|enjoy|prefer)\s+([a-z' -]{3,40}?)(?=[.,!?;]|\s+(?:and|but|because|so|when|with|on|in|every)\b|$)""", RegexOption.IGNORE_CASE)
    private val pastRx = Regex("""\b(?:i|we)\s+(went to|visited|tried|joined|bought|watched|played|ran|completed|finished|started|learned|met)\s+([a-z' -]{2,40}?)(?=[.,!?;]|\s+(?:and|but|because|so|when|with|last|yesterday|ago)\b|$)""", RegexOption.IGNORE_CASE)
    private val planRx = Regex("""\b(?:i|we)\s+(?:want to|plan to|am going to|'m going to|would like to|hope to)\s+([a-z' -]{3,40}?)(?=[.,!?;]|\s+(?:and|but|because|so|when)\b|$)""", RegexOption.IGNORE_CASE)
    private val problemWords = setOf("problem", "issue", "trouble", "difficult", "hard", "mistake", "broken", "late", "deadline", "complain", "conflict")
    private val pastMarkers = Regex("""\b(was|were|went|did|had|got|made|took|saw|met|ago|yesterday|last)\b|\b[a-z]{3,}ed\b""")

    /** Turns "my team" into "your team" so the phrase can be asked back. */
    fun toSecondPerson(phrase: String): String {
        fun w(x: String) = Regex("""\b$x\b""", RegexOption.IGNORE_CASE)
        return phrase.trim().replace(w("my"), "your").replace(w("mine"), "yours").replace(w("our"), "your")
            .replace(w("me"), "you").replace(w("myself"), "yourself").replace(Regex("""\s+"""), " ")
    }

    private fun clean(s: String) = s.replace('’', '\'')

    /** Main noun-ish phrase: the longest run of content words, max 3 words. */
    fun keyPhrase(answer: String): String? {
        val words = TextNormalizer.words(answer)
        var best = emptyList<String>(); var cur = mutableListOf<String>()
        for (w in words) {
            if (w !in TextNormalizer.stopwords && w.length > 2 && !w.endsWith("ly")) { cur.add(w); if (cur.size > best.size) best = cur.toList(); if (cur.size == 3) cur = mutableListOf() }
            else cur = mutableListOf()
        }
        return best.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    fun next(answer: String, usedTemplates: Set<String>, previousQuestion: String = ""): FollowUp {
        val a = clean(answer)
        val lower = a.lowercase()
        val wc = TextNormalizer.words(a).size
        val candidates = mutableListOf<FollowUp>()

        // Simplify only when there is nothing to build on; a short but concrete answer ("I enjoy running marathons") gets a real follow-up.
        if (wc < 3 || Regex("""\b(don't know|do not know|no idea|not sure|i'm sorry|pardon)\b""").containsMatchIn(lower)) {
            candidates += FollowUp("Let me ask in a simpler way. Could you tell me one or two things about that?", "retry_simple")
        }
        // A problem story is the strongest follow-up hook (SAIL-style problem solving), so it comes first.
        val hasProblem = problemWords.any { Regex("""\b$it\b""").containsMatchIn(lower) }
        if (hasProblem) {
            candidates += FollowUp("How did you solve that problem in the end?", "problem_solve")
            candidates += FollowUp("If the same thing happened again, what would you do differently?", "problem_again")
        }
        likeRx.find(a)?.groupValues?.get(1)?.let { toSecondPerson(it) }?.takeIf { it.isNotBlank() }?.let { x ->
            candidates += FollowUp("How did you first get into $x?", "like_origin")
            candidates += FollowUp("How often do you have time for $x these days?", "like_freq")
            candidates += FollowUp("What is the hardest part of $x for you?", "like_hard")
        }
        pastRx.find(a)?.let { m -> m.groupValues[1].lowercase() to toSecondPerson(m.groupValues[2]).trim() }?.let { (verb, obj) ->
            val noun = obj.removePrefix("the ").trim()
            // "a problem", "the deadline" etc. are already handled by the problem rule.
            if (noun.isNotBlank() && problemWords.none { noun.lowercase().contains(it) }) {
                val ofWhat = if (verb == "went to" || verb == "visited") "your trip to $noun" else noun
                candidates += FollowUp("What was the most memorable moment of $ofWhat?", "past_memorable")
                candidates += FollowUp("What was the most difficult part of $ofWhat, and how did you deal with it?", "past_difficult")
            }
        }
        planRx.find(a)?.groupValues?.get(1)?.let { toSecondPerson(it) }?.let { x ->
            candidates += FollowUp("Why do you want to $x?", "plan_why")
            candidates += FollowUp("What will you do first to $x?", "plan_first")
        }
        if (wc in 3..14) {
            val k = keyPhrase(a)
            candidates += FollowUp(if (k != null) "Could you give me a specific example about $k?" else "Could you give me a specific example?", "short_example")
        }
        val k = keyPhrase(a)
        if (pastMarkers.containsMatchIn(lower)) candidates += FollowUp("How did you feel about it at that time?", "past_feel")
        if (k != null) {
            candidates += FollowUp("You mentioned $k. Why is that important to you?", "generic_why")
            candidates += FollowUp("How is $k different now compared to a few years ago?", "generic_compare")
            candidates += FollowUp("What would you tell someone who has never experienced $k?", "generic_recommend")
        }
        candidates += FollowUp("Can you tell me more about why you think so?", "fallback_why")
        candidates += FollowUp("What happened after that?", "fallback_next")
        candidates += FollowUp("Is there anything you would like to change about it?", "fallback_change")

        return candidates.firstOrNull { it.templateId !in usedTemplates && !it.text.equals(previousQuestion, ignoreCase = true) }
            ?: FollowUp("Let's move on. " + SailQuestionBank.openers.first { it != previousQuestion }, "new_topic_${usedTemplates.size}")
    }
}

/** Result of one conversation. Learning reference only: not an official SAIL grade. */
@Serializable
data class ConversationReport(
    val turns: Int,
    val avgLatencyMs: Long?,
    val slowStarts: Int,
    val totalSpeechMs: Long,
    val avgWords: Int,
    val longPauses: Int,
    val connectorsPerAnswer: Float,
    val repeatedPhrases: List<String>,
    /** Share of answers that reuse at least one content word of their question (rough relevance signal). */
    val onTopicRatio: Float,
    val strengths: List<String>,
    val fixes: List<String>,
    /** Weak competency keys (SPONTANEOUS_RESPONSE, FLUENCY, ...), most important first. */
    val weakCompetencies: List<String>,
    val aiSummary: String? = null,
    val limits: List<String> = emptyList(),
)

object ConversationScoring {
    const val SLOW_START_MS = 3000L

    fun report(turns: List<ConvTurn>): ConversationReport {
        val measured = turns.mapNotNull { it.metrics?.responseLatencyMs }
        val avgLatency = measured.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val slow = measured.count { it > SLOW_START_MS }
        val words = turns.map { TextNormalizer.words(it.answer).size }
        val avgWords = if (words.isEmpty()) 0 else words.average().toInt()
        val pauses = turns.sumOf { it.metrics?.longPauses ?: 0 }
        val connectors = turns.sumOf { DiscourseAnalyzer.analyze(it.answer).connectorCount }
        val perAnswer = if (turns.isEmpty()) 0f else connectors.toFloat() / turns.size
        val repeated = repeatedPhrases(turns.map { it.answer })
        val onTopic = if (turns.isEmpty()) 0f else turns.count { overlaps(it.question, it.answer) }.toFloat() / turns.size

        val strengths = mutableListOf<String>()
        val fixes = mutableListOf<String>()
        val weak = mutableListOf<String>()
        if (avgLatency != null) {
            if (avgLatency <= 2000) strengths += "질문이 끝나고 평균 %.1f초 안에 답을 시작했습니다.".format(avgLatency / 1000f)
            if (slow > 0) { fixes += "답변 시작이 3초를 넘은 질문이 ${slow}개였습니다. \"That's a good question. Let me think…\" 같은 시작 문장으로 먼저 입을 여세요."; weak += "SPONTANEOUS_RESPONSE" }
        }
        if (avgWords >= 40) strengths += "답변당 평균 ${avgWords}단어로 충분히 길게 말했습니다."
        else if (turns.isNotEmpty()) { fixes += "답변당 평균 ${avgWords}단어로 짧습니다. 답 → 이유 → 구체적 예시 순서로 2~3문장을 더하세요."; weak += "COHERENCE" }
        if (pauses >= turns.size && turns.isNotEmpty()) { fixes += "2초 이상 멈춘 횟수가 ${pauses}회였습니다. 멈출 때 \"you know\", \"I mean\" 대신 짧은 연결어(so, and then)를 쓰는 연습을 하세요."; weak += "FLUENCY" }
        if (perAnswer < 1f && turns.isNotEmpty()) { fixes += "연결어(because, so, but, for example)가 답변당 ${"%.1f".format(perAnswer)}개입니다. 답변마다 2개 이상 넣어 보세요."; weak += "COHERENCE" }
        else if (perAnswer >= 2f) strengths += "연결어를 답변당 ${"%.1f".format(perAnswer)}개 사용해 문장을 이어 갔습니다."
        if (repeated.isNotEmpty()) { fixes += "같은 표현을 여러 번 반복했습니다: ${repeated.take(3).joinToString { "\"$it\"" }}. 다른 표현으로 바꿔 말해 보세요."; weak += "VOCABULARY" }
        if (onTopic < 0.6f && turns.size >= 2) { fixes += "질문에 나온 단어를 답변에서 다시 쓰지 않은 경우가 많습니다. 첫 문장에서 질문을 받아 말하면(\"The hardest part was…\") 대응이 분명해집니다."; weak += "FOLLOWUP_HANDLING" }
        if (strengths.isEmpty() && turns.isNotEmpty()) strengths += "${turns.size}턴 대화를 끝까지 이어 갔습니다."

        return ConversationReport(
            turns = turns.size, avgLatencyMs = avgLatency, slowStarts = slow, totalSpeechMs = turns.sumOf { it.metrics?.speechMs ?: 0 },
            avgWords = avgWords, longPauses = pauses, connectorsPerAnswer = perAnswer, repeatedPhrases = repeated, onTopicRatio = onTopic,
            strengths = strengths.take(2), fixes = fixes.take(3), weakCompetencies = weak.distinct(),
            limits = listOf(
                "학습용 참고 지표입니다. SAIL 공식 등급 예측이 아닙니다.",
                "발음과 억양은 측정하지 않습니다. 단어 수와 반복 표현은 기기 음성 인식 결과를 기준으로 하므로 실제와 다를 수 있습니다.",
                "답변 시작 시간은 질문 음성이 끝난 뒤 마이크가 처음 목소리를 감지한 시점까지입니다. 마이크 준비 시간(약 0.3초)이 포함됩니다.",
            ) + if (turns.any { it.typed }) listOf("직접 입력한 답변은 시간 지표에서 제외했습니다.") else emptyList(),
        )
    }

    /** 3-word phrases (with at least one content word) used 3+ times across all answers. */
    fun repeatedPhrases(answers: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        answers.forEach { a ->
            val w = TextNormalizer.words(a)
            for (i in 0..w.size - 3) {
                val g = w.subList(i, i + 3)
                if (g.all { it in TextNormalizer.stopwords }) continue
                val key = g.joinToString(" ")
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.filter { it.value >= 3 }.entries.sortedByDescending { it.value }.map { it.key }
    }

    fun overlaps(question: String, answer: String): Boolean {
        val q = TextNormalizer.contentLemmas(question).toSet()
        val a = TextNormalizer.contentLemmas(answer).toSet()
        return q.isEmpty() || q.intersect(a).isNotEmpty()
    }
}

/** Spending guard for paid AI calls in a conversation. Pure so the limits are testable. */
object AiBudget {
    const val PER_CONVERSATION = 6

    fun canCall(callsThisConversation: Int, callsToday: Int, dailyCap: Int): Boolean =
        callsThisConversation < PER_CONVERSATION && callsToday < dailyCap
}
