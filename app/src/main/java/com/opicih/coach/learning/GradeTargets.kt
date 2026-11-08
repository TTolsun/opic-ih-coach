package com.opicih.coach.learning

import com.opicih.coach.data.prefs.LearnerGrade

/**
 * 목표 등급별 개선안 길이와 구조 규칙.
 * 단어 수 범위는 학습 설계를 위한 제안값이며 공식 기준이 아닙니다(학습 전략, 미검증).
 */
data class ImproveTarget(val minWords: Int, val maxWords: Int, val structure: String) {
    val rangeLabel: String get() = "${minWords}~${maxWords}단어"
}

enum class LengthFit { SHORTER, WITHIN, LONGER }

object GradeTargets {
    private val IM2_TARGET = ImproveTarget(
        50, 80,
        "5-7 short, simple sentences. At least 2 sentences in the past tense when the question allows it. " +
            "Use only basic connectors (and, but, so, because). One concrete detail is enough.",
    )
    private val IM3_TARGET = ImproveTarget(
        70, 110,
        "A connected paragraph: opener, one specific description, one short experience, a feeling, a wrap-up. " +
            "Keep one time frame steady for at least 3 sentences, then add one sentence in another tense.",
    )
    private val IH_TARGET = ImproveTarget(
        110, 160,
        "Flow: opener, specific description, a short experience or example, feeling, wrap-up. " +
            "Use past + present + at least one future sentence when it fits, with varied connectors.",
    )

    /** 다음 시험 목표 등급에 맞는 개선안 규칙. IM2 이하는 IM2 규칙, IH 이상은 IH 규칙을 씁니다. */
    fun improve(target: LearnerGrade): ImproveTarget = when {
        target <= LearnerGrade.IM2 -> IM2_TARGET
        target == LearnerGrade.IM3 -> IM3_TARGET
        else -> IH_TARGET
    }

    fun fit(wordCount: Int, target: LearnerGrade): LengthFit {
        val t = improve(target)
        return when {
            wordCount < t.minWords -> LengthFit.SHORTER
            wordCount > t.maxWords -> LengthFit.LONGER
            else -> LengthFit.WITHIN
        }
    }

    /** 스크립트 상세 화면에 보여 줄 한 줄: 현재 버전이 목표 등급의 권장 길이와 어떻게 다른지 알려 줍니다. */
    fun fitLabel(wordCount: Int, target: LearnerGrade): String {
        val t = improve(target)
        val verdict = when (fit(wordCount, target)) {
            LengthFit.SHORTER -> "권장보다 짧음"
            LengthFit.WITHIN -> "권장 범위"
            LengthFit.LONGER -> "권장보다 김"
        }
        return "${wordCount}단어 · ${target.code} 목표 권장 ${t.rangeLabel} · $verdict"
    }
}
