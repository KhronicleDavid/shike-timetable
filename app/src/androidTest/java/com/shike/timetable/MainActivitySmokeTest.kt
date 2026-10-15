package com.shike.timetable

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.ScheduleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** 从真实 Activity 和本机存储验证用户主流程，不依赖测试专用的页面入口。 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val cleanStorage = object : ExternalResource() {
        override fun before() = resetPreferences()
        override fun after() = resetPreferences()
    }

    // 外层规则先清理再启动 Activity；每项测试均从首次启动示例课表开始。
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(cleanStorage).around(compose)

    @Test fun widgetCourseLinkOpensDetailsAndDoesNotReopenAfterReturning() {
        val course = storedSchedule().courses.first()
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(android.content.Intent(activity, MainActivity::class.java)
                .setAction(com.shike.timetable.widget.WIDGET_OPEN_ACTION)
                .putExtra(com.shike.timetable.widget.WIDGET_COURSE_ID, course.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.onNodeWithText("编辑课程").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(course.name)).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("拾课").assertIsDisplayed()
        compose.onNodeWithText("编辑课程").assertDoesNotExist()
    }

    @Test
    fun addCourse_recreateActivity_thenDelete_persistsBothChanges() {
        val name = "界面测试 · 数据结构"
        val room = "测试楼 101"

        compose.onNodeWithText("拾课").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加课程").performClick()
        enterField("课程名称 *", name)
        enterField("教室", room)
        closeSoftKeyboard()
        // 示例课表周五第 1–2 节没有课程，覆盖无冲突的直接保存路径。
        compose.onNodeWithContentDescription("星期五").performScrollTo().performClick()
        compose.onNodeWithText("保存课程").performClick()

        searchCourses(name)
        courseCard(name, room).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        courseCard(name, room).assertIsDisplayed()

        courseCard(name, room).performClick()
        compose.onNodeWithText("编辑课程").assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除这门课程？").assertIsDisplayed()
        // 确认前课程仍保存在本机，防止点击删除按钮就立即删除。
        assertTrue(storedSchedule().courses.any { it.name == name })
        compose.onNodeWithText("删除课程").performClick()
        searchCourses(name)
        courseCard(name, room).assertDoesNotExist()
        compose.onNodeWithText("没有找到相关课程").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        courseCard(name, room).assertDoesNotExist()
        assertFalse(storedSchedule().courses.any { it.name == name })
    }

    @Test
    fun blankNameAndTimeConflict_requireValidationAndExplicitConfirmation() {
        val name = "界面测试 · 冲突课程"
        val room = "测试冲突教室"

        compose.onNodeWithContentDescription("添加课程").performClick()
        compose.onNodeWithText("保存课程").performClick()
        compose.onNodeWithText("请填写课程名称").assertIsDisplayed()
        enterField("课程名称 *", name)
        enterField("教室", room)
        closeSoftKeyboard()
        // 默认周一第 1–2 节与示例高等数学重叠，覆盖真正的冲突路径。
        compose.onNodeWithText("保存课程").performClick()
        compose.onNodeWithText("上课时间有重叠").assertIsDisplayed()
        compose.onNodeWithText("返回修改").performClick()
        compose.onNodeWithText("添加课程").assertIsDisplayed()
        assertFalse(storedSchedule().courses.any { it.name == name })

        compose.onNodeWithText("保存课程").performClick()
        compose.onNodeWithText("仍然保存").performClick()
        searchCourses(name)
        courseCard(name, room).assertIsDisplayed()
        assertTrue(storedSchedule().courses.any { it.name == name })
    }

    @Test
    fun weekPickerAndDarkTheme_workAcrossNavigationAndRecreation() {
        compose.onNode(
            hasClickAction() and hasText("第 ", substring = true) and hasText(" 周", substring = true),
        ).performClick()
        compose.onNodeWithText("选择教学周").assertIsDisplayed()
        // 星期日期没有点击行为，限定可点击节点避免与日历数字混淆。
        compose.onNode(hasText("1") and hasClickAction()).performClick()
        compose.onNodeWithText("第 1 周").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("第 1 周").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一周").performClick()
        compose.onNodeWithText("第 2 周").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一周").performClick()
        compose.onNodeWithText("第 1 周").assertIsDisplayed()

        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("让课表更适合你").assertIsDisplayed()
        compose.onNodeWithText("深色").performScrollTo().performClick().assertIsSelected()
        assertEquals("dark", storedSchedule().settings.darkMode)

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("深色").performScrollTo().assertIsSelected()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithText("拾课").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加课程").assertIsDisplayed()
    }

    private fun enterField(label: String, text: String) {
        compose.onNode(hasSetTextAction() and hasText(label))
            .performScrollTo()
            .performTextInput(text)
    }

    private fun searchCourses(name: String) {
        compose.onNodeWithText("课程").performClick()
        compose.onNodeWithText("我的课程").assertIsDisplayed()
        // 课程搜索是该页唯一输入框；过滤后课程卡片不受长列表滚动影响。
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        closeSoftKeyboard()
    }

    private fun courseCard(name: String, room: String) = compose.onNode(
        hasClickAction() and hasText(name) and hasText(room),
    )

    private fun storedSchedule() = ScheduleStore(targetContext()).read()

    private fun resetPreferences() {
        assertTrue(
            "测试应用存储应在 Activity 启动前同步清空",
            targetContext().getSharedPreferences("shike_schedule", Context.MODE_PRIVATE)
                .edit().clear().commit(),
        )
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext
}
