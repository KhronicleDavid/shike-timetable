package com.shike.timetable

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.ScheduleStore
import com.shike.timetable.theme.*
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/** 只使用示例课表，覆盖新增主题的实际切换、图片解码与共享交互状态。 */
class SagiriAppearanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val clean = object : ExternalResource() {
        override fun before() = reset()
        override fun after() = reset()
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(clean).around(compose)

    @Test fun sagiriThemeLoadsAllArtworkAndPersistsWithTimeSpecificDialogue() {
        val schedule = ScheduleStore(context()).read()
        val theme = ThemeRepository.get(context()).find("sagiri")
        assertEquals("sagiri", theme.id)
        val content = theme.companion!!
        content.artworks.forEach { art ->
            assertNotNull("立绘无法解码 ${art.file}", ThemeImages.load(context(), theme, art))
            assertNotNull("紧凑立绘无法解码 ${art.compactFile}", ThemeImages.load(context(), theme, art, 256, compact = true))
        }
        compose.onNodeWithTag("companion_quote").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("整体主题").performScrollTo().performClick()
        compose.onNodeWithText("纱雾主题").performClick()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithContentDescription("吉祥物：和泉纱雾").assertIsDisplayed()
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertDoesNotExist()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        fun line() = compose.onNodeWithTag("companion_quote").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text
        val recent = mutableListOf(line())
        repeat(8) {
            val before = LocalDateTime.now()
            compose.onNodeWithTag("companion_quote").performClick()
            val after = LocalDateTime.now()
            val next = line()
            if (DayPart.at(before.toLocalTime()) == DayPart.at(after.toLocalTime())) {
                assertTrue(next in content.quotes.getValue(DayPart.at(after.toLocalTime())))
                assertFalse("换句应避开最近五句", next in recent.takeLast(5))
            }
            recent += next
        }
        snapshot("纱雾-课表-浅色")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals("sagiri", AppearanceStore(context()).themeId)
        assertEquals(schedule, ScheduleStore(context()).read())
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("课程").performClick()
        snapshot("纱雾-课程")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("纱雾的小小画册").performScrollTo().performClick()
        content.artworks.forEach { art ->
            compose.onNodeWithText(art.label).performScrollTo().performClick().assertIsSelected()
        }
        snapshot("纱雾-画册", dialog = true)
        compose.onNodeWithContentDescription("关闭画册").performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.onNodeWithText("课表").performClick()
        snapshot("纱雾-课表-深色")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("纱雾陪伴").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("纱雾陪伴").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("整体主题").performScrollTo().performClick()
        compose.onNodeWithText("阳茉莉主题").performClick()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertIsDisplayed()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(schedule.courses, ScheduleStore(context()).read().courses)
    }

    private fun snapshot(name: String, dialog: Boolean = false) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val directory = File(context().filesDir, "visual-check").apply { mkdirs() }
        val bitmap = if (dialog) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            else compose.onRoot().captureToImage().asAndroidBitmap()
        val suffix = if (context().resources.configuration.fontScale > 1.2f) "-大字号" else ""
        File(directory, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun reset() {
        listOf("shike_schedule", "shike_appearance", CompanionInteractionStore.PREFERENCES).forEach {
            context().getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AppearanceStore(context()).voiceEnabled = false
    }
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
}
