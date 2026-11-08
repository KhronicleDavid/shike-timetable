package com.shike.timetable.quotes

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

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
    /** 每天08:00从当前句前进一步，手动操作不修改周期，不把定时切换改成24小时倒计时。 */
    fun choose(quotes: List<DailyQuote>, saved: QuoteChoice?, now: ZonedDateTime, advance: Boolean = false): QuoteChoice {
        require(quotes.isNotEmpty())
        val cycle = cycleAt(now)
        val index = saved?.let { s -> quotes.indexOfFirst { it.id == s.id } } ?: -1
        val start = index.takeIf { it >= 0 } ?: 0
        val days = if (saved != null && index >= 0 && cycle > saved.cycle) (cycle - saved.cycle) % quotes.size else 0
        val next = ((start.toLong() + days + if (advance) 1 else 0) % quotes.size).toInt()
        return QuoteChoice(cycle, quotes[next].id)
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
