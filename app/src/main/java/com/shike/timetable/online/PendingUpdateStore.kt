package com.shike.timetable.online

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** 系统安装授权可能重启进程；仅恢复经过完整复验的专用更新缓存。 */
internal class PendingUpdateStore(context: Context) {
    private val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    private val metadata = AtomicFile(File(directory, "ready.json"))
    fun file(update: AppUpdate) = File(directory, "shike-${update.versionCode}.apk")
    fun save(update: AppUpdate, awaitingPermission: Boolean) {
        val bytes = JSONObject().put("schemaVersion", 1).put("versionName", update.versionName)
            .put("versionCode", update.versionCode).put("minSdk", update.minSdk).put("notes", update.notes.take(16000))
            .put("url", update.artifact.url).put("bytes", update.artifact.bytes).put("sha256", update.artifact.sha256)
            .put("awaitingPermission", awaitingPermission).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        val output = metadata.startWrite()
        try { output.write(bytes); metadata.finishWrite(output) }
        catch (error: Exception) { metadata.failWrite(output); throw error }
    }
    fun load(): SavedUpdate? {
        if (!metadata.baseFile.exists() && !File(metadata.baseFile.path + ".bak").exists()) return null
        require(metadata.baseFile.length() <= 65536)
        val root = JSONObject(metadata.readFully().toString(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 1)
        val name = root.getString("versionName"); val code = root.getInt("versionCode"); val minSdk = root.getInt("minSdk")
        val notes = root.getString("notes")
        require(name.isNotBlank() && name.length <= 40 && code > 0 && minSdk >= 26 && notes.length <= 16000)
        return SavedUpdate(AppUpdate(name, code, minSdk, notes, Distribution.artifact(root, Distribution.MAX_APK)), root.getBoolean("awaitingPermission"))
    }
    fun clear() { metadata.delete() }
}
internal data class SavedUpdate(val update: AppUpdate, val awaitingPermission: Boolean)
