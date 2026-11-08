package com.opicih.coach.analysis

/** [clean]은 표기를 뺀 본문입니다. 단어 수와 시제 분석은 이 본문으로 합니다. */
data class VerbatimParse(
    val clean: String,
    val selfCorrections: Int,
    val abandoned: Int,
    /** 끝맺지 못한 문장의 앞부분(오류 노트 예시용). 길면 뒤쪽 [MAX_FRAGMENT]자만 남깁니다. */
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
    private val SPACES = Regex("\\s+")

    fun parse(text: String): VerbatimParse {
        var selfCorrections = 0
        val fragments = mutableListOf<String>()
        var boundary = 0
        for (m in TAG.findAll(text)) {
            if (m.groupValues[1].equals("abandoned", ignoreCase = true)) {
                val before = text.substring(boundary, m.range.first)
                // 같은 줄에서 마지막 문장 부호 뒤부터가 끝맺지 못한 부분입니다.
                val start = before.indexOfLast { it == '.' || it == '!' || it == '?' } + 1
                val fragment = ANY_BRACKET.replace(before.substring(start), "").replace(SPACES, " ").trim()
                if (fragment.isNotEmpty()) fragments += fragment.takeLast(MAX_FRAGMENT)
                // 위 판단은 이 태그의 앞부분만 보므로, 다음 문장 후보 계산은 태그 뒤에서 다시 시작합니다.
                boundary = m.range.last + 1
                continue
            }
            selfCorrections++
        }
        val abandoned = TAG.findAll(text).count { it.groupValues[1].equals("abandoned", ignoreCase = true) }
        val clean = ANY_BRACKET.replace(text, " ").replace(SPACES, " ").trim()
        return VerbatimParse(clean, selfCorrections, abandoned, fragments)
    }
}
