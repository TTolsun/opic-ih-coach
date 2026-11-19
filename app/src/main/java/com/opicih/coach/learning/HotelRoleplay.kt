package com.opicih.coach.learning

import kotlinx.serialization.Serializable

@Serializable
enum class HotelStage(val number: Int, val label: String, val type: String) {
    QUESTIONS(11, "호텔에 질문하기", "ROLEPLAY"),
    ALTERNATIVES(12, "예약 문제에 대안 제시하기", "ROLEPLAY"),
    EXPERIENCE(13, "관련 경험 말하기", "PAST_EXPERIENCE"),
}

object HotelRoleplay {
    const val TOPIC = "Role-play: Hotel reservation"

    fun nextStage(saved: Set<HotelStage>): HotelStage? = HotelStage.entries.firstOrNull { it !in saved }

    /** The bank lists the two role-play questions in order, followed by the related experience. */
    fun questions(pool: List<QuestionSource>): Map<HotelStage, QuestionSource> {
        val hotel = pool.filter { it.topic == TOPIC }.distinctBy { it.text }
        val roleplay = hotel.filter { it.type == HotelStage.QUESTIONS.type }
        val experience = hotel.firstOrNull { it.type == HotelStage.EXPERIENCE.type }
        if (roleplay.size < 2 || experience == null) return emptyMap()
        return mapOf(
            HotelStage.QUESTIONS to roleplay[0],
            HotelStage.ALTERNATIVES to roleplay[1],
            HotelStage.EXPERIENCE to experience,
        )
    }

    private val sentence = Regex("[^.!?\\r\\n]+[.!?]*")
    private val suggestion = Regex(
        """\b(?:(?:could|can)\s+(?:I|we|you)|(?:we|you|I)\s+(?:could|can)|(?:how|what) about|what if (?:I|we|you)|why don['’]t we|let['’]s|I suggest|would it be possible to|would you (?:be able to|mind)|(?:one|another) option (?:is|would be) to|please)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val actionClause = Regex(
        """^(?:(?:please|possibly|maybe|instead)\s+)*([a-z]+)\s+\w""",
        RegexOption.IGNORE_CASE,
    )
    private val accommodation = Regex(
        """^(?:(?:another|a different|a smaller|a larger|the same)\s+(?:room|hotel|date)|a\s+(?:refund|discount))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val actions = setOf(
        "check", "look", "search", "offer", "book", "reserve", "move", "switch", "take", "try", "stay",
        "find", "give", "get", "wait", "upgrade", "cancel", "refund", "change", "accept", "use", "call",
        "contact", "pay", "match", "arrange", "transfer", "provide", "put", "send", "honor",
        "honour", "rebook", "reschedule",
    )
    private val lookup = setOf("check", "look", "search")
    private val alternateInventory = Regex(
        """\b(?:another|different|alternative|available|vacancy|other|nearby|sister)\b|\b(?:a|any) (?:rooms?|hotels?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun hasAction(phrase: String): Boolean {
        if (accommodation.containsMatchIn(phrase)) return true
        val verb = actionClause.find(phrase)?.groupValues?.get(1)?.lowercase() ?: return false
        val stem = verb.removeSuffix("ing")
        val action = listOf(verb, stem, "${stem}e").firstOrNull { it in actions } ?: return false
        return action !in lookup || alternateInventory.containsMatchIn(phrase)
    }

    /** Returns verbatim candidate sentences, preserving coordinated options inside a single excerpt. */
    fun alternativeExcerpts(transcript: String): List<String> =
        sentence.findAll(transcript).map { it.value.trim() }.filter { excerpt ->
            suggestion.findAll(excerpt).any { hasAction(excerpt.substring(it.range.last + 1)) }
        }.distinct().toList()
}
