package com.opicih.coach.learning

import kotlinx.serialization.Serializable

@Serializable
data class MockQuestion(val no: Int, val topic: String, val text: String, val type: String, val stage: String, val source: String)

data class QuestionSource(val topic: String, val text: String, val type: String, val source: String)

/**
 * Builds a practice test that follows the publicly described OPIc structure
 * (self-introduction, survey combo sets, unexpected topic, role-play set, comparison/issue).
 * Questions are AI-generated practice items or the user's own lists, never claimed to be official.
 */
object MockTestBuilder {
    enum class Adjust { EASIER, SAME, HARDER }

    fun build(pool: List<QuestionSource>, survey: Set<String>, count: Int, selfLevel: Int, adjust: Adjust = Adjust.SAME, seed: Long = System.currentTimeMillis()): List<MockQuestion> {
        val rnd = java.util.Random(seed)
        fun matches(q: QuestionSource, t: String) = q.topic.contains(t.substringBefore(" "), true) || t.contains(q.topic.substringBefore(" "), true)
        val used = mutableSetOf<String>()
        fun pick(filter: (QuestionSource) -> Boolean): QuestionSource? =
            pool.filter { it.text !in used && filter(it) }.shuffled(rnd).firstOrNull()?.also { used += it.text }

        val out = mutableListOf<MockQuestion>()
        fun add(q: QuestionSource?, stage: String) { if (q != null) out += MockQuestion(out.size + 1, q.topic, q.text, q.type, stage, q.source) }

        add(pick { it.type == "SELF_INTRO" } ?: QuestionSource("Self Introduction", "Let's start the interview now. Tell me a little bit about yourself.", "SELF_INTRO", "AI_GENERATED"), "자기소개")

        val surveyTopics = survey.filter { t -> pool.any { matches(it, t) } }.shuffled(rnd)
        val comboCount = if (count >= 15) 3 else 2
        val comboTopics = surveyTopics.take(comboCount - 1).toMutableList()
        // One unexpected (non-survey) combo, as in the real test.
        val unexpected = pool.map { it.topic }.distinct().filter { t -> survey.none { s -> t.contains(s.substringBefore(" "), true) } && !t.startsWith("Role-play") && t != "Self Introduction" }.shuffled(rnd).firstOrNull()
        val combos = comboTopics.map { it to "Survey 콤보" } + listOfNotNull(unexpected?.let { it to "돌발 콤보" })
        val typeOrder = when {
            selfLevel <= 3 -> listOf("DESCRIPTION", "ROUTINE", "PAST_EXPERIENCE")
            else -> listOf("DESCRIPTION", "ROUTINE", "PAST_EXPERIENCE", "COMPARISON")
        }
        combos.forEach { (topic, stage) ->
            val wanted = listOf(typeOrder[0], if (selfLevel >= 5 && rnd.nextBoolean()) "COMPARISON" else typeOrder[1], "PAST_EXPERIENCE")
            wanted.forEach { type -> add(pick { matches(it, topic) && it.type == type } ?: pick { matches(it, topic) && it.type != "ROLEPLAY" }, "$stage · $topic") }
        }

        // Role-play set: ask questions → solve a problem → related experience.
        val rp = pool.filter { it.topic.startsWith("Role-play") }.map { it.topic }.distinct().shuffled(rnd).firstOrNull()
        if (rp != null) pool.filter { it.topic == rp && it.text !in used }.take(3).forEach { used += it.text; add(it, "롤플레이") }
        else repeat(2) { add(pick { it.type == "ROLEPLAY" }, "롤플레이") }

        val endTypes = when (adjust) {
            Adjust.EASIER -> listOf("DESCRIPTION", "ROUTINE")
            Adjust.SAME -> listOf("COMPARISON", "ISSUE")
            Adjust.HARDER -> listOf("ISSUE", "COMPARISON", "ISSUE")
        }
        var i = 0
        while (out.size < count && i < 10) {
            val t = endTypes[i % endTypes.size]
            add(pick { it.type == t } ?: pick { it.type != "ROLEPLAY" && it.type != "SELF_INTRO" }, if (t == "ISSUE") "이슈/고난도" else "마무리")
            i++
        }
        return out.take(count).mapIndexed { idx, q -> q.copy(no = idx + 1) }
    }

    /** Re-plans the questions after the mid-test difficulty readjustment, keeping the ones already answered. */
    fun readjust(current: List<MockQuestion>, answered: Int, pool: List<QuestionSource>, survey: Set<String>, selfLevel: Int, adjust: Adjust): List<MockQuestion> {
        if (adjust == Adjust.SAME) return current
        val rebuilt = build(pool.filter { p -> current.take(answered).none { it.text == p.text } }, survey, current.size, selfLevel, adjust)
        val tail = rebuilt.filter { it.stage.startsWith("롤플레이") || it.stage.startsWith("이슈") || it.stage.startsWith("마무리") }
        val keep = current.take(answered)
        val rest = (tail + current.drop(answered)).distinctBy { it.text }.take(current.size - answered)
        return (keep + rest).mapIndexed { i, q -> q.copy(no = i + 1) }
    }
}
