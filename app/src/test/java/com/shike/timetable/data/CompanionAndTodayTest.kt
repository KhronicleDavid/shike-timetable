package com.shike.timetable.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CompanionAndTodayTest {
    @Test fun timeSlotsCrossMidnightAndRotateWithoutJitter() {
        assertEquals(HimariDayPart.Evening, HimariCompanion.partAt(LocalTime.of(4, 59)))
        assertEquals(HimariDayPart.Morning, HimariCompanion.partAt(LocalTime.of(5, 0)))
        assertEquals(HimariDayPart.Noon, HimariCompanion.partAt(LocalTime.of(11, 0)))
        assertEquals(HimariDayPart.Evening, HimariCompanion.partAt(LocalTime.of(17, 0)))
        val morning = LocalDateTime.of(2026, 10, 15, 8, 0)
        assertEquals(HimariCompanion.bucketAt(morning), HimariCompanion.bucketAt(morning.plusMinutes(29)))
        assertEquals(HimariCompanion.bucketAt(morning) + 1, HimariCompanion.bucketAt(morning.plusMinutes(30)))
        assertEquals(HimariCompanion.at(morning), HimariCompanion.at(morning.plusMinutes(29)))
        assertNotEquals(HimariCompanion.at(morning).artwork, HimariCompanion.at(morning.plusMinutes(30)).artwork)
        assertEquals(4, (0L..3L).map { HimariCompanion.at(morning.plusMinutes(it * 30)).artwork }.toSet().size)
        assertEquals(morning.toLocalDate().plusDays(1).atStartOfDay(), HimariCompanion.nextBoundary(morning.withHour(23)))
        assertEquals(morning.withHour(11), HimariCompanion.nextBoundary(morning))
        val midnight = morning.toLocalDate().plusDays(1).atStartOfDay()
        assertEquals(HimariCompanion.bucketAt(midnight.minusSeconds(1)) + 1, HimariCompanion.bucketAt(midnight))
        assertEquals(midnight.withHour(5), HimariCompanion.nextBoundary(midnight))
        assertEquals(morning.withHour(11), HimariCompanion.nextBoundary(morning.withHour(5)))
    }
    @Test fun everySlotHasMultiplePicturesAndDistinctShortDialogue() {
        assertEquals(120, HimariCompanion.allLines.size)
        assertEquals(HimariCompanion.allLines.size, HimariCompanion.allLines.toSet().size)
        assertTrue(HimariCompanion.allLines.all { it.isNotBlank() && it.length <= 45 })
        assertEquals(6, HimariCompanion.quietLines.size)
        listOf(HimariCompanion.morningLines, HimariCompanion.noonLines, HimariCompanion.eveningLines)
            .forEach { assertEquals(38, it.size) }
        HimariDayPart.entries.forEach { assertEquals(4, HimariCompanion.artworks.getValue(it).distinct().size) }
    }
    @Test fun randomTapAvoidsCurrentAndRecentFiveLinesWithinTheTimeSlot() {
        val morning = LocalDateTime.of(2026, 10, 15, 8, 0)
        val pools = listOf(HimariCompanion.morningLines, HimariCompanion.noonLines, HimariCompanion.eveningLines)
        listOf(morning, morning.withHour(13), morning.withHour(21)).forEachIndexed { index, moment ->
            val random = Random(300 + index)
            var current = HimariCompanion.at(moment)
            val recent = mutableListOf(current.line)
            val seen = mutableSetOf<String>()
            repeat(200) {
                val next = HimariCompanion.randomNext(moment, current, recent, random)
                assertEquals(HimariCompanion.partAt(moment.toLocalTime()), next.part)
                assertTrue(next.line in pools[index])
                assertTrue(next.artwork in HimariCompanion.artworks.getValue(next.part))
                assertNotEquals(current.line, next.line)
                assertNotEquals(current.artwork, next.artwork)
                assertFalse(next.line in recent.takeLast(5))
                seen += next.line
                current = next
                recent += next.line
            }
            assertTrue("点击应覆盖多条语录", seen.size > 20)
        }
    }
    @Test fun randomTapUsesTheNewTimeSlotAndCanBeReproducedForTests() {
        val morning = LocalDateTime.of(2026, 10, 15, 8, 0)
        val previous = HimariCompanion.at(morning)
        val noon = morning.withHour(11)
        val recent = HimariCompanion.morningLines.takeLast(5)
        val selected = HimariCompanion.randomNext(noon, previous, recent, Random(42))
        assertEquals(HimariDayPart.Noon, selected.part)
        assertTrue(selected.line in HimariCompanion.noonLines)
        assertTrue(selected.artwork in HimariCompanion.artworks.getValue(HimariDayPart.Noon))
        assertFalse(selected.line in HimariCompanion.morningLines)
        assertEquals(selected, HimariCompanion.randomNext(noon, previous, recent, Random(42)))
        val late = HimariCompanion.randomNext(morning.withHour(2), selected, listOf(selected.line), Random(7))
        assertEquals(HimariDayPart.Evening, late.part)
        assertTrue(late.line in HimariCompanion.eveningLines)
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
