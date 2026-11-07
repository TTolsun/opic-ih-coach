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
        // Paraphrase groups common in OPIc answers (Blind mode accepts same content in other words).
        "autumn" to "fall", "scenery" to "view", "landscape" to "view", "sight" to "view",
        "place" to "spot", "location" to "spot", "unique" to "special", "lovely" to "beautiful",
        "trip" to "travel", "journey" to "travel", "traveling" to "travel", "travelling" to "travel",
        "beach" to "coast", "shore" to "coast", "seaside" to "coast", "farming" to "farm", "farmer" to "farm",
        "delicious" to "tasty", "yummy" to "tasty", "scared" to "afraid", "frightened" to "afraid",
        "exhausted" to "tired", "sick" to "ill", "unwell" to "ill", "pal" to "friend", "buddy" to "friend",
        "store" to "shop", "fond" to "love", "enjoy" to "love", "fan" to "love",
        "chat" to "talk", "speak" to "talk", "spoke" to "talk",
        // Irregular past forms, so "ate"/"eat" or "saw"/"see" count as the same content word.
        "ate" to "eat", "eaten" to "eat", "saw" to "see", "seen" to "see", "felt" to "feel", "took" to "take",
        "taken" to "take", "made" to "make", "came" to "come", "met" to "meet", "found" to "find",
        "gave" to "give", "given" to "give", "thought" to "think", "told" to "tell", "said" to "say", "slept" to "sleep",
        "spent" to "spend", "brought" to "bring", "caught" to "catch", "lost" to "lose", "won" to "win",
        "drank" to "drink", "drove" to "drive", "rode" to "ride", "swam" to "swim", "sat" to "sit", "stood" to "stand",
        "wrote" to "write", "began" to "start",
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
