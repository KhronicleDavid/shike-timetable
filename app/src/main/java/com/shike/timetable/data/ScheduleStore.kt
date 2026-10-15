package com.shike.timetable.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import com.shike.timetable.widget.TodayWidgetUpdates

/** 全部数据只保存在本机；导入成功后由调用者决定是否保存。 */
class ScheduleStore internal constructor(private val preferences: SharedPreferences, private val onChanged: () -> Unit = {}) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("shike_schedule", Context.MODE_PRIVATE),
        { TodayWidgetUpdates.request(context.applicationContext) },
    )

    var lastReadWarning: String? = null
        private set

    fun read(): ScheduleData {
        lastReadWarning = null
        val saved = preferences.getString("schedule_json", null)
        if (saved != null) {
            return try {
                importJson(saved)
            } catch (exception: IllegalArgumentException) {
                // 原始键保持不动，即使用户随后保存新课表，独立备份仍然可以取回。
                preferences.edit().putString("corrupted_backup", saved).apply()
                lastReadWarning = "已保存的课表无法读取：${exception.message}。原始内容已保留备份，暂时显示空课表。"
                ScheduleData(emptyList(), DemoData.settings)
            }
        }
        return DemoData.schedule.also(::save)
    }

    fun save(data: ScheduleData) {
        val json = exportJson(data)
        preferences.edit().putString("schedule_json", json).apply()
        lastReadWarning = null
        onChanged()
    }

    fun exportJson(data: ScheduleData): String = ScheduleJson.exportJson(data)

    fun importJson(text: String): ScheduleData = ScheduleJson.importJson(text)

    fun readCorruptedBackup(): String? = preferences.getString("corrupted_backup", null)
}

/** 单独提供编码器，让导入规则能够在纯 JVM 环境中验证。 */
object ScheduleJson {
    private const val SCHEMA_VERSION = 1
    private val courseKeys = setOf(
        "id", "name", "teacher", "room", "day", "startPeriod", "endPeriod",
        "startWeek", "endWeek", "weekPattern", "colorIndex", "note",
    )
    private val settingsKeys = setOf("name", "startDate", "totalWeeks", "showWeekend", "darkMode", "periods")

    fun exportJson(data: ScheduleData): String {
        validateData(data)
        val settings = data.settings
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("settings", JSONObject()
                .put("name", settings.name)
                .put("startDate", settings.startDate)
                .put("totalWeeks", settings.totalWeeks)
                .put("showWeekend", settings.showWeekend)
                .put("darkMode", settings.darkMode)
                .put("periods", JSONArray().also { periods ->
                    settings.periods.forEach { period ->
                        periods.put(JSONObject().put("start", period.start).put("end", period.end))
                    }
                }))
            .put("courses", JSONArray().also { courses ->
                data.courses.forEach { course ->
                    courses.put(JSONObject()
                        .put("id", course.id)
                        .put("name", course.name)
                        .put("teacher", course.teacher)
                        .put("room", course.room)
                        .put("day", course.day)
                        .put("startPeriod", course.startPeriod)
                        .put("endPeriod", course.endPeriod)
                        .put("startWeek", course.startWeek)
                        .put("endWeek", course.endWeek)
                        .put("weekPattern", course.weekPattern)
                        .put("colorIndex", course.colorIndex)
                        .put("note", course.note))
                }
            })
            .toString(2)
    }

