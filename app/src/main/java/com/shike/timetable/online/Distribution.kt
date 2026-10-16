package com.shike.timetable.online

import org.json.JSONObject
import java.net.URI

/** 公开目录只描述数据，不携带脚本。下载地址固定为本仓库的正式Release附件。 */
object Distribution {
    const val REPOSITORY = "KhronicleDavid/shike-timetable"
    const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val THEMES = "https://raw.githubusercontent.com/$REPOSITORY/main/distribution/themes.json"
    const val MAX_JSON = 1024 * 1024L
    const val MAX_APK = 64 * 1024 * 1024L
    const val MAX_THEME = 128 * 1024 * 1024L

    fun releaseUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null &&
            uri.rawPath.matches(Regex("/$REPOSITORY/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))) { "下载地址不属于拾课的GitHub发布页" }
        return value
    }
    fun sha(value: String): String = value.removePrefix("sha256:").lowercase().also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "发布文件缺少有效的校验信息" }
    }
    fun artifact(json: JSONObject, limit: Long): Artifact = Artifact(
        releaseUrl(json.getString("url")), json.getLong("bytes").also { require(it in 1..limit) { "发布文件大小超出限制" } }, sha(json.getString("sha256")))

    fun themes(text: String): List<OnlineTheme> {
        val root = JSONObject(text); require(root.getInt("schemaVersion") == 1) { "主题目录版本暂不支持，请先更新应用" }
        val items = root.getJSONArray("themes"); require(items.length() <= 100) { "主题目录过大" }
        val result = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            OnlineTheme(item.getString("id").also { require(it.matches(Regex("[a-z][a-z0-9_-]{0,39}")) && it != "minimal") },
                item.getInt("version").also { require(it > 0) }, item.label("name", 80), item.label("description", 1000),
                item.label("notes", 4000), item.getInt("minVersionCode").also { require(it > 0) }, artifact(item, MAX_THEME))
        }
        require(result.map { it.id }.distinct().size == result.size) { "主题目录出现重复条目" }
        return result
    }
    fun release(text: String): ReleaseListing {
        val root = JSONObject(text)
        require(!root.getBoolean("draft") && !root.getBoolean("prerelease")) { "暂时没有正式版本" }
        val tag = root.label("tag_name", 80)
        val assets = root.getJSONArray("assets"); require(assets.length() <= 100)
        val files = (0 until assets.length()).map { i ->
            val item = assets.getJSONObject(i)
            require(item.getString("state") == "uploaded") { "发布附件尚未就绪" }
            ReleaseAsset(item.label("name", 180), Artifact(releaseUrl(item.getString("browser_download_url")),
                item.getLong("size").also { require(it > 0) }, sha(item.getString("digest"))))
        }
        require(files.map { it.name }.distinct().size == files.size)
        return ReleaseListing(tag, root.optString("body").take(16000), files)
    }
    fun update(text: String, release: ReleaseListing): AppUpdate {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1) { "更新信息版本暂不支持" }
        require(root.getString("tag") == release.tag) { "版本信息与发布页不一致，请稍后重试" }
        val name = root.label("versionName", 40)
        val code = root.getInt("versionCode").also { require(it > 0) }
        val minSdk = root.getInt("minSdk").also { require(it >= 26) }
        val asset = release.files.singleOrNull { it.name == root.getString("assetName") } ?: error("发布页缺少安装包")
        require(asset.name.endsWith(".apk") && asset.artifact.bytes <= MAX_APK) { "安装包大小或类型不支持" }
        return AppUpdate(name, code, minSdk, release.notes, asset.artifact)
    }
    private fun JSONObject.label(key: String, max: Int): String = getString(key).also { require(it.isNotBlank() && it.length <= max) { "发布信息不完整" } }
}

data class Artifact(val url: String, val bytes: Long, val sha256: String)
data class ReleaseAsset(val name: String, val artifact: Artifact)
data class ReleaseListing(val tag: String, val notes: String, val files: List<ReleaseAsset>)
data class AppUpdate(val versionName: String, val versionCode: Int, val minSdk: Int, val notes: String, val artifact: Artifact)
data class OnlineTheme(val id: String, val version: Int, val name: String, val description: String, val notes: String, val minVersionCode: Int, val artifact: Artifact)
