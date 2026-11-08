package com.opicih.coach.learning

import com.opicih.coach.analysis.DiscourseMetrics

/**
 * 같은 처음 보는 질문에 1문장 → 3문장 → 5문장 이상으로 단계를 올리는 문단 확장 사다리.
 * 조건 수치는 학습 설계를 위한 제안값이며 공식 기준이 아닙니다.
 */
enum class LadderLevel(val label: String, val minSentences: Int, val minConnectors: Int, val minPast: Int) {
    ONE("1문장", 1, 0, 0),
    THREE("3문장", 3, 1, 0),
    FIVE("5문장 이상", 5, 2, 1);

    /** 다음 단계. 마지막이면 null입니다. */
    fun next(): LadderLevel? = entries.getOrNull(ordinal + 1)

    /** 화면에 보여 줄 한 줄 미션(한국어). */
    val missionKo: String
        get() = buildString {
            append("${label}으로 답하세요")
            if (minConnectors > 0) append(" · 연결어 ${minConnectors}개 이상")
            if (minPast > 0) append(" · 과거형 표현 ${minPast}개 이상")
        }
}

data class LadderResult(val level: LadderLevel, val passed: Boolean, val missing: List<String>)

object ParagraphLadder {
    /** 문장 부호가 없는 인식 결과에서 한 문장으로 치는 평균 단어 수(추정값). */
    const val WORDS_PER_SENTENCE = 10

    /** 문장 부호가 있어도 한 문장이 이보다 길면 부호가 제대로 찍히지 않은 것으로 보고 단어 수로 추정합니다. */
    const val MAX_WORDS_PER_PUNCTUATED_SENTENCE = 25

    /**
     * 문장 수. 기기 음성 인식은 마침표를 넣지 않거나 끝에 하나만 찍는 경우가 많습니다. 부호가 없거나, 부호는 있어도
     * 한 문장이 지나치게 길면 단어 수로 추정합니다. 그렇지 않으면 긴 답변도 1문장으로 계산되어 3단계 이상을
     * 통과할 수 없습니다.
     */
    fun sentenceCount(d: DiscourseMetrics, hasSentencePunctuation: Boolean): Int {
        val estimate = d.wordCount / WORDS_PER_SENTENCE
        val wordsPerSentence = if (d.sentenceCount > 0) d.wordCount / d.sentenceCount else d.wordCount
        val trustPunctuation = hasSentencePunctuation && wordsPerSentence <= MAX_WORDS_PER_PUNCTUATED_SENTENCE
        return if (trustPunctuation) d.sentenceCount else maxOf(d.sentenceCount, estimate)
    }

    /** 답변의 담화 지표로 [level] 통과 여부와 부족한 조건을 판정합니다. */
    fun judge(level: LadderLevel, d: DiscourseMetrics, hasSentencePunctuation: Boolean = true): LadderResult {
        val sentences = sentenceCount(d, hasSentencePunctuation)
        val missing = mutableListOf<String>()
        if (sentences < level.minSentences) missing += "문장 ${level.minSentences}개 이상 (지금 ${sentences}개)"
        if (d.connectorCount < level.minConnectors) missing += "연결어 ${level.minConnectors}개 이상 (지금 ${d.connectorCount}개)"
        if (d.pastCount < level.minPast) missing += "과거형 표현 ${level.minPast}개 이상 (지금 ${d.pastCount}개)"
        return LadderResult(level, missing.isEmpty(), missing)
    }
}
