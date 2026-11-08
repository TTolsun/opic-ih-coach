package com.opicih.coach.learning

import com.opicih.coach.data.prefs.LearnerGrade

/**
 * 다음 시험 준비도 조건의 기준값. 목표 등급이 높을수록 요구 수준이 올라갑니다.
 * 수치는 학습 설계를 위한 제안값이며 공식 등급 기준과 연결된 근거가 없습니다(학습 전략, 미검증).
 * IH 값은 기존 앱이 쓰던 값(90단어, 65점, 모의시험 60점)을 그대로 유지합니다.
 */
data class ReadinessCriteria(
    val grade: LearnerGrade,
    val minWords: Int,
    val minScore: Int,
    val minMockScore: Int,
) {
    companion object {
        fun of(target: LearnerGrade): ReadinessCriteria = when {
            target <= LearnerGrade.IM2 -> ReadinessCriteria(LearnerGrade.IM2, minWords = 50, minScore = 55, minMockScore = 50)
            target == LearnerGrade.IM3 -> ReadinessCriteria(LearnerGrade.IM3, minWords = 70, minScore = 60, minMockScore = 55)
            else -> ReadinessCriteria(LearnerGrade.IH, minWords = 90, minScore = 65, minMockScore = 60)
        }
    }
}