    /** 不进行类型强转、修剪周次或自动修复；错误直接以中文说明。 */
    fun importJson(text: String): ScheduleData {
        if (text.isBlank()) throw IllegalArgumentException("导入文件为空")
        StrictJson.validate(text)
        try {
            val tokener = JSONTokener(text)
            val root = tokener.nextValue() as? JSONObject
                ?: fail("导入文件的最外层必须是 JSON 对象")
            if (tokener.nextClean() != '\u0000') fail("JSON 对象后存在多余内容")
            requireKeys(root, setOf("schemaVersion", "settings", "courses"), "课表")
            if (int(root, "schemaVersion", "文件版本") != SCHEMA_VERSION) {
                fail("暂不支持此课表文件版本")
            }
            val settingObject = objectValue(root, "settings", "学期设置")
            requireKeys(settingObject, settingsKeys, "学期设置")
            val periodArray = arrayValue(settingObject, "periods", "作息时间")
            val periods = (0 until periodArray.length()).map { index ->
                val period = periodArray.opt(index) as? JSONObject ?: fail("第 ${index + 1} 节作息必须是对象")
                requireKeys(period, setOf("start", "end"), "第 ${index + 1} 节作息")
                PeriodTime(string(period, "start", "开始时间"), string(period, "end", "结束时间"))
            }
            val settings = TermSettings(
                name = string(settingObject, "name", "学期名称"),
                startDate = string(settingObject, "startDate", "学期开始日期"),
                totalWeeks = int(settingObject, "totalWeeks", "学期周数"),
                showWeekend = boolean(settingObject, "showWeekend", "显示周末"),
                darkMode = string(settingObject, "darkMode", "外观模式"),
                periods = periods,
            )
            ScheduleRules.settingsValidationError(settings)?.let(::fail)
            val courseArray = arrayValue(root, "courses", "课程列表")
            val courses = (0 until courseArray.length()).map { index ->
                val course = courseArray.opt(index) as? JSONObject ?: fail("第 ${index + 1} 门课程必须是对象")
                val label = "第 ${index + 1} 门课程"
                requireKeys(course, courseKeys, label)
                Course(
                    id = string(course, "id", "$label 标识"),
                    name = string(course, "name", "$label 名称"),
                    teacher = string(course, "teacher", "$label 教师"),
                    room = string(course, "room", "$label 教室"),
                    day = int(course, "day", "$label 星期"),
                    startPeriod = int(course, "startPeriod", "$label 开始节次"),
                    endPeriod = int(course, "endPeriod", "$label 结束节次"),
                    startWeek = int(course, "startWeek", "$label 开始周"),
                    endWeek = int(course, "endWeek", "$label 结束周"),
                    weekPattern = string(course, "weekPattern", "$label 周次类型"),
                    colorIndex = int(course, "colorIndex", "$label 颜色"),
                    note = string(course, "note", "$label 备注"),
                )
            }
            return ScheduleData(courses, settings).also(::validateData)
        } catch (exception: JSONException) {
            throw IllegalArgumentException("课表文件不是有效的 JSON，请检查文件内容", exception)
        }
    }

    private fun validateData(data: ScheduleData) {
        ScheduleRules.settingsValidationError(data.settings)?.let(::fail)
        val ids = mutableSetOf<String>()
        data.courses.forEachIndexed { index, course ->
            ScheduleRules.validationError(course, data.settings.totalWeeks)?.let { error ->
                fail("第 ${index + 1} 门课程：$error")
            }
            if (!ids.add(course.id.lowercase())) fail("第 ${index + 1} 门课程与其他课程标识重复")
        }
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>, label: String) {
        val actual = value.keys().asSequence().toSet()
        val missing = expected - actual
        if (missing.isNotEmpty()) fail("$label 缺少必要字段：${missing.joinToString("、")}")
        val unknown = actual - expected
        if (unknown.isNotEmpty()) fail("$label 存在不支持的字段：${unknown.joinToString("、")}")
    }

    private fun string(value: JSONObject, key: String, label: String): String =
        value.opt(key) as? String ?: fail("$label 必须是文本")

    private fun int(value: JSONObject, key: String, label: String): Int {
        val number = value.opt(key)
        return when (number) {
            is Int -> number
            is Long -> if (number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) number.toInt()
                else fail("$label 数值过大")
            else -> fail("$label 必须是整数")
        }
    }

    private fun boolean(value: JSONObject, key: String, label: String): Boolean =
        value.opt(key) as? Boolean ?: fail("$label 必须是布尔值")

    private fun objectValue(value: JSONObject, key: String, label: String): JSONObject =
        value.opt(key) as? JSONObject ?: fail("$label 必须是对象")

    private fun arrayValue(value: JSONObject, key: String, label: String): JSONArray =
        value.opt(key) as? JSONArray ?: fail("$label 必须是数组")

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
