package com.shike.timetable.importer

import com.shike.timetable.data.Course
import com.shike.timetable.data.StrictJson
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SchoolImportResult(
    val courses: List<Course>,
    val suggestedTermName: String?,
    val suggestedStartDate: String?,
    val maxWeek: Int,
    val warnings: List<String>,
    val sourceLabel: String = "电子科技大学",
)

/**
 * 只读取 HTML / JavaScript 中的静态数据，不执行网页脚本。
 *
 * EAMS 的原始周占用串可以使用年历周；教学周必须根据 marshalTable 的参数转换。
 * 已渲染 TD 的 title 使用学校脚本计算好的教学周，因此优先读取它。
 */
object EamsImportParser {
    private const val MAX_HTML_LENGTH = 4 * 1024 * 1024
    private const val MAX_PERIOD = 12
    private const val MAX_WEEK = 30
    private val noTable = "未找到课表数据，请先登录教务系统，选择学期并打开「我的课表」后再导入。"

    fun parseHtml(html: String): SchoolImportResult {
        require(html.length <= MAX_HTML_LENGTH) { "课表页面过大，请仅导入「我的课表」页面。" }
        val content = html.trimStart().removePrefix("\uFEFF").trimStart()
        if (content.startsWith("{")) return parseExportJson(content)
        val scripts = Regex("<script\\b[^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
        val tokens = JsScanner(if (scripts.isEmpty()) html.takeIf { !it.trimStart().startsWith("<") }.orEmpty() else scripts.joinToString("\n")).scan()
        val context = ScriptContext(tokens)
        val warnings = mutableListOf<String>()
        val domSlots = parseRenderedCells(html, context.unitCount)
        val slots = if (domSlots.isNotEmpty()) {
            context.requireRenderedCoverage(domSlots)
            context.partialRangeWarning()?.let { warnings += it }
            domSlots
        } else context.parseActivities(warnings)
        require(slots.isNotEmpty()) { noTable }
        val courses = buildCourses(slots)
        require(courses.isNotEmpty()) { "当前课表没有可导入的上课安排，请确认学期与教学周范围。" }
        val termName = selectedTermName(html) ?: context.stringVariable("semesterName", "termName")
        val startDate = context.stringVariable("firstMonday", "firstWeekMonday", "semesterStartDate")
            ?.let(::mondayDate)
        return SchoolImportResult(
            courses = courses,
            suggestedTermName = termName,
            suggestedStartDate = startDate,
            maxWeek = courses.maxOf { it.endWeek },
            warnings = warnings.distinct(),
        )
    }

    /** WakeUp 树维导出的 JSON.stringify(table0)，以及带明确 marshal 参数的静态导出。 */
    private fun parseExportJson(text: String): SchoolImportResult {
        StrictJson.validate(text)
        val root = JSONObject(text)
        fun integer(objectValue: JSONObject, key: String, required: Boolean = true): Int? {
            if (!objectValue.has(key)) {
                require(!required) { "教务课表文件缺少 $key 字段。" }
                return null
            }
            val value = objectValue.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) {
                "教务课表文件的 $key 必须是整数。"
            }
            return value.toInt()
        }
        fun string(objectValue: JSONObject, key: String, required: Boolean = true): String? {
            if (!objectValue.has(key)) {
                require(!required) { "教务课表文件缺少 $key 字段。" }
                return null
            }
            val value = objectValue.get(key)
            require(value is String) { "教务课表文件的 $key 必须是文本。" }
            return decodeEntities(value).trim()
        }
        val unitCounts = integer(root, "unitCounts", false)
        val unitCount = integer(root, "unitCount", false) ?: unitCounts?.takeIf { it % 7 == 0 }?.div(7)
            ?: error("教务课表文件缺少有效的每天节次数。")
        require(unitCount in 1..60) { "教务课表文件的每天节次数无效。" }
        val cellCount = unitCount * 7
        require(unitCounts == null || unitCounts == cellCount) { "教务课表文件的节次总数与每天节次数不一致。" }
        val activities = root.opt("activities")
        require(activities is JSONArray && activities.length() == cellCount) { "教务课表文件必须包含完整的 $cellCount 个节次单元格。" }
        for (index in 0 until cellCount) {
            val cell = activities.get(index)
            require(cell is JSONArray) { "教务课表文件第 ${index + 1} 个单元格必须是课程数组。" }
            for (item in 0 until cell.length()) {
                val activity = cell.get(item)
                require(activity is JSONObject) { "教务课表文件包含无效课程对象。" }
                string(activity, "courseName")
                string(activity, "teacherName")
                string(activity, "roomName")
                val weekKey = if (activity.has("vaildWeeks")) "vaildWeeks" else "validWeeks"
                string(activity, weekKey)
                if (activity.has("vaildWeeks") && activity.has("validWeeks")) {
                    require(string(activity, "vaildWeeks") == string(activity, "validWeeks")) { "教务课表文件包含相互矛盾的周次字段。" }
                }
            }
        }
        val warnings = mutableListOf<String>()
        integer(root, "startWeek", false)?.takeIf { it > 1 }?.let {
            warnings += "当前教务页面从第 $it 周开始展示，请确认已选择完整学期。"
        }
        val slots = mutableListOf<Slot>()
        val marshaled = root.opt("marshalContents")
        if (marshaled != null && marshaled != JSONObject.NULL) {
            require(marshaled is JSONArray && marshaled.length() == cellCount) { "教务课表文件的已显示周次单元格数量不完整。" }
            val rendered = StringBuilder()
            for (index in 0 until cellCount) {
                val value = marshaled.get(index)
                require(value == JSONObject.NULL || value is String) { "教务课表文件的已显示周次必须是文本。" }
                if (value == JSONObject.NULL || (value is String && value.isBlank())) {
                    val cell = activities.getJSONArray(index)
                    for (item in 0 until cell.length()) {
                        val activity = cell.getJSONObject(item)
                        val weekKey = if (activity.has("vaildWeeks")) "vaildWeeks" else "validWeeks"
                        val weeks = string(activity, weekKey)!!
                        if (weeks.isNotBlank() && (!Regex("[01]+").matches(weeks) || weeks.contains('1'))) {
                            require(!marshaledHasAnyContent(marshaled)) {
                                "教务课表文件的已显示周次不完整，请在教务系统显示全部教学周后重新导出。"
                            }
                        }
                    }
                }
                if (value is String && value.isNotBlank()) {
                    rendered.append("<td id='TD${index}_0' title='")
                        .append(value.replace("&", "&amp;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;"))
                        .append("'></td>")
                }
            }
            if (rendered.isNotEmpty()) slots += parseRenderedCells(rendered.toString(), unitCount)
        }
        if (slots.isEmpty()) {
            val bounds = if (root.has("marshalArgs")) {
                val args = root.get("marshalArgs")
                require(args is JSONArray && args.length() == 3) { "教务课表文件的 marshalArgs 必须包含 3 个整数。" }
                (0..2).map { index ->
                    val value = args.get(index)
                    require(value is Number && value.toDouble() == value.toInt().toDouble()) { "教务课表文件的 marshalArgs 必须包含整数。" }
                    value.toInt()
                }
            } else if (root.has("from") || root.has("startWeek") || root.has("endWeek")) {
                listOf(integer(root, "from")!!, integer(root, "startWeek")!!, integer(root, "endWeek")!!)
            } else null
            val year = integer(root, "year", false)
            for (index in 0 until cellCount) {
                val cell = activities.getJSONArray(index)
                for (item in 0 until cell.length()) {
                    val activity = cell.getJSONObject(item)
                    val name = courseNameAndCode(string(activity, "courseName")!!).first
                    require(name.isNotBlank()) { "教务课表文件包含空课程名称。" }
                    val weekKey = if (activity.has("vaildWeeks")) "vaildWeeks" else "validWeeks"
                    val rawWeeks = string(activity, weekKey)!!
                    val weeks = if (Regex("[01]+").matches(rawWeeks)) bitmapWeeks(rawWeeks, bounds, year, warnings) else chineseWeeks(rawWeeks)
                    val day = index / unitCount + 1
                    val period = index % unitCount + 1
                    validateSlot(day, period, weeks)
                    if (weeks.isNotEmpty()) slots += Slot(string(activity, "courseId", false).orEmpty(), name, string(activity, "teacherName")!!, string(activity, "roomName")!!, day, period, weeks)
                }
            }
        }
        val courses = buildCourses(slots)
        require(courses.isNotEmpty()) { "教务课表文件没有可导入的上课安排，请确认已导出对应学期的课表。" }
        return SchoolImportResult(
            courses = courses,
            suggestedTermName = string(root, "semesterName", false) ?: string(root, "termName", false),
            suggestedStartDate = string(root, "firstMonday", false)?.let(::mondayDate),
            maxWeek = courses.maxOf { it.endWeek },
            warnings = warnings.distinct(),
        )
    }

