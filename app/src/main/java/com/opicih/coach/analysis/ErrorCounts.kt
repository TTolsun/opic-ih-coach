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
    /** 과거 맥락에서 현재형을 쓴 횟수. 스스로 고친 첫 시도는 세지 않습니다. */
    val tense: Int? = null,
    val abandoned: Int? = null,
    val selfCorrection: Int? = null,
    /** 원문 그대로 전사(CLOUD_STT)에서 센 값인가. 추세는 이 값이 true인 답변만 씁니다. */
    val verbatim: Boolean = false,
    /** tense 값의 출처: "AI" 또는 "LOCAL". */
    val tenseSource: String = "LOCAL",
    val localTense: Int? = null,
    val localTenseExamples: List<String> = emptyList(),
    val aiTense: Int? = null,
    val aiAbandoned: Int? = null,
    val aiSelfCorrection: Int? = null,
) {
    /** 한 답변의 횟수와, 같은 값에서 나온 오류 노트 항목. */
    class Analysis(val counts: ErrorCounts, val notebook: List<ErrorItem>)

    companion object {
        /**
         * 전사를 한 번만 읽어 횟수와 오류 노트 항목을 함께 만듭니다.
         *
         * @param raw 저장된 전사 원문(표기 포함)
         * @param verbatimSource 원문 그대로 전사에서 나온 텍스트인가
         * @param ai AI 평가 결과. 시제 오류 수를 돌려줬으면 그 값을, 아니면 규칙 탐지 값을 씁니다.
         *   AI 값을 쓸 때는 AI가 낸 오류 항목이 노트의 근거이므로 규칙 탐지 항목을 따로 넣지 않습니다.
         */
        fun analyze(raw: String, verbatimSource: Boolean, ai: Feedback?): Analysis {
            val parsed = VerbatimMarkup.parse(raw)
            val hits = TenseErrorDetector.find(parsed.uncorrected)
            val evaluation = ai?.takeIf { it.source == "AI" }
            val aiTense = evaluation?.tenseErrors
            val measuredVerbatim = verbatimSource && parsed.clean.isNotBlank()
            val examples = hits.map { FeedbackComposer.shorten(it.sentence, 80) }.distinct()
            val localTense = hits.size.takeIf { parsed.uncorrected.isNotBlank() }
            val counts = ErrorCounts(
                tense = aiTense ?: localTense,
                abandoned = parsed.abandoned.takeIf { measuredVerbatim },
                selfCorrection = parsed.selfCorrections.takeIf { measuredVerbatim },
                verbatim = measuredVerbatim,
                tenseSource = if (aiTense != null) "AI" else "LOCAL",
                localTense = localTense,
                localTenseExamples = examples,
                aiTense = aiTense,
                aiAbandoned = evaluation?.abandonedSentences.takeIf { measuredVerbatim },
                aiSelfCorrection = evaluation?.selfCorrections.takeIf { measuredVerbatim },
            )
            val items = mutableListOf<ErrorItem>()
            if (aiTense == null) examples.firstOrNull()?.let {
                items += ErrorItem("tense_in_past_context", "GRAMMAR", it, "과거 이야기에서는 동사를 과거형으로 말해 보세요.")
            }
            if (measuredVerbatim) parsed.abandonedFragments.firstOrNull()?.let {
                items += ErrorItem("abandoned_sentence", "FLUENCY", it, "쉬운 표현으로 바꿔서라도 문장을 끝까지 말하기")
            }
            val have = ai?.errors?.map { it.key }?.toSet().orEmpty()
            return Analysis(counts, items.filter { it.key !in have }.distinctBy { it.key })
        }
    }
}

/** Map known model aliases before counting repeated errors. Preserve unrelated error categories. */
internal fun ErrorItem.withCanonicalKey(): ErrorItem = when (key) {
    "irrelevant_description", "missing_problem_solving" -> copy(key = "task_incomplete")
    else -> this
}
