package com.shike.timetable

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.ScheduleStore
import com.shike.timetable.theme.*
import com.shike.timetable.widget.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.time.LocalDateTime
import java.io.File

class CompanionVoiceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private val clean = object : ExternalResource() {
        override fun before() = reset()
        override fun after() = reset()
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(clean).around(compose)
    private fun reset() {
        main { CompanionVoicePlayback.cancel(context()) }
        context().getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).edit().clear()
            .putString("theme_id", "himari").putBoolean("voice_enabled", true).commit()
        context().getSharedPreferences(CompanionInteractionStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun all114BundledVoiceClipsPrepareInAndroidMediaPlayer() {
        val theme = ThemeRepository.get(context()).find("himari")
        assertEquals(114, theme.companion!!.voices.size)
        theme.companion.voices.values.forEach { clip ->
            val player = MediaPlayer()
            try {
                ThemeFiles.setDataSource(context(), theme, clip, player)
                player.prepare()
                assertTrue("语音时长不正常 ${clip.file}", player.duration in 1000..20000)
            } finally { player.release() }
        }
    }

    @Test fun rapidAppAndWidgetClicksStayLockedUntilRealCompletionIncludingRecreation() {
        val context = context()
        val theme = ThemeRepository.get(context).find("himari")
        val store = CompanionInteractionStore(context)
        val schedule = ScheduleStore(context).read()
        compose.onNodeWithTag("companion_quote").performClick()
        compose.waitUntil(15000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Playing }
        val session = CompanionVoicePlayback.sessions.value!!
        val started = SystemClock.elapsedRealtime()
        val duration = MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(java.io.File(theme.directory!!, theme.companion!!.voices.getValue(session.moment.line).file).path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            } finally { retriever.release() }
        }
        compose.onNodeWithTag("companion_quote").assertIsNotEnabled()
        compose.onNodeWithTag("companion_portrait_shuffle").assertIsNotEnabled()
        main {
            repeat(12) {
                CompanionVoicePlayback.shuffle(context)
                CompanionWidgetReceiver().onReceive(context, Intent().setAction("com.shike.timetable.widget.SHUFFLE_COMPANION"))
            }
            assertEquals(session.token, CompanionVoicePlayback.sessions.value!!.token)
            // A refresh crossing the morning/noon/night boundary must keep the spoken text pinned.
            assertEquals(session.moment, store.current(theme, LocalDateTime.now().plusHours(8)))
        }
        compose.activityRule.scenario.recreate()
        assertEquals(session.token, CompanionVoicePlayback.sessions.value?.token)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(250)
        assertEquals(session.token, CompanionVoicePlayback.sessions.value?.token)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(20000) { !CompanionVoicePlayback.isBusy }
        assertTrue("不能早于音频完成解锁", SystemClock.elapsedRealtime() - started >= duration - 900)
        compose.onNodeWithTag("companion_quote").assertIsEnabled()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("companion_quote").performClick()
        compose.waitUntil(15000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Playing }
        assertNotEquals(session.token, CompanionVoicePlayback.sessions.value!!.token)
        assertNotEquals(session.moment.line, CompanionVoicePlayback.sessions.value!!.moment.line)
        assertEquals(schedule, ScheduleStore(context).read())
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        SystemClock.sleep(250)
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val target = File(context.filesDir, "visual-check/阳茉莉-语音播放.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun widgetCanStartSpeechAndDisabledVoiceAndSagiriStaySilent() {
        val context = context()
        val appearance = AppearanceStore(context)
        main {
            val views = TodayWidgetViews.build(context, 799, ScheduleStore(context).read())
                .apply(context, FrameLayout(context))
            views.findViewById<android.view.View>(R.id.widget_quote).performClick()
        }
        compose.waitUntil(15000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Playing }
        main { CompanionVoicePlayback.cancel(context) }
        compose.waitUntil(5000) { !CompanionVoicePlayback.isBusy }
        appearance.voiceEnabled = false
        val store = CompanionInteractionStore(context)
        val theme = ThemeRepository.get(context).find("himari")
        val old = store.current(theme)
        main { CompanionVoicePlayback.shuffle(context); CompanionVoicePlayback.shuffle(context) }
        assertFalse(CompanionVoicePlayback.isBusy)
        assertNotEquals(old?.line, store.current(theme)?.line)
        appearance.themeId = "sagiri"
        appearance.voiceEnabled = true
        main { CompanionVoicePlayback.shuffle(context) }
        assertFalse(CompanionVoicePlayback.isBusy)
        assertEquals("sagiri", appearance.themeId)
    }

    @Test fun transientAudioFocusPauseKeepsSwitchLockedAndResumesTheSameSentence() {
        val context = context()
        main { CompanionVoicePlayback.shuffle(context) }
        compose.waitUntil(15000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Playing }
        val session = CompanionVoicePlayback.sessions.value!!
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            main { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(focus)) }
            compose.waitUntil(5000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Paused }
            SystemClock.sleep(400)
            main { repeat(10) { CompanionVoicePlayback.shuffle(context) } }
            assertEquals(session.moment, CompanionVoicePlayback.sessions.value!!.moment)
            compose.onNodeWithTag("companion_quote").assertIsNotEnabled()
            main { audio.abandonAudioFocusRequest(focus) }
            compose.waitUntil(5000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Playing }
            assertEquals(session.token, CompanionVoicePlayback.sessions.value!!.token)
            compose.waitUntil(20000) { !CompanionVoicePlayback.isBusy }
            compose.onNodeWithTag("companion_quote").assertIsEnabled()
        } finally { main { audio.abandonAudioFocusRequest(focus) } }
    }
}
