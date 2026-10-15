package com.shike.timetable.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.MainActivity
import com.shike.timetable.R
import com.shike.timetable.data.*
import com.shike.timetable.theme.*
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 在独立模拟器中绑定真正的 AppWidgetHost，验证系统远程列表而非静态预览。 */
class TodayWidgetTest {
    @Test fun systemWidgetScrollsResizesRefreshesAndHandlesAnEmptyDay() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        shell("appwidget grantbind --package com.shike.timetable --user 0")
        val date = LocalDate.now()
        val settings = TermSettings(startDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(), darkMode = "light")
        val courses = (1..10).map { number -> Course(name = "桌面测试课程 $number", room = "测试教学楼 ${100 + number}",
            day = date.dayOfWeek.value, startPeriod = number, endPeriod = number, startWeek = 1, endWeek = 20) }
        val data = ScheduleData(courses, settings)
        ScheduleStore(context).save(data)
        context.getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).edit().putBoolean("show_himari", true).commit()
        context.getSharedPreferences("shike_companion_interaction", Context.MODE_PRIVATE).edit().clear().commit()
        val manager = AppWidgetManager.getInstance(context)
        lateinit var host: AppWidgetHost
        var hostCreated = false
        lateinit var widget: AppWidgetHostView
        var id = AppWidgetManager.INVALID_APPWIDGET_ID
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(description: String, condition: () -> Boolean) {
            val limit = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < limit) {
                var ready = false
                main { ready = condition() }
                if (ready) return
                SystemClock.sleep(100)
            }
            throw AssertionError(description)
        }
        fun snapshot(name: String) {
            SystemClock.sleep(250)
            val directory = File(context.filesDir, "visual-check").apply { mkdirs() }
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("截图失败")
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    host = AppWidgetHost(activity, 13008)
                    hostCreated = true
                    host.deleteHost()
                    id = host.allocateAppWidgetId()
                    assertTrue("测试宿主应能绑定小组件", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, TodayWidgetProvider::class.java)))
                    val info = manager.getAppWidgetInfo(id)
                    assertTrue(info.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL != 0)
                    widget = host.createView(activity, id, info)
                    val background = FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.parseColor("#E6ECE3")) }
                    val margin = (16 * activity.resources.displayMetrics.density).toInt()
                    background.addView(widget, FrameLayout.LayoutParams(-1, (240 * activity.resources.displayMetrics.density).toInt(), Gravity.CENTER).apply {
                        leftMargin = margin; rightMargin = margin
                    })
                    activity.addContentView(background, ViewGroup.LayoutParams(-1, -1))
                    host.startListening()
                    widget.updateAppWidget(TodayWidgetViews.build(context, id, data, date.atTime(8, 0)))
                }
                waitFor("真实小组件列表未加载十门课") { widget.findViewById<ListView>(R.id.widget_courses)?.count == 10 }
                main { widget.updateAppWidget(TodayWidgetViews.build(context, id, data, date.atTime(8, 0))) }
                snapshot("小组件-紧凑")
                main {
                    val list = widget.findViewById<ListView>(R.id.widget_courses)
                    assertTrue("240dp 紧凑尺寸应至少完整显示一门课程", list.height >= list.getChildAt(0).height)
                }
                // 紧凑尺寸可能只容纳一行多一点；短可视区的一次滑动未必越过整行。
                repeat(3) {
                    var movedPastFirstRow = false
                    main { movedPastFirstRow = widget.findViewById<ListView>(R.id.widget_courses).firstVisiblePosition > 0 }
                    if (!movedPastFirstRow) {
                        onView(withId(R.id.widget_courses)).perform(swipeUp())
                        SystemClock.sleep(250)
                    }
                }
                waitFor("列表应通过手势滚动到后面的课程") { widget.findViewById<ListView>(R.id.widget_courses).firstVisiblePosition > 0 }
                main { widget.findViewById<ListView>(R.id.widget_courses).setSelection(9) }
                waitFor("最后一门课程和教室应保留") {
                    val list = widget.findViewById<ListView>(R.id.widget_courses)
                    (0 until list.childCount).any { list.getChildAt(it)?.findViewById<TextView>(R.id.widget_course_name)?.text == "桌面测试课程 10" }
                }
                main {
                    val list = widget.findViewById<ListView>(R.id.widget_courses)
                    val lastRow = (0 until list.childCount).map { list.getChildAt(it) }.first { it.findViewById<TextView>(R.id.widget_course_name)?.text == "桌面测试课程 10" }
                    assertEquals("测试教学楼 110", lastRow.findViewById<TextView>(R.id.widget_course_room).text.toString())
                    widget.layoutParams = (widget.layoutParams as FrameLayout.LayoutParams).apply { height = (440 * context.resources.displayMetrics.density).toInt() }
                    @Suppress("DEPRECATION")
                    widget.updateAppWidgetSize(Bundle(), 200, 440, 360, 440)
                    widget.findViewById<ListView>(R.id.widget_courses).setSelection(0)
                }
                waitFor("增大后应能同时显示更多课程") { widget.findViewById<ListView>(R.id.widget_courses).childCount >= 3 }
                snapshot("小组件-展开")
                val initialMoment = LocalDateTime.now()
                val initialLine = CompanionInteractionStore(context).current(ThemeRepository.get(context).find("himari"), initialMoment)!!.line
                main { widget.updateAppWidget(TodayWidgetViews.build(context, id, data, initialMoment)) }
                waitFor("语录应与应用共享当前选择") { widget.findViewById<TextView>(R.id.widget_quote).text.toString() == "阳茉莉：$initialLine" }
                main { assertEquals(android.view.View.VISIBLE, widget.findViewById<android.view.View>(R.id.widget_shuffle_hint).visibility) }
                val firstTapMoment = LocalDateTime.now()
                onView(withId(R.id.widget_quote)).perform(click())
                waitFor("点击语录应随机换一句") {
                    widget.findViewById<TextView>(R.id.widget_quote).text.toString() != "阳茉莉：$initialLine"
                }
                waitFor("首次换句后应永久隐藏提示") { widget.findViewById<android.view.View>(R.id.widget_shuffle_hint).visibility == android.view.View.GONE }
                assertFalse(CompanionInteractionStore(context).showShuffleHint())
                val firstObservedMoment = LocalDateTime.now()
                val quoteAfterTap = CompanionInteractionStore(context).current(ThemeRepository.get(context).find("himari"), firstObservedMoment)!!.line
                main {
                    if (CompanionEngine.bucketAt(firstTapMoment) == CompanionEngine.bucketAt(firstObservedMoment)) {
                        assertEquals("阳茉莉：$quoteAfterTap", widget.findViewById<TextView>(R.id.widget_quote).text.toString())
                    }
                    assertEquals("点击语录不应改变课程数量", 10, widget.findViewById<ListView>(R.id.widget_courses).count)
                }
                assertEquals("随机互动不应写入课程数据", data, ScheduleStore(context).read())
                var beforePortraitTap = ""
                main { beforePortraitTap = widget.findViewById<TextView>(R.id.widget_quote).text.toString() }
                onView(withId(R.id.widget_portrait)).perform(click())
                waitFor("点击立绘也应切换语录") {
                    widget.findViewById<TextView>(R.id.widget_quote).text.toString() != beforePortraitTap
                }
                val selectedMoment = LocalDateTime.now()
                val selectedLine = CompanionInteractionStore(context).current(ThemeRepository.get(context).find("himari"), selectedMoment)!!.line
                main { widget.findViewById<TextView>(R.id.widget_title).text = "等待刷新验证" }
                onView(withId(R.id.widget_refresh)).perform(click())
                waitFor("刷新按钮应更新小组件") { widget.findViewById<TextView>(R.id.widget_title).text.toString() == "今日课表" }
                main {
                    if (CompanionEngine.bucketAt(selectedMoment) == CompanionEngine.bucketAt(LocalDateTime.now())) {
                        assertEquals("普通刷新应保留手选语录", "阳茉莉：$selectedLine", widget.findViewById<TextView>(R.id.widget_quote).text.toString())
                    }
                    assertEquals(10, widget.findViewById<ListView>(R.id.widget_courses).count)
                }
                val changed = data.copy(courses = listOf(courses.first().copy(name = "已更新的课程", room = "新教学楼 201")))
                ScheduleStore(context).save(changed)
                waitFor("保存课表后桌面列表应自动刷新") { widget.findViewById<ListView>(R.id.widget_courses)?.count == 1 }
                waitFor("新课程名和教室应加载") {
                    widget.findViewById<ListView>(R.id.widget_courses).getChildAt(0)?.findViewById<TextView>(R.id.widget_course_room)?.text == "新教学楼 201"
                }
                main {
                    if (CompanionEngine.bucketAt(selectedMoment) == CompanionEngine.bucketAt(LocalDateTime.now())) {
                        assertEquals("修改课程也应保留手选语录", "阳茉莉：$selectedLine", widget.findViewById<TextView>(R.id.widget_quote).text.toString())
                    }
                }
                main { widget.updateAppWidget(TodayWidgetViews.build(context, id, changed, date.atTime(13, 0))) }
                snapshot("小组件-午间")
                val darkData = changed.copy(settings = settings.copy(darkMode = "dark"))
                ScheduleStore(context).save(darkData)
                waitFor("深色模式下课程文字应同步变亮") {
                    widget.findViewById<ListView>(R.id.widget_courses).getChildAt(0)?.findViewById<TextView>(R.id.widget_course_name)?.currentTextColor == android.graphics.Color.parseColor("#E6EAE7")
                }
                main { widget.updateAppWidget(TodayWidgetViews.build(context, id, darkData, date.atTime(21, 0))) }
                snapshot("小组件-夜晚")
                AppearanceStore(context).themeId = "sagiri"
                TodayWidgetUpdates.request(context)
                val sagiri = ThemeRepository.get(context).find("sagiri")
                waitFor("纱雾主题应刷新小组件语录和樱粉配色") {
                    widget.findViewById<TextView>(R.id.widget_quote).text.startsWith("纱雾：") &&
                        widget.findViewById<TextView>(R.id.widget_quote).currentTextColor == sagiri.dark.color("primary").toInt()
                }
                listOf(8 to "早晨", 13 to "午间", 21 to "夜晚").forEach { (hour, label) ->
                    val moment = date.atTime(hour, 0)
                    val choice = CompanionInteractionStore(context).current(sagiri, moment)!!
                    val art = sagiri.companion!!.artwork(choice.artwork)!!
                    val expected = ThemeImages.load(context, sagiri, art, 256, compact = true)!!
                    main { widget.updateAppWidget(TodayWidgetViews.build(context, id, darkData, moment)) }
                    waitFor("纱雾小组件应展示对应时段与紧凑图") {
                        widget.findViewById<TextView>(R.id.widget_quote).text.toString() == "纱雾：${choice.line}" &&
                            (widget.findViewById<android.widget.ImageView>(R.id.widget_portrait).drawable as? android.graphics.drawable.BitmapDrawable)
                                ?.bitmap?.sameAs(expected) == true
                    }
                    snapshot("小组件-纱雾-$label")
                }
                assertEquals("切换纱雾主题不能更改课程", darkData, ScheduleStore(context).read())
                assertFalse("新增主题不能重现换句提示", CompanionInteractionStore(context).showShuffleHint())
                AppearanceStore(context).themeId = "minimal"
                TodayWidgetUpdates.request(context)
                waitFor("切换简约主题应隐藏角色与语录") {
                    widget.findViewById<android.view.View>(R.id.widget_portrait_companion).visibility == android.view.View.GONE &&
                        widget.findViewById<TextView>(R.id.widget_quote).text.isEmpty() &&
                        widget.findViewById<TextView>(R.id.widget_title).currentTextColor == android.graphics.Color.parseColor("#E7ECF3")
                }
                assertEquals(darkData, ScheduleStore(context).read())
                snapshot("小组件-简约")
                AppearanceStore(context).themeId = "himari"
                TodayWidgetUpdates.request(context)
                waitFor("返回角色主题应保留提示已隐藏状态") {
                    widget.findViewById<android.view.View>(R.id.widget_portrait_companion).visibility == android.view.View.VISIBLE &&
                        widget.findViewById<android.view.View>(R.id.widget_shuffle_hint).visibility == android.view.View.GONE
                }
                ScheduleStore(context).save(changed.copy(courses = emptyList()))
                waitFor("清空后不应保留过期课程") { widget.findViewById<ListView>(R.id.widget_courses)?.count == 0 }
                waitFor("无课提示应可见") { widget.findViewById<TextView>(R.id.widget_empty).visibility == android.view.View.VISIBLE }
                snapshot("小组件-无课")
                context.getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).edit().putBoolean("show_companion", false).commit()
                TodayWidgetUpdates.request(context)
                waitFor("关闭角色后应隐藏立绘及语录切换区域") {
                    widget.findViewById<android.view.View>(R.id.widget_portrait).visibility == android.view.View.GONE &&
                        widget.findViewById<android.view.View>(R.id.widget_companion).visibility == android.view.View.GONE &&
                        widget.findViewById<android.view.View>(R.id.widget_portrait_companion).visibility == android.view.View.GONE
                }
            }
        } finally {
            if (hostCreated) main { if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(id); runCatching { host.stopListening() } }
            context.getSharedPreferences("shike_schedule", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("shike_companion_interaction", Context.MODE_PRIVATE).edit().clear().commit()
            shell("appwidget revokebind --package com.shike.timetable --user 0")
        }
    }
}
