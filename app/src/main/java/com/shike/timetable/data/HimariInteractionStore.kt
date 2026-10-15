package com.shike.timetable.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.time.LocalDateTime

/** 应用与桌面小组件共用的陪伴选择；独立保存，不改变课表或备份格式。 */
class HimariInteractionStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    fun current(moment: LocalDateTime = LocalDateTime.now()): HimariMoment = synchronized(lock) {
        readCurrent(moment)
    }

    fun shuffle(moment: LocalDateTime = LocalDateTime.now()): HimariMoment = synchronized(lock) {
        val current = readCurrent(moment)
        val next = HimariCompanion.randomNext(moment, current, readRecent())
        save(moment, next, readRecent() + current.line + next.line)
        next
    }

    private fun readCurrent(moment: LocalDateTime): HimariMoment {
        val fallback = HimariCompanion.at(moment)
        val saved = runCatching { preferences.all }.getOrDefault(emptyMap())
        val line = saved["line"] as? String
        val artwork = saved["artwork"] as? String
        val lines = when (fallback.part) {
            HimariDayPart.Morning -> HimariCompanion.morningLines
            HimariDayPart.Noon -> HimariCompanion.noonLines
            HimariDayPart.Evening -> HimariCompanion.eveningLines
        }
        if (saved["bucket"] == HimariCompanion.bucketAt(moment) &&
            saved["part"] == fallback.part.name && line in lines &&
            artwork in HimariCompanion.artworks.getValue(fallback.part)) {
            return HimariMoment(fallback.part, artwork!!, line!!)
        }
        save(moment, fallback, readRecent() + fallback.line)
        return fallback
    }

    private fun readRecent(): List<String> = runCatching {
        val saved = preferences.all["recent"] as? String ?: return@runCatching emptyList()
        if (saved.length > 1_500) return@runCatching emptyList()
        val array = JSONArray(saved)
        // 本地偏好损坏时不解析任意大小的历史，也不将未知文字带到界面。
        if (array.length() > 10) return@runCatching emptyList()
        (0 until array.length()).mapNotNull { array.opt(it) as? String }
            .filter { it in HimariCompanion.allLines }.takeLast(5)
    }.getOrDefault(emptyList())

    private fun save(moment: LocalDateTime, choice: HimariMoment, recent: List<String>) {
        val history = recent.fold(emptyList<String>()) { result, line ->
            if (result.lastOrNull() == line) result else result + line
        }.takeLast(5)
        runCatching {
            preferences.edit()
                .putLong("bucket", HimariCompanion.bucketAt(moment))
                .putString("part", choice.part.name)
                .putString("artwork", choice.artwork)
                .putString("line", choice.line)
                .putString("recent", JSONArray(history).toString())
                .apply()
        }
    }

    companion object {
        const val PREFERENCES = "shike_companion_interaction"
        /** 同一进程的组件、多个 Store 实例和小组件后台线程共享一次读改写。 */
        private val lock = Any()
    }
}
