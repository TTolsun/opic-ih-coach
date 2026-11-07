package com.opicih.coach.analysis

import kotlinx.serialization.Serializable

@Serializable
data class SentenceMatch(val sentence: String, val coverage: Float, val covered: Boolean)

@Serializable
data class CompareResult(
    /** Fraction of target content words that appeared anywhere in the answer (order independent). */
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
    fun compare(target: String, spoken: String, keywords: List<String> = emptyList()): CompareResult {
        val spokenLemmas = TextNormalizer.contentLemmas(spoken)
        val spokenSet = spokenLemmas.toSet()
        val targetLemmas = TextNormalizer.contentLemmas(target)
        val targetSet = targetLemmas.toSet()

        val coverage = if (targetSet.isEmpty()) 0f else targetSet.count { it in spokenSet }.toFloat() / targetSet.size

        val sentenceMatches = TextNormalizer.sentences(target).map { s ->
            val lem = TextNormalizer.contentLemmas(s).toSet()
            val c = if (lem.isEmpty()) 1f else lem.count { it in spokenSet }.toFloat() / lem.size
            SentenceMatch(s, c, c >= 0.5f)
        }

        val kw = keywords.ifEmpty { pickKeywords(target) }
        val (used, missing) = kw.partition { k ->
            val lem = TextNormalizer.contentLemmas(k)
            lem.isNotEmpty() && lem.count { it in spokenSet } * 2 >= lem.size
        }

        val lcs = lcsLength(targetLemmas, spokenLemmas)
        val order = if (targetLemmas.isEmpty()) 0f else lcs.toFloat() / targetLemmas.size

        return CompareResult(coverage, order, sentenceMatches, missing, used)
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
