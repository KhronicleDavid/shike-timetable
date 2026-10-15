package com.shike.timetable.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** 不依赖 Android 的日期、筛选、冲突和校验规则。 */
object ScheduleRules {
    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val timePattern = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    /** 开学前返回 0；学期结束后继续返回真实周次，方便提示当前不在学期内。 */
    fun currentWeek(settings: TermSettings, date: LocalDate = LocalDate.now()): Int {
        val start = LocalDate.parse(settings.startDate)
        val elapsed = ChronoUnit.DAYS.between(start, date)
        return if (elapsed < 0) 0 else (elapsed / 7 + 1).toInt()
    }

    /** startDate 表示第 1 周周一，week 可以超过学期周数。 */
    fun dateForDay(settings: TermSettings, week: Int, day: Int): LocalDate {
        require(week >= 1) { "教学周必须从第 1 周开始" }
        require(day in 1..7) { "星期必须在周一至周日之间" }
        return LocalDate.parse(settings.startDate)
            .plusDays((week.toLong() - 1) * 7 + day - 1)
    }

    fun coursesForWeek(courses: List<Course>, week: Int): List<Course> = courses
        .filter { week in it.startWeek..it.endWeek && matchesPattern(it.weekPattern, week) }
        .sortedWith(compareBy<Course>({ it.day }, { it.startPeriod }, { it.endPeriod }, { it.name }))

    /** 仅当星期、包含端点的节次区间和至少一个实际上课周均重叠时才冲突。 */
    fun conflicts(candidate: Course, courses: List<Course>): Boolean = courses.any { other ->
        if (other.id == candidate.id || other.day != candidate.day ||
            candidate.startPeriod > other.endPeriod || other.startPeriod > candidate.endPeriod
        ) {
            false
        } else {
            val firstWeek = maxOf(candidate.startWeek, other.startWeek)
            val lastWeek = minOf(candidate.endWeek, other.endWeek)
            (firstWeek..lastWeek).any { week ->
                matchesPattern(candidate.weekPattern, week) && matchesPattern(other.weekPattern, week)
            }
        }
    }

    fun validationError(course: Course, totalWeeks: Int): String? = when {
        !uuidPattern.matches(course.id) -> "课程标识必须是有效的 UUID"
        course.name.isBlank() -> "请填写课程名称"
        course.day !in 1..7 -> "请选择周一至周日"
        course.startPeriod !in 1..12 || course.endPeriod !in 1..12 -> "节次必须在第 1 至 12 节之间"
        course.startPeriod > course.endPeriod -> "结束节次不能早于开始节次"
        totalWeeks !in 1..30 -> "学期周数必须在 1 至 30 周之间"
        course.startWeek !in 1..totalWeeks || course.endWeek !in 1..totalWeeks -> "上课周次必须在第 1 至 $totalWeeks 周之间"
        course.startWeek > course.endWeek -> "结束周不能早于开始周"
        course.weekPattern !in setOf("all", "odd", "even") -> "周次类型必须为每周、单周或双周"
        course.colorIndex !in 0..5 -> "课程颜色必须在 6 种预设颜色中选择"
        (course.startWeek..course.endWeek).none { matchesPattern(course.weekPattern, it) } -> "所选周次范围内没有符合单双周条件的上课周"
        else -> null
    }

    fun settingsValidationError(settings: TermSettings): String? {
        if (settings.name.isBlank()) return "请填写学期名称"
        val start = try {
            if (!Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(settings.startDate)) {
                return "学期开始日期必须使用 YYYY-MM-DD 格式"
            }
            LocalDate.parse(settings.startDate, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            return "学期开始日期无效"
        }
        if (start.dayOfWeek != DayOfWeek.MONDAY) return "学期开始日期应选择第 1 周的周一"
        if (settings.totalWeeks !in 1..30) return "学期周数必须在 1 至 30 周之间"
        if (settings.darkMode !in setOf("system", "light", "dark")) return "外观模式必须为跟随系统、浅色或深色"
        if (settings.periods.size != 12) return "作息时间必须完整包含第 1 至 12 节"
        var previousEnd: LocalTime? = null
        settings.periods.forEachIndexed { index, period ->
            if (!timePattern.matches(period.start) || !timePattern.matches(period.end)) {
                return "第 ${index + 1} 节时间必须使用有效的 HH:mm 格式"
            }
            val begin = LocalTime.parse(period.start)
            val finish = LocalTime.parse(period.end)
            if (!begin.isBefore(finish)) return "第 ${index + 1} 节结束时间必须晚于开始时间"
            if (previousEnd?.isAfter(begin) == true) return "第 ${index + 1} 节与上一节时间重叠"
            previousEnd = finish
        }
        return null
    }

    internal fun matchesPattern(pattern: String, week: Int): Boolean = week >= 1 && when (pattern) {
        "all" -> true
        "odd" -> week % 2 == 1
        "even" -> week % 2 == 0
        else -> false
    }
}
