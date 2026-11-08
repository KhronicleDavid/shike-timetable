package com.shike.timetable.quotes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyQuoteTest {
    private val quotes = (0..110).map { DailyQuote("q$it", "短句$it", "作品$it", "", "https://example.com/$it") }
    private fun time(value: String) = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]")
    @Test fun oneHundredPlusShortUniqueOfflineQuotesHaveCreditsAndTraceableSources() {
        val file=File("src/main/assets/quotes/daily.json")
        val data=DailyQuoteRepository.parse(file.readText())
        assertTrue(data.size >= 100);assertTrue(data.all { it.text.codePointCount(0,it.text.length)<=50 })
        assertEquals(data.size,data.map { it.id }.toSet().size)
        assertEquals(data.size,data.map { it.text }.toSet().size)
        assertTrue(data.all { it.credit.contains(it.work) && it.sourceUrl.startsWith("https://") })
        val rows=JSONObject(file.readText()).getJSONArray("quotes")
        val preferred=(0 until rows.length()).map { rows.getJSONObject(it).getBoolean("preferred") }
        assertTrue(preferred.takeWhile { it }.size >= 15)
        assertFalse(preferred.dropWhile { it }.any { it })
    }
    @Test fun midnightDoesNotResetAndEightOClockIsTheOnlyDailyBoundary() {
        val before=time("2026-11-08T07:59:59")
        assertEquals(LocalDate.of(2026,11,7).toEpochDay(),DailyQuoteEngine.cycleAt(before))
        assertEquals(DailyQuoteEngine.cycleAt(time("2026-11-08T00:00:00")),DailyQuoteEngine.cycleAt(before))
        assertEquals(LocalDate.of(2026,11,8).toEpochDay(),DailyQuoteEngine.cycleAt(time("2026-11-08T08:00:00")))
    }
    @Test fun manualClicksNeverPostponeTheFixedEightOClockAdvance() {
        val now=time("2026-11-08T07:59:00")
        var choice=DailyQuoteEngine.choose(quotes,null,now)
        repeat(13) { choice=DailyQuoteEngine.choose(quotes,choice,now,true) }
        assertEquals("q13",choice.id)
        choice=DailyQuoteEngine.choose(quotes,choice,time("2026-11-08T08:00:00"))
        assertEquals("q14",choice.id)
        repeat(4) { choice=DailyQuoteEngine.choose(quotes,choice,time("2026-11-08T08:00:01")) }
        assertEquals("q14",choice.id)
        choice=DailyQuoteEngine.choose(quotes,choice,time("2026-11-08T23:59:59"),true)
        assertEquals("q15",choice.id)
        choice=DailyQuoteEngine.choose(quotes,choice,time("2026-11-09T07:59:59"))
        assertEquals("q15",choice.id)
        choice=DailyQuoteEngine.choose(quotes,choice,time("2026-11-09T08:00:00"))
        assertEquals("q16",choice.id)
    }
    @Test fun missedDaysAdvanceWithoutTapsAndPoolWrapsWithoutRepeatingAdjacentQuotes() {
        val start=time("2026-11-08T12:00:00")
        var choice=QuoteChoice(DailyQuoteEngine.cycleAt(start),"q110")
        choice=DailyQuoteEngine.choose(quotes,choice,start,true);assertEquals("q0",choice.id)
        choice=DailyQuoteEngine.choose(quotes,choice,start.plusDays(3));assertEquals("q3",choice.id)
        assertEquals(choice,DailyQuoteEngine.choose(quotes,choice,start.plusDays(3)))
        choice=DailyQuoteEngine.choose(quotes,choice,start.plusDays(114));assertEquals("q3",choice.id)
    }
    @Test fun nextAlarmUsesLocalEightIncludingDstAndTimezoneChanges() {
        val shanghai=time("2026-11-08T08:00:00")
        assertEquals(time("2026-11-09T08:00:00"),DailyQuoteEngine.nextBoundary(shanghai))
        val ny=LocalDate.of(2026,10,31).atTime(8,0).atZone(ZoneId.of("America/New_York"))
        assertEquals(25,Duration.between(ny,DailyQuoteEngine.nextBoundary(ny)).toHours())
        listOf("Asia/Shanghai","Asia/Tokyo","America/Los_Angeles","Europe/London").forEach { name ->
            val local=shanghai.withZoneSameInstant(ZoneId.of(name));val next=DailyQuoteEngine.nextBoundary(local)
            assertTrue(next>local);assertEquals(8,next.hour);assertEquals(0,next.minute)
        }
    }
    @Test fun clockBackwardsMissingIdsAndNewCatalogNeverCrashOrUndoAChoice() {
        val start=time("2026-11-08T12:00:00");val saved=QuoteChoice(DailyQuoteEngine.cycleAt(start),"q10")
        assertEquals("q10",DailyQuoteEngine.choose(quotes,saved,start.minusDays(2)).id)
        assertEquals("q0",DailyQuoteEngine.choose(quotes,QuoteChoice(saved.cycle,"removed"),start).id)
        val changed=quotes.filterNot { it.id=="q10" }
        assertNotNull(DailyQuoteEngine.choose(changed,saved,start))
    }
    @Test fun invalidCatalogCreditsLengthsAndDuplicatesAreRejected() {
        val raw=File("src/main/assets/quotes/daily.json").readText()
        listOf(JSONObject(raw).apply { getJSONArray("quotes").getJSONObject(0).put("text","字".repeat(51)) },
            JSONObject(raw).apply { getJSONArray("quotes").getJSONObject(0).put("work","") },
            JSONObject(raw).apply { getJSONArray("quotes").getJSONObject(0).put("sourceUrl","file:///private") },
            JSONObject(raw).apply { getJSONArray("quotes").put(getJSONArray("quotes").getJSONObject(0)) }).forEach {
            assertThrows(Exception::class.java) { DailyQuoteRepository.parse(it.toString()) }
        }
    }
}
