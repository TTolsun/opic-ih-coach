package com.opicih.coach.analysis

/**
 * A few article omissions this learner repeats ("when I was kid", "take nap"), checked locally.
 *
 * This is not a grammar checker: each pattern is a fixed phrase where a missing "a" is always wrong, so a
 * match is never a false alarm. The key matches the seeded error note (`missing_article`), so a hit adds to
 * the same 반복 오류 entry that Home already shows.
 */
object ArticleSlips {
    const val KEY = "missing_article"

    data class Slip(val found: String, val correction: String)

    private val patterns = listOf(
        Regex("""\bwhen i was (kid|child|teenager|student)\b""") to { m: MatchResult -> "when I was a ${m.groupValues[1]}" },
        Regex("""\bas (kid|child|teenager)\b""") to { m: MatchResult -> "as a ${m.groupValues[1]}" },
        Regex("""\btak(e|es|ing) nap\b""") to { m: MatchResult -> "tak${m.groupValues[1]} a nap" },
        Regex("""\btook nap\b""") to { _: MatchResult -> "took a nap" },
    )

    fun find(transcript: String): List<Slip> {
        // Matching runs on lowercase text; the reported phrase is cut from the original so the error note keeps the
        // learner's own casing (lowercase() keeps ASCII lengths, so the ranges line up).
        val text = transcript.lowercase()
        return patterns.flatMap { (re, fix) -> re.findAll(text).map { Slip(transcript.substring(it.range), fix(it)) }.toList() }
            .distinctBy { it.found }
    }
}
