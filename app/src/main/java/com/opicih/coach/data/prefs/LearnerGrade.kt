package com.opicih.coach.data.prefs

/**
 * OPIc 등급 사다리. 순서(ordinal)가 곧 높낮이이므로 항목 순서를 바꾸면 안 됩니다.
 * 학습자의 현재 등급과 다음 시험 목표 등급을 표현하는 데 쓰이며, 공식 등급 예측과는 무관합니다.
 */
enum class LearnerGrade(val code: String) {
    NH("NH"), IL("IL"), IM1("IM1"), IM2("IM2"), IM3("IM3"), IH("IH"), AL("AL");

    /** 한 단계 위 등급. 마지막 등급이면 자기 자신입니다. */
    fun next(): LearnerGrade = entries.getOrElse(ordinal + 1) { this }

    companion object {
        /** 공식 결과 문자열("IM2", "im 2" 등)을 등급으로 바꿉니다. 알 수 없는 값이면 null입니다. */
        fun parse(text: String?): LearnerGrade? {
            val key = text?.filter { !it.isWhitespace() }?.uppercase().orEmpty()
            return entries.firstOrNull { it.code == key }
        }

        fun of(name: String?, default: LearnerGrade): LearnerGrade = entries.firstOrNull { it.name == name } ?: default
    }
}

/**
 * 학습자의 현재 등급, 다음 시험의 목표 등급, 최종 목표를 한곳에 모읍니다.
 * 프롬프트와 준비도 기준은 이 값을 읽고, 등급 문자열을 코드에 직접 쓰지 않습니다.
 */
data class LearnerProfile(
    val current: LearnerGrade = LearnerGrade.IM1,
    val target: LearnerGrade = LearnerGrade.IM2,
    val finalGoal: LearnerGrade = LearnerGrade.IH,
) {
    companion object {
        val DEFAULT = LearnerProfile()
    }
}
