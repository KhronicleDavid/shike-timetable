package com.shike.timetable

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.Course
import com.shike.timetable.data.PeriodTime
import com.shike.timetable.data.ScheduleData
import com.shike.timetable.data.ScheduleJson
import com.shike.timetable.data.ScheduleRules
import com.shike.timetable.data.ScheduleStore
import com.shike.timetable.data.TermSettings
import com.shike.timetable.ui.SchoolImportViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 从真实设置入口和确认按钮验证导入的保存边界。
 * 完全虚构的 HTML 通过文件/网页共用解析入口输入；测试不访问学校、不需要账号。
 */
@RunWith(AndroidJUnit4::class)
class SchoolImportSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val original = ScheduleData(
        courses = listOf(
            Course(
                id = "00000000-0000-4000-8000-000000000001",
                name = "导入前的虚构课程",
                room = "原测试教室",
                day = 5,
                startPeriod = 1,
                endPeriod = 2,
                startWeek = 1,
                endWeek = 20,
            ),
        ),
        settings = TermSettings(
            name = "导入前的虚构学期",
            startDate = "2026-09-07",
            totalWeeks = 20,
            darkMode = "dark",
            periods = TermSettings().periods.mapIndexed { index, slot ->
                if (index == 0) PeriodTime("08:10", "08:50") else slot
            },
        ),
    )
    private val cleanStorage = object : ExternalResource() {
        override fun before() {
            assertTrue(
                "应在 Activity 启动前同步写入虚构原课表",
                preferences().edit().clear()
                    .putString("schedule_json", ScheduleJson.exportJson(original)).commit(),
            )
        }

        override fun after() {
            assertTrue("测试结束后应清空本机课表", preferences().edit().clear().commit())
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(cleanStorage).around(compose)

    @Test
    fun invalidPage_reportsErrorAndKeepsExistingSchedule() {
        openImportFromSettings()
        val model = submitHtml("<html><body>虚构的空页面，没有任何课表数据。</body></html>")

        assertEquals("welcome", model.stage)
        assertNull(model.preview)
        compose.onNodeWithText("未找到课表数据", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertEquals(original, storedSchedule())

        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("让课表更适合你").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(original, storedSchedule())
    }

    @Test
    fun previewAndEdits_surviveRecreation_closeCancelsWithoutSaving() {
        openImportFromSettings()
        val model = submitHtml(FICTIONAL_HTML)
        assertPreview(model)
        assertEquals(original, storedSchedule())

        replaceField("学期名称", "确认前编辑的虚构学期")
        replaceField("学期总周数（至少 14 周）", "18")
        closeSoftKeyboard()
        // 尚未确认时重建 Activity，预览和用户修改应继续存在，原课表仍保留。
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val recreatedModel = importModel()
        assertSame(model, recreatedModel)
        assertEquals("preview", recreatedModel.stage)
        assertEquals("确认前编辑的虚构学期", recreatedModel.termName)
        assertEquals("18", recreatedModel.weeksText)
        compose.onNodeWithText("课表已读取").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("学期名称") and hasText("确认前编辑的虚构学期"))
            .performScrollTo().assertIsDisplayed()
        assertEquals(original, storedSchedule())

        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("让课表更适合你").performScrollTo().assertIsDisplayed()
        assertNull(recreatedModel.preview)
        assertEquals(original, storedSchedule())
    }

    @Test
    fun confirmReplacement_persistsExactArrangementsAndPreservesThemeAndPeriods() {
        openImportFromSettings()
        saveScreenshot("school-import-entry.png")
        val model = submitHtml(FICTIONAL_HTML)
        assertPreview(model)
        saveScreenshot("school-import-preview.png")
        assertEquals(original, storedSchedule())

        compose.onNodeWithText("确认替换并导入").performScrollTo().performClick()
        compose.onNodeWithText("拾课").assertIsDisplayed()
        val imported = storedSchedule()
        assertEquals(setOf("虚构课程甲", "虚构课程乙"), imported.courses.map { it.name }.toSet())
        assertEquals(4, imported.courses.size)
        assertFalse(imported.courses.any { it.id == "00000000-0000-4000-8000-000000000001" })
        assertEquals("虚构测试学期", imported.settings.name)
        assertEquals("2026-09-07", imported.settings.startDate)
        assertEquals(original.settings.totalWeeks, imported.settings.totalWeeks)
        assertEquals("dark", imported.settings.darkMode)
        assertEquals(original.settings.periods, imported.settings.periods)

        // 第 5 周故意缺课；第 6 周在 A 室，第 7 周才换到 B 室，不能填平离散周。
        assertTrue(ScheduleRules.coursesForWeek(imported.courses, 5).none { it.name == "虚构课程甲" })
        assertEquals(
            listOf("测试楼 A 室"),
            ScheduleRules.coursesForWeek(imported.courses, 6).filter { it.name == "虚构课程甲" }.map { it.room },
        )
        assertEquals(
            listOf("测试楼 B 室"),
            ScheduleRules.coursesForWeek(imported.courses, 7).filter { it.name == "虚构课程甲" }.map { it.room },
        )
        assertTrue(ScheduleRules.coursesForWeek(imported.courses, 2).none { it.name == "虚构课程乙" })
        assertTrue(imported.courses.filter { it.name == "虚构课程甲" }.all {
            it.day == 1 && it.startPeriod == 1 && it.endPeriod == 2
        })
        assertTrue(imported.courses.filter { it.name == "虚构课程乙" }.all {
            it.day == 3 && it.startPeriod == 5 && it.endPeriod == 6 && it.room.isEmpty()
        })

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("拾课").assertIsDisplayed()
        assertEquals(imported, storedSchedule())
    }

    @Test
    fun totalWeeksBelowLatestCourse_rejectsConfirmationAndKeepsPreviewAndStorage() {
        openImportFromSettings()
        val model = submitHtml(FICTIONAL_HTML)
        assertPreview(model)
        replaceField("学期总周数（至少 14 周）", "13")
        closeSoftKeyboard()

        compose.onNodeWithText("确认替换并导入").performScrollTo().performClick()
        compose.onNodeWithText("课程延伸至第 14 周，学期总周数不能小于此值。")
            .performScrollTo().assertIsDisplayed()
        assertEquals("preview", model.stage)
        assertEquals(14, model.preview?.maxWeek)
        assertEquals(original, storedSchedule())

        compose.onNodeWithText("关闭").performClick()
        assertEquals(original, storedSchedule())
    }

    private fun openImportFromSettings() {
        compose.onNode(hasClickAction() and hasText("设置")).performClick()
        compose.onNode(hasClickAction() and hasText("从教务导入")).performScrollTo().performClick()
        compose.onNodeWithText("从教务系统导入").assertIsDisplayed()
        assertEquals(original, storedSchedule())
    }

    private fun submitHtml(html: String): SchoolImportViewModel {
        val model = importModel()
        val settings = storedSchedule().settings
        compose.runOnUiThread { model.fileSelected(settings) { html } }
        compose.waitUntil(timeoutMillis = 10_000) { !model.busy }
        compose.waitForIdle()
        return model
    }

    private fun assertPreview(model: SchoolImportViewModel) {
        assertNull("虚构课表应该完整解析", model.error)
        assertEquals("preview", model.stage)
        assertEquals(14, model.preview?.maxWeek)
        compose.onNodeWithText("课表已读取").assertIsDisplayed()
        compose.onNodeWithText("2 门课程 · 4 条排课 · 最晚第 14 周").assertIsDisplayed()
    }

    private fun replaceField(label: String, text: String) {
        compose.onNode(hasSetTextAction() and hasText(label))
            .performScrollTo().performTextReplacement(text)
    }

    private fun importModel(): SchoolImportViewModel {
        var model: SchoolImportViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)["school-import", SchoolImportViewModel::class.java]
        }
        return checkNotNull(model)
    }

    private fun saveScreenshot(name: String) {
        closeSoftKeyboard()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val directory = targetContext().filesDir
            File(directory, name).outputStream().use { output ->
                assertTrue("截图应成功保存", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun storedSchedule() = ScheduleStore(targetContext()).read()
    private fun preferences() = targetContext().getSharedPreferences("shike_schedule", Context.MODE_PRIVATE)
    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        val FICTIONAL_HTML = """
            <html><head><script>
              var table0 = new CourseTable(2026, 84);
              var unitCount = 12;
              table0.marshalTable(2, 1, 20);
              var firstMonday = "2026-09-07";
            </script></head><body>
              <select name="semester.id"><option selected value="fictional">虚构测试学期</option></select>
              <table><tr>
                <td id="TD0_0" rowspan="2" title="虚构教师甲 虚构课程甲(F001);(连1-4 6,测试楼 A 室);(连7-14,测试楼 B 室)"></td>
                <td id="TD28_0" rowspan="2" title="虚构教师乙 虚构课程乙(F002);(单1-13)"></td>
              </tr></table>
            </body></html>
        """.trimIndent()
    }
}
