package com.opicih.coach.analysis

import kotlinx.serialization.Serializable

@Serializable
data class PauseMetrics(
    val totalMs: Long,
    val speechMs: Long,
    val longPauseCount: Int,
    val maxPauseMs: Long,
    /** Silence before the first speech frame (hesitation before starting). */
    val leadingSilenceMs: Long,
) {
    val speechRatio: Float get() = if (totalMs == 0L) 0f else speechMs.toFloat() / totalMs
}

/**
 * Real acoustic measurement (not STT based): splits a loudness timeline into speech / silence.
 * Input is one level value per frame, either PCM RMS (record mode) or onRmsChanged dB (live mode).
 */
object PauseAnalyzer {
    const val LONG_PAUSE_MS = 2000L

    /**
     * @param levels loudness per frame
     * @param frameMs frame length in ms
     * @param threshold value at or above which a frame counts as speech
     */
    fun analyze(levels: List<Float>, frameMs: Long, threshold: Float): PauseMetrics {
        if (levels.isEmpty()) return PauseMetrics(0, 0, 0, 0, 0)
        val total = levels.size * frameMs
        // Smooth tiny gaps (< 200 ms) between syllables so they do not count as silence.
        val speech = levels.map { it >= threshold }.toMutableList()
        val bridge = (200 / frameMs).toInt().coerceAtLeast(1)
        var i = 0
        while (i < speech.size) {
            if (!speech[i]) {
                var j = i
                while (j < speech.size && !speech[j]) j++
                val gap = j - i
                if (i > 0 && j < speech.size && gap <= bridge) for (k in i until j) speech[k] = true
                i = j
            } else i++
        }
        val firstSpeech = speech.indexOfFirst { it }
        val lastSpeech = speech.indexOfLast { it }
        if (firstSpeech < 0) return PauseMetrics(total, 0, 0, 0, total)

        var longPauses = 0
        var maxPause = 0L
        var run = 0
        for (idx in firstSpeech..lastSpeech) {
            if (!speech[idx]) run++ else {
                if (run > 0) {
                    val ms = run * frameMs
                    if (ms >= LONG_PAUSE_MS) longPauses++
                    if (ms > maxPause) maxPause = ms
                }
                run = 0
            }
        }
        val speechMs = speech.count { it } * frameMs
        return PauseMetrics(total, speechMs, longPauses, maxPause, firstSpeech * frameMs)
    }

    /**
     * Speech threshold adapted to this recording: a multiple of the background noise floor (20th percentile),
     * clamped so a silent room does not turn noise into "speech" and a loud room does not hide quiet speech.
     */
    fun adaptiveThreshold(levels: List<Float>, factor: Float = 2.5f, min: Float = 250f, max: Float = 1500f): Float {
        if (levels.isEmpty()) return min
        val sorted = levels.sorted()
        val noise = sorted[(sorted.size * 0.2f).toInt().coerceIn(0, sorted.lastIndex)]
        return (noise * factor).coerceIn(min, max)
    }

    /** RMS of 16-bit PCM samples, in the 0..32768 range. */
    fun rms(samples: ShortArray, length: Int = samples.size): Float {
        if (length == 0) return 0f
        var sum = 0.0
        for (k in 0 until length) { val v = samples[k].toDouble(); sum += v * v }
        return kotlin.math.sqrt(sum / length).toFloat()
    }
}
