package com.shike.timetable.quotes

import android.content.Context
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.random.Random

data class DailyQuote(val id: String, val text: String, val work: String, val speaker: String, val sourceUrl: String) {
    val credit: String get() = if (speaker.isBlank()) "《$work》" else "$speaker ·《$work》"
}
data class QuoteChoice(val cycle: Long, val id: String)

object DailyQuoteEngine {
    fun cycleAt(now: ZonedDateTime): Long = (if (now.toLocalTime() < LocalTime.of(8, 0)) now.toLocalDate().minusDays(1) else now.toLocalDate()).toEpochDay()
    fun nextBoundary(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(8, 0).atZone(now.zone)
        return if (now < today) today else now.toLocalDate().plusDays(1).atTime(8, 0).atZone(now.zone)
    }
    /** 首次、手动及跨08:00周期时随机选句；刷新保持选择，且不连续重复。 */
    fun choose(quotes: List<DailyQuote>, saved: QuoteChoice?, now: ZonedDateTime, advance: Boolean = false,
        random: Random = Random.Default): QuoteChoice {
        require(quotes.isNotEmpty())
        val cycle = cycleAt(now)
        val index = saved?.let { s -> quotes.indexOfFirst { it.id == s.id } } ?: -1
        if (index >= 0 && !advance && cycle <= saved!!.cycle) return QuoteChoice(cycle, saved.id)
        val groups = quotes.groupBy { it.work }
        val previous = quotes.getOrNull(index)
        // 先按作品等概率选，再在该作中选句；多部作品时连续两次也不选同一作品。
        val works = groups.keys.filter { groups.size == 1 || it != previous?.work }
        val pool = groups.getValue(works[random.nextInt(works.size)]).filter { quotes.size == 1 || it.id != previous?.id }
        return QuoteChoice(cycle, pool[random.nextInt(pool.size)].id)
    }
}

object DailyQuoteRepository {
    @Volatile private var cached: List<DailyQuote>? = null
    fun get(context: Context): List<DailyQuote> = cached ?: synchronized(this) {
        cached ?: parse(context.assets.open("quotes/daily.json").bufferedReader().use { it.readText() }).also { cached = it }
    }
    fun parse(text: String): List<DailyQuote> {
        require(text.length <= 1_000_000)
        val root = JSONObject(text); require(root.getInt("schemaVersion") == 1)
        val rows = root.getJSONArray("quotes"); require(rows.length() in 100..2000)
        val result = (0 until rows.length()).map { i ->
            val item = rows.getJSONObject(i)
            val value = item.getString("text"); val work = item.getString("work"); val speaker = item.optString("speaker")
            val source = item.getString("sourceUrl"); val id = item.getString("id")
            require(value.isNotBlank() && value.codePointCount(0, value.length) <= 50 && '\n' !in value)
            require(work.isNotBlank() && work.length <= 100 && speaker.length <= 80)
            require(id.matches(Regex("[a-z0-9_-]{1,64}")))
            val uri = java.net.URI(source)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
            DailyQuote(id, value, work, speaker, source)
        }
        require(result.map { it.id }.distinct().size == result.size && result.map { it.text }.distinct().size == result.size)
        return result
    }
}
