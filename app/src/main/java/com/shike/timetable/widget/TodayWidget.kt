package com.shike.timetable.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.shike.timetable.MainActivity
import com.shike.timetable.R
import com.shike.timetable.data.*
import com.shike.timetable.ui.himariArtworkResource
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

const val WIDGET_OPEN_ACTION = "com.shike.timetable.widget.OPEN_TODAY"
const val WIDGET_COURSE_ID = "widget_course_id"
private const val REFRESH_ACTION = "com.shike.timetable.widget.REFRESH"
private const val SHUFFLE_ACTION = "com.shike.timetable.widget.SHUFFLE_COMPANION"

object TodayWidgetUpdates {
    internal val executor = Executors.newSingleThreadExecutor()
    fun request(context: Context) {
        context.applicationContext.sendBroadcast(Intent(context, TodayWidgetProvider::class.java).setAction(REFRESH_ACTION))
    }
    fun requestPin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
            ComponentName(context, TodayWidgetProvider::class.java), null, null)
    }
    internal fun refreshNow(context: Context, moment: LocalDateTime = LocalDateTime.now()) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        if (ids.isEmpty()) { cancelBoundary(context); return }
        val store = ScheduleStore(context)
        val data = store.read()
        ids.forEach { id ->
            manager.updateAppWidget(id, TodayWidgetViews.build(context, id, data, moment, store.lastReadWarning != null))
            if (Build.VERSION.SDK_INT < 31 || TodayCourses.forDate(data, moment.toLocalDate()).size > 100) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_courses)
            }
        }
        scheduleBoundary(context, moment)
    }
    private fun boundaryIntent(context: Context) = PendingIntent.getBroadcast(context, 701,
        Intent(context, TodayWidgetProvider::class.java).setAction(REFRESH_ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun scheduleBoundary(context: Context, moment: LocalDateTime) {
        val next = HimariCompanion.nextBoundary(moment).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, boundaryIntent(context))
    }
    internal fun cancelBoundary(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(boundaryIntent(context))
    }
}

