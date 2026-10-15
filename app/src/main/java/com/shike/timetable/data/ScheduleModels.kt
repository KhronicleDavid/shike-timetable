package com.shike.timetable.data

import java.util.UUID

/** 一门课的一次固定排课；节次、星期和教学周均从 1 开始。 */
data class Course(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val day: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekPattern: String = "all",
    val colorIndex: Int = 0,
    val note: String = "",
)

data class PeriodTime(val start: String, val end: String)

data class TermSettings(
    val name: String = "2026 · 秋季学期",
    val startDate: String = "2026-09-07",
    val totalWeeks: Int = 20,
    val showWeekend: Boolean = false,
    val darkMode: String = "system",
    val periods: List<PeriodTime> = defaultPeriodTimes(),
)

data class ScheduleData(val courses: List<Course>, val settings: TermSettings)

internal fun defaultPeriodTimes(): List<PeriodTime> = listOf(
    PeriodTime("08:00", "08:45"),
    PeriodTime("08:55", "09:40"),
    PeriodTime("10:00", "10:45"),
    PeriodTime("10:55", "11:40"),
    PeriodTime("14:00", "14:45"),
    PeriodTime("14:55", "15:40"),
    PeriodTime("16:00", "16:45"),
    PeriodTime("16:55", "17:40"),
    PeriodTime("19:00", "19:45"),
    PeriodTime("19:55", "20:40"),
    PeriodTime("20:50", "21:35"),
    PeriodTime("21:45", "22:30"),
)
