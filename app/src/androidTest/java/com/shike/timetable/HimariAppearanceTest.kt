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
import com.shike.timetable.data.HimariInteractionStore

/** 示例数据上的视觉核查，以及陪伴开关重建后的持久化。 */
class HimariAppearanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val clean = object : ExternalResource() {
        override fun before() = reset()
        override fun after() = reset()
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(clean).around(compose)

    @Test fun tappingCompanionChangesTheLineAndKeepsItAcrossTabsAndRecreation() {
        fun line() = compose.onNodeWithTag("himari_quote").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text
        val history = mutableListOf(line())
        repeat(7) {
            compose.onNodeWithTag(if (it % 2 == 0) "himari_quote" else "himari_portrait_shuffle").performClick()
            val next = line()
            assertNotEquals("点击后不应连续显示同一句", history.last(), next)
            history += next
        }
        val selected = line()
        snapshot("阳茉莉-点击换句")
        compose.onNodeWithText("设置").performClick()
        assertEquals(selected, line())
        compose.onNodeWithText("课表").performClick()
        assertEquals(selected, line())
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(selected, line())
        assertEquals(selected, HimariInteractionStore(context()).current().line)
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("阳茉莉陪伴").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("himari_quote").assertDoesNotExist()
        compose.onNodeWithTag("himari_portrait_shuffle").assertDoesNotExist()
        compose.onNodeWithText("点点我 · 换句话").assertDoesNotExist()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithText("今日安排").assertIsDisplayed()
        compose.onNodeWithTag("himari_quote").assertDoesNotExist()
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
        compose.onNodeWithText("学习").performClick().assertIsSelected()
        compose.onNodeWithText("原画").performClick().assertIsSelected()
        listOf("早晨·热饮", "早晨·伸展", "午间·小仓鼠", "午间·图书馆", "夜晚·星星", "夜晚·挥手").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick().assertIsSelected()
        }
        snapshot("阳茉莉-画册-新增")
        compose.onNodeWithContentDescription("关闭画册").performClick()
        compose.onNodeWithContentDescription("显示周末").performScrollTo().performClick()
        compose.onNodeWithText("课表").performClick()
        snapshot("阳茉莉-课表-七天")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("显示周末").performScrollTo().performClick()
        compose.onNodeWithContentDescription("阳茉莉陪伴").performScrollTo().performClick().assertIsOff()
        assertFalse(context().getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).getBoolean("show_himari", true))
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
        context().getSharedPreferences(HimariInteractionStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
}
