package com.opicih.coach.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.opicih.coach.analysis.PauseAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Records 16 kHz mono PCM to a WAV file and keeps a 20 ms RMS timeline for PauseAnalyzer.
 * This is the precise acoustic measurement path (record-first mode). Resources are always released in finally.
 */
class PcmRecorder {
    data class Result(val file: File, val durationMs: Long, val rms: List<Float>)

    private var job: Job? = null
    private var record: AudioRecord? = null
    private var output: File? = null
    private val rms = mutableListOf<Float>()
    @Volatile private var running = false

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO before calling.
    fun start(scope: CoroutineScope, file: File): Boolean {
        stopInternal()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 4))
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        record = rec; output = file; rms.clear(); running = true
        file.parentFile?.mkdirs()
        job = scope.launch(Dispatchers.IO) {
            val raf = RandomAccessFile(file, "rw")
            try {
                raf.setLength(0)
                raf.write(ByteArray(44)) // header placeholder
                rec.startRecording()
                _recording.value = true
                val buf = ShortArray(FRAME)
                val bytes = ByteArray(FRAME * 2)
                var total = 0L
                while (isActive && running) {
                    val n = rec.read(buf, 0, FRAME)
                    if (n <= 0) { if (n < 0) break else continue }
                    val r = PauseAnalyzer.rms(buf, n)
                    synchronized(rms) { rms += r }
                    _level.value = r
                    for (i in 0 until n) { bytes[i * 2] = (buf[i].toInt() and 0xff).toByte(); bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xff).toByte() }
                    raf.write(bytes, 0, n * 2)
                    total += n * 2
                    if (total > MAX_BYTES) break // ~10 minutes safety cap
                }
                writeWavHeader(raf, total)
            } finally {
                runCatching { rec.stop() }
                rec.release()
                raf.close()
                _recording.value = false
            }
        }
        return true
    }

    /** Stops recording and waits until the file is finalized. */
    suspend fun stop(): Result? {
        val f = output ?: return null
        running = false
        job?.join()
        job = null; record = null
        val list = synchronized(rms) { rms.toList() }
        return withContext(Dispatchers.IO) { if (f.exists() && f.length() > 44) Result(f, list.size * FRAME_MS, list) else null }
    }

    fun cancel() { stopInternal(); output?.delete() }

    private fun stopInternal() {
        running = false
        _recording.value = false
        job?.cancel(); job = null
        record = null
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Long) {
        val byteRate = SAMPLE_RATE * 2
        fun le32(v: Long) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        raf.seek(0)
        raf.write("RIFF".toByteArray()); raf.write(le32(36 + dataLen)); raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(1))
        raf.write(le32(SAMPLE_RATE.toLong())); raf.write(le32(byteRate.toLong())); raf.write(le16(2)); raf.write(le16(16))
        raf.write("data".toByteArray()); raf.write(le32(dataLen))
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20L
        const val FRAME = 320 // 20 ms at 16 kHz
        const val MAX_BYTES = 10L * 60 * SAMPLE_RATE * 2
        /** RMS threshold (0..32768) above which a 20 ms frame counts as speech. */
        const val SPEECH_RMS = 700f
    }
}
