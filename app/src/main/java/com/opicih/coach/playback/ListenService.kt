package com.opicih.coach.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.opicih.coach.App
import com.opicih.coach.MainActivity
import com.opicih.coach.R
import com.opicih.coach.alarm.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 스크립트 듣기 재생을 화면 밖에서 살려 두는 Foreground Service(mediaPlayback).
 *
 * 재생 엔진([ListenPlayer])은 앱 컨테이너가 갖고, 이 서비스는 다음 네 가지를 맡는다.
 * 1. 프로세스를 포그라운드로 유지하고 재생 중에만 WakeLock을 잡는다.
 * 2. MediaSession으로 잠금화면·알림·이어폰 버튼을 엔진에 연결한다.
 * 3. 오디오 포커스를 요청하고, 전화나 다른 앱이 가져가면 일시정지한다(끝나도 자동 재개하지 않는다).
 * 4. 이어폰이 빠지면(BECOMING_NOISY) 일시정지한다.
 *
 * 모든 재생 명령은 이 서비스를 거친다. 화면은 상태만 읽고 [command]로 명령을 보낸다.
 */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val keepAlive = SilentKeepAlive()
    private var foreground = false
    private var wasActive = false

    private val player get() = (application as App).container.listen

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) player.pause()
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { player.pause() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "ListenPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { handle(ACTION_PLAY) }
                override fun onPause() { handle(ACTION_PAUSE) }
                override fun onSkipToNext() { handle(ACTION_NEXT) }
                override fun onStop() { handle(ACTION_STOP) }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { player.state.collect { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService()로 시작했으면 몇 초 안에 startForeground()를 불러야 하므로 가장 먼저 한다.
        enterForeground(player.state.value)
        handle(intent?.action)
        // 명령을 처리한 뒤에도 재생 상태가 아니고 멈춰 있지도 않으면(정지 명령, 포커스 거부 등) 서비스를 끝낸다.
        if (player.state.value.status == ListenStatus.IDLE) shutDown()
        return START_NOT_STICKY
    }

    private fun shutDown() {
        releaseWake()
        keepAlive.stop()
        abandonFocus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun handle(action: String?) {
        when (action) {
            ACTION_PLAY -> if (requestFocus()) player.play() else player.reportNotice("다른 앱이 소리를 사용 중이라 재생하지 못했습니다. 통화나 다른 재생이 끝난 뒤 다시 눌러 주세요.")
            ACTION_PAUSE -> player.pause()
            ACTION_NEXT -> if (requestFocus()) player.next()
            ACTION_STOP -> player.stop()
        }
    }

    private fun render(s: ListenState) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP,
                )
                .setState(
                    when (s.status) {
                        ListenStatus.PLAYING -> PlaybackState.STATE_PLAYING
                        ListenStatus.PAUSED -> PlaybackState.STATE_PAUSED
                        ListenStatus.IDLE -> PlaybackState.STATE_STOPPED
                    },
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.scriptTitle.ifBlank { "스크립트 듣기" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.stepLabel.ifBlank { "OPIc 스크립트" })
                .build(),
        )
        when (s.status) {
            ListenStatus.PLAYING -> {
                acquireWake()
                // 이 프로세스가 오디오를 재생 중이어야 이어폰 버튼이 이 앱의 세션으로 온다(SilentKeepAlive 설명 참고).
                keepAlive.start()
                enterForeground(s)
            }
            ListenStatus.PAUSED -> {
                releaseWake()
                keepAlive.stop()
                abandonFocus()
                // 알림은 남겨 다시 재생할 수 있게 하되, 사용자가 밀어서 지울 수 있도록 포그라운드에서는 내려놓는다.
                if (foreground) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH); foreground = false }
                getSystemService(NotificationManager::class.java).notify(Notifications.ID_LISTEN, notification(s))
            }
            // 서비스가 막 만들어져 첫 상태(IDLE)를 받은 경우에는 아직 시작 명령이 처리되기 전이므로 끝내지 않는다.
            ListenStatus.IDLE -> if (wasActive) shutDown()
        }
        if (s.status != ListenStatus.IDLE) wasActive = true
    }

    private fun enterForeground(s: ListenState) {
        val n = notification(s)
        runCatching {
            ServiceCompat.startForeground(this, Notifications.ID_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        }
    }

    private fun notification(s: ListenState): Notification {
        val playing = s.status == ListenStatus.PLAYING
        val open = PendingIntent.getActivity(
            this, 330, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "listen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(code: Int, icon: Int, label: String, act: String) = Notification.Action.Builder(
            Icon.createWithResource("android", icon), label,
            PendingIntent.getForegroundService(this, code, Intent(this, ListenService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        val toggle = if (playing) action(331, android.R.drawable.ic_media_pause, "일시정지", ACTION_PAUSE) else action(332, android.R.drawable.ic_media_play, "재생", ACTION_PLAY)
        return Notification.Builder(this, Notifications.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(s.scriptTitle.ifBlank { "스크립트 듣기" })
            .setContentText(s.notice ?: s.stepLabel.ifBlank { "재생 버튼을 눌러 시작하세요" })
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggle)
            .addAction(action(333, android.R.drawable.ic_media_next, "다음", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focusRequest = it }
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() { focusRequest?.let { audio.abandonAudioFocusRequest(it) } }

    /** 화면이 꺼진 사이 단계 간 대기 중에 CPU가 잠들지 않게 한다. 시간 제한은 렌더 때마다 다시 잡는다. */
    private fun acquireWake() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opic:listen").also { wakeLock = it }
        if (!wl.isHeld) wl.acquire(WAKE_TIMEOUT_MS)
    }

    private fun releaseWake() { wakeLock?.takeIf { it.isHeld }?.release() }

    override fun onDestroy() {
        player.pause()
        releaseWake()
        keepAlive.stop()
        abandonFocus()
        runCatching { unregisterReceiver(noisy) }
        session.isActive = false
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.opicih.coach.listen.PLAY"
        const val ACTION_PAUSE = "com.opicih.coach.listen.PAUSE"
        const val ACTION_NEXT = "com.opicih.coach.listen.NEXT"
        const val ACTION_STOP = "com.opicih.coach.listen.STOP"
        private const val WAKE_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /** 화면에서 재생 명령을 보낸다. 항상 서비스를 거쳐 포커스·알림·세션이 함께 갱신된다. */
        fun command(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java).setAction(action))
        }
    }
}
