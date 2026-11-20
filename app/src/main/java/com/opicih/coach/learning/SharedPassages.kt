package com.opicih.coach.learning

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ScriptEntity

/** Excerpts are always from the learner's originals. A match suggests material to compare. */
internal data class SharedPassage(
    val other: ScriptEntity,
    val here: String,
    val there: String,
    val hereDetail: String?,
    val thereDetail: String?,
    val similarity: Double,
)

internal fun sharedPassages(source: ScriptEntity, library: List<ScriptEntity>): List<SharedPassage> {
    data class Excerpt(val text: String, val words: Set<String>)
    fun excerpts(script: ScriptEntity) = ScriptSegmenter.plan(script.original).sentences.map { sentence ->
        Excerpt(sentence, TextNormalizer.contentLemmas(sentence).toSet())
    }

    // Prefer a nearby sentence with details absent from the other answer.
    fun detail(answer: List<Excerpt>, anchor: Excerpt, other: List<Excerpt>): String? {
        val otherWords = other.flatMap { it.words }.toSet()
        val index = answer.indexOf(anchor)
        return answer.indices.filter { it != index }.map { i ->
            i to answer[i].words.count { it !in otherWords }
        }.filter { (_, distinctWords) -> distinctWords >= 2 }
            .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second.coerceAtMost(6) }.thenBy { -kotlin.math.abs(it.first - index) })
            ?.let { (i, _) -> answer[i].text }
    }

    val original = excerpts(source)
    if (original.none { it.words.size >= 3 }) return emptyList()
    return library.asSequence().filter { it.id != source.id && it.source != ContentSource.AI_SAMPLE.name }.mapNotNull { other ->
        val alternative = excerpts(other)
        var bestScore = 0.0
        var pair: Pair<Excerpt, Excerpt>? = null
        for (there in alternative) {
            for (here in original) {
                val common = here.words.count { it in there.words }
                if (common < 3) continue
                val similarity = 2.0 * common / (here.words.size + there.words.size)
                if (similarity >= 0.5 && similarity > bestScore) {
                    bestScore = similarity
                    pair = here to there
                }
            }
        }
        pair?.let { (here, there) ->
            SharedPassage(other, here.text, there.text, detail(original, here, alternative),
                detail(alternative, there, original), bestScore)
        }
    }.sortedWith(compareByDescending<SharedPassage> { it.similarity }.thenBy { it.other.id }).take(3).toList()
}
