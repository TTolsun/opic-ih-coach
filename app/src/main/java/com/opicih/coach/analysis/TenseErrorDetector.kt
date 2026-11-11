package com.opicih.coach.analysis

/** 과거 맥락에서 현재형을 쓴 구절 하나. [sentence]는 화면과 오류 노트의 예시입니다. */
data class TenseHit(val sentence: String, val verb: String)

/**
 * 과거 맥락에서 주어 뒤의 현재형 동사 후보를 찾습니다.
 * 과거 표지는 바로 다음 문장까지 적용합니다. 빈 줄과 명시적 시간 전환은
 * 맥락을 끊으며, 직접 인용 속의 표현은 화자의 서술에서 제외합니다.
 */
object TenseErrorDetector {
    private const val SUBJECT = "(?:i|we|they|he|she|it|my \\w+|our \\w+|the \\w+)"
    private const val PAST_MARKER =
        "yesterday|last (night|week|weekend|month|year|summer|winter|spring|fall|time)|ago|when i was|the other day|back then|in (?:19|20)\\d\\d"
    private const val OTHER_TIME =
        "now|nowadays|these days|today|currently|at present|at the moment|since then|ever since|so far|tomorrow|" +
            "next (?:weekend|week|month|year|summer|winter|spring|fall|time|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|" +
            "in the future|$SUBJECT (?:will|won't)|$SUBJECT'll|(?:am|is|are|'m|'s|'re) going to (?!the\\b|a\\b|an\\b)\\w+"
    private val TIME_MARKER = Regex("\\b(?:($PAST_MARKER)|$OTHER_TIME)\\b")
    private val HABIT_MARKER = Regex("\\b(usually|always|often|never|still|every|sometimes)\\b")
    private val ADVERB = "(?:(?:also|really|just|then|finally|actually) )?"

    /** 현재형 동사(원형과 3인칭). 과거형이 불규칙이라 현재형과 모양이 달라서 구분할 수 있는 것만 넣었습니다. */
    private val PRESENT_VERBS = listOf(
        "go", "goes", "eat", "eats", "see", "sees", "have", "has", "do", "does", "make", "makes", "take", "takes",
        "get", "gets", "come", "comes", "buy", "buys", "meet", "meets", "feel", "feels", "think", "thinks", "say", "says",
        "tell", "tells", "find", "finds", "give", "gives", "know", "knows", "leave", "leaves", "run", "runs", "sit", "sits",
        "sleep", "sleeps", "spend", "spends", "drink", "drinks", "write", "writes", "lose", "loses", "pay", "pays",
        "bring", "brings", "begin", "begins", "swim", "swims", "wear", "wears", "win", "wins", "drive", "drives",
        "send", "sends", "hear", "hears", "forget", "forgets", "choose", "chooses", "catch", "catches", "ride", "rides",
        "speak", "speaks", "sing", "sings", "fall", "falls", "keep", "keeps", "am", "is", "are",
    ).joinToString("|")

    private val HIT = Regex("\\b($SUBJECT) $ADVERB($PRESENT_VERBS)\\b(?! (?:been|had)\\b)")
    private val CLAUSE_BOUNDARY = Regex(
        "[;:–—]+|,(?=\\s*(?:$PAST_MARKER|$OTHER_TIME)\\b)|\\b(?:and|but|so)\\b(?=\\s+(?:$SUBJECT|$PAST_MARKER|$OTHER_TIME)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val DIRECT_QUOTE = Regex(
        """"[^"]*(?:"|$)|“[^”]*(?:”|$)|(?<![\w])'(?=\S).*?'(?!\w)|‘.*?’(?!\w)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PARAGRAPH_BOUNDARY = Regex("\n[ \t]*\n")
    // 숫자는 남깁니다: "in 2019" 같은 과거 표지가 사라지면 안 됩니다.
    private val NOT_LETTERS = Regex("[^a-z0-9' ]")
    private val SPACES = Regex("\\s+")
    private fun norm(s: String): String =
        s.lowercase().replace('’', '\'').replace(NOT_LETTERS, " ").replace(SPACES, " ").trim()

    fun find(text: String): List<TenseHit> = buildList {
        val narration = DIRECT_QUOTE.replace(text) { quote ->
            val end = quote.value.trimEnd('"', '\'', '”', '’').trimEnd().lastOrNull()
            if (end != null && end in ".!?") "$end " else "; "
        }.replace("\r\n", "\n").replace('\r', '\n')
        for (paragraph in narration.split(PARAGRAPH_BOUNDARY)) {
            var precedingPast = false
            for (sentence in TextNormalizer.sentences(paragraph)) {
                precedingPast = scan(sentence, precedingPast, this)
            }
        }
    }

    /** Returns whether an explicit anchor in this sentence can carry over. */
    private fun scan(sentence: String, precedingPast: Boolean, hits: MutableList<TenseHit>): Boolean {
        var past = precedingPast
        var hasAnchor = false
        for (clause in sentence.split(CLAUSE_BOUNDARY)) {
            val normalized = norm(clause)
            val markers = TIME_MARKER.findAll(normalized).toList()
            if (markers.isEmpty() && HABIT_MARKER.containsMatchIn(normalized)) {
                past = false
                continue
            }
            for (hit in HIT.findAll(normalized)) {
                // A trailing time expression can govern the whole clause.
                val marker = markers.lastOrNull { it.range.first <= hit.range.first } ?: markers.firstOrNull()
                val inPast = marker?.let { it.groups[1] != null } ?: past
                if (inPast) hits.add(TenseHit(clause.trim(), hit.groupValues[2]))
            }
            markers.lastOrNull()?.let {
                past = it.groups[1] != null
                hasAnchor = true
            }
        }
        return past && hasAnchor
    }

    fun count(text: String): Int = find(text).size
}
