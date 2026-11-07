package com.opicih.coach.speech

import android.content.Context
import android.os.SystemClock
import com.opicih.coach.ai.Transcriber
import com.opicih.coach.analysis.PauseAnalyzer
import com.opicih.coach.analysis.PauseMetrics
import com.opicih.coach.data.prefs.CaptureMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class CaptureResult(
    val transcript: String,
    val pauses: PauseMetrics?,
    val durationMs: Long,
    val audioPath: String?,
    val captureMode: String,
    val fromDeviceStt: Boolean,
    /** Non-fatal issue to show (e.g. network error mid-answer, transcription failed). */
    val warning: String? = null,
)

/**
 * One answer capture, shared by the Speaking and Mock screens.
 * LIVE_STT: SpeechRecognizer with auto-restart + onRmsChanged pause estimate (no audio file: the mic is busy).
 * RECORD_ONLY: PCM WAV recording + precise RMS pause analysis; text only if a cloud transcriber is configured.
 */
class AnswerCapture(private val context: Context, private val audioDir: File) {
    private val live = LiveRecognizer(context)
    private val recorder = PcmRecorder()
    private var mode: CaptureMode = CaptureMode.LIVE_STT
    private var startedAt = 0L
    private var file: File? = null

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    val liveState get() = live.state
    val recorderLevel get() = recorder.level

    /** @return null on success, otherwise an error message. */
    fun start(scope: CoroutineScope, captureMode: CaptureMode): String? {
        mode = captureMode
        startedAt = SystemClock.elapsedRealtime()
        return when (captureMode) {
            CaptureMode.LIVE_STT -> { live.start(); live.state.value.error }
            CaptureMode.RECORD_ONLY -> {
                val f = File(audioDir, "ans_${System.currentTimeMillis()}.wav")
                file = f
                if (recorder.start(scope, f)) null else "녹음을 시작하지 못했습니다. 다른 앱이 마이크를 사용 중인지 확인하세요."
            }
        }
    }

    val elapsedMs: Long get() = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    suspend fun stop(transcriber: Transcriber?): CaptureResult {
        val duration = elapsedMs
        startedAt = 0L
        return when (mode) {
            CaptureMode.LIVE_STT -> {
                val warning = live.state.value.error
                val text = live.finish()
                val levels = live.levels.toList()
                val pauses = if (levels.size >= 10) {
                    // onRmsChanged samples arrive ~every 50 ms; resample to a fixed 50 ms grid.
                    val grid = FloatArray((duration / 50).toInt().coerceAtLeast(1)) { -2f }
                    levels.forEach { (t, v) -> val i = (t / 50).toInt(); if (i in grid.indices) grid[i] = maxOf(grid[i], v) }
                    PauseAnalyzer.analyze(grid.toList(), 50, LIVE_SPEECH_DB)
                } else null
                CaptureResult(text, pauses, duration, null, CaptureMode.LIVE_STT.name, true, warning.takeIf { text.isBlank() || it?.contains("네트워크") == true })
            }
            CaptureMode.RECORD_ONLY -> {
                val r = recorder.stop()
                if (r == null) return CaptureResult("", null, duration, null, CaptureMode.RECORD_ONLY.name, false, "녹음 파일이 비어 있습니다.")
                val pauses = PauseAnalyzer.analyze(r.rms, PcmRecorder.FRAME_MS, PauseAnalyzer.adaptiveThreshold(r.rms))
                var warning: String? = null
                val text = if (transcriber != null) {
                    transcriber.transcribe(r.file).getOrElse { warning = it.message; "" }
                } else ""
                CaptureResult(text, pauses, r.durationMs, r.file.absolutePath, if (transcriber != null) "CLOUD_STT" else CaptureMode.RECORD_ONLY.name, false, warning)
            }
        }
    }

    fun cancel() {
        live.stop(cancelOnly = true)
        recorder.cancel()
        startedAt = 0L
    }

    fun release() { live.release(); recorder.cancel() }

    companion object {
        /** onRmsChanged dB above which a frame counts as speech (engine dependent, typically -2..10). */
        const val LIVE_SPEECH_DB = 3.5f
    }
}
