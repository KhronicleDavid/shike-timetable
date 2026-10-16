package com.shike.timetable

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.ScheduleStore
import com.shike.timetable.theme.*
import com.shike.timetable.widget.TodayWidgetViews
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.io.File
import java.time.LocalDateTime

/** 新主题只含原画与15幅重绘，不使用占位台词或其他角色的语音。 */
class SylviaAppearanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private val clean = object : ExternalResource() {
        override fun before() = reset("sylvia")
        override fun after() = reset("")
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(clean).around(compose)
    private fun reset(themeId: String) {
        main { CompanionVoicePlayback.cancel(context()) }
        listOf("shike_appearance", CompanionInteractionStore.PREFERENCES).forEach {
            context().getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AppearanceStore(context()).apply { this.themeId = themeId; voiceEnabled = true }
    }

    @Test fun galleryTimeSpecificArtworkAndThemeSelectionWorkWithoutDialogue() {
        val theme = ThemeRepository.get(context()).find("sylvia")
        val content = theme.companion!!
        assertEquals(16, content.artworks.size)
        content.artworks.forEach { art ->
            assertNotNull(art.file, ThemeImages.load(context(), theme, art))
            assertNotNull(art.compactFile, ThemeImages.load(context(), theme, art, 256, compact = true))
        }
        val schedule = ScheduleStore(context()).read()
        val store = CompanionInteractionStore(context())
        compose.onNodeWithContentDescription("吉祥物：希尔维娅").assertIsDisplayed()
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
        compose.onNodeWithText("点点我 · 换句话").assertDoesNotExist()
        compose.onNodeWithText("点点我 · 换幅图").assertIsDisplayed()
        repeat(8) {
            val old = store.current(theme)!!
            compose.onNodeWithTag("companion_portrait_shuffle").performClick()
            val next = store.current(theme)!!
            assertEquals("", next.line)
            assertNotEquals(old.artwork, next.artwork)
            assertTrue(next.artwork in content.pictures(next.part).map { it.id })
            assertFalse(CompanionVoicePlayback.isBusy)
        }
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        snapshot("希尔维娅-课表-浅色")
        compose.activityRule.scenario.recreate()
        assertEquals("sylvia", AppearanceStore(context()).themeId)
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
        compose.onNodeWithText("课程").performClick()
        snapshot("希尔维娅-课程")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("角色语音").assertDoesNotExist()
        compose.onNodeWithText("希尔维娅的小小画册").performScrollTo().performClick()
        content.artworks.forEach { art -> compose.onNodeWithText(art.label).performScrollTo().performClick().assertIsSelected() }
        snapshot("希尔维娅-画册", dialog = true)
        compose.onNodeWithContentDescription("关闭画册").performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.onNodeWithText("课表").performClick()
        snapshot("希尔维娅-课表-深色")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("整体主题").performScrollTo().performClick()
        compose.onNodeWithText("阳茉莉主题").performClick()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithTag("companion_quote").assertIsDisplayed()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(schedule.courses, ScheduleStore(context()).read().courses)
        assertEquals(schedule.settings.copy(darkMode = "dark"), ScheduleStore(context()).read().settings)
    }

    @Test fun widgetShowsQArtworkAndCoursesButNoBlankDialogueAreaAcrossAllPeriods() {
        val context = context()
        val theme = ThemeRepository.get(context).find("sylvia")
        val store = CompanionInteractionStore(context)
        listOf(8, 13, 21).forEach { hour ->
            val time = LocalDateTime.now().withHour(hour).withMinute(5)
            main {
                val moment = store.current(theme, time)!!
                val layout = TodayWidgetViews.build(context, 901, ScheduleStore(context).read(), time)
                    .apply(context, FrameLayout(context))
                assertEquals(View.GONE, layout.findViewById<View>(R.id.widget_companion).visibility)
                assertEquals(View.GONE, layout.findViewById<View>(R.id.widget_quote).visibility)
                assertEquals("", layout.findViewById<TextView>(R.id.widget_quote).text.toString())
                val portrait = layout.findViewById<ImageView>(R.id.widget_portrait)
                assertNotNull(portrait.drawable)
                assertTrue(portrait.contentDescription.contains("切换插画"))
                val expected = ThemeImages.load(context, theme, theme.companion!!.artwork(moment.artwork)!!, 256, compact = true)
                val actual = (portrait.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertTrue(actual.sameAs(expected))
                portrait.performClick()
            }
            compose.waitForIdle()
            assertFalse(CompanionVoicePlayback.isBusy)
        }
    }
    private fun snapshot(name: String, dialog: Boolean = false) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        SystemClock.sleep(250)
        val bitmap = if (dialog) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            else compose.onRoot().captureToImage().asAndroidBitmap()
        val suffix = if (context().resources.configuration.fontScale > 1.2f) "-大字号" else ""
        val target = File(context().filesDir, "visual-check/$name$suffix.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
