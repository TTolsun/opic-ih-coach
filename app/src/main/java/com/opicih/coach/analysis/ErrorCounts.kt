package com.opicih.coach.analysis

import kotlinx.serialization.Serializable

/**
 * IM1과 IM3를 가르는 오류의 횟수. `metricsJson`(AttemptMetrics)에 저장되어 진행 화면의 추세가 됩니다.
 *
 * 값이 null이면 "측정하지 못함"입니다. 기기 음성 인식은 문법을 고치고 필러를 지우므로, 자기 수정과 중단 문장은
 * 원문 그대로 전사([verbatim])한 답변에서만 셉니다.
 */
@Serializable
data class ErrorCounts(
    /** 과거 맥락에서 현재형을 쓴 횟수. */
    val tense: Int? = null,
    val abandoned: Int? = null,
    val selfCorrection: Int? = null,
    /** 원문 그대로 전사(CLOUD_STT)에서 센 값인가. 추세는 이 값이 true인 답변만 씁니다. */
    val verbatim: Boolean = false,
    /** tense 값의 출처: "AI" 또는 "LOCAL". */
    val tenseSource: String = "LOCAL",
) {
    companion object {
        /**
         * @param raw 저장된 전사 원문(표기 포함)
         * @param verbatimSource 원문 그대로 전사에서 나온 텍스트인가
         * @param ai AI 평가 결과. 시제 오류 수를 돌려줬으면 그 값을, 아니면 규칙 탐지 값을 씁니다.
         */
        fun from(raw: String, verbatimSource: Boolean, ai: Feedback?): ErrorCounts {
            val parsed = VerbatimMarkup.parse(raw)
            val aiTense = ai?.takeIf { it.source == "AI" }?.tenseErrors
            return ErrorCounts(
                tense = aiTense ?: TenseErrorDetector.count(parsed.clean),
                abandoned = if (verbatimSource) maxOf(parsed.abandoned, ai?.abandonedSentences ?: 0) else null,
                selfCorrection = if (verbatimSource) maxOf(parsed.selfCorrections, ai?.selfCorrections ?: 0) else null,
                verbatim = verbatimSource,
                tenseSource = if (aiTense != null) "AI" else "LOCAL",
            )
        }

        /** 오류 노트에 쌓을 항목. 같은 key는 한 번만 넣습니다(AI가 이미 낸 항목이 우선). */
        fun notebookItems(raw: String, verbatimSource: Boolean, existing: List<ErrorItem>): List<ErrorItem> {
            val parsed = VerbatimMarkup.parse(raw)
            val items = mutableListOf<ErrorItem>()
            TenseErrorDetector.find(parsed.clean).take(2).forEach {
                items += ErrorItem("tense_in_past_context", "GRAMMAR", FeedbackComposer.shorten(it.sentence, 80), "과거 이야기에서는 동사를 과거형(went, ate, was)으로 유지")
            }
            if (verbatimSource) parsed.abandonedFragments.take(2).forEach {
                items += ErrorItem("abandoned_sentence", "FLUENCY", it, "쉬운 표현으로 바꿔서라도 문장을 끝까지 말하기")
            }
            val have = existing.map { it.key }.toSet()
            return items.filter { it.key !in have }.distinctBy { it.key }
        }
    }
}
