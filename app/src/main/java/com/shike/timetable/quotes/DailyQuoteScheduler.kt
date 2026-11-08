package com.shike.timetable.quotes

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shike.timetable.widget.TodayWidgetProvider
import com.shike.timetable.widget.TodayWidgetUpdates
import java.time.ZonedDateTime

object DailyQuoteScheduler {
    const val NEXT_ACTION = "com.shike.timetable.quotes.NEXT"
    private const val ACTION = "com.shike.timetable.quotes.DAILY_BOUNDARY"
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 1800,
        Intent(context, DailyQuoteReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun canScheduleExactly(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val widgets = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        if (!DailyQuoteStore(context).enabled || widgets.isEmpty()) { alarm.cancel(pending(context)); return }
        val time = DailyQuoteEngine.nextBoundary(now).toInstant().toEpochMilli()
        try {
            if (canScheduleExactly(context)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context))
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context))
        } catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context)) }
    }
    fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
}

class DailyQuoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        TodayWidgetUpdates.executor.execute {
            try {
                if (intent.action == DailyQuoteScheduler.NEXT_ACTION) DailyQuoteStore(context).next() else DailyQuoteStore(context).current()
                TodayWidgetUpdates.refreshNow(context)
                DailyQuoteScheduler.schedule(context)
            } finally { result.finish() }
        }
    }
}