    private fun marshaledHasAnyContent(contents: JSONArray): Boolean = (0 until contents.length()).any {
        (contents.get(it) as? String)?.isNotBlank() == true
    }

    private fun bitmapWeeks(raw: String, bounds: List<Int>?, tableYear: Int?, warnings: MutableList<String>): Set<Int> {
        require(raw.length <= 106) { "教务课表的原始周次位图长度异常。" }
        val baseline = bounds ?: error("课表使用原始周次位图，但缺少教学周基准。请打开已显示课程的「我的课表」页面后再导入，或导出含 marshalContents 的教务课表文件。")
        val (from, start, end) = baseline
        require(from in 1..54 && start >= 1 && end >= start && end <= 60) { "教务课表的教学周基准无效。" }
        if (start > 1) warnings += "当前教务页面从第 $start 周开始展示，请确认已选择完整学期。"
        val validStart = from + start - 2
        var bits = raw
        if (bits.take(validStart).contains('1') && !bits.drop(validStart).contains('1')) {
            val year = tableYear ?: error("跨年课表缺少年份，无法确定教学周，请打开已显示课程的课表页面后重试。")
            require(year in 1900..9999) { "教务课表年份无效。" }
            if (LocalDate.of(year, 12, 31).dayOfWeek != DayOfWeek.SATURDAY) {
                require(bits.length == 53) { "跨年课表周次位图长度异常，无法完整导入。" }
                bits = bits.substring(1, 53) + "0"
            }
        }
        if (from > 1 && bits.take(from - 1).contains('1')) bits += bits.take(from - 1)
        val weeks = bits.indices.filter { bits[it] == '1' }.map { it - from + 2 }
            .filter { it in start..end }.toSortedSet()
        require(weeks.all { it <= MAX_WEEK }) { "教务课表包含超过 $MAX_WEEK 周的安排，目前无法完整导入。" }
        return weeks
    }

