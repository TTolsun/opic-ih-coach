package com.opicih.coach.analysis

/**
 * [clean]은 표기를 뺀 본문으로 단어 수와 시제 분석에 씁니다.
 * [uncorrected]는 거기서 스스로 고친 첫 시도까지 뺀 본문으로, 시제 오류 탐지에 씁니다.
 */
data class VerbatimParse(
    val clean: String,
    val uncorrected: String,
    val selfCorrections: Int,
    val abandoned: Int,
    /** 끝맺지 못한 문장의 앞부분(오류 노트 예시용). 길면 뒤쪽 [VerbatimMarkup.MAX_FRAGMENT]자만 남깁니다. */
    val abandonedFragments: List<String>,
)

/**
 * 원문 그대로 전사(녹음 전사)의 표기를 읽습니다.
 * 전사 프롬프트는 자기 수정 뒤에 `[self-correction]`, 끝맺지 못한 문장 뒤에 `[abandoned]`를 붙이도록 요청합니다
 * (`GeminiTranscriber.PROMPT`). 그 밖의 `[laughs]` 같은 짧은 괄호 표기는 본문에서 지웁니다.
 */
object VerbatimMarkup {
    const val SELF_CORRECTION = "[self-correction]"
    const val ABANDONED = "[abandoned]"
    const val MAX_FRAGMENT = 80

    private val TAG = Regex("\\[(self-correction|abandoned)]", RegexOption.IGNORE_CASE)
    private val ANY_BRACKET = Regex("\\[[^\\]]{1,30}]")
    private val HORIZONTAL_SPACES = Regex("[^\\S\\r\\n]+")

    private fun tidy(s: String) = ANY_BRACKET.replace(s, " ").replace("\r\n", "\n").replace('\r', '\n')
        .lineSequence().joinToString("\n") { HORIZONTAL_SPACES.replace(it, " ").trim() }.trim()

    private fun lastBoundary(s: String): Int = s.indexOfLast { it in ".!?\r\n" }

    /** 마지막 문장 부호까지를 남기고 그 뒤(아직 끝나지 않은 부분)를 버립니다. 부호가 없으면 전부 버립니다. */
    private fun dropUnfinishedTail(s: String): String = s.substring(0, lastBoundary(s) + 1)

    fun parse(text: String): VerbatimParse {
        var selfCorrections = 0
        var abandoned = 0
        val fragments = mutableListOf<String>()
        val uncorrected = StringBuilder()
        var cursor = 0
        for (m in TAG.findAll(text)) {
            val before = text.substring(cursor, m.range.first)
            if (m.groupValues[1].equals("abandoned", ignoreCase = true)) {
                abandoned++
                // 같은 문장에서 마지막 문장 부호 뒤부터가 끝맺지 못한 부분입니다.
                val fragment = tidy(before.substring(lastBoundary(before) + 1))
                if (fragment.isNotEmpty()) fragments += fragment.takeLast(MAX_FRAGMENT)
                uncorrected.append(before).append(';')
            } else {
                selfCorrections++
                // 고치기 전의 첫 시도는 학습자가 스스로 바로잡은 것이므로 시제 오류로 세지 않습니다.
                uncorrected.append(dropUnfinishedTail(before))
            }
            uncorrected.append(' ')
            cursor = m.range.last + 1
        }
        uncorrected.append(text.substring(cursor))
        return VerbatimParse(tidy(text), tidy(uncorrected.toString()), selfCorrections, abandoned, fragments)
    }
}
