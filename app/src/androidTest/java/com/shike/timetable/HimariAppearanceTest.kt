package com.shike.timetable

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.io.File
import com.shike.timetable.theme.*

/** 示例数据上的视觉核查，以及陪伴开关重建后的持久化。 */
class HimariAppearanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val clean = object : ExternalResource() {
        override fun before() = reset()
        override fun after() = reset()
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(clean).around(compose)

    @Test fun tappingCompanionChangesTheLineAndKeepsItAcrossTabsAndRecreation() {
        fun line() = compose.onNodeWithTag("companion_quote").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text
        val history = mutableListOf(line())
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertIsDisplayed()
        repeat(7) {
            compose.onNodeWithTag(if (it % 2 == 0) "companion_quote" else "companion_portrait_shuffle").performClick()
            val next = line()
            assertNotEquals("点击后不应连续显示同一句", history.last(), next)
            history += next
            compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        }
        val selected = line()
        snapshot("阳茉莉-点击换句")
        compose.onNodeWithText("设置").performClick()
        assertEquals(selected, line())
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("课表").performClick()
        assertEquals(selected, line())
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(selected, line())
        assertEquals(selected, CompanionInteractionStore(context()).current(ThemeRepository.get(context()).find("himari"))!!.line)
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("阳茉莉陪伴").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
        compose.onNodeWithTag("companion_portrait_shuffle").assertDoesNotExist()
        compose.onNodeWithText("点点我 · 换句话").assertDoesNotExist()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithText("今日安排").assertIsDisplayed()
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
    }

    @Test fun aFirstInteractionElsewhereHidesTheHintOnAnAlreadyVisibleScreen() {
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            CompanionInteractionStore(context()).shuffle(ThemeRepository.get(context()).find("himari"))
        }
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("companion_quote").assertIsDisplayed()
    }

    @Test fun wholeThemeSwitchPersistsWithoutChangingTheScheduleOrRestoringTheHint() {
        val schedule = com.shike.timetable.data.ScheduleStore(context()).read()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("companion_quote").performClick()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("整体主题").performScrollTo().performClick()
        compose.onNodeWithText("简约主题").performClick()
        compose.onNodeWithText("当前主题不包含角色插画与语录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertDoesNotExist()
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
        compose.onNodeWithText("今日安排").assertIsDisplayed()
        snapshot("主题-简约")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals("minimal", AppearanceStore(context()).themeId)
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
        assertEquals(schedule, com.shike.timetable.data.ScheduleStore(context()).read())
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("整体主题").performScrollTo().performClick()
        compose.onNodeWithText("阳茉莉主题").performClick()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertIsDisplayed()
        compose.onNodeWithTag("companion_quote").assertIsDisplayed()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        snapshot("主题-阳茉莉-提示已隐藏")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("shuffle_hint", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(CompanionInteractionStore(context()).showShuffleHint())
    }

    @Test fun companionCanBeHiddenAndRestoredAcrossRecreation() {
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertIsDisplayed()
        snapshot("阳茉莉-课表-浅色")
        compose.onNodeWithText("课程").performClick()
        snapshot("阳茉莉-课程")
        compose.onNodeWithContentDescription("添加课程").performClick()
        snapshot("阳茉莉-课程编辑")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("设置").performClick()
        snapshot("阳茉莉-设置")
        compose.onNodeWithText("阳茉莉的小小画册").performScrollTo().performClick()
        snapshot("阳茉莉-画册")
        ThemeRepository.get(context()).find("himari").companion!!.artworks.forEach {
            compose.onNodeWithText(it.label).performScrollTo().performClick().assertIsSelected()
        }
        snapshot("阳茉莉-画册-新增")
        compose.onNodeWithContentDescription("关闭画册").performClick()
        compose.onNodeWithContentDescription("显示周末").performScrollTo().performClick()
        compose.onNodeWithText("课表").performClick()
        snapshot("阳茉莉-课表-七天")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("显示周末").performScrollTo().performClick()
        compose.onNodeWithContentDescription("阳茉莉陪伴").performScrollTo().performClick().assertIsOff()
        assertFalse(context().getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).getBoolean("show_companion", true))
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("阳茉莉陪伴").performScrollTo().assertIsOff()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertDoesNotExist()
        compose.onNodeWithText("今日安排").assertIsDisplayed()
        snapshot("阳茉莉-关闭陪伴")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("阳茉莉陪伴").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertIsDisplayed()
        snapshot("阳茉莉-课表-深色")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("浅色").performScrollTo().performClick()
        compose.onNodeWithText("从教务导入").performScrollTo().performClick()
        compose.onNodeWithText("新学期，一起准备好").assertIsDisplayed()
        snapshot("阳茉莉-教务导入")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("清空课表").performScrollTo().performClick()
        compose.onNodeWithText("确认清空").performClick()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithText("这一周，留白也很美").assertIsDisplayed()
        compose.onNodeWithText("添加第一门课").assertIsDisplayed()
        snapshot("阳茉莉-空课表")
    }

    private fun snapshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val directory = File(context().filesDir, "visual-check").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot())
        // Dialog 位于独立窗口；使用系统合成截图，避免只截到背后的 Activity。
        val bitmap = if (name.startsWith("阳茉莉-画册")) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: throw AssertionError("画册截图失败")
        } else roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun reset() {
        context().getSharedPreferences("shike_schedule", Context.MODE_PRIVATE).edit().clear().commit()
        context().getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        context().getSharedPreferences(CompanionInteractionStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
}
