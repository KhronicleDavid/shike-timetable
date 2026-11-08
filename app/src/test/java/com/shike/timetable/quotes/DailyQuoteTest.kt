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
        assertTrue(data.size >= 211);assertTrue(data.all { it.text.codePointCount(0,it.text.length)<=50 })
        assertTrue(data.groupingBy { it.work }.eachCount().values.all { it <= 5 })
        assertEquals(5,data.count { it.work == "CLANNAD" })
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
    @Test fun manualRandomChoicesNeverPostponeTheFixedEightOClockBoundary() {
        val now=time("2026-11-08T07:59:00")
        val random=kotlin.random.Random(47)
        var choice=DailyQuoteEngine.choose(quotes,null,now,random=random)
        repeat(13) {
            val old=choice
            choice=DailyQuoteEngine.choose(quotes,choice,now,true,random)
            assertNotEquals(old.id,choice.id)
            assertEquals(old.cycle,choice.cycle)
        }
        val manual=choice
        choice=DailyQuoteEngine.choose(quotes,choice,time("2026-11-08T08:00:00"),random=random)
        assertNotEquals(manual.id,choice.id)
        val atEight=choice
        repeat(4) { choice=DailyQuoteEngine.choose(quotes,choice,time("2026-11-08T08:00:01"),random=random) }
        assertEquals(atEight,choice)
        assertEquals(choice,DailyQuoteEngine.choose(quotes,choice,time("2026-11-09T07:59:59"),random=random))
        assertNotEquals(choice.id,DailyQuoteEngine.choose(quotes,choice,time("2026-11-09T08:00:00"),random=random).id)
    }
    @Test fun randomPoolIncludesEveryOtherQuoteAndMissedDaysSwitchOnlyOnceOnReturn() {
        val now=time("2026-11-08T12:00:00")
        val saved=QuoteChoice(DailyQuoteEngine.cycleAt(now),"q54")
        val random=kotlin.random.Random(91)
        val results=(1..10000).map { DailyQuoteEngine.choose(quotes,saved,now,true,random).id }.toSet()
        assertEquals(quotes.map { it.id }.toSet()-saved.id,results)
        val missed=DailyQuoteEngine.choose(quotes,saved,now.plusDays(111),random=random)
        assertNotEquals(saved.id,missed.id)
        assertEquals(missed,DailyQuoteEngine.choose(quotes,missed,now.plusDays(111),random=random))
        assertEquals("q0",DailyQuoteEngine.choose(quotes.take(1),null,now).id)
        assertEquals("q0",DailyQuoteEngine.choose(quotes.take(1),QuoteChoice(saved.cycle,"q0"),now,true).id)
    }
    @Test fun gamesHaveEqualRandomOpportunityRegardlessOfTheirQuoteCounts() {
        val now=time("2026-11-09T12:00:00")
        val pool=(0..30).map { quotes[it].copy(work=if (it == 0) "一条的作品" else if (it <= 5) "五条的作品" else "二十五条的作品") }
        val random=kotlin.random.Random(22)
        val counts=mutableMapOf<String,Int>()
        repeat(30000) {
            val choice=DailyQuoteEngine.choose(pool,null,now,random=random)
            val work=pool.first { it.id == choice.id }.work
            counts[work]=(counts[work] ?: 0)+1
        }
        assertEquals(3,counts.size)
        assertTrue(counts.values.all { it in 9500..10500 })
        var current=DailyQuoteEngine.choose(pool,null,now,random=random)
        repeat(1000) {
            val next=DailyQuoteEngine.choose(pool,current,now,true,random)
            assertNotEquals(pool.first { it.id == current.id }.work,pool.first { it.id == next.id }.work)
            current=next
        }
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
        assertTrue(DailyQuoteEngine.choose(quotes,QuoteChoice(saved.cycle,"removed"),start).id in quotes.map { it.id })
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
