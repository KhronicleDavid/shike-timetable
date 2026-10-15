package com.shike.timetable.importer

import com.shike.timetable.data.Course
import com.shike.timetable.data.ScheduleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class EamsImportParserTest {
    @Test fun anonymizedRealLayoutPreservesEveryWeekPeriodTeacherAndRoom() {
        val html = javaClass.getResource("/importer/uestc-anonymous-layout.html")!!.readText()
        val result = EamsImportParser.parseHtml(html)
        val expected = mutableSetOf<String>()
        fun add(index: Int, span: Int, course: Int, weeks: Iterable<Int>, rooms: String = "") {
            for (week in weeks) for (period in index % 12 + 1 until index % 12 + 1 + span) {
                expected += "$week|${index / 12 + 1}|$period|验证课程$course|教师$course|$rooms"
            }
        }
        add(12, 2, 1, 1..15, "示例教室1")
        add(24, 2, 2, 1..12, "示例教室2")
        add(48, 4, 3, 8..14, "示例教室3,示例教室4")
        add(72, 2, 4, listOf(15), "示例教室5")
        add(2, 2, 2, 1..12, "示例教室2")
        add(26, 2, 3, 1..14, "示例教室1")
        add(38, 2, 1, (1..4) + (6..14), "示例教室1")
        add(4, 2, 5, 1..13, "示例教室6")
        add(16, 2, 6, 1..9, "示例教室7")
        add(16, 2, 6, 10..11, "示例教室8,示例教室9")
        add(28, 2, 7, 1..12, "示例教室10")
        add(40, 2, 6, (1..4) + (6..8), "示例教室7")
        add(40, 2, 6, 9..10, "示例教室8,示例教室9")
        add(6, 2, 7, 1..12, "示例教室11")
        add(18, 2, 8, 1..7, "示例教室12")
        add(18, 2, 6, 10..11, "示例教室7,示例教室10")
        add(30, 2, 9, 3..18)
        add(42, 2, 8, (1..4) + listOf(6), "示例教室12")
        add(42, 2, 6, 9..10, "示例教室7,示例教室10")
        add(32, 2, 5, 1..13, "示例教室6")
        add(44, 4, 8, 7..8, "示例教室13")
        add(34, 1, 5, 1..12, "示例教室6")
        val actual = (1..30).flatMap { week ->
            ScheduleRules.coursesForWeek(result.courses, week).flatMap { course ->
                (course.startPeriod..course.endPeriod).map { period ->
                    "$week|${course.day}|$period|${course.name}|${course.teacher}|${course.room}"
                }
            }
        }.toSet()
        assertEquals(expected, actual)
        assertEquals(9, result.courses.map { it.name }.distinct().size)
        assertEquals(18, result.maxWeek)
        assertTrue(result.courses.all { ScheduleRules.validationError(it, result.maxWeek) == null })
        assertTrue(result.courses.filter { it.name == "验证课程9" }.all { it.room.isEmpty() })
    }

    @Test fun renderedFixturePreservesCoursesAndExactWeeks() {
        val html = javaClass.getResource("/importer/uestc-rendered.html")!!.readText()
        val result = EamsImportParser.parseHtml(html)
        assertEquals("2026-2027 第一学期", result.suggestedTermName)
        assertEquals("2026-09-07", result.suggestedStartDate)
        assertEquals(11, result.maxWeek)
        assertEquals(setOf(1, 2, 4, 6), activeWeeks(result.courses.filter { it.name == "数据结构" }))
        assertEquals(setOf(7, 9, 11), activeWeeks(result.courses.filter { it.name == "大学英语" }))
        assertTrue(result.courses.filter { it.name == "数据结构" }.all { it.teacher == "示例老师" && it.room == "清水河 主楼 A101" && it.startPeriod == 1 && it.endPeriod == 2 })
        assertTrue(result.courses.all { ScheduleRules.validationError(it, result.maxWeek) == null })
    }

    @Test fun taskActivityFixtureReadsTeacherObjectsAndGridWidth() {
        val html = javaClass.getResource("/importer/uestc-static.html")!!.readText()
        val result = EamsImportParser.parseHtml(html)
        val course = result.courses.single()
        assertEquals("离散数学", course.name)
        assertEquals("示例教师甲,示例教师乙", course.teacher)
        assertEquals("品学楼 A-101", course.room)
        assertEquals(2, course.day)
        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
        assertEquals(setOf(1, 3, 5, 7, 9), activeWeeks(result.courses))
    }

    @Test fun calendarWeekBitmapUsesMarshalBaseline() {
        val bits = bitmap(53, setOf(35, 36, 38, 40))
        val result = EamsImportParser.parseHtml(script(bits, marshal = "36,1,20"))
        assertEquals(setOf(1, 2, 4, 6), activeWeeks(result.courses))
    }

    @Test fun crossYearBitmapUsesSchoolsYearEndShift() {
        val result = EamsImportParser.parseHtml(script(bitmap(53, setOf(1, 2, 3)), marshal = "36,1,20", year = 2026))
        assertEquals(setOf(19, 20), activeWeeks(result.courses))
        val saturday = EamsImportParser.parseHtml(script(bitmap(53, setOf(0, 1)), marshal = "36,1,20", year = 2022))
        assertEquals(setOf(19, 20), activeWeeks(saturday.courses))
    }

    @Test fun rawBitmapWithoutBaselineIsRejected() {
        failsWith("教学周基准", script(bitmap(53, setOf(1, 2)), marshal = null))
    }

    @Test fun renderedCellsTakePriorityOverRawBitmap() {
        val html = script(bitmap(53, setOf(45, 46)), marshal = null) +
            "<td id='TD0_0' rowspan='2' title='示例教师 测试课(C01);(连1-3,教室A)'></td>"
        assertEquals(setOf(1, 2, 3), activeWeeks(EamsImportParser.parseHtml(html).courses))
    }

    @Test fun renderedCellsAlsoBypassUnresolvedDynamicGridCode() {
        val html = script("011", index = "unknownDay*unitCount+0") +
            "<td id='TD0_0' title='示例教师 测试课(C01);(连1-3,教室A)'></td>"
        assertEquals(setOf(1, 2, 3), activeWeeks(EamsImportParser.parseHtml(html).courses))
        failsWith("动态节次", script("011", index = "unknownDay*unitCount+0"))
    }

    @Test fun htmlCannotSilentlyDropAnUnrenderedStaticActivityCell() {
        val html = script("011", index = "0*unitCount+0") + script("011", index = "1*unitCount+0") +
            "<td id='TD0_0' title='示例教师 测试课程(C01);(连1-2,示例教室)'></td>"
        failsWith("尚未完整显示", html)
    }

    @Test fun renderedRowspanCoversEveryConsecutiveStaticActivityPeriod() {
        val html = script("011", index = "0*unitCount+0") + script("011", index = "0*unitCount+1") +
            "<td id='TD0_0' rowspan='2' title='示例教师 测试课程(C01);(连1-2,示例教室)'></td>"
        val result = EamsImportParser.parseHtml(html)
        assertEquals(1, result.courses.size)
        assertEquals(1, result.courses.single().startPeriod)
        assertEquals(2, result.courses.single().endPeriod)
    }

    @Test fun emptyStaticActivityCellDoesNotRequireRenderedCourseContent() {
        val html = script("011", index = "0*unitCount+0") + script("000", index = "1*unitCount+0") +
            "<td id='TD0_0' title='示例教师 测试课程(C01);(连1-2,示例教室)'></td>"
        assertEquals(setOf(1, 2), activeWeeks(EamsImportParser.parseHtml(html).courses))
    }

    @Test fun staticActivitiesOutsideKnownDisplayedWeeksDoNotRequireDomCells() {
        val html = script(bitmap(53, setOf(3, 4)), index = "0*unitCount+0", marshal = "2,3,20", year = 2022) +
            script(bitmap(53, setOf(1, 2)), index = "1*unitCount+0", marshal = "2,3,20", year = 2022) +
            "<td id='TD0_0' title='示例教师 测试课程(C01);(连3-4,示例教室)'></td>"
        val result = EamsImportParser.parseHtml(html)
        assertEquals(setOf(3, 4), activeWeeks(result.courses))
        assertTrue(result.warnings.any { it.contains("第 3 周") })
    }

    @Test fun dynamicActivityMetadataKeepsRenderedDomCompatibility() {
        val html = "<script>var unitCount=12; activity=new TaskActivity('',teachers(),'',courseName(),'',roomName(),weeks()); index=12;</script>" +
            "<td id='TD0_0' title='示例教师 测试课程(C01);(连1-2,示例教室)'></td>"
        assertEquals(setOf(1, 2), activeWeeks(EamsImportParser.parseHtml(html).courses))
    }

    @Test fun meaningfulStaticActivityWeekAndPeriodErrorsRemainVisibleWithDomPresent() {
        val dom = "<td id='TD0_0' title='示例教师 测试课程(C01);(连1-2,示例教室)'></td>"
        failsWith("第 13 节", script("011", index = "0*unitCount+12", unitCount = 13) + dom)
        failsWith("超过 30 周", script(bitmap(53, setOf(31)), marshal = "2,1,40") + dom)
    }

    @Test fun renderedCourseWithoutTeacherCanStillBeImported() {
        val course = EamsImportParser.parseHtml("<td id='TD0_0' title='测试课程(C01);(连1-3,教室A)'></td>").courses.single()
        assertEquals("测试课程", course.name)
        assertEquals("", course.teacher)
    }

    @Test fun onlyCourseCodeSuffixIsRemovedFromCourseName() {
        val html = "<td id='TD0_0' title='教师 程序设计(实验)(DEMO-1);(连1-3,教室A)'></td>"
        assertEquals("程序设计(实验)", EamsImportParser.parseHtml(html).courses.single().name)
        val withoutCode = "<td id='TD0_0' title='教师 程序设计(实验);(连1-3,教室A)'></td>"
        assertEquals("程序设计(实验)", EamsImportParser.parseHtml(withoutCode).courses.single().name)
    }

    @Test fun numericClassroomIsNotMistakenForExcessiveTeachingWeek() {
        val course = EamsImportParser.parseHtml("<td id='TD0_0' title='教师 测试课(C01);(连1-3,101)'></td>").courses.single()
        assertEquals("101", course.room)
        assertEquals(setOf(1, 2, 3), activeWeeks(listOf(course)))
    }

    @Test fun separateScriptsEscapesAndHtmlEntitiesArePreserved() {
        val html = """
            <script>var unitCount=12; var teacher='欧阳\\'示例';</script>
            <script>
            activity=new TaskActivity('',teacher,'C01',"C &amp; D \\u8bfe",'', '教室 \\"A\\"', '0111');
            index=0*unitCount+2; index=0*unitCount+3;
            table0.marshalTable(2,1,20);
            </script>
        """.trimIndent().replace("\\\\", "\\")
        val course = EamsImportParser.parseHtml(html).courses.single()
        assertEquals("欧阳'示例", course.teacher)
        assertEquals("C & D 课", course.name)
        assertEquals("教室 \"A\"", course.room)
        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
    }

    @Test fun identicalCopiesAreDeduplicatedAndNamesHaveStableColors() {
        val one = "<td id='TD0_0' title='教师 课程(C01);(连1-3,A101)'></td>"
        val result = EamsImportParser.parseHtml(one + one + "<td id='TD24_0' title='教师 课程(C01);(连1-3,A101)'></td>")
        assertEquals(2, result.courses.size)
        assertEquals(1, result.courses.map { it.colorIndex }.toSet().size)
        assertEquals(result.courses.map { it.id }, EamsImportParser.parseHtml(one + one + "<td id='TD24_0' title='教师 课程(C01);(连1-3,A101)'></td>").courses.map { it.id })
    }

    @Test fun neighboringPeriodsWithDifferentWeeksRemainSeparate() {
        val html = "<td id='TD0_0' title='教师 课程(C01);(连1-2,A101)'></td>" +
            "<td id='TD1_0' title='教师 课程(C01);(连3-4,A101)'></td>"
        val courses = EamsImportParser.parseHtml(html).courses
        assertEquals(2, courses.size)
        assertTrue(courses.all { it.startPeriod == it.endPeriod })
    }

    @Test fun canceledWeeksOnlyAffectMatchingCourseDayAndPeriod() {
        val html = """
            <td id='TD0_0' rowspan='2' title='教师 课程(C01);(连1-6,A101)'></td>
            <td id='TD0_0' title='教师 课程(C01);(3,停课)'></td>
            <td id='TD12_0' title='教师 课程(C01);(连1-6,A101)'></td>
        """
        val courses = EamsImportParser.parseHtml(html).courses
        assertFalse(ScheduleRules.coursesForWeek(courses.filter { it.day == 1 && it.startPeriod == 1 }, 3).isNotEmpty())
        assertTrue(ScheduleRules.coursesForWeek(courses.filter { it.day == 1 && it.startPeriod == 2 }, 3).isNotEmpty())
        assertTrue(ScheduleRules.coursesForWeek(courses.filter { it.day == 2 }, 3).isNotEmpty())
    }

    @Test fun everyRandomWeekSubsetRoundTripsWithoutAddingOrDroppingWeeks() {
        val random = java.util.Random(1008)
        repeat(200) {
            val selected = (1..30).filter { random.nextBoolean() }.toSet().ifEmpty { setOf(30) }
            val result = EamsImportParser.parseHtml(script(bitmap(53, selected), marshal = "2,1,30"))
            assertEquals(selected, activeWeeks(result.courses))
            assertTrue(result.courses.all { ScheduleRules.validationError(it, 30) == null })
        }
    }

    @Test fun chineseWeeksRespectAbsoluteParityAndSpaceSeparatedSingleWeeks() {
        val html = "<td id='TD0_0' title='教师 课程(C01);(单2-7 双3-8 10 13,示例教室)'></td>"
        assertEquals(setOf(3, 5, 7, 4, 6, 8, 10, 13), activeWeeks(EamsImportParser.parseHtml(html).courses))
    }

    @Test fun firstBitDoesNotShiftLateStartingCourses() {
        val result = EamsImportParser.parseHtml(script(bitmap(53, setOf(7, 8, 9))))
        assertEquals(setOf(7, 8, 9), activeWeeks(result.courses))
    }

    @Test fun periodBeyondTwelveCannotBeSilentlyDropped() {
        failsWith("第 13 节", script("011", index = "0*unitCount+12", unitCount = 13))
    }

    @Test fun weekBeyondThirtyCannotBeSilentlyDropped() {
        failsWith("超过 30 周", script(bitmap(53, setOf(31)), marshal = "2,1,40"))
        failsWith("超过 30 周", "<td id='TD0_0' title='教师 课程(C01);(连1-31,A101)'></td>")
    }

    @Test fun commentsAndCourseTextNeverBecomeExecutableStatements() {
        val html = """
            <script>
            var unitCount=12;
            // activity=new TaskActivity('', '坏数据', '', '错误课', '', '', '011'); index=999;
            /* new TaskActivity('','','','','','','011') */
            activity=new TaskActivity('', '教师', 'C01', 'new TaskActivity(恶意文本)', '', 'A101', '011');
            index=0*unitCount+0;
            table0.marshalTable(2,1,20);
            </script>
        """
        assertEquals("new TaskActivity(恶意文本)", EamsImportParser.parseHtml(html).courses.single().name)
    }

    @Test fun unsupportedDynamicValuesFailWithSpecificMessage() {
        failsWith("授课教师", "<script>activity=new TaskActivity('', fetchSecret(), 'C01', '课程', '', 'A101','011'); index=0; table0.marshalTable(2,1,20);</script>")
    }

    @Test fun loginPageCannotLookLikeAnEmptySuccessfulImport() {
        failsWith("请先登录", "<html><title>电子科技大学统一身份认证</title><form><input type='password'></form></html>")
    }

    @Test fun renderedRowCannotCrossTheDayBoundary() {
        failsWith("星期边界", "<td id='TD11_0' rowspan='2' title='教师 课程(C01);(连1-3,A101)'></td>")
    }

    @Test fun selectedPartialTeachingRangeIsVisibleInWarnings() {
        val result = EamsImportParser.parseHtml(script(bitmap(53, setOf(1, 2, 3, 4, 5)), marshal = "2,3,20"))
        assertEquals(setOf(3, 4, 5), activeWeeks(result.courses))
        assertTrue(result.warnings.any { it.contains("第 3 周") })
    }

    @Test fun wakeUpExportUsesAlreadyMarshaledTeachingWeeks() {
        val root = exportJson()
        root.getJSONArray("marshalContents").put(14, "示例教师 测试课程(C01)<br>(单3-9,清水河101,清水河102)")
        val course = EamsImportParser.parseHtml(root.toString()).courses.single()
        assertEquals(2, course.day)
        assertEquals(3, course.startPeriod)
        assertEquals("清水河101,清水河102", course.room)
        assertEquals(setOf(3, 5, 7, 9), activeWeeks(listOf(course)))
        assertEquals(course, EamsImportParser.parseHtml("\uFEFF" + root.toString()).courses.single())
    }

    @Test fun wakeUpExportRawWeeksRequireExplicitBaseline() {
        val root = exportJson()
        root.getJSONArray("activities").getJSONArray(0).put(exportActivity(bitmap(53, setOf(7, 9))))
        failsWith("教学周基准", root.toString())
        root.put("marshalArgs", JSONArray(listOf(2, 1, 20)))
        assertEquals(setOf(7, 9), activeWeeks(EamsImportParser.parseHtml(root.toString()).courses))
    }

    @Test fun wakeUpExportAcceptsCorrectedValidWeeksSpelling() {
        val root = exportJson()
        val activity = exportActivity("单1-7")
        activity.remove("vaildWeeks")
        activity.put("validWeeks", "单1-7")
        root.getJSONArray("activities").getJSONArray(0).put(activity)
        assertEquals(setOf(1, 3, 5, 7), activeWeeks(EamsImportParser.parseHtml(root.toString()).courses))
    }

    @Test fun wakeUpExportRejectsMixedTypesAndIncompleteGrid() {
        val wrongTeacher = exportJson()
        wrongTeacher.getJSONArray("activities").getJSONArray(0).put(exportActivity("连1-7").put("teacherName", 1))
        failsWith("teacherName 必须是文本", wrongTeacher.toString())
        failsWith("必须是整数", exportJson().put("unitCount", "12").toString())
        failsWith("84 个节次", exportJson().put("activities", JSONArray()).toString())
        failsWith("单元格必须是课程数组", exportJson().also { it.getJSONArray("activities").put(0, JSONObject()) }.toString())
    }

    @Test fun wakeUpExportRejectsUnsupportedPeriodsAndNonStandardJson() {
        val root = exportJson(13)
        root.getJSONArray("activities").getJSONArray(12).put(exportActivity("连1-7"))
        failsWith("第 13 节", root.toString())
        failsWith("双引号", "{unitCounts:84}")
    }

    @Test fun wakeUpExportRejectsConflictingWeekFields() {
        val root = exportJson()
        root.getJSONArray("activities").getJSONArray(0).put(exportActivity("连1-7").put("validWeeks", "连2-7"))
        failsWith("相互矛盾", root.toString())
    }

    @Test fun wakeUpExportCannotSilentlyDropAnUnmarshaledActiveCell() {
        val root = exportJson()
        root.getJSONArray("activities").getJSONArray(0).put(exportActivity("连1-7"))
        root.getJSONArray("activities").getJSONArray(1).put(exportActivity("连1-7"))
        root.getJSONArray("marshalContents").put(0, "示例教师 测试课程(C01)<br>(连1-7,示例教室)")
        failsWith("已显示周次不完整", root.toString())
    }

    private fun exportJson(units: Int = 12): JSONObject = JSONObject().put("unitCounts", units * 7).put("year", 2026)
        .put("activities", JSONArray((0 until units * 7).map { JSONArray() }))
        .put("marshalContents", JSONArray((0 until units * 7).map { JSONObject.NULL }))

    private fun exportActivity(weeks: String): JSONObject = JSONObject().put("teacherId", "T01").put("teacherName", "示例教师")
        .put("courseId", "C01").put("courseName", "测试课程").put("roomId", "R01").put("roomName", "示例教室").put("vaildWeeks", weeks)

    private fun activeWeeks(courses: List<Course>): Set<Int> = (1..30).filter { ScheduleRules.coursesForWeek(courses, it).isNotEmpty() }.toSet()
    private fun bitmap(length: Int, indices: Set<Int>): String = (0 until length).joinToString("") { if (it in indices) "1" else "0" }
    private fun script(bits: String, marshal: String? = "2,1,20", year: Int = 2026, index: String = "0*unitCount+0", unitCount: Int = 12): String = """
        <script>
        var unitCount=$unitCount;
        var table0=new CourseTable($year,unitCount*7);
        activity=new TaskActivity('', '示例教师', 'C01', '测试课程', '', '示例教室', '$bits');
        index=$index;
        ${marshal?.let { "table0.marshalTable($it);" }.orEmpty()}
        </script>
    """
    private fun failsWith(message: String, html: String) {
        try { EamsImportParser.parseHtml(html); throw AssertionError("应拒绝此页面：$message") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message)) }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message)) }
    }
}
