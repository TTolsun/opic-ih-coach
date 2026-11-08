package com.opicih.coach.learning

import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.prefs.Settings

/** `PracticeAttemptEntity.captureMode`에 저장되는, 녹음을 Gemini로 원문 그대로 전사한 답변의 표기. */
const val CAPTURE_CLOUD_STT = "CLOUD_STT"

/** 이번 답변을 어떻게 캡처할지. [note]는 원문 그대로 전사를 못 쓴 이유로, 결과 카드의 측정 한계에 표시됩니다. */
data class CaptureChoice(val mode: CaptureMode, val verbatim: Boolean, val note: String? = null)

/**
 * 즉석 답변(자유 발화, 모의시험, 틀 붙이기, 문단 사다리)은 기본이 녹음 + 원문 그대로 전사입니다.
 * 기기 음성 인식은 문법을 고치고 필러를 지워서, IM1과 IM3를 가르는 시제 오류와 중단 문장이 보이지 않기 때문입니다.
 * 음성을 외부로 보내므로 설정의 전사 켜기와 음성 외부 전송 동의, 키가 모두 있을 때만 자동으로 씁니다.
 * 스크립트를 보고 하는 연습과 SAIL 대화는 사용자가 고른 캡처 방식을 그대로 따릅니다.
 */
object CapturePolicy {
    private val DIAGNOSTIC_MODES = setOf(PracticeMode.FREE, PracticeMode.MOCK, PracticeMode.FRAME_ATTACH, PracticeMode.PARAGRAPH_LADDER)

    fun isDiagnostic(mode: PracticeMode): Boolean = mode in DIAGNOSTIC_MODES

    fun choose(mode: PracticeMode, s: Settings, hasGeminiKey: Boolean): CaptureChoice {
        val cloudReady = s.cloudSttEnabled && s.audioUploadConsent && hasGeminiKey
        if (!isDiagnostic(mode)) return CaptureChoice(s.captureMode, verbatim = s.captureMode == CaptureMode.RECORD_ONLY && cloudReady)
        if (cloudReady) return CaptureChoice(CaptureMode.RECORD_ONLY, verbatim = true)
        val reason = when {
            !s.cloudSttEnabled -> "설정에서 녹음 전사를 켜지 않았습니다"
            !s.audioUploadConsent -> "음성 외부 전송에 동의하지 않았습니다"
            else -> "Gemini 키가 없습니다"
        }
        return CaptureChoice(s.captureMode, verbatim = false, note = "원문 그대로 전사를 쓰지 않았습니다: $reason.")
    }
}
