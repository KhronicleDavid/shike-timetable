package com.shike.timetable.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.shike.timetable.MainActivity
import com.shike.timetable.R
import com.shike.timetable.data.*
import com.shike.timetable.theme.*
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
        val next = CompanionEngine.nextBoundary(moment).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, boundaryIntent(context))
    }
    internal fun cancelBoundary(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(boundaryIntent(context))
    }
}

class TodayWidgetProvider : AppWidgetProvider() {
    private fun refresh(context: Context) {
        val result = goAsync()
        TodayWidgetUpdates.executor.execute {
            try {
                val appContext = context.applicationContext
                val moment = LocalDateTime.now()
                TodayWidgetUpdates.refreshNow(appContext, moment)
            } finally { result.finish() }
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onEnabled(context: Context) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    override fun onDisabled(context: Context) = TodayWidgetUpdates.cancelBoundary(context)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(REFRESH_ACTION, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            refresh(context)
        } else super.onReceive(context, intent)
    }
}

/** 播放入口不导出，只有应用创建的 PendingIntent 能触发桌面语音。 */
class CompanionWidgetReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SHUFFLE_ACTION) CompanionVoicePlayback.shuffle(context)
    }
}

internal object TodayWidgetViews {
    fun dark(context: Context, data: ScheduleData): Boolean = data.settings.darkMode == "dark" ||
        (data.settings.darkMode == "system" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

    fun build(context: Context, id: Int, data: ScheduleData, moment: LocalDateTime = LocalDateTime.now(), readError: Boolean = false): RemoteViews {
        val today = moment.toLocalDate()
        val courses = TodayCourses.forDate(data, today)
        val appearance = AppearanceStore(context)
        val theme = ThemeRepository.get(context).find(appearance.themeId)
        val interaction = CompanionInteractionStore(context)
        val companion = interaction.current(theme, moment)
        val isDark = dark(context, data)
        val palette = theme.palette(isDark)
        val primary = palette.color("primary").toInt()
        val text = palette.color("onSurface").toInt()
        val secondary = palette.color("onSurfaceVariant").toInt()
        val showMascot = appearance.showCompanion && theme.companion != null && companion != null
        val name = theme.companion?.shortName.orEmpty()
        val voice = CompanionVoicePlayback.sessions.value
        val hasDialogue = theme.companion?.hasDialogue == true
        val shuffleDescription = if (hasDialogue) "点击换一句陪伴语" else "点击切换插画"
        return RemoteViews(context.packageName, R.layout.widget_today).apply {
            setInt(R.id.widget_background, "setColorFilter", palette.color("cardStart").toInt())
            setTextViewText(R.id.widget_title, if (readError) "课表需要检查" else "今日课表")
            setTextViewText(R.id.widget_date, "${today.monthValue}月${today.dayOfMonth}日 · 星期${"一二三四五六日"[today.dayOfWeek.value - 1]}")
            setTextViewText(R.id.widget_quote, if (showMascot && hasDialogue) "$name：${companion?.line.orEmpty()}" else "")
            setViewVisibility(R.id.widget_quote, if (showMascot && hasDialogue) View.VISIBLE else View.GONE)
            setImageViewBitmap(R.id.widget_portrait, theme.companion?.artwork(companion?.artwork.orEmpty())?.let { ThemeImages.load(context, theme, it, 256, compact = true) })
            setViewVisibility(R.id.widget_portrait, if (showMascot) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_portrait_companion, if (showMascot) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_companion, if (showMascot && hasDialogue) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_shuffle_hint, voice?.displayLabel ?: if (hasDialogue) "点点我 · 换句话" else "点点我 · 换幅图")
            setViewVisibility(R.id.widget_shuffle_hint, if (showMascot && (voice?.displayLabel != null || (voice == null && interaction.showShuffleHint()))) View.VISIBLE else View.GONE)
            setContentDescription(R.id.widget_quote, "$name：${companion?.line.orEmpty()}。${voice?.displayLabel ?: if (voice != null) "正在播放语音" else shuffleDescription}")
            setContentDescription(R.id.widget_portrait, "${theme.companion?.name.orEmpty()}，$shuffleDescription")
            setContentDescription(R.id.widget_shuffle_hint, shuffleDescription)
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
            val shuffle = Intent(context, CompanionWidgetReceiver::class.java).setAction(SHUFFLE_ACTION)
                .setData(Uri.parse("shike://widget/$id/companion"))
            val shufflePendingIntent = PendingIntent.getBroadcast(context, id, shuffle,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_portrait, shufflePendingIntent)
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
        val palette = ThemeRepository.get(context).find(AppearanceStore(context).themeId).palette(dark)
        setInt(R.id.widget_row_background, "setColorFilter", palette.color("surface").toInt())
        setTextViewText(R.id.widget_course_name, entry.course.name)
        setTextViewText(R.id.widget_course_room, entry.room)
        setTextViewText(R.id.widget_course_time, "${entry.time} · ${entry.course.startPeriod}–${entry.course.endPeriod}节")
        setTextColor(R.id.widget_course_name, palette.color("onSurface").toInt())
        setTextColor(R.id.widget_course_room, palette.color("onSurfaceVariant").toInt())
        setTextColor(R.id.widget_course_time, palette.color("primary").toInt())
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
