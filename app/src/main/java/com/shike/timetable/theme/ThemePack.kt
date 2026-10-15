package com.shike.timetable.theme

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

enum class DayPart(val key: String, val label: String) {
    Morning("morning", "早晨"), Noon("noon", "午间"), Evening("evening", "夜晚");
    companion object {
        fun at(time: LocalTime) = when (time.hour) {
            in 5..10 -> Morning
            in 11..16 -> Noon
            else -> Evening
        }
    }
}

data class ThemeArtwork(
    val id: String, val label: String, val file: String, val crop: Boolean,
    val periods: Set<DayPart>, val scenes: Set<String>,
    val compactFile: String? = null,
)
data class ThemePalette(val colors: Map<String, Long>, val courseAccents: List<Long>) {
    fun color(key: String): Long = colors.getValue(key)
}
data class ThemeCompanion(
    val name: String, val shortName: String, val tagline: String, val attribution: String,
    val artworks: List<ThemeArtwork>, val quotes: Map<DayPart, List<String>>,
    val contextLines: Map<String, String>,
) {
    fun pictures(part: DayPart) = artworks.filter { part in it.periods }
    fun scene(name: String) = artworks.firstOrNull { name in it.scenes } ?: artworks.first()
    fun artwork(id: String) = artworks.firstOrNull { it.id == id }
    val allLines get() = quotes.values.flatten() + contextLines.values
}
data class ThemePack(
    val id: String, val version: Int, val name: String, val description: String,
    val light: ThemePalette, val dark: ThemePalette, val companion: ThemeCompanion?,
) {
    fun palette(darkMode: Boolean) = if (darkMode) dark else light
    fun asset(artwork: ThemeArtwork, compact: Boolean = false) = "themes/$id/${if (compact) artwork.compactFile ?: artwork.file else artwork.file}"
}
data class CompanionMoment(val part: DayPart, val artwork: String, val line: String)

/** 配置负责内容，页面和小组件只消费此模型；新增主题不需要新增角色枚举。 */
object ThemePackParser {
    val requiredColors = listOf("primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer", "tertiary",
        "tertiaryContainer", "onTertiaryContainer", "background", "onBackground", "surface", "onSurface",
        "surfaceVariant", "onSurfaceVariant", "surfaceContainer", "surfaceContainerLow", "surfaceContainerHigh",
        "surfaceContainerHighest", "outline", "outlineVariant", "cardStart", "cardEnd")
    private val identifier = Regex("[a-z][a-z0-9_-]{0,39}")

