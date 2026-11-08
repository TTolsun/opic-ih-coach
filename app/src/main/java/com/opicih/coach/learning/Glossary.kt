package com.opicih.coach.learning

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * One glossary entry: a word or an idiom with the meaning it has in the user's scripts (not the first dictionary sense).
 * [forms] are the exact surface forms that appear in the scripts, so no stemming is needed to find "relieving my fatigue".
 */
@Serializable
data class GlossaryEntry(
    val phrase: String,
    val forms: List<String> = emptyList(),
    /** IDIOM = 단어 뜻만으로 유추하기 어려운 숙어·구동사·관용 표현, WORD = 단어 */
    val kind: String = "WORD",
    val meaningKo: String,
    val noteKo: String? = null,
    /** A simpler wording the learner may say instead. */
    val easier: String? = null,
) {
    val isIdiom: Boolean get() = kind == "IDIOM"
    val allForms: List<String> get() = forms.ifEmpty { listOf(phrase) }
}

@Serializable data class GlossaryFile(val note: String = "", val entries: List<GlossaryEntry>)

/** A word of the text: [start] inclusive, [end] exclusive, [norm] lower-cased with typographic apostrophes folded. */
data class GlossaryToken(val start: Int, val end: Int, val norm: String)

/** A part of the text that has a glossary entry. Spans never overlap. */
data class GlossarySpan(val start: Int, val end: Int, val entry: GlossaryEntry)

/**
 * What the learner tapped. [entry] is the idiom (or word) that covers the tap; [wordEntry] is the tapped word's own
 * entry when it differs, so tapping "grabbed" inside an idiom still shows the word. [surface] is the covered text.
 */
data class GlossaryLookup(
    val entry: GlossaryEntry?,
    val wordEntry: GlossaryEntry?,
    val surface: String,
    val sentence: String,
    val start: Int,
    val end: Int,
)

class Glossary(entries: List<GlossaryEntry>) {
    private val byForm: Map<String, GlossaryEntry>
    private val maxTokens: Int
    private val byPhrase: Map<String, GlossaryEntry> = entries.associateBy { it.phrase.lowercase() }

    init {
        val map = LinkedHashMap<String, GlossaryEntry>()
        var max = 1
        for (e in entries) for (f in e.allForms) {
            val toks = tokenize(f)
            if (toks.isEmpty()) continue
            max = maxOf(max, toks.size)
            // First entry wins, so the file order decides between two entries that share a form.
            map.putIfAbsent(toks.joinToString(" ") { it.norm }, e)
        }
        byForm = map
        maxTokens = max
    }

    val size: Int get() = byPhrase.size

    fun entry(phrase: String): GlossaryEntry? = byPhrase[phrase.lowercase()]

    /** Greedy, longest match first, left to right. A longer idiom beats the single words inside it. */
    fun spans(text: String): List<GlossarySpan> {
        val toks = tokenize(text)
        val out = mutableListOf<GlossarySpan>()
        var i = 0
        while (i < toks.size) {
            var matched = 0
            for (len in minOf(maxTokens, toks.size - i) downTo 1) {
                val key = (i until i + len).joinToString(" ") { toks[it].norm }
                val e = byForm[key] ?: continue
                out += GlossarySpan(toks[i].start, toks[i + len - 1].end, e)
                matched = len
                break
            }
            i += if (matched > 0) matched else 1
        }
        return out
    }

    /** Resolves a tap at character [offset]. Returns null when the tap is not on a word. */
    fun lookupAt(text: String, offset: Int, spans: List<GlossarySpan> = spans(text)): GlossaryLookup? {
        val tok = tokenize(text).firstOrNull { offset >= it.start && offset < it.end }
            ?: tokenize(text).firstOrNull { offset == it.end }
            ?: return null
        val span = spans.firstOrNull { tok.start >= it.start && tok.end <= it.end }
        val word = byForm[tok.norm]
        val start = span?.start ?: tok.start
        val end = span?.end ?: tok.end
        return GlossaryLookup(
            entry = span?.entry ?: word,
            wordEntry = word?.takeIf { it != span?.entry },
            surface = text.substring(start, end),
            sentence = sentenceAround(text, start, end),
            start = start,
            end = end,
        )
    }

    companion object {
        private val WORD = Regex("[A-Za-z0-9]+(?:['’\\-][A-Za-z0-9]+)*")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Glossary = Glossary(json.decodeFromString(GlossaryFile.serializer(), text).entries)

        fun tokenize(text: String): List<GlossaryToken> = WORD.findAll(text).map {
            GlossaryToken(it.range.first, it.range.last + 1, it.value.lowercase().replace('’', '\''))
        }.toList()

        /** The sentence that contains [start, end): from the previous . ! ? or line break to the next one. */
        fun sentenceAround(text: String, start: Int, end: Int): String {
            // A period inside an abbreviation or number (U.S., 3.5) does not end a sentence.
            fun isStop(i: Int): Boolean = when (text[i]) {
                '!', '?', '\n' -> true
                '.' -> {
                    val inside = i > 0 && i + 1 < text.length && text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
                    val abbrevEnd = i >= 2 && text[i - 1].isLetter() && text[i - 2] == '.'
                    !inside && !abbrevEnd
                }
                else -> false
            }
            var from = start
            while (from > 0 && !isStop(from - 1)) from--
            var to = end
            while (to < text.length && !isStop(to)) to++
            if (to < text.length) to++
            return text.substring(from, to).trim()
        }
    }
}

/**
 * Counts how many different days a phrase was looked up. Looking the same phrase up again on the same day (re-reading
 * the sheet) is one lookup; coming back on another day means it was not memorized yet, which is the signal for review.
 */
object LookupCounter {
    /** A phrase looked up on this many different days becomes a review card automatically. */
    const val AUTO_REVIEW_DAYS = 2

    /**
     * Whether a lookup at [nowMillis] starts a new day for the phrase. [lastAnyMillis] is the latest lookup of the
     * phrase in any script, so a day is counted once even when the phrase is looked up in two scripts that day.
     */
    fun isNewDay(lastAnyMillis: Long?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (lastAnyMillis == null) return true
        val lastDay = Instant.ofEpochMilli(lastAnyMillis).atZone(zone).toLocalDate()
        return Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().isAfter(lastDay)
    }

    /** Folds typographic apostrophes and line breaks so the same phrase always compares equal. */
    fun normalize(text: String): String = text.replace('’', '\'').replace(Regex("\\s+"), " ").trim()

    fun shouldAutoAdd(totalDays: Int, alreadyInReview: Boolean): Boolean = !alreadyInReview && totalDays >= AUTO_REVIEW_DAYS
}
