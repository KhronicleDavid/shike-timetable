package com.shike.timetable.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import com.shike.timetable.theme.*
import java.io.File

class CompanionAndTodayTest {
    private val theme = ThemePackParser.parse(File("src/main/assets/themes/himari/theme.json").readText())
    private val content = theme.companion!!
    private fun at(moment: LocalDateTime) = CompanionEngine.at(theme, moment)!!
    private fun pictures(part: DayPart) = content.pictures(part).map { it.id }
    private fun next(moment: LocalDateTime, current: CompanionMoment, recent: List<String>, random: Random) =
        CompanionEngine.randomNext(theme, moment, current, recent, random)!!
    @Test fun timeSlotsCrossMidnightAndRotateWithoutJitter() {
        assertEquals(DayPart.Evening, DayPart.at(LocalTime.of(4, 59)))
        assertEquals(DayPart.Morning, DayPart.at(LocalTime.of(5, 0)))
        assertEquals(DayPart.Noon, DayPart.at(LocalTime.of(11, 0)))
        assertEquals(DayPart.Evening, DayPart.at(LocalTime.of(17, 0)))
        val morning = LocalDateTime.of(2026, 10, 15, 8, 0)
        assertEquals(CompanionEngine.bucketAt(morning), CompanionEngine.bucketAt(morning.plusMinutes(29)))
        assertEquals(CompanionEngine.bucketAt(morning) + 1, CompanionEngine.bucketAt(morning.plusMinutes(30)))
        assertEquals(at(morning), at(morning.plusMinutes(29)))
        if (pictures(DayPart.Morning).size > 1) assertNotEquals(at(morning).artwork, at(morning.plusMinutes(30)).artwork)
        assertEquals(morning.toLocalDate().plusDays(1).atStartOfDay(), CompanionEngine.nextBoundary(morning.withHour(23)))
        assertEquals(morning.withHour(11), CompanionEngine.nextBoundary(morning))
        val midnight = morning.toLocalDate().plusDays(1).atStartOfDay()
        assertEquals(CompanionEngine.bucketAt(midnight.minusSeconds(1)) + 1, CompanionEngine.bucketAt(midnight))
        assertEquals(midnight.withHour(5), CompanionEngine.nextBoundary(midnight))
        assertEquals(morning.withHour(11), CompanionEngine.nextBoundary(morning.withHour(5)))
    }
    @Test fun everySlotHasValidContentWithoutHardcodedCounts() {
        assertTrue(content.allLines.all { it.isNotBlank() })
        DayPart.entries.forEach {
            val lines = content.quotes.getValue(it)
            assertTrue(lines.isNotEmpty())
            assertEquals(lines.size, lines.distinct().size)
            assertTrue(pictures(it).isNotEmpty())
        }
    }
    @Test fun randomTapAvoidsCurrentAndRecentFiveLinesWithinTheTimeSlot() {
        val morning = LocalDateTime.of(2026, 10, 15, 8, 0)
        val pools = listOf(content.quotes.getValue(DayPart.Morning), content.quotes.getValue(DayPart.Noon), content.quotes.getValue(DayPart.Evening))
        listOf(morning, morning.withHour(13), morning.withHour(21)).forEachIndexed { index, moment ->
            val random = Random(300 + index)
            var current = at(moment)
            val recent = mutableListOf(current.line)
            val seen = mutableSetOf<String>()
            repeat(200) {
                val next = next(moment, current, recent, random)
                assertEquals(DayPart.at(moment.toLocalTime()), next.part)
                assertTrue(next.line in pools[index])
                assertTrue(next.artwork in pictures(next.part))
                if (pools[index].size > 1) assertNotEquals(current.line, next.line)
                if (pictures(next.part).size > 1) assertNotEquals(current.artwork, next.artwork)
                if (pools[index].size > 5) assertFalse(next.line in recent.takeLast(5))
                seen += next.line
                current = next
                recent += next.line
            }
            assertTrue("点击应覆盖多条语录", seen.size >= minOf(6, pools[index].size))
        }
    }
    @Test fun randomTapUsesTheNewTimeSlotAndCanBeReproducedForTests() {
        val morning = LocalDateTime.of(2026, 10, 15, 8, 0)
        val previous = at(morning)
        val noon = morning.withHour(11)
        val recent = content.quotes.getValue(DayPart.Morning).takeLast(5)
        val selected = next(noon, previous, recent, Random(42))
        assertEquals(DayPart.Noon, selected.part)
        assertTrue(selected.line in content.quotes.getValue(DayPart.Noon))
        assertTrue(selected.artwork in pictures(DayPart.Noon))
        assertEquals(selected, next(noon, previous, recent, Random(42)))
        val late = next(morning.withHour(2), selected, listOf(selected.line), Random(7))
        assertEquals(DayPart.Evening, late.part)
        assertTrue(late.line in content.quotes.getValue(DayPart.Evening))
    }
    @Test fun todayUsesRealWeekOddEvenWeekendAndClassroomFallback() {
        val settings = TermSettings(startDate = "2026-09-07", totalWeeks = 20, showWeekend = false)
        val odd = Course(name = "单周", day = 1, weekPattern = "odd", room = "A101")
        val even = Course(name = "双周", day = 1, weekPattern = "even")
        val weekend = Course(name = "周末课", day = 7)
        val data = ScheduleData(listOf(odd, even, weekend), settings)
        assertEquals(listOf("单周"), TodayCourses.forDate(data, LocalDate.parse("2026-09-07")).map { it.course.name })
        val secondWeek = TodayCourses.forDate(data, LocalDate.parse("2026-09-14")).single()
        assertEquals("双周", secondWeek.course.name)
        assertEquals("教室待定", secondWeek.room)
        assertEquals("08:00–09:40", secondWeek.time)
        assertEquals("周末课", TodayCourses.forDate(data, LocalDate.parse("2026-09-13")).single().course.name)
        assertTrue(TodayCourses.forDate(data, LocalDate.parse("2026-09-06")).isEmpty())
        assertTrue(TodayCourses.forDate(data, LocalDate.parse("2027-03-01")).isEmpty())
    }
}
