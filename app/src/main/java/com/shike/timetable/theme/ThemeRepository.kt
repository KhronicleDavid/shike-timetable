package com.shike.timetable.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject

/** 列表是唯一注册入口；所有角色素材、语录和浅深配色均来自各主题目录。 */
class ThemeRepository private constructor(val themes: List<ThemePack>, val defaultId: String) {
    fun find(id: String?) = themes.firstOrNull { it.id == id }
        ?: themes.firstOrNull { it.id == defaultId } ?: themes.first()

    companion object {
        @Volatile private var cached: ThemeRepository? = null
        fun get(context: Context): ThemeRepository = cached ?: synchronized(this) {
            cached ?: read { path -> context.applicationContext.assets.open(path).bufferedReader().use { it.readText() } }
                .also { cached = it }
        }
        fun read(load: (String) -> String): ThemeRepository {
            val index = JSONObject(load("themes/catalog.json"))
            require(index.getInt("schemaVersion") == 1) { "不支持的主题列表版本" }
            val list = index.getJSONArray("themes")
            val themes = (0 until list.length()).map { i ->
                val id = list.getString(i)
                require(id.matches(Regex("[a-z][a-z0-9_-]{0,39}"))) { "主题列表 id 不合法" }
                ThemePackParser.parse(load("themes/$id/theme.json")).also { require(it.id == id) { "主题目录与 id 不一致" } }
            }
            require(themes.isNotEmpty() && themes.map { it.id }.distinct().size == themes.size) { "主题列表为空或重复" }
            val defaultId = index.getString("defaultTheme")
            require(themes.any { it.id == defaultId }) { "默认主题必须在列表中" }
            return ThemeRepository(themes, defaultId)
        }
    }
}

/** 图片按用途解码并缓存，桌面使用较小尺寸，避免把原始大图放入 RemoteViews。 */
object ThemeImages {
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Synchronized fun load(context: Context, theme: ThemePack, artwork: ThemeArtwork, maxEdge: Int = 1024, compact: Boolean = false): Bitmap? {
        val path = theme.asset(artwork, compact)
        val key = "${theme.version}:$path:$maxEdge"
        cache.get(key)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= maxEdge) inSampleSize *= 2
            }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }?.also { cache.put(key, it) }
        }.getOrNull()
    }
}

class AppearanceStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("shike_appearance", Context.MODE_PRIVATE))
    var themeId: String
        get() = preferences.all["theme_id"] as? String ?: ""
        set(value) { preferences.edit().putString("theme_id", value).apply() }
    var showCompanion: Boolean
        get() = preferences.all["show_companion"] as? Boolean ?: preferences.all["show_himari"] as? Boolean ?: true
        set(value) { preferences.edit().putBoolean("show_companion", value).apply() }
    var voiceEnabled: Boolean
        get() = preferences.all["voice_enabled"] as? Boolean ?: true
        set(value) { preferences.edit().putBoolean("voice_enabled", value).apply() }
}