    fun parse(text: String): ThemePack {
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == 1) { "不支持的主题配置版本" }
        val id = json.text("id")
        require(identifier.matches(id)) { "主题 id 只能使用小写字母、数字、下划线和连字符" }
        val version = json.getInt("version").also { require(it > 0) { "主题版本必须为正整数" } }
        val palettes = json.getJSONObject("palettes")
        require(!json.has("companion") || json.isNull("companion") || json.get("companion") is JSONObject) { "companion 必须是对象或省略" }
        val companion = json.optJSONObject("companion")?.let { content ->
            val artworks = content.getJSONArray("artworks").let { array -> (0 until array.length()).map { index ->
                val art = array.getJSONObject(index)
                val artId = art.text("id").also { require(identifier.matches(it)) { "插画 id 不合法" } }
                val file = imagePath(art.text("file"))
                val compactFile = if (art.has("compactFile")) imagePath(art.text("compactFile")) else null
                val periodKeys = art.strings("periods")
                require(periodKeys.all { key -> DayPart.entries.any { it.key == key } }) { "未知的插画时段" }
                ThemeArtwork(artId, art.text("label"), file, art.optString("framing", "fit").also {
                    require(it in setOf("fit", "crop")) { "framing 只能为 fit 或 crop" }
                } == "crop", DayPart.entries.filter { it.key in periodKeys }.toSet(), art.strings("scenes").toSet(), compactFile)
            } }
            require(artworks.isNotEmpty() && artworks.map { it.id }.distinct().size == artworks.size) { "插画不能为空或有重复 id" }
            val pools = content.getJSONObject("quotes")
            val quotes = DayPart.entries.associateWith { part ->
                val lines = pools.strings(part.key)
                require(lines.isNotEmpty() && lines.size == lines.distinct().size) { "${part.label}语录池不能为空或重复" }
                require(artworks.any { part in it.periods }) { "${part.label}至少需要一幅插画（可一图用于多个时段）" }
                lines
            }
            val contexts = content.optJSONObject("contextLines")?.let { entries ->
                entries.keys().asSequence().associateWith { entries.text(it) }
            }.orEmpty()
            ThemeCompanion(content.text("name"), content.text("shortName"), content.text("tagline"),
                content.optString("attribution"), artworks, quotes, contexts)
        }
        return ThemePack(id, version, json.text("name"), json.text("description"),
            palette(palettes.getJSONObject("light")), palette(palettes.getJSONObject("dark")), companion)
    }

    private fun imagePath(file: String): String {
        require(file.matches(Regex("(?:[a-zA-Z0-9_-]+/)*[a-zA-Z0-9_.-]+\\.(png|webp|jpg|jpeg)")) &&
            file.split('/').none { it == "." || it == ".." }) { "插画路径必须是主题目录内的相对图片路径" }
        return file
    }
    private fun palette(json: JSONObject): ThemePalette {
        val colors = requiredColors.associateWith { key -> parseColor(json.text(key)) }
        val accents = json.strings("courseAccents").map(::parseColor)
        require(accents.size == 6) { "每套配色需要六种课程颜色，以保持已有课程的配色编号" }
        return ThemePalette(colors, accents)
    }
    private fun parseColor(value: String): Long {
        require(value.matches(Regex("#[0-9a-fA-F]{6}"))) { "配色使用 #RRGGBB 格式：$value" }
        return 0xFF000000L or value.drop(1).toLong(16)
    }
    private fun JSONObject.text(key: String): String = get(key).let {
        require(it is String && it.isNotBlank()) { "$key 必须是非空文本" }
        it.trim()
    }
    private fun JSONObject.strings(key: String): List<String> {
        require(!has(key) || opt(key) is org.json.JSONArray) { "$key 必须是数组" }
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { index ->
            val value = array.get(index)
            require(value is String && value.isNotBlank()) { "$key 不能包含空白或非文本项目" }
            value.trim()
        }
    }
}

object CompanionEngine {
    fun bucketAt(moment: LocalDateTime) = moment.toLocalDate().toEpochDay() * 48 + moment.hour * 2 + moment.minute / 30

    fun at(theme: ThemePack, moment: LocalDateTime): CompanionMoment? {
        val content = theme.companion ?: return null
        val part = DayPart.at(moment.toLocalTime())
        val lines = content.quotes.getValue(part)
        val pictures = content.pictures(part)
        val bucket = bucketAt(moment)
        return CompanionMoment(part, pictures[Math.floorMod(bucket, pictures.size.toLong()).toInt()].id,
            lines[Math.floorMod(bucket, lines.size.toLong()).toInt()])
    }

    fun randomNext(theme: ThemePack, moment: LocalDateTime, current: CompanionMoment?,
        recentLines: List<String>, random: Random = Random.Default): CompanionMoment? {
        val content = theme.companion ?: return null
        val part = DayPart.at(moment.toLocalTime())
        val lines = content.quotes.getValue(part)
        // 一个时段可以缩减到一句/一幅图，依然能使用，不会在空候选列表上崩溃。
        val alternatives = lines.filterNot { it == current?.line }.ifEmpty { lines }
        val recent = recentLines.takeLast(5)
        var candidates = alternatives.filterNot { it in recent }
        for (released in 1..recent.size) {
            if (candidates.isNotEmpty()) break
            candidates = alternatives.filterNot { it in recent.drop(released) }
        }
        val pictures = content.pictures(part)
        val art = pictures.filterNot { it.id == current?.artwork }.ifEmpty { pictures }.random(random)
        return CompanionMoment(part, art.id, candidates.ifEmpty { alternatives }.random(random))
    }

    fun nextBoundary(moment: LocalDateTime): LocalDateTime = listOf(5, 11, 17)
        .map { moment.toLocalDate().atTime(it, 0) }.firstOrNull { it.isAfter(moment) }
        ?: moment.toLocalDate().plusDays(1).atStartOfDay()
}
