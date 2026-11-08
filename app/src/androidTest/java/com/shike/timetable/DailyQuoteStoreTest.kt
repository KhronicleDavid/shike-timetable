package com.shike.timetable

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.quotes.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DailyQuoteStoreTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun defaultOptOutCorruptStateAndCrossInstanceSelectionRecover() {
        val prefs=context.getSharedPreferences("test_daily_quote_store",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val rows=DailyQuoteRepository.get(context)
        val one=DailyQuoteStore(prefs,rows);val two=DailyQuoteStore(prefs,rows)
        val now=ZonedDateTime.parse("2026-11-08T07:59:00+08:00[Asia/Shanghai]")
        try {
            assertFalse(one.enabled);assertNull(one.current(now))
            one.enabled=true
            val first=one.current(now)!!;assertEquals(first,two.current(now))
            val second=two.next(now);assertNotEquals(first,second);assertEquals(second,one.current(now))
            val atEight=one.current(now.plusMinutes(1))!!;assertNotEquals(second,atEight)
            assertEquals(atEight,two.current(now.plusMinutes(1)))
            one.enabled=false;assertNull(two.current(now));one.enabled=true
            assertEquals(atEight,two.current(now.plusMinutes(1)))
            for (raw in listOf("broken", "{\"cycle\":-9223372036854775808,\"id\":\"unknown\"}",JSONObject().put("cycle",2).put("id","removed").toString())) {
                prefs.edit().putString("selection",raw).commit()
                assertNotNull(one.current(now))
            }
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun concurrentAppAndWidgetClicksNeverLoseAnAdvance() {
        val prefs=context.getSharedPreferences("test_daily_quote_concurrent",Context.MODE_PRIVATE);prefs.edit().clear().commit()
        val rows=DailyQuoteRepository.get(context);val now=ZonedDateTime.now()
        val stores=(1..4).map { DailyQuoteStore(prefs,rows) }
        stores.first().enabled=true;assertEquals(rows.first(),stores.first().current(now))
        val workers=Executors.newFixedThreadPool(4)
        try {
            val jobs=(0 until 80).map { i -> workers.submit { stores[i%4].next(now) } }
            jobs.forEach { it.get(10,TimeUnit.SECONDS) }
            assertEquals(rows[80],stores.first().current(now))
        } finally { workers.shutdownNow();prefs.edit().clear().commit() }
    }
}
