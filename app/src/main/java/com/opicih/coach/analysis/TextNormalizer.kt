package com.opicih.coach.analysis

/** Pure Kotlin text helpers shared by the analyzers. No Android dependencies, unit tested. */
object TextNormalizer {
    private val contractions = mapOf(
        "i'm" to "i am", "you're" to "you are", "it's" to "it is", "that's" to "that is", "there's" to "there is",
        "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "can't" to "can not", "couldn't" to "could not",
        "won't" to "will not", "wasn't" to "was not", "weren't" to "were not", "isn't" to "is not", "aren't" to "are not",
        "i've" to "i have", "i'd" to "i would", "i'll" to "i will", "we're" to "we are", "they're" to "they are",
        "we'll" to "we will", "they'll" to "they will", "he's" to "he is", "she's" to "she is", "let's" to "let us",
        "what's" to "what is", "gonna" to "going to", "wanna" to "want to",
    )

    private val numberWords = mapOf(
        "0" to "zero", "1" to "one", "2" to "two", "3" to "three", "4" to "four", "5" to "five", "6" to "six",
        "7" to "seven", "8" to "eight", "9" to "nine", "10" to "ten",
    )

    val stopwords = setOf(
        "a", "an", "the", "and", "or", "but", "so", "to", "of", "in", "on", "at", "for", "with", "from", "by", "about",
        "i", "me", "my", "mine", "you", "your", "we", "our", "us", "they", "them", "their", "he", "she", "his", "her",
        "it", "its", "this", "that", "these", "those", "there", "here", "is", "am", "are", "was", "were", "be", "been",
        "being", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "should", "not", "no",
        "very", "really", "just", "also", "too", "as", "if", "then", "than", "when", "while", "because", "what", "which",
        "who", "how", "all", "some", "any", "much", "many", "lot", "lots", "one", "get", "got", "go", "went", "like",
        "um", "uh", "well", "yeah", "okay", "ok", "actually", "honestly", "know", "think", "let", "s", "t",
    )

    /** Light synonym folding so meaning-equivalent wording is accepted (Script Practice principle). */
    private val synonyms = mapOf(
        "large" to "big", "huge" to "big", "enormous" to "big", "comfy" to "comfortable", "glad" to "happy",
        "delighted" to "happy", "stunning" to "beautiful", "gorgeous" to "beautiful", "breathtaking" to "beautiful",
        "amazing" to "great", "awesome" to "great", "fantastic" to "great", "wonderful" to "great", "terrible" to "bad",
        "awful" to "bad", "movie" to "film", "drugstore" to "pharmacy", "drug" to "pharmacy", "toilet" to "restroom",
        "bathroom" to "restroom", "purchase" to "buy", "bought" to "buy", "usually" to "often", "normally" to "often",
        "begin" to "start", "kid" to "child", "children" to "child", "young" to "child", "mom" to "mother", "mum" to "mother",
        "jog" to "run", "jogging" to "run", "running" to "run", "ran" to "run", "tv" to "television",
    )

    fun normalize(text: String): String {
        var t = text.lowercase()
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        contractions.forEach { (k, v) -> t = t.replace(Regex("\\b" + Regex.escape(k) + "\\b"), v) }
        t = t.replace(Regex("[^a-z0-9'\\s-]"), " ").replace("-", " ")
        t = t.replace(Regex("\\b(\\d+)\\b")) { m -> numberWords[m.value] ?: m.value }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun words(text: String): List<String> =
        normalize(text).split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** Very small lemmatizer: enough to match "jumped"/"jumping"/"jumps" to "jump". */
    fun lemma(word: String): String {
        val w = synonyms[word] ?: word
        val base = when {
            w.length > 5 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3).let { if (it.length > 2 && it[it.length - 1] == it[it.length - 2]) it.dropLast(1) else it }
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2).let { if (it.length > 2 && it[it.length - 1] == it[it.length - 2]) it.dropLast(1) else it }
            w.length > 3 && w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")) -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
        return synonyms[base] ?: base
    }

    fun contentLemmas(text: String): List<String> =
        words(text).filter { it !in stopwords && it.length > 1 }.map { lemma(it) }

    fun sentences(text: String): List<String> =
        text.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { words(it).isNotEmpty() }

    fun jaccard(a: Collection<String>, b: Collection<String>): Double {
        val sa = a.toSet(); val sb = b.toSet()
        if (sa.isEmpty() && sb.isEmpty()) return 1.0
        val inter = sa.intersect(sb).size.toDouble()
        return inter / (sa.size + sb.size - inter)
    }
}
