package com.opicih.coach.analysis

import kotlinx.serialization.Serializable

@Serializable
data class SentenceMatch(val sentence: String, val coverage: Float, val covered: Boolean)

@Serializable
data class CompareResult(
    /**
     * How much of the script's content was conveyed, 0..1 (order independent). Average of per-sentence partial
     * scores weighted by sentence length; see [ScriptComparator.FULL_CREDIT_OVERLAP]. Learning reference only.
     */
    val coverage: Float,
    /** Word-order similarity (LCS over content lemmas / target length). */
    val orderSimilarity: Float,
    val sentences: List<SentenceMatch>,
    val missingKeywords: List<String>,
    val usedKeywords: List<String>,
)

/**
 * Compares a target script with what the user actually said.
 * Sentence order may change and simple synonyms are accepted, because the goal is to say the same content,
 * not to recite the script word for word.
 */
object ScriptComparator {
    /**
     * Raw word overlap at which a sentence counts as fully conveyed. A paraphrase keeps the key nouns and verbs
     * but rarely reuses every adjective or adverb, so ~70 % of the content lemmas is treated as "same content".
     * Below that the score falls linearly, so an unrelated answer (raw overlap ~0-15 %) still scores low.
     */
    const val FULL_CREDIT_OVERLAP = 0.7f

    /** A sentence is reported as covered from this partial score (= raw overlap of about 42 %). */
    const val COVERED_SCORE = 0.6f

    /**
     * Words that carry style, emphasis or time framing but not the story itself ("absolutely breathtaking",
     * "last fall"). They are ignored only for coverage; the shared stopword list stays unchanged.
     */
    private val lowInformation = setOf(
        "absolutely", "totally", "completely", "definitely", "especially", "extremely", "pretty", "quite", "super",
        "kind", "sort", "thing", "stuff", "way", "time", "even", "still", "always", "never", "ever", "since", "maybe",
        "guess", "mean", "say", "said", "feel", "felt", "seem", "seemed", "look", "looked", "last", "ago", "once",
        "honest", "honestly", "basically", "anyway", "suddenly", "finally", "thankfully", "luckily", "soon", "now",
        "need", "want", "tell", "remember", "make", "made", "take", "took", "come", "came", "back", "lot", "much",
    )

    private fun coverageLemmas(text: String): List<String> =
        TextNormalizer.words(text)
            .filter { it !in TextNormalizer.stopwords && it !in lowInformation && it.length > 1 }
            .map { TextNormalizer.lemma(it) }
            .filter { it !in lowInformation }

    fun compare(target: String, spoken: String, keywords: List<String> = emptyList()): CompareResult {
        val spokenLemmas = TextNormalizer.contentLemmas(spoken)
        val spokenSet = spokenLemmas.toSet()
        val targetLemmas = TextNormalizer.contentLemmas(target)

        val weighted = TextNormalizer.sentences(target).map { s ->
            val lem = coverageLemmas(s).toSet().ifEmpty { TextNormalizer.contentLemmas(s).toSet() }
            val raw = if (lem.isEmpty()) 1f else lem.count { matchesAny(it, spokenSet) }.toFloat() / lem.size
            val score = (raw / FULL_CREDIT_OVERLAP).coerceAtMost(1f)
            Triple(SentenceMatch(s, score, score >= COVERED_SCORE), lem.size, score)
        }
        val sentenceMatches = weighted.map { it.first }
        // Sentence-weighted partial credit: long sentences matter more, empty sentences (e.g. "Yes!") not at all.
        val totalWeight = weighted.sumOf { it.second }
        val coverage = if (totalWeight == 0) 0f else weighted.sumOf { (it.third * it.second).toDouble() }.toFloat() / totalWeight

        val kw = keywords.ifEmpty { pickKeywords(target) }
        val (used, missing) = kw.partition { k ->
            val lem = TextNormalizer.contentLemmas(k)
            lem.isNotEmpty() && lem.count { matchesAny(it, spokenSet) } * 2 >= lem.size
        }

        val lcs = lcsLength(targetLemmas, spokenLemmas)
        val order = if (targetLemmas.isEmpty()) 0f else lcs.toFloat() / targetLemmas.size

        return CompareResult(coverage, order, sentenceMatches, missing, used)
    }

    private fun matchesAny(lemma: String, spoken: Set<String>): Boolean =
        lemma in spoken || spoken.any { similar(lemma, it) }

    /**
     * Tolerates speech-recognition spelling and derivation differences between two lemmas:
     * one edit apart for words of 6+ letters ("Ganwon"/"Gangwon"), or a shared 6-letter stem ("interesting"/"interested").
     * Shorter words must match exactly so that "sea"/"see" or "coast"/"toast" are not confused.
     */
    internal fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 6 || b.length < 6) return false
        if (a.take(6) == b.take(6)) return true
        return kotlin.math.abs(a.length - b.length) <= 1 && editDistanceAtMostOne(a, b)
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** Fallback keywords: the most "specific" content words (longest, non-repeated) of the target. */
    fun pickKeywords(target: String, max: Int = 6): List<String> =
        TextNormalizer.words(target)
            .filter { it !in TextNormalizer.stopwords && it.length >= 5 }
            .distinctBy { TextNormalizer.lemma(it) }
            .sortedByDescending { it.length }
            .take(max)

    private fun lcsLength(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val prev = IntArray(b.size + 1)
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.size]
    }
}
