package com.shike.timetable.theme

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.shike.timetable.widget.TodayWidgetUpdates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime

enum class VoicePhase(val label: String) {
    Preparing("语音准备中…"), Playing("正在说话 · 播完再换句"), Paused("语音已暂停 · 等待恢复")
}
data class VoiceSession(val token: Long, val themeId: String, val moment: CompanionMoment, val phase: VoicePhase)

/** 应用和所有桌面小组件共用一个会话，不排队，不按估算时长提前解锁。 */
object CompanionVoicePlayback {
    private val state = MutableStateFlow<VoiceSession?>(null)
    val sessions = state.asStateFlow()
    val isBusy get() = state.value != null
    private val main = Handler(Looper.getMainLooper())
    internal var stopCurrent: (() -> Unit)? = null

    fun shuffle(context: Context) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { shuffle(app) }; return }
        if (isBusy) return
        val appearance = AppearanceStore(app)
        if (!appearance.showCompanion) return
        val theme = ThemeRepository.get(app).find(appearance.themeId)
        val next = CompanionInteractionStore(app).shuffle(theme, LocalDateTime.now()) ?: return
        if (!appearance.voiceEnabled || theme.companion?.voices?.get(next.line) == null) {
            TodayWidgetUpdates.request(app)
            return
        }
        val session = VoiceSession(System.nanoTime(), theme.id, next, VoicePhase.Preparing)
        state.value = session
        TodayWidgetUpdates.request(app)
        try {
            ContextCompat.startForegroundService(app, Intent(app, CompanionVoiceService::class.java)
                .putExtra("token", session.token))
        } catch (_: RuntimeException) {
            finish(app, session.token)
            Toast.makeText(app, "暂时无法播放语音，请稍后再试", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun phase(context: Context, token: Long, phase: VoicePhase) {
        state.value?.takeIf { it.token == token }?.let { state.value = it.copy(phase = phase) }
        TodayWidgetUpdates.request(context)
    }
    internal fun finish(context: Context, token: Long) {
        if (state.value?.token == token) state.value = null
        TodayWidgetUpdates.request(context)
    }

    /** 关语音、关角色和切主题是显式取消；普通换句和界面刷新不能中断播放。 */
    fun cancel(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { cancel(context.applicationContext) }; return }
        stopCurrent?.invoke() ?: context.stopService(Intent(context, CompanionVoiceService::class.java))
        state.value?.let { finish(context, it.token) }
    }
}
