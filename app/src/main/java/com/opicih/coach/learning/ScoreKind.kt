package com.opicih.coach.learning

import com.opicih.coach.data.db.PracticeMode

/**
 * 점수가 무엇을 재는지 구분합니다.
 *
 * - [MEMORIZATION]: 스크립트를 얼마나 떠올려 말했는지(coverage 중심). 외울수록 오릅니다.
 * - [SPEAKING]: 스크립트 없이 즉석에서 말한 답변의 점수. coverage를 쓰지 않고, 준비도와 추세는 이 점수만 씁니다.
 *
 * 종류는 시도 기록에 저장된 mode에서 항상 같은 값으로 정해지므로, 별도 열 없이도 과거 기록이 같은 기준으로 분류됩니다.
 */
enum class ScoreKind(val label: String) {
    MEMORIZATION("암기 점수"),
    SPEAKING("말하기 점수");

    companion object {
        fun of(mode: PracticeMode): ScoreKind = when (mode) {
            PracticeMode.READ_LISTEN, PracticeMode.SHADOWING, PracticeMode.FILL_BLANK,
            PracticeMode.KEYWORD, PracticeMode.BLIND -> MEMORIZATION
            PracticeMode.RANDOM, PracticeMode.VARIATION, PracticeMode.FREE,
            PracticeMode.MOCK, PracticeMode.SAIL_CONVERSATION,
            PracticeMode.FRAME_ATTACH, PracticeMode.PARAGRAPH_LADDER -> SPEAKING
        }

        fun of(modeName: String?): ScoreKind = of(PracticeMode.of(modeName))

        /** 말하기 점수로 집계하는 mode 이름. 준비도와 추세 계산이 이 집합을 씁니다. */
        val SPEAKING_MODES: Set<String> =
            PracticeMode.entries.filter { of(it) == SPEAKING }.map { it.name }.toSet()

        /**
         * 준비도와 약점 분석에 쓰는 답변. 문단 사다리는 1문장 답에서 시작하도록 만든 단계별 연습이라, 일부러 짧은 답이
         * 길이·점수 기준을 깎지 않도록 제외합니다. 사다리 기록은 말하기 점수 추세에는 그대로 나타납니다.
         */
        val READINESS_MODES: Set<String> = SPEAKING_MODES - PracticeMode.PARAGRAPH_LADDER.name
    }
}
