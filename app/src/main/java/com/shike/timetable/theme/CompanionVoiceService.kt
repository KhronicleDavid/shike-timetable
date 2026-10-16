package com.shike.timetable.theme

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.widget.Toast
import com.shike.timetable.MainActivity
import com.shike.timetable.R

/** 短语音也由前台服务持有；回到桌面、旋转屏幕时不截断当前句。 */
class CompanionVoiceService : Service() {
    private var player: MediaPlayer? = null
    private var token: Long? = null
    private var focus: AudioFocusRequest? = null
    private var prepared = false
    private var hasFocus = false
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val timeout = Runnable { end("语音加载失败，请重试") }
    private val cancelPlayback: () -> Unit = { end(null) }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { end(null) }
    }

    override fun onCreate() {
        super.onCreate()
        CompanionVoicePlayback.stopCurrent = cancelPlayback
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("companion_voice", "角色语音", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = CompanionVoicePlayback.sessions.value
        if (session == null || intent?.getLongExtra("token", -1) != session.token) {
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (token != null) return START_NOT_STICKY
        token = session.token
        val theme = ThemeRepository.get(this).find(session.themeId)
        val clip = theme.companion?.voices?.get(session.moment.line)
        val notification = Notification.Builder(this, "companion_voice")
            .setSmallIcon(R.drawable.ic_voice).setContentTitle("${theme.companion?.shortName}正在说话")
            .setContentText(session.moment.line).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 80, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(81, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(81, notification)
            requireNotNull(clip)
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            ThemeFiles.setDataSource(this, theme, clip, media)
            media.setOnCompletionListener { end(null) }
            media.setOnErrorListener { _, _, _ -> end("语音播放失败，请重试"); true }
            media.setOnPreparedListener {
                handler.removeCallbacks(timeout)
                prepared = true
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener({ change -> onFocus(change) }, handler).build()
                focus = request
                hasFocus = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (hasFocus) resume() else end("其他应用正在使用声音，请稍后再试")
            }
            handler.postDelayed(timeout, 10_000)
            media.prepareAsync()
        } catch (_: Exception) { end("语音加载失败，请重试") }
        return START_NOT_STICKY
    }

    private fun onFocus(change: Int) {
        if (token == null) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; resume() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                runCatching { if (player?.isPlaying == true) player?.pause() }
                token?.let { CompanionVoicePlayback.phase(this, it, VoicePhase.Paused) }
            }
            AudioManager.AUDIOFOCUS_LOSS -> end(null)
        }
    }
    private fun resume() {
        if (!prepared || !hasFocus || player == null) return
        try {
            player!!.start()
            token?.let { CompanionVoicePlayback.phase(this, it, VoicePhase.Playing) }
        } catch (_: IllegalStateException) { end("语音播放失败，请重试") }
    }
    private fun release() {
        handler.removeCallbacks(timeout)
        prepared = false
        hasFocus = false
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        player?.setOnPreparedListener(null)
        player?.release(); player = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        token?.let { CompanionVoicePlayback.finish(this, it) }; token = null
    }
    private fun end(message: String?) {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    override fun onDestroy() {
        release()
        if (CompanionVoicePlayback.stopCurrent === cancelPlayback) CompanionVoicePlayback.stopCurrent = null
        unregisterReceiver(noisy)
        super.onDestroy()
    }
}
