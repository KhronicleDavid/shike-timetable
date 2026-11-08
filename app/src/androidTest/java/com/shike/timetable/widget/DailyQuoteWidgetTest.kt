package com.shike.timetable.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.R
import com.shike.timetable.ThemeTestContent
import com.shike.timetable.data.*
import com.shike.timetable.quotes.*
import com.shike.timetable.theme.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

class DailyQuoteWidgetTest {
    @Test fun longCourseAndRoomNamesWrapWithoutEllipsis() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.runOnMainSync {
            val course=Course(name="用于检查长课程名称自然换行与完整展示的课程".repeat(3),room="第一教学楼一百零一号多媒体综合实验教室".repeat(3),
                day=1,startPeriod=1,endPeriod=2,startWeek=1,endWeek=20)
            val entry=TodayCourse(course,"08:00–09:35",course.room)
            val view=TodayWidgetViews.row(context,entry,false).apply(context,FrameLayout(context))
            val width=(200*context.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            view.layout(0,0,view.measuredWidth,view.measuredHeight)
            for (id in listOf(R.id.widget_course_name,R.id.widget_course_room)) {
                val text=view.findViewById<TextView>(id);val layout=text.layout
                assertTrue(layout.lineCount>2)
                assertEquals(text.text.length,layout.getLineEnd(layout.lineCount-1))
                for(line in 0 until layout.lineCount) assertEquals(0,layout.getEllipsisCount(line))
            }
        }
    }
    @Test fun twoRealWidgetsShareTapAutomaticAdvanceCopyAndKeepMascotAndCourseRows() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        fun main(action: ()->Unit)=instrumentation.runOnMainSync(action)
        fun shell(command: String)=instrumentation.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).use { input->input.readBytes() } }
        fun waitFor(message: String, condition: ()->Boolean) {
            val limit=SystemClock.elapsedRealtime()+15000
            while(SystemClock.elapsedRealtime()<limit) { var ready=false;main { ready=condition() };if(ready)return;SystemClock.sleep(100) }
            throw AssertionError(message)
        }
        val previous=ScheduleStore(context).read();val oldTheme=AppearanceStore(context).themeId
        val preferences=context.getSharedPreferences(DailyQuoteStore.PREFERENCES,Context.MODE_PRIVATE)
        preferences.edit().clear().commit();val quoteStore=DailyQuoteStore(context);quoteStore.enabled=true
        val themeStore=ExternalThemeStore(context)
        if(themeStore.installed().none { it.id=="himari" }) {
            val archive=ThemeTestContent.archive(context,instrumentation.context,"himari")
            try { themeStore.install(themeStore.prepare(archive)) } finally { archive.delete() }
        }
        AppearanceStore(context).apply { themeId="himari"; showCompanion=true; voiceEnabled=false }
        val now=ZonedDateTime.now();val rows=DailyQuoteRepository.get(context)
        val longest=rows.maxBy { it.text.length }
        preferences.edit().putString("selection",JSONObject().put("cycle",DailyQuoteEngine.cycleAt(now)).put("id",longest.id).toString()).commit()
        val date=LocalDate.now()
        val data=ScheduleData((1..10).map { n->Course(name="一言测试课程$n",room="测试教室${100+n}",day=date.dayOfWeek.value,startPeriod=n,endPeriod=n,startWeek=1,endWeek=20) },
            TermSettings(startDate=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),darkMode="light"))
        ScheduleStore(context).save(data)
        shell("appwidget grantbind --package com.shike.timetable --user 0")
        lateinit var host: AppWidgetHost;lateinit var widget: AppWidgetHostView;lateinit var second: AppWidgetHostView
        var created=false
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    host=AppWidgetHost(activity,18008);created=true;host.deleteHost()
                    val manager=AppWidgetManager.getInstance(activity)
                    fun make(): AppWidgetHostView {
                        val id=host.allocateAppWidgetId()
                        assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,TodayWidgetProvider::class.java)))
                        val info=manager.getAppWidgetInfo(id)
                        return host.createView(activity,id,info).apply { updateAppWidget(TodayWidgetViews.build(context,id,data)) }
                    }
                    widget=make();second=make();host.startListening()
                    val background=FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.parseColor("#E6ECE3")) }
                    val density=activity.resources.displayMetrics.density
                    background.addView(widget,FrameLayout.LayoutParams(-1,(240*density).toInt(),Gravity.CENTER).apply { leftMargin=(16*density).toInt();rightMargin=leftMargin })
                    // 第二实例仍在系统宿主登记并接收真实更新，不覆盖前景测试组件。
                    background.addView(second,FrameLayout.LayoutParams(1,1,Gravity.BOTTOM))
                    activity.addContentView(background,ViewGroup.LayoutParams(-1,-1))
                }
                waitFor("小组件课程未加载") { widget.findViewById<ListView>(R.id.widget_courses)?.count==10 }
                waitFor("最长短句未同步") { widget.findViewById<TextView>(R.id.widget_daily_text)?.text.toString()==longest.text }
                main {
                    assertEquals(View.VISIBLE,widget.findViewById<View>(R.id.widget_portrait).visibility)
                    assertEquals(View.GONE,widget.findViewById<View>(R.id.widget_companion).visibility)
                    fun assertComplete(id: Int) {
                        val view=widget.findViewById<TextView>(id)
                        val layout=view.layout
                        assertNotNull(layout)
                        assertEquals(view.text.length,layout.getLineEnd(layout.lineCount-1))
                        for (line in 0 until layout.lineCount) assertEquals("正文或出处不能省略",0,layout.getEllipsisCount(line))
                        assertTrue("文字应完整放入TextView",view.height>=layout.height)
                    }
                    assertComplete(R.id.widget_daily_text);assertComplete(R.id.widget_daily_credit)
                    val list=widget.findViewById<ListView>(R.id.widget_courses)
                    android.util.Log.d("DailyWidgetLayout", "header=${widget.findViewById<View>(R.id.widget_heading).height}, quoteLines=${widget.findViewById<TextView>(R.id.widget_daily_text).lineCount}, maxLines=${widget.findViewById<TextView>(R.id.widget_daily_text).maxLines}, list=${list.height}, row=${list.getChildAt(0).height}")
                    assertTrue("240dp下至少有一门课程的教室可见：列表${list.height}px，课程${list.getChildAt(0).height}px，组件${widget.height}px",list.height-list.paddingTop-list.paddingBottom>=list.getChildAt(0).height)
                }
                val directory=File(context.filesDir,"visual-check").apply { mkdirs() }
                SystemClock.sleep(6500) // 等待上个复制用例的系统剪贴板浮层消失。
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap->File(directory,"小组件-每日一言.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                // 逐条验证完整句库，含窄至200dp的小组件：正文和出处均不得截断或省略。
                for (width in listOf(200,280)) {
                    for (entry in rows) {
                        main {
                            val density=context.resources.displayMetrics.density
                            widget.layoutParams=widget.layoutParams.apply { this.width=(width*density).toInt();height=(320*density).toInt() }
                            preferences.edit().putString("selection",JSONObject().put("cycle",DailyQuoteEngine.cycleAt(ZonedDateTime.now())).put("id",entry.id).toString()).commit()
                            widget.updateAppWidget(TodayWidgetViews.build(context,widget.appWidgetId,data))
                            val w=android.view.View.MeasureSpec.makeMeasureSpec((width*density).toInt(),android.view.View.MeasureSpec.EXACTLY)
                            val h=android.view.View.MeasureSpec.makeMeasureSpec((320*density).toInt(),android.view.View.MeasureSpec.EXACTLY)
                            widget.measure(w,h);widget.layout(0,0,widget.measuredWidth,widget.measuredHeight)
                            for (id in listOf(R.id.widget_daily_text,R.id.widget_daily_credit)) {
                                val text=widget.findViewById<TextView>(id);val layout=text.layout
                                assertEquals("${entry.id}:完整显示正文与出处",text.text.length,layout.getLineEnd(layout.lineCount-1))
                                for (line in 0 until layout.lineCount) assertEquals("${entry.id}:不应出现省略号",0,layout.getEllipsisCount(line))
                                assertTrue(text.height>=layout.height)
                            }
                            assertTrue(widget.findViewById<ListView>(R.id.widget_courses).height>0)
                        }
                    }
                }
                preferences.edit().putString("selection",JSONObject().put("cycle",DailyQuoteEngine.cycleAt(ZonedDateTime.now())).put("id",longest.id).toString()).commit()
                main { widget.updateAppWidget(TodayWidgetViews.build(context,widget.appWidgetId,data)) }
                main { widget.findViewById<TextView>(R.id.widget_daily_text).performClick() }
                waitFor("点击未同步两个小组件") {
                    val a=widget.findViewById<TextView>(R.id.widget_daily_text).text.toString()
                    val b=second.findViewById<TextView>(R.id.widget_daily_text).text.toString()
                    a!=longest.text && a==b && a==quoteStore.current()!!.text
                }
                val selected=quoteStore.current()!!
                main { widget.findViewById<TextView>(R.id.widget_daily_copy).performClick() }
                waitFor("桌面复制正文未成功") {
                    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()==selected.text
                }
                assertEquals(selected,quoteStore.current())
                // 模拟08:00边界前最后一次记录，通过真正的Receiver刷新，期间不点击换句。
                preferences.edit().putString("selection",JSONObject().put("cycle",DailyQuoteEngine.cycleAt(ZonedDateTime.now())-1).put("id",selected.id).toString()).commit()
                context.sendBroadcast(Intent(context,DailyQuoteReceiver::class.java))
                waitFor("无点击的每日边界未同步") {
                    val a=widget.findViewById<TextView>(R.id.widget_daily_text).text.toString()
                    val b=second.findViewById<TextView>(R.id.widget_daily_text).text.toString()
                    a!=selected.text && a==b
                }
                main {
                    widget.layoutParams=widget.layoutParams.apply { height=(400*context.resources.displayMetrics.density).toInt() }
                    widget.requestLayout()
                }
                waitFor("变大小组件未保留课程") { widget.findViewById<ListView>(R.id.widget_courses).height>150*context.resources.displayMetrics.density }
                main { widget.findViewById<ListView>(R.id.widget_courses).setSelection(9) }
                waitFor("课程列表不能滚动到底") { widget.findViewById<ListView>(R.id.widget_courses).lastVisiblePosition==9 }
                assertEquals(data,ScheduleStore(context).read())
                quoteStore.enabled=false
                main { widget.updateAppWidget(TodayWidgetViews.build(context,widget.appWidgetId,data)) }
                main { assertEquals(View.GONE,widget.findViewById<View>(R.id.widget_daily_box).visibility) }
            }
        } finally {
            if(created)main { host.stopListening();host.deleteHost() }
            quoteStore.enabled=false;DailyQuoteScheduler.cancel(context)
            AppearanceStore(context).themeId=oldTheme;ScheduleStore(context).save(previous)
            shell("appwidget revokebind --package com.shike.timetable --user 0")
        }
    }
}
