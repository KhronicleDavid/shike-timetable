package com.shike.timetable.quotes

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.ZonedDateTime

class DailyQuoteStore internal constructor(private val preferences: SharedPreferences, private val quotes: List<DailyQuote>) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE), DailyQuoteRepository.get(context))
    var enabled: Boolean
        get() = preferences.all["enabled"] == true
        set(value) { synchronized(lock) { preferences.edit().putBoolean("enabled", value).apply(); updates.value += 1 } }
    fun current(now: ZonedDateTime = ZonedDateTime.now()): DailyQuote? = synchronized(lock) {
        if (!enabled) return@synchronized null
        select(now, false)
    }
    fun next(now: ZonedDateTime = ZonedDateTime.now()): DailyQuote? = synchronized(lock) {
        if (!enabled) return@synchronized null
        select(now, true)
    }
    private fun select(now: ZonedDateTime, advance: Boolean): DailyQuote {
        val saved = runCatching {
            val raw = preferences.all["selection"] as? String ?: return@runCatching null
            require(raw.length <= 300)
            val json = JSONObject(raw)
            val cycle = json.getLong("cycle")
            require(cycle in java.time.LocalDate.MIN.toEpochDay()..java.time.LocalDate.MAX.toEpochDay())
            QuoteChoice(cycle, json.getString("id"))
        }.getOrNull()
        val choice = DailyQuoteEngine.choose(quotes, saved, now, advance)
        if (saved != choice) {
            preferences.edit().putString("selection", JSONObject().put("cycle", choice.cycle).put("id", choice.id).toString()).apply()
            updates.value += 1
        }
        return quotes.first { it.id == choice.id }
    }
    companion object {
        const val PREFERENCES = "shike_daily_quote"
        private val lock = Any()
        private val updates = MutableStateFlow(0L)
        val changes = updates.asStateFlow()
    }
}
