package com.opicih.coach.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 재생 중에만 무음 오디오를 이 앱의 프로세스에서 계속 재생한다.
 *
 * TTS 소리는 TTS 엔진 앱이 내므로, 안드로이드는 이 앱을 "소리를 내는 앱"으로 보지 않는다. 그러면 이어폰 버튼과
 * 잠금화면 미디어 키가 마지막으로 소리를 낸 다른 앱(예: 음악 앱)의 세션으로 가 버린다.
 * 같은 UID가 오디오를 재생하고 있으면 시스템이 이 앱의 MediaSession을 미디어 버튼 대상으로 고른다.
 */
class SilentKeepAlive {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return
        runCatching {
            val rate = 8000
            val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            running = true
            t.play()
            // 0으로 채운 버퍼를 재생 속도에 맞춰 계속 쓴다. write가 버퍼가 빌 때까지 막아 주므로 CPU는 거의 쓰지 않는다.
            thread = Thread {
                val silence = ByteArray(size)
                while (running) { if (t.write(silence, 0, silence.size) < 0) break }
            }.apply { isDaemon = true; name = "ListenSilentKeepAlive"; start() }
        }.onFailure { running = false; track?.runCatching { release() }; track = null }
    }

    @Synchronized
    fun stop() {
        if (!running && track == null) return
        running = false
        thread?.runCatching { join(400) }
        thread = null
        track?.runCatching { stop(); release() }
        track = null
    }
}
