package com.shike.timetable.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScheduleJsonTest {
    @Test fun exportedDataRoundTripsIncludingChineseAndEscapedText() {
        val data = DemoData.schedule.copy(courses = DemoData.courses.map {
            it.copy(note = "第一行\n第二行 \"引号\" \\ 路径")
        })
        assertEquals(data, ScheduleJson.importJson(ScheduleJson.exportJson(data)))
        assertEquals(ScheduleData(emptyList(), DemoData.settings), ScheduleJson.importJson(
            ScheduleJson.exportJson(ScheduleData(emptyList(), DemoData.settings)),
        ))
    }

    @Test fun importDoesNotTruncateWeeksToFitTerm() {
        val json = validJson()
        json.getJSONArray("courses").getJSONObject(0).put("endWeek", 21)
        expectError("第 1 至 20 周") { ScheduleJson.importJson(json.toString()) }
    }

    @Test fun importRejectsFractionalOrStringNumbersAndStringBoolean() {
        val fractional = validJson()
        fractional.getJSONArray("courses").getJSONObject(0).put("day", 1.5)
        expectError("必须是整数") { ScheduleJson.importJson(fractional.toString()) }
        val stringNumber = validJson()
        stringNumber.getJSONObject("settings").put("totalWeeks", "20")
        expectError("必须是整数") { ScheduleJson.importJson(stringNumber.toString()) }
        val stringBoolean = validJson()
        stringBoolean.getJSONObject("settings").put("showWeekend", "false")
        expectError("必须是布尔值") { ScheduleJson.importJson(stringBoolean.toString()) }
    }

    @Test fun importRejectsDuplicateIdentityWithoutRemovingCourses() {
        val json = validJson()
        val courses = json.getJSONArray("courses")
        courses.getJSONObject(1).put("id", courses.getJSONObject(0).getString("id").uppercase())
        expectError("标识重复") { ScheduleJson.importJson(json.toString()) }
    }

    @Test fun importRejectsMissingFieldsUnknownVersionsAndInvalidTimes() {
        val missing = validJson()
        missing.getJSONArray("courses").getJSONObject(0).remove("day")
        expectError("缺少必要字段") { ScheduleJson.importJson(missing.toString()) }
        val version = validJson().put("schemaVersion", 2)
        expectError("不支持") { ScheduleJson.importJson(version.toString()) }
        val times = validJson()
        times.getJSONObject("settings").getJSONArray("periods").getJSONObject(0).put("start", "25:00")
        expectError("HH:mm") { ScheduleJson.importJson(times.toString()) }
    }

    @Test fun importRejectsNonstandardJsonAndTrailingContent() {
        expectError("双引号") { ScheduleJson.importJson("{'schemaVersion':1}") }
        expectError("双引号") { ScheduleJson.importJson("{schemaVersion:1}") }
        expectError("重复字段") { ScheduleJson.importJson("{\"schemaVersion\":1,\"schemaVersion\":1}") }
        expectError("重复字段") { ScheduleJson.importJson("{\"name\":1,\"na\\u006de\":2}") }
        expectError("双引号") { ScheduleJson.importJson("{\"schemaVersion\":1,}") }
        expectError("多余内容") { ScheduleJson.importJson(ScheduleJson.exportJson(DemoData.schedule) + " {}") }
        expectError("为空") { ScheduleJson.importJson("  ") }
    }

    @Test fun importAllowsScheduleConflictsForUserReview() {
        val overlapping = DemoData.courses.first().copy(id = java.util.UUID.randomUUID().toString(), name = "另一门课")
        val data = DemoData.schedule.copy(courses = DemoData.courses + overlapping)
        assertEquals(data, ScheduleJson.importJson(ScheduleJson.exportJson(data)))
        assertTrue(ScheduleRules.conflicts(overlapping, DemoData.courses))
    }

    private fun validJson(): JSONObject = JSONObject(ScheduleJson.exportJson(DemoData.schedule))

    private fun expectError(fragment: String, action: () -> Unit) {
        try {
            action()
            fail("预期导入失败：$fragment")
        } catch (error: IllegalArgumentException) {
            assertTrue("实际错误：${error.message}", error.message.orEmpty().contains(fragment))
        }
    }
}
