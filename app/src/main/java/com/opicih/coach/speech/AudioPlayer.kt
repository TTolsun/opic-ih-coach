package com.opicih.coach.speech

import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Plays a recorded answer, with slow playback. Only one player exists at a time to avoid leaks. */
class AudioPlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing

    fun play(file: File, speed: Float = 1.0f, onError: (String) -> Unit = {}) {
        stop()
        if (!file.exists()) { onError("녹음 파일이 없습니다. 보관 기간이 지나 삭제되었을 수 있습니다."); return }
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            mp.setOnCompletionListener { stop() }
            mp.start()
            player = mp
            _playing.value = file.absolutePath
        } catch (e: Exception) {
            mp.release()
            onError("재생하지 못했습니다: ${e.message}")
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _playing.value = null
    }
}