class TodayWidgetProvider : AppWidgetProvider() {
    private fun refresh(context: Context, shuffleCompanion: Boolean = false) {
        val result = goAsync()
        TodayWidgetUpdates.executor.execute {
            try {
                val appContext = context.applicationContext
                val moment = LocalDateTime.now()
                if (shuffleCompanion) HimariInteractionStore(appContext).shuffle(moment)
                TodayWidgetUpdates.refreshNow(appContext, moment)
            } finally { result.finish() }
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onEnabled(context: Context) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    override fun onDisabled(context: Context) = TodayWidgetUpdates.cancelBoundary(context)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SHUFFLE_ACTION) {
            refresh(context, shuffleCompanion = true)
        } else if (intent.action in setOf(REFRESH_ACTION, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            refresh(context)
        } else super.onReceive(context, intent)
    }
}

internal object TodayWidgetViews {
    fun dark(context: Context, data: ScheduleData): Boolean = data.settings.darkMode == "dark" ||
        (data.settings.darkMode == "system" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

    fun build(context: Context, id: Int, data: ScheduleData, moment: LocalDateTime = LocalDateTime.now(), readError: Boolean = false): RemoteViews {
        val today = moment.toLocalDate()
        val courses = TodayCourses.forDate(data, today)
        val companion = HimariInteractionStore(context).current(moment)
        val isDark = dark(context, data)
        val primary = Color.parseColor(if (isDark) "#A3D4C6" else "#2C716C")
        val text = Color.parseColor(if (isDark) "#E6EAE7" else "#293B50")
        val secondary = Color.parseColor(if (isDark) "#C0CEC4" else "#607268")
        val showMascot = context.getSharedPreferences("shike_appearance", Context.MODE_PRIVATE).getBoolean("show_himari", true)
        return RemoteViews(context.packageName, R.layout.widget_today).apply {
            setInt(R.id.widget_root, "setBackgroundResource", if (isDark) R.drawable.widget_background_dark else R.drawable.widget_background)
            setTextViewText(R.id.widget_title, if (readError) "课表需要检查" else "今日课表")
            setTextViewText(R.id.widget_date, "${today.monthValue}月${today.dayOfMonth}日 · 星期${"一二三四五六日"[today.dayOfWeek.value - 1]}")
            setTextViewText(R.id.widget_quote, "阳茉莉：${companion.line}")
            setImageViewResource(R.id.widget_himari, himariArtworkResource(companion.artwork))
            setViewVisibility(R.id.widget_himari, if (showMascot) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_portrait_companion, if (showMascot) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_companion, if (showMascot) View.VISIBLE else View.GONE)
            setContentDescription(R.id.widget_quote, "阳茉莉：${companion.line}。点击换一句陪伴语")
            setTextColor(R.id.widget_title, text)
            setTextColor(R.id.widget_date, secondary)
            setTextColor(R.id.widget_quote, primary)
            setTextColor(R.id.widget_shuffle_hint, secondary)
            setTextColor(R.id.widget_refresh, primary)
            setTextColor(R.id.widget_summary, secondary)
            setTextColor(R.id.widget_empty, secondary)
            setTextViewText(R.id.widget_empty, if (readError) "课表读取异常，请打开拾课检查。" else "今天没有课，自由安排时间。")
            setTextViewText(R.id.widget_summary, if (readError) "点击标题打开应用" else "${courses.size} 门课程 · 上下滑动查看")
            setEmptyView(R.id.widget_courses, R.id.widget_empty)
            val launch = Intent(context, MainActivity::class.java).setAction(WIDGET_OPEN_ACTION)
                .setData(Uri.parse("shike://widget/$id/today")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            setOnClickPendingIntent(R.id.widget_open_today, PendingIntent.getActivity(context, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val shuffle = Intent(context, TodayWidgetProvider::class.java).setAction(SHUFFLE_ACTION)
                .setData(Uri.parse("shike://widget/$id/companion"))
            val shufflePendingIntent = PendingIntent.getBroadcast(context, id, shuffle,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_himari, shufflePendingIntent)
            setOnClickPendingIntent(R.id.widget_quote, shufflePendingIntent)
            setOnClickPendingIntent(R.id.widget_shuffle_hint, shufflePendingIntent)
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            setPendingIntentTemplate(R.id.widget_courses, PendingIntent.getActivity(context, id + 10000, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or mutable))
            val refresh = Intent(context, TodayWidgetProvider::class.java).setAction(REFRESH_ACTION)
                .setData(Uri.parse("shike://widget/$id/refresh"))
            setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if (Build.VERSION.SDK_INT >= 31 && courses.size <= 100) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                courses.forEach { entry -> items.addItem(stableId(entry.course), row(context, entry, isDark)) }
                setRemoteAdapter(R.id.widget_courses, items.build())
            } else {
                @Suppress("DEPRECATION")
                setRemoteAdapter(R.id.widget_courses, Intent(context, TodayCoursesService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("shike://widget/$id/courses")))
            }
        }
    }
    internal fun stableId(course: Course): Long = UUID.fromString(course.id).let { it.mostSignificantBits xor it.leastSignificantBits }
    internal fun row(context: Context, entry: TodayCourse, dark: Boolean): RemoteViews = RemoteViews(context.packageName, R.layout.widget_course_row).apply {
        setInt(R.id.widget_course_row, "setBackgroundResource", if (dark) R.drawable.widget_row_background_dark else R.drawable.widget_row_background)
        setTextViewText(R.id.widget_course_name, entry.course.name)
        setTextViewText(R.id.widget_course_room, entry.room)
        setTextViewText(R.id.widget_course_time, "${entry.time} · ${entry.course.startPeriod}–${entry.course.endPeriod}节")
        setTextColor(R.id.widget_course_name, Color.parseColor(if (dark) "#E6EAE7" else "#293B50"))
        setTextColor(R.id.widget_course_room, Color.parseColor(if (dark) "#C0CEC4" else "#607268"))
        setTextColor(R.id.widget_course_time, Color.parseColor(if (dark) "#A3D4C6" else "#2C716C"))
        setContentDescription(R.id.widget_course_row, "${entry.course.name}，${entry.room}，${entry.time}")
        setOnClickFillInIntent(R.id.widget_course_row, Intent().putExtra(WIDGET_COURSE_ID, entry.course.id))
    }
}

class TodayCoursesService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TodayCoursesFactory(applicationContext)
}

internal class TodayCoursesFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var entries: List<TodayCourse> = emptyList()
    private var dark = false
    override fun onCreate() = reload()
    override fun onDataSetChanged() = reload()
    private fun reload() {
        val data = ScheduleStore(context).read()
        entries = TodayCourses.forDate(data, LocalDateTime.now().toLocalDate())
        dark = TodayWidgetViews.dark(context, data)
    }
    override fun getCount() = entries.size
    override fun getViewAt(position: Int): RemoteViews? = entries.getOrNull(position)?.let { TodayWidgetViews.row(context, it, dark) }
    override fun getItemId(position: Int): Long = entries.getOrNull(position)?.let { TodayWidgetViews.stableId(it.course) } ?: position.toLong()
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun hasStableIds() = true
    override fun onDestroy() { entries = emptyList() }
}
