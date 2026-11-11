package com.opicih.coach.analysis

import kotlinx.serialization.Serializable

@Serializable
data class DiscourseMetrics(
    val wordCount: Int,
    val sentenceCount: Int,
    val avgSentenceLength: Float,
    val pastCount: Int,
    val presentCount: Int,
    val futureCount: Int,
    val connectorCount: Int,
    val connectorsUsed: List<String>,
    val experienceMarkers: Int,
    val fillerCount: Int,
    val repeatedWords: Int,
    /** Type-token ratio of content words. */
    val lexicalDiversity: Float,
    val feelingWords: Int,
) {
    val timeFrames: Int get() = listOf(pastCount, presentCount, futureCount).count { it > 0 }
}

/**
 * Heuristic discourse analysis on a transcript. Results are labelled "참고용" in the UI because
 * STT output may already be corrected by the recognizer (fillers removed, grammar smoothed).
 */
object DiscourseAnalyzer {
    private val irregularPast = setOf(
        "was", "were", "went", "had", "did", "saw", "ate", "got", "took", "made", "came", "bought", "felt", "found",
        "thought", "told", "said", "left", "kept", "met", "ran", "sat", "slept", "spent", "stayed", "drank", "gave",
        "knew", "lost", "paid", "sent", "stood", "wore", "won", "wrote", "began", "broke", "brought", "caught",
        "chose", "drove", "fell", "flew", "forgot", "grew", "heard", "held", "hurt", "let", "lent", "meant", "put",
        "read", "rode", "rang", "rose", "sang", "sold", "shot", "shut", "spoke", "swam", "taught", "threw", "understood",
        "woke", "used", "realized", "decided", "visited", "enjoyed", "missed", "arrived", "started", "finished",
    )
    private val presentMarkers = setOf("am", "is", "are", "do", "does", "usually", "always", "often", "these", "now", "nowadays")
    private val futurePatterns = listOf("will", "going to", "plan to", "planning to", "hope to", "want to", "would like to", "next week", "next year", "next time", "in the future", "someday", "looking forward")
    val connectors = listOf(
        "because", "so", "but", "however", "actually", "after that", "then", "first of all", "first", "second",
        "on the other hand", "compared to", "in addition", "also", "especially", "for example", "while", "when",
        "although", "even though", "anyway", "in the end", "finally", "since", "that's why", "as a result", "to be honest",
        "looking back", "the thing is", "plus", "besides",
    )
    private val experienceMarkers = listOf(
        "i remember", "one time", "last year", "last summer", "last winter", "last fall", "last week", "last month",
        "when i was", "back then", "years ago", "the other day", "once",
    )
    private val fillers = listOf("um", "uh", "er", "erm", "hmm", "ah", "you know", "i mean", "like")
    private val feelings = setOf(
        "happy", "sad", "excited", "nervous", "embarrassed", "frustrated", "surprised", "shocked", "relieved", "moved",
        "proud", "sorry", "scared", "worried", "touching", "bittersweet", "unforgettable", "memorable", "alive", "rejuvenated",
        "comfortable", "uncomfortable", "angry", "upset", "satisfied", "thrilled", "disappointed",
    )

    fun analyze(transcript: String): DiscourseMetrics {
        val norm = " " + TextNormalizer.normalize(transcript) + " "
        val words = TextNormalizer.words(transcript)
        val sentences = TextNormalizer.sentences(transcript).ifEmpty { if (words.isEmpty()) emptyList() else listOf(transcript) }

        val past = words.count { it in irregularPast || (it.length > 4 && it.endsWith("ed") && it !in setOf("need", "speed", "feed", "seed", "bed", "red")) } +
            countPhrase(norm, "used to")
        val future = futurePatterns.sumOf { countPhrase(norm, it) }
        val present = words.count { it in presentMarkers }

        val usedConnectors = connectors.filter { countPhrase(norm, it) > 0 }
        val connectorCount = connectors.sumOf { countPhrase(norm, it) }

        val content = words.filter { it !in TextNormalizer.stopwords }
        val ttr = if (content.isEmpty()) 0f else content.toSet().size.toFloat() / content.size
        val repeats = sentences.sumOf { sentence ->
            TextNormalizer.words(sentence).zipWithNext().count { (a, b) -> a == b }
        }

        return DiscourseMetrics(
            wordCount = words.size,
            sentenceCount = sentences.size,
            avgSentenceLength = if (sentences.isEmpty()) 0f else words.size.toFloat() / sentences.size,
            pastCount = past,
            presentCount = present,
            futureCount = future,
            connectorCount = connectorCount,
            connectorsUsed = usedConnectors,
            experienceMarkers = experienceMarkers.sumOf { countPhrase(norm, it) },
            fillerCount = fillers.sumOf { countPhrase(norm, it) },
            repeatedWords = repeats,
            lexicalDiversity = ttr,
            feelingWords = words.count { it in feelings },
        )
    }

    private fun countPhrase(normalizedPadded: String, phrase: String): Int {
        val p = " $phrase "
        var idx = normalizedPadded.indexOf(p)
        var n = 0
        while (idx >= 0) { n++; idx = normalizedPadded.indexOf(p, idx + p.length - 1) }
        return n
    }
}
