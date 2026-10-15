package com.shike.timetable.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRulesTest {
    private val settings = TermSettings()
    private val base = Course(name = "测试课程", day = 2, startPeriod = 3, endPeriod = 4)

    @Test fun currentWeekRespectsStartAndSundayBoundary() {
        assertEquals(0, ScheduleRules.currentWeek(settings, LocalDate.parse("2026-09-06")))
        assertEquals(0, ScheduleRules.currentWeek(settings, LocalDate.parse("2026-01-01")))
        assertEquals(1, ScheduleRules.currentWeek(settings, LocalDate.parse("2026-09-07")))
        assertEquals(1, ScheduleRules.currentWeek(settings, LocalDate.parse("2026-09-13")))
        assertEquals(2, ScheduleRules.currentWeek(settings, LocalDate.parse("2026-09-14")))
        assertEquals(5, ScheduleRules.currentWeek(settings, LocalDate.parse("2026-10-08")))
        assertEquals(21, ScheduleRules.currentWeek(settings, LocalDate.parse("2027-01-25")))
    }

    @Test fun dayDateCanCrossYearAndExceedTerm() {
        assertEquals(LocalDate.parse("2026-09-07"), ScheduleRules.dateForDay(settings, 1, 1))
        assertEquals(LocalDate.parse("2026-09-13"), ScheduleRules.dateForDay(settings, 1, 7))
        assertEquals(LocalDate.parse("2027-01-03"), ScheduleRules.dateForDay(settings, 17, 7))
        assertEquals(LocalDate.parse("2027-01-25"), ScheduleRules.dateForDay(settings, 21, 1))
    }

    @Test fun oddAndEvenCoursesCanShareTimeWithoutConflict() {
        val odd = base.copy(weekPattern = "odd")
        val even = base.copy(id = newId(), weekPattern = "even")
        assertFalse(ScheduleRules.conflicts(odd, listOf(even)))
        assertTrue(ScheduleRules.conflicts(odd, listOf(even.copy(weekPattern = "all"))))
    }

    @Test fun overlappingWeekRangesStillNeedAnActualSharedTeachingWeek() {
        val odd = base.copy(startWeek = 1, endWeek = 2, weekPattern = "odd")
        val onlySecondWeek = base.copy(id = newId(), startWeek = 2, endWeek = 3)
        assertFalse(ScheduleRules.conflicts(odd, listOf(onlySecondWeek)))
        assertTrue(ScheduleRules.conflicts(odd.copy(endWeek = 3), listOf(onlySecondWeek)))
    }

    @Test fun conflictIncludesEndpointsButExcludesDifferentDaysAndSameId() {
        assertTrue(ScheduleRules.conflicts(base, listOf(base.copy(id = newId(), startPeriod = 4, endPeriod = 5))))
        assertFalse(ScheduleRules.conflicts(base, listOf(base.copy(id = newId(), startPeriod = 5, endPeriod = 6))))
        assertFalse(ScheduleRules.conflicts(base, listOf(base.copy(id = newId(), day = 3))))
        assertFalse(ScheduleRules.conflicts(base, listOf(base)))
        assertFalse(ScheduleRules.conflicts(base.copy(endWeek = 5), listOf(base.copy(id = newId(), startWeek = 6))))
    }

    @Test fun weekFilterUsesAbsoluteParityAndExcludesInactiveWeeks() {
        val all = base.copy(startWeek = 2, endWeek = 6)
        val odd = base.copy(id = newId(), startWeek = 2, endWeek = 6, weekPattern = "odd")
        val even = base.copy(id = newId(), startWeek = 2, endWeek = 6, weekPattern = "even")
        assertEquals(setOf(all.id, odd.id), ScheduleRules.coursesForWeek(listOf(all, odd, even), 3).map { it.id }.toSet())
        assertEquals(setOf(all.id, even.id), ScheduleRules.coursesForWeek(listOf(all, odd, even), 4).map { it.id }.toSet())
        assertTrue(ScheduleRules.coursesForWeek(listOf(all, odd, even), 0).isEmpty())
        assertTrue(ScheduleRules.coursesForWeek(listOf(all, odd, even), 7).isEmpty())
    }

    @Test fun validationRejectsInvertedRangesAndAnEmptyParitySelection() {
        assertNull(ScheduleRules.validationError(base, 20))
        assertNotNull(ScheduleRules.validationError(base.copy(startPeriod = 5, endPeriod = 4), 20))
        assertNotNull(ScheduleRules.validationError(base.copy(startWeek = 10, endWeek = 9), 20))
        assertNotNull(ScheduleRules.validationError(base.copy(endWeek = 21), 20))
        assertNotNull(ScheduleRules.validationError(base.copy(startWeek = 2, endWeek = 2, weekPattern = "odd"), 20))
    }

    @Test fun settingsRejectInvalidDateAndOverlappingTimes() {
        assertNull(ScheduleRules.settingsValidationError(settings))
        assertNotNull(ScheduleRules.settingsValidationError(settings.copy(startDate = "2026-02-30")))
        assertNotNull(ScheduleRules.settingsValidationError(settings.copy(startDate = "2026-09-08")))
        val periods = settings.periods.toMutableList().apply { this[1] = PeriodTime("08:30", "09:40") }
        assertNotNull(ScheduleRules.settingsValidationError(settings.copy(periods = periods)))
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString()
}
