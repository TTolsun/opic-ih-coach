package com.opicih.coach.speech

enum class VoiceGender(val label: String) { FEMALE("여성"), MALE("남성"), UNKNOWN("") }

/**
 * 안드로이드 TTS는 음성의 성별을 알려 주지 않는다. Google TTS 음성은 이름의 코드(예: en-us-x-iob-local의 iob)가
 * 같은 목소리를 가리키므로, 알려진 코드의 성별을 표로 들고 있다. 표에 없는 음성은 UNKNOWN이다.
 * 표는 코드 이름으로 추정한 것이라서 화면에서는 항상 "추정"으로 안내하고 미리듣기로 확인하게 한다.
 */
object VoiceGenders {
    private val FEMALE = setOf(
        "en-us-x-iob", "en-us-x-iog", "en-us-x-sfg", "en-us-x-tpc", "en-us-x-tpf",
        "en-gb-x-gba", "en-gb-x-gbc", "en-gb-x-gbg",
        "ko-kr-x-ism", "ko-kr-x-kob",
    )
    private val MALE = setOf(
        "en-us-x-iol", "en-us-x-iom", "en-us-x-tpd",
        "en-gb-x-gbb", "en-gb-x-gbd", "en-gb-x-rjs",
        "ko-kr-x-koc", "ko-kr-x-kod",
    )

    fun of(voiceName: String): VoiceGender {
        val code = voiceName.lowercase().removeSuffix("-local").removeSuffix("-network")
        return when (code) {
            in FEMALE -> VoiceGender.FEMALE
            in MALE -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }
}
