package com.shike.timetable.theme

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** 各主题分别记住选择，首次交互提示则全局只出现一次。课表及备份不受影响。 */
class CompanionInteractionStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    fun showShuffleHint(): Boolean = preferences.all[HINT_DISMISSED] != true
    fun showPlaybackHint(): Boolean = preferences.all[PLAYBACK_HINT_DISMISSED] != true
    internal fun dismissPlaybackHint() { preferences.edit().putBoolean(PLAYBACK_HINT_DISMISSED, true).apply() }
    fun current(theme: ThemePack, moment: LocalDateTime = LocalDateTime.now()): CompanionMoment? = synchronized(lock) {
        readCurrent(theme, moment)
    }
    fun shuffle(theme: ThemePack, moment: LocalDateTime = LocalDateTime.now()): CompanionMoment? = synchronized(lock) {
        if (CompanionVoicePlayback.isBusy) return@synchronized readCurrent(theme, moment)
        val current = readCurrent(theme, moment) ?: return@synchronized null
        val recent = readRecent(theme)
        val next = CompanionEngine.randomNext(theme, moment, current, recent)!!
        save(theme, moment, next, recent + current.line + next.line)
        preferences.edit().putBoolean(HINT_DISMISSED, true).apply()
        next
    }

    private fun choice(theme: ThemePack): JSONObject = runCatching {
        val raw = preferences.all["choice_${theme.id}"] as? String
        if (raw != null && raw.length <= 4_000) JSONObject(raw) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun readCurrent(theme: ThemePack, moment: LocalDateTime): CompanionMoment? {
        CompanionVoicePlayback.sessions.value?.takeIf { it.themeId == theme.id }?.let { return it.moment }
        val fallback = CompanionEngine.at(theme, moment) ?: return null
        val content = theme.companion!!
        var saved = choice(theme)
        // 1.4 的单角色偏好按原 id 迁移，主题之间不借用语录。
        if (!preferences.contains("choice_${theme.id}") && theme.id == "himari") {
            val old = preferences.all
            saved = JSONObject().apply {
                put("bucket", old["bucket"] as? Long ?: -1)
                put("part", old["part"] as? String ?: "")
                put("artwork", old["artwork"] as? String ?: "")
                put("line", old["line"] as? String ?: "")
            }
        }
        val line = saved.optString("line")
        val art = saved.optString("artwork")
        val compatiblePart = saved.optString("part") in setOf(fallback.part.key, fallback.part.name)
        val valid = saved.opt("bucket") is Number && saved.optLong("bucket", -1) == CompanionEngine.bucketAt(moment) &&
            compatiblePart && (line in content.quotes.getValue(fallback.part) || (!content.hasDialogue && line.isEmpty())) &&
            content.pictures(fallback.part).any { it.id == art }
        val selected = if (valid) CompanionMoment(fallback.part, art, line) else fallback
        if (!valid || !preferences.contains("choice_${theme.id}")) save(theme, moment, selected, readRecent(theme) + selected.line)
        return selected
    }

    private fun readRecent(theme: ThemePack): List<String> = runCatching {
        val array = choice(theme).optJSONArray("recent") ?: JSONArray()
        if (array.length() > 10) return@runCatching emptyList()
        val lines = theme.companion?.allLines.orEmpty()
        (0 until array.length()).mapNotNull { array.opt(it) as? String }.filter { it in lines }.takeLast(5)
    }.getOrDefault(emptyList())

    private fun save(theme: ThemePack, moment: LocalDateTime, selected: CompanionMoment, recent: List<String>) {
        val history = recent.filter { it.isNotBlank() }.fold(emptyList<String>()) { result, line -> if (result.lastOrNull() == line) result else result + line }.takeLast(5)
        val json = JSONObject().apply {
            put("bucket", CompanionEngine.bucketAt(moment)); put("part", selected.part.key)
            put("artwork", selected.artwork); put("line", selected.line); put("recent", JSONArray(history))
        }
        preferences.edit().putString("choice_${theme.id}", json.toString()).apply()
    }
    companion object {
        // 保留旧偏好文件名，只迁移结构，不修改已有课表或角色开关。
        const val PREFERENCES = "shike_companion_interaction"
        const val HINT_DISMISSED = "shuffle_hint_dismissed"
        const val PLAYBACK_HINT_DISMISSED = "playback_hint_dismissed"
        private val lock = Any()
    }
}
