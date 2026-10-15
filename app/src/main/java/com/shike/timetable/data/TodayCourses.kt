package com.shike.timetable.data

import java.time.LocalDate

data class TodayCourse(val course: Course, val time: String, val room: String)

object TodayCourses {
    fun forDate(data: ScheduleData, date: LocalDate): List<TodayCourse> {
        val week = ScheduleRules.currentWeek(data.settings, date)
        if (week !in 1..data.settings.totalWeeks) return emptyList()
        return ScheduleRules.coursesForWeek(data.courses, week).filter { it.day == date.dayOfWeek.value }
            .map { course -> TodayCourse(course,
                "${data.settings.periods[course.startPeriod - 1].start}–${data.settings.periods[course.endPeriod - 1].end}",
                course.room.ifBlank { "教室待定" }) }
    }
}