    private data class Slot(
        val courseId: String,
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val period: Int,
        val weeks: Set<Int>,
    )

    private data class Identity(val courseId: String, val name: String, val teacher: String, val day: Int)
    private data class Cell(val identity: Identity, val room: String, val period: Int)
    private data class WeekRange(val first: Int, val last: Int, val pattern: String)

    private fun buildCourses(slots: List<Slot>): List<Course> {
        val canceled = mutableMapOf<Pair<Identity, Int>, MutableSet<Int>>()
        val normal = linkedMapOf<Cell, MutableSet<Int>>()
        slots.forEach { slot ->
            val identity = Identity(slot.courseId, slot.name, slot.teacher, slot.day)
            if (slot.room.trim() == "停课") {
                canceled.getOrPut(identity to slot.period) { sortedSetOf() }.addAll(slot.weeks)
            } else {
                normal.getOrPut(Cell(identity, slot.room, slot.period)) { sortedSetOf() }.addAll(slot.weeks)
            }
        }
        normal.forEach { (cell, weeks) -> weeks.removeAll(canceled[cell.identity to cell.period].orEmpty()) }
        val grouped = normal.filterValues { it.isNotEmpty() }.entries.groupBy {
            Triple(it.key.identity, it.key.room, it.value.toList())
        }
        val courses = mutableListOf<Course>()
        grouped.forEach { (key, entries) ->
            val periods = entries.map { it.key.period }.distinct().sorted()
            var begin = 0
            while (begin < periods.size) {
                var end = begin
                while (end + 1 < periods.size && periods[end + 1] == periods[end] + 1) end++
                splitWeeks(key.third).forEach { weeks ->
                    val identity = key.first
                    val stableKey = listOf(identity, key.second, periods[begin], periods[end], weeks).joinToString("|")
                    courses += Course(
                        id = UUID.nameUUIDFromBytes(stableKey.toByteArray(Charsets.UTF_8)).toString(),
                        name = identity.name,
                        teacher = identity.teacher,
                        room = key.second,
                        day = identity.day,
                        startPeriod = periods[begin],
                        endPeriod = periods[end],
                        startWeek = weeks.first,
                        endWeek = weeks.last,
                        weekPattern = weeks.pattern,
                        colorIndex = Math.floorMod(identity.name.hashCode(), 6),
                        note = "从电子科技大学教务系统导入",
                    )
                }
                begin = end + 1
            }
        }
        return courses.sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name }, { it.startWeek }, { it.room }))
    }

    /** 最少区间覆盖实际教学周；每个区间都必须精确匹配，不填充缺失周。 */
    private fun splitWeeks(weeks: List<Int>): List<WeekRange> {
        val best = arrayOfNulls<List<WeekRange>>(weeks.size + 1)
        best[weeks.size] = emptyList()
        for (i in weeks.indices.reversed()) {
            var answer: List<WeekRange>? = null
            for (step in listOf(1, 2)) {
                var j = i
                while (j < weeks.size && (j == i || weeks[j] - weeks[j - 1] == step)) {
                    val pattern = if (step == 1 || j == i) "all" else if (weeks[i] % 2 == 1) "odd" else "even"
                    val candidate = listOf(WeekRange(weeks[i], weeks[j], pattern)) + best[j + 1]!!
                    if (answer == null || candidate.size < answer.size ||
                        (candidate.size == answer.size && candidate[0].last > answer[0].last)
                    ) answer = candidate
                    j++
                }
            }
            best[i] = answer
        }
        return best[0].orEmpty()
    }

    private fun validateSlot(day: Int, period: Int, weeks: Set<Int>) {
        require(day in 1..7) { "教务课表包含无法识别的星期（$day），请检查页面格式。" }
        require(period in 1..MAX_PERIOD) { "教务课表包含第 $period 节，目前支持第 1 至 $MAX_PERIOD 节，无法完整导入。" }
        require(weeks.all { it in 1..MAX_WEEK }) { "教务课表包含超过 $MAX_WEEK 周的安排，目前无法完整导入。" }
    }

    private fun parseRenderedCells(html: String, unitCount: Int): List<Slot> {
        val slots = mutableListOf<Slot>()
        tags(html, "td").forEach { attributes ->
            val id = attributes["id"] ?: return@forEach
            val match = Regex("TD(\\d+)(?:_0)?", RegexOption.IGNORE_CASE).matchEntire(id) ?: return@forEach
            val title = attributes["title"]?.trim().orEmpty()
            if (title.isEmpty()) return@forEach
            val index = match.groupValues[1].toIntOrNull() ?: error("课表节次索引过大。")
            val span = (attributes["rowspan"] ?: attributes["colspan"])?.toIntOrNull() ?: 1
            require(span >= 1) { "教务课表的连续节次无效。" }
            val day = index / unitCount + 1
            val firstPeriod = index % unitCount + 1
            require(firstPeriod + span - 1 <= unitCount) { "教务课表的连续节次跨越了星期边界。" }
            val parts = title.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), ";")
                .split(';', '；').map { it.trim() }.filter { it.isNotEmpty() }
            var course: Triple<String, String, String>? = null
            parts.forEach { part ->
                if (part.startsWith("(") || part.startsWith("（")) {
                    val info = course ?: error("教务课表课程标题不完整，无法完整导入。")
                    require(part.endsWith(")") || part.endsWith("）")) { "教务课表周次格式不完整。" }
                    val weekRoom = part.substring(1, part.length - 1)
                    // 学校 marshal 周次中的逗号后跟数字/连/单/双；教室前的逗号后是地点文本。
                    val roomDelimiter = Regex("[,，](?=\\s*[^\\s\\d单双连,，])").find(weekRoom)
                        ?: Regex("[,，](?=\\s*\\d+\\s*$)").findAll(weekRoom).lastOrNull()?.takeIf {
                            weekRoom.substring(it.range.first + 1).trim().toIntOrNull()?.let { roomNumber -> roomNumber > MAX_WEEK } == true
                        }
                    val rawWeeks = roomDelimiter?.let { weekRoom.substring(0, it.range.first) } ?: weekRoom
                    val room = roomDelimiter?.let { weekRoom.substring(it.range.first + 1).trim() }.orEmpty()
                    val weeks = chineseWeeks(rawWeeks)
                    for (period in firstPeriod until firstPeriod + span) {
                        validateSlot(day, period, weeks)
                        if (weeks.isNotEmpty()) slots += Slot(info.third, info.second, info.first, room, day, period, weeks)
                    }
                } else {
                    val coursePart = Regex("^(.*?)\\s+(.+)$").matchEntire(part)
                    if (coursePart != null) {
                        val (name, code) = courseNameAndCode(coursePart.groupValues[2])
                        course = Triple(coursePart.groupValues[1].trim(), name, code)
                    } else {
                        val (name, code) = courseNameAndCode(part)
                        course = Triple("", name, code)
                    }
                }
            }
            require(course != null && parts.any { it.startsWith("(") || it.startsWith("（") }) {
                "教务课表课程标题缺少周次，无法完整导入。"
            }
        }
        return slots
    }

    /** 末尾课程代码用于辨别课程；「(实验)」等课程名称组成部分保持原样。 */
    private fun courseNameAndCode(raw: String): Pair<String, String> {
        val text = raw.trim()
        val match = Regex("^(.+)\\(([A-Za-z0-9][A-Za-z0-9._-]*)\\)$").matchEntire(text)
        if (match != null && match.groupValues[2].any { it.isDigit() }) {
            return match.groupValues[1].trim() to match.groupValues[2]
        }
        return text to ""
    }

    private fun chineseWeeks(raw: String): Set<Int> {
        val normalized = raw.replace("周", "").trim().replace('，', ',').replace('、', ',')
            .replace('—', '-').replace('–', '-').replace('~', '-').replace('至', '-')
            .replace(Regex("\\s*-\\s*"), "-").replace(Regex("([单双连])\\s+(?=\\d)"), "$1")
            .replace(Regex("\\s+"), ",")
        require(normalized.isNotEmpty()) { "教务课表缺少有效教学周。" }
        val weeks = sortedSetOf<Int>()
        var offset = 0
        val pattern = Regex("([单双连]?)(\\d+)(?:-(\\d+))?")
        while (offset < normalized.length) {
            if (normalized[offset] == ',') { offset++; continue }
            val match = pattern.find(normalized, offset)
            require(match != null && match.range.first == offset) { "无法识别教务周次「$raw」，请检查课表格式。" }
            val start = match.groupValues[2].toIntOrNull() ?: error("教务周次数字过大。")
            val end = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: start
            require(start in 1..MAX_WEEK && end in start..MAX_WEEK) { "教务课表包含无效周次或超过 $MAX_WEEK 周的安排，目前无法完整导入。" }
            (start..end).filterTo(weeks) { week ->
                when (match.groupValues[1]) { "单" -> week % 2 == 1; "双" -> week % 2 == 0; else -> true }
            }
            offset = match.range.last + 1
        }
        require(weeks.isNotEmpty()) { "教务课表的单双周范围没有有效上课周。" }
        return weeks
    }

    private fun selectedTermName(html: String): String? {
        Regex("<select\\b([^>]*)>([\\s\\S]*?)</select\\s*>", RegexOption.IGNORE_CASE).findAll(html).forEach { select ->
            val attr = attributes(select.groupValues[1])
            if (attr["name"] != "semester.id" && !attr["id"].orEmpty().contains("semester", true)) return@forEach
            Regex("<option\\b([^>]*)>([\\s\\S]*?)</option\\s*>", RegexOption.IGNORE_CASE).findAll(select.groupValues[2]).forEach { option ->
                if (attributes(option.groupValues[1]).containsKey("selected")) {
                    return decodeEntities(option.groupValues[2].replace(Regex("<[^>]+>"), "")).trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    private fun mondayDate(value: String): String? = runCatching {
        val normalized = if (Regex("\\d{8}").matches(value)) "${value.take(4)}-${value.substring(4, 6)}-${value.takeLast(2)}" else value
        LocalDate.parse(normalized).takeIf { it.dayOfWeek == DayOfWeek.MONDAY }?.toString()
    }.getOrNull()

    private fun tags(html: String, tag: String): Sequence<Map<String, String>> = sequence {
        val opener = Regex("<$tag\\b", RegexOption.IGNORE_CASE)
        var offset = 0
        while (offset < html.length) {
            val found = opener.find(html, offset) ?: break
            var end = found.range.last + 1
            var quote: Char? = null
            while (end < html.length) {
                val c = html[end]
                if (quote != null) { if (c == quote) quote = null }
                else if (c == '\'' || c == '"') quote = c
                else if (c == '>') break
                end++
            }
            if (end == html.length) break
            yield(attributes(html.substring(found.range.last + 1, end)))
            offset = end + 1
        }
    }

    private fun attributes(text: String): Map<String, String> = Regex(
        "([\\w.:-]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?"
    ).findAll(text).associate { match ->
        match.groupValues[1].lowercase() to decodeEntities(match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty())
    }

    private fun decodeEntities(text: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[A-Za-z]+);").replace(text) { match ->
        val name = match.groupValues[1]
        val code = when {
            name.startsWith("#x", true) -> name.substring(2).toIntOrNull(16)
            name.startsWith("#") -> name.substring(1).toIntOrNull()
            else -> null
        }
        if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) String(Character.toChars(code))
        else when (name) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            "nbsp", "ensp", "emsp" -> " "; "middot" -> "·"; "ndash" -> "–"; "mdash" -> "—"
            else -> match.value
        }
    }

    private data class Token(val text: String, val string: Boolean = false)

    /** 字符串、注释和标识符分开读取，避免把课程名中的 JavaScript 文本误识别为代码。 */
    private class JsScanner(private val source: String) {
        private var pos = 0
        fun scan(): List<Token> {
            val tokens = mutableListOf<Token>()
            while (pos < source.length) {
                val c = source[pos]
                when {
                    c.isWhitespace() -> pos++
                    source.startsWith("//", pos) -> { pos = source.indexOf('\n', pos).takeIf { it >= 0 } ?: source.length }
                    source.startsWith("/*", pos) -> { pos = source.indexOf("*/", pos + 2).takeIf { it >= 0 }?.plus(2) ?: source.length }
                    c == '\'' || c == '"' -> tokens += Token(readString(c), true)
                    c == '`' -> { readString(c); tokens += Token("<dynamic-template>") }
                    c.isLetterOrDigit() || c == '_' || c == '$' -> {
                        val begin = pos++
                        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '$')) pos++
                        tokens += Token(source.substring(begin, pos))
                    }
                    else -> { tokens += Token(c.toString()); pos++ }
                }
            }
            return tokens
        }
        private fun readString(quote: Char): String {
            pos++
            val result = StringBuilder()
            while (pos < source.length) {
                val c = source[pos++]
                if (c == quote) return result.toString()
                if (c != '\\') { result.append(c); continue }
                require(pos < source.length) { "课表页面中的字符串不完整。" }
                when (val escaped = source[pos++]) {
                    'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                    'b' -> result.append('\b'); 'f' -> result.append('\u000c'); 'v' -> result.append('\u000b')
                    '\n' -> Unit
                    '\r' -> if (pos < source.length && source[pos] == '\n') pos++
                    'u', 'x' -> {
                        val size = if (escaped == 'u') 4 else 2
                        require(pos + size <= source.length) { "课表页面中的转义字符不完整。" }
                        val number = source.substring(pos, pos + size).toIntOrNull(16)
                            ?: error("课表页面中的转义字符无效。")
                        result.append(number.toChar()); pos += size
                    }
                    else -> result.append(escaped)
                }
            }
            error("课表页面中的字符串不完整。")
        }
    }

    private sealed interface Value {
        data class Text(val value: String) : Value
        data class Number(val value: Int) : Value
        data class Array(val values: List<Value>) : Value
        data class Object(val values: Map<String, Value>) : Value
    }

    private class ScriptContext(private val tokens: List<Token>) {
        private val variables = mutableMapOf<String, Value>()
        private data class Activity(val args: List<List<Token>>, val variables: Map<String, Value>, val indices: MutableSet<Int>, var dynamicIndex: Boolean = false)
        private val activities = mutableListOf<Activity>()
        var unitCount = 12
            private set
        private var tableYear: Int? = null
        private var marshal: List<Int>? = null

        init {
            var pos = 0
            var current: Activity? = null
            while (pos < tokens.size) {
                val token = tokens[pos]
                if (!token.string && token.text == "new" && tokens.getOrNull(pos + 2)?.text == "(") {
                    val name = tokens[pos + 1].text
                    val call = arguments(pos + 2)
                    if (name == "TaskActivity") {
                        current = Activity(call.first, variables.toMap(), sortedSetOf())
                        activities += current
                    } else if (name == "CourseTable") {
                        tableYear = number(call.first.getOrNull(0).orEmpty(), variables)
                    }
                    pos = call.second + 1
                    continue
                }
                if (!token.string && token.text in setOf("marshalTable", "marshalTableForAdminclass") && tokens.getOrNull(pos + 1)?.text == "(") {
                    val args = arguments(pos + 1).first.map { number(it, variables) }
                    if (args.size == 3 && args.all { it != null }) marshal = args.filterNotNull()
                }
                if (!token.string && tokens.getOrNull(pos + 1)?.text == "=" && tokens.getOrNull(pos + 2)?.text != "=") {
                    val expression = expressionAt(pos + 2)
                    val value = literal(expression, variables)
                    if (value != null) variables[token.text] = value
                    if (token.text == "unitCount") {
                        unitCount = (value as? Value.Number)?.value ?: error("无法识别教务课表每天的节次数。")
                        require(unitCount in 1..60) { "教务课表每天的节次数无效。" }
                    }
                    if (token.text == "index" && current != null) {
                        val index = number(expression, variables)
                        if (index == null) current.dynamicIndex = true else current.indices += index
                    }
                }
                pos++
            }
        }

        fun stringVariable(vararg names: String): String? = names.firstNotNullOfOrNull { (variables[it] as? Value.Text)?.value }
        fun partialRangeWarning(): String? = marshal?.get(1)?.takeIf { it > 1 }
            ?.let { "当前教务页面从第 $it 周开始展示，请确认已选择完整学期。" }

        /**
         * DOM 仍是课程内容的来源，只检查静态活动所声明的格子是否已经显示。
         * 动态周值/索引不猜测；已明确的越界、周次格式和缺格错误直接报告。
         */
        fun requireRenderedCoverage(slots: List<Slot>) {
            val covered = slots.mapTo(mutableSetOf()) { (it.day - 1) * unitCount + it.period - 1 }
            val missing = mutableSetOf<Int>()
            for (activity in activities) {
                if (activity.indices.isEmpty()) continue
                require(activity.args.size >= 7) { "教务课表的课程参数不完整。" }
                val raw = (literal(activity.args[6], activity.variables) as? Value.Text)?.value
                    ?.let(::decodeEntities)?.trim() ?: continue
                if (raw.isEmpty()) continue
                val weeks = if (Regex("[01]+").matches(raw)) {
                    if (!raw.contains('1')) continue
                    // 没有静态 marshal 基准时不推导教学周，但已知活动格仍必须显示。
                    if (marshal == null) null else bitmapWeeks(raw, marshal, tableYear, mutableListOf())
                } else chineseWeeks(raw)
                if (weeks != null && weeks.isEmpty()) continue
                for (index in activity.indices) {
                    require(index >= 0) { "教务课表包含负数节次索引。" }
                    validateSlot(index / unitCount + 1, index % unitCount + 1, weeks.orEmpty())
                    if (index !in covered) missing += index
                }
            }
            require(missing.isEmpty()) {
                "课表尚未完整显示（还有 ${missing.size} 个节次没有课程内容），请等待页面加载完成或刷新后重新读取。"
            }
        }

        fun parseActivities(warnings: MutableList<String>): List<Slot> {
            val slots = mutableListOf<Slot>()
            require(activities.none { it.dynamicIndex }) { "教务课表使用动态节次表达式，暂时无法完整导入。" }
            activities.filter { it.indices.isNotEmpty() }.forEach { activity ->
                require(activity.args.size >= 7) { "教务课表的课程参数不完整。" }
                fun text(index: Int, label: String): String =
                    (literal(activity.args[index], activity.variables) as? Value.Text)?.value?.let(::decodeEntities)?.trim()
                        ?: error("无法识别教务课表中的$label，请打开已显示课程的「我的课表」页面后重试。")
                val teacher = text(1, "授课教师")
                val courseId = (literal(activity.args[2], activity.variables) as? Value.Text)?.value.orEmpty()
                val name = courseNameAndCode(text(3, "课程名称")).first
                val room = text(5, "上课地点")
                require(name.isNotBlank()) { "教务课表包含空课程名称。" }
                val rawWeeks = text(6, "教学周次")
                val weeks = if (Regex("[01]+").matches(rawWeeks)) bitmapWeeks(rawWeeks, marshal, tableYear, warnings) else chineseWeeks(rawWeeks)
                activity.indices.forEach { index ->
                    require(index >= 0) { "教务课表包含负数节次索引。" }
                    val day = index / unitCount + 1
                    val period = index % unitCount + 1
                    validateSlot(day, period, weeks)
                    if (weeks.isNotEmpty()) slots += Slot(courseId, name, teacher, room, day, period, weeks)
                }
            }
            return slots
        }

        private fun expressionAt(begin: Int): List<Token> {
            var pos = begin
            var depth = 0
            while (pos < tokens.size) {
                val t = tokens[pos]
                if (!t.string) {
                    if (t.text in setOf("(", "[", "{")) depth++
                    if (t.text in setOf(")", "]", "}")) { if (depth == 0) break; depth-- }
                    if (depth == 0 && t.text in setOf(";", ",")) break
                }
                pos++
            }
            return tokens.subList(begin, pos)
        }

        private fun arguments(open: Int): Pair<List<List<Token>>, Int> {
            val result = mutableListOf<List<Token>>()
            var start = open + 1
            var pos = start
            var depth = 0
            while (pos < tokens.size) {
                val t = tokens[pos]
                if (!t.string) {
                    if (t.text in setOf("(", "[", "{")) depth++
                    if (t.text in setOf(")", "]", "}")) {
                        if (depth == 0) { result += tokens.subList(start, pos); return result to pos }
                        depth--
                    }
                    if (t.text == "," && depth == 0) { result += tokens.subList(start, pos); start = pos + 1 }
                }
                pos++
            }
            error("课表页面中的课程参数不完整。")
        }

        private fun literal(expression: List<Token>, vars: Map<String, Value>): Value? {
            if (expression.size == 1) {
                val token = expression.single()
                return if (token.string) Value.Text(token.text)
                else token.text.toIntOrNull()?.let(Value::Number) ?: vars[token.text]
            }
            if (expression.size == 6 && expression[1].text == "." && expression[2].text == "join" && expression[3].text == "(" && expression[4].string && expression[5].text == ")") {
                val array = vars[expression[0].text] as? Value.Array
                val directNames = array?.values?.map { (it as? Value.Text)?.value }
                val names = if (expression[0].text == "actTeacherName" && directNames.isNullOrEmpty()) {
                        (vars["actTeachers"] as? Value.Array)?.values?.map { ((it as? Value.Object)?.values?.get("name") as? Value.Text)?.value }
                    } else directNames
                if (names != null && names.all { it != null }) return Value.Text(names.filterNotNull().joinToString(expression[4].text))
            }
            if (expression.firstOrNull()?.text == "[" && expression.lastOrNull()?.text == "]") {
                val parts = split(expression.subList(1, expression.lastIndex))
                val values = parts.filter { it.isNotEmpty() }.map { literal(it, vars) }
                if (values.all { it != null }) return Value.Array(values.filterNotNull())
            }
            if (expression.firstOrNull()?.text == "{" && expression.lastOrNull()?.text == "}") {
                val values = mutableMapOf<String, Value>()
                for (part in split(expression.subList(1, expression.lastIndex))) {
                    if (part.size < 3 || part[1].text != ":") return null
                    // EAMS 的教师对象同时包含 lab 等无关字段；仅保存可安全读取的字段。
                    literal(part.drop(2), vars)?.let { values[part[0].text] = it }
                }
                return Value.Object(values)
            }
            number(expression, vars)?.let { return Value.Number(it) }
            return null
        }

        private fun split(expression: List<Token>): List<List<Token>> {
            val parts = mutableListOf<List<Token>>()
            var depth = 0
            var start = 0
            expression.forEachIndexed { index, token ->
                if (!token.string) {
                    if (token.text in setOf("(", "[", "{")) depth++
                    if (token.text in setOf(")", "]", "}")) depth--
                    if (token.text == "," && depth == 0) { parts += expression.subList(start, index); start = index + 1 }
                }
            }
            parts += expression.subList(start, expression.size)
            return parts
        }

        /** 仅支持整数常量、静态整数变量以及 + - * / 括号，绝不求值任意 JavaScript。 */
        private fun number(expression: List<Token>, vars: Map<String, Value>): Int? {
            var pos = 0
            fun atom(): Long? {
                val token = expression.getOrNull(pos++) ?: return null
                if (token.string) return null
                return when (token.text) {
                    "-" -> atom()?.let { -it }
                    "+" -> atom()
                    else -> token.text.toLongOrNull() ?: (vars[token.text] as? Value.Number)?.value?.toLong()
                }
            }
            // EAMS 生成的索引是 D * unitCount + P；括号在扫描时消除，只允许单层包裹整个值。
            if (expression.firstOrNull()?.text == "(" && expression.lastOrNull()?.text == ")") return number(expression.subList(1, expression.lastIndex), vars)
            fun product(): Long? {
                var value = atom() ?: return null
                while (expression.getOrNull(pos)?.text in setOf("*", "/")) {
                    val op = expression[pos++].text
                    val right = atom() ?: return null
                    value = if (op == "*") {
                        runCatching { Math.multiplyExact(value, right) }.getOrNull() ?: return null
                    } else { if (right == 0L || value % right != 0L) return null; value / right }
                }
                return value
            }
            var value = product() ?: return null
            while (expression.getOrNull(pos)?.text in setOf("+", "-")) {
                val op = expression[pos++].text
                val right = product() ?: return null
                value = runCatching { if (op == "+") Math.addExact(value, right) else Math.subtractExact(value, right) }.getOrNull() ?: return null
            }
            return value.takeIf { pos == expression.size && it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        }
    }
}
