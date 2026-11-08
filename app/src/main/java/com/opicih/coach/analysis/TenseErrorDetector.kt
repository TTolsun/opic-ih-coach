package com.opicih.coach.analysis

/** 과거 맥락에서 현재형을 쓴 문장 하나. [sentence]는 화면과 오류 노트의 예시입니다. */
data class TenseHit(val sentence: String, val verb: String)

/**
 * 과거 시간 표지(yesterday, last weekend, ago, when I was ...)가 있는 문장에서 현재형 동사를 찾는 규칙 탐지기입니다.
 * "Last weekend I go to the park"처럼 과거 맥락에서 현재형을 쓴 오류를 잡기 위한 것으로,
 * 기존 과거형 개수 세기(`DiscourseAnalyzer`)는 이런 오류를 보지 못합니다.
 *
 * 오탐을 줄이려고 범위를 좁게 잡았습니다: 불규칙 동사 목록과 be 동사만 보고, 주어 바로 뒤의 동사만 봅니다.
 * 문장에 now/usually/every 같은 현재 표지가 있으면 그 문장은 건너뜁니다. 학습용 참고값이며 문법 검사기가 아닙니다.
 */
object TenseErrorDetector {
    private val PAST_MARKER = Regex(
        "\\b(yesterday|last (night|week|weekend|month|year|summer|winter|spring|fall|time)|ago|when i was|the other day|in (19|20)\\d\\d)\\b",
    )
    private val PRESENT_MARKER = Regex("\\b(now|nowadays|these days|today|usually|always|often|never|still|every|sometimes)\\b")
    private val SUBJECT = "(?:i|we|they|he|she|it|my \\w+|our \\w+|the \\w+)"
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
    private val NOT_LETTERS = Regex("[^a-z' ]")
    private val SPACES = Regex("\\s+")
    private const val CHUNK_WORDS = 12
    private const val LONG_SEGMENT_WORDS = 25

    private fun norm(s: String): String =
        s.lowercase().replace('’', '\'').replace(NOT_LETTERS, " ").replace('\'', ' ').replace(SPACES, " ").trim()

    /**
     * 문장 부호가 없는 인식 결과는 한 덩어리가 길어서, 앞쪽의 과거 표지가 뒤쪽 미래 이야기에까지 번지지 않도록
     * 긴 덩어리를 [CHUNK_WORDS]단어씩 나눠서 봅니다.
     */
    private fun segments(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { seg ->
            val words = seg.split(SPACES)
            if (words.size <= LONG_SEGMENT_WORDS) listOf(seg) else words.chunked(CHUNK_WORDS).map { it.joinToString(" ") }
        }

    fun find(text: String): List<TenseHit> = segments(text).flatMap { seg ->
        val n = norm(seg)
        if (!PAST_MARKER.containsMatchIn(n) || PRESENT_MARKER.containsMatchIn(n)) emptyList()
        else HIT.findAll(n).map { TenseHit(seg.trim(), it.groupValues[2]) }.toList()
    }

    fun count(text: String): Int = find(text).size
}
