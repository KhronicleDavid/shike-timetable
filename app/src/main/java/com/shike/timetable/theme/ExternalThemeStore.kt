package com.shike.timetable.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** 主题放在应用自己的非备份目录，不要求存储权限，也不依赖原ZIP仍在下载目录。 */
class ExternalThemeStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "themes").apply { mkdirs() }
    private val pending = File(app.cacheDir, "theme-imports").apply { mkdirs() }

    fun prepare(uri: Uri): PreparedTheme {
        val archive = File(pending, "${UUID.randomUUID()}.zip")
        try {
            app.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开主题压缩包" }
                archive.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        require(total <= ThemeArchive.MAX_ARCHIVE_BYTES) { "主题压缩包超过128MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return prepare(archive)
        } finally { archive.delete() }
    }

    internal fun prepare(archive: File): PreparedTheme {
        val prepared = ThemeArchive.prepare(archive, File(pending, UUID.randomUUID().toString()), ::validateMedia)
        try {
            require(prepared.theme.id !in ThemeRepository.bundled(app).themes.map { it.id }) { "不能替换内置简约主题" }
            return prepared
        } catch (error: Exception) { discard(prepared); throw error }
    }

    fun discard(prepared: PreparedTheme) { prepared.directory.deleteRecursively() }

    fun install(prepared: PreparedTheme): ThemePack = synchronized(lock) {
        require(prepared.directory.parentFile?.canonicalFile == pending.canonicalFile && prepared.directory.isDirectory) { "待安装主题已失效，请重新选择压缩包" }
        require(prepared.theme.id !in ThemeRepository.bundled(app).themes.map { it.id }) { "不能替换内置主题" }
        val home = File(root, prepared.theme.id).apply { mkdirs() }
        val destination = File(home, UUID.randomUUID().toString())
        try {
            check(prepared.directory.renameTo(destination)) { "无法保存主题，请检查剩余空间" }
            val pointer = AtomicFile(File(home, "active"))
            val stream = pointer.startWrite()
            try { stream.write(destination.name.toByteArray(Charsets.UTF_8)); pointer.finishWrite(stream) }
            catch (error: Exception) { pointer.failWrite(stream); throw error }
            check(pointer.readFully().toString(Charsets.UTF_8) == destination.name) { "主题保存未完成，原主题保持不变" }
        } catch (error: Exception) { destination.deleteRecursively(); throw error }
        notifyChanged(app)
        // 新版本已完整发布；清理旧修订。正在播放的文件已由MediaPlayer持有打开的描述符。
        home.listFiles()?.filter { it.isDirectory && it != destination }?.forEach { it.deleteRecursively() }
        prepared.theme.copy(directory = destination, installedBytes = prepared.bytes)
    }

    fun remove(id: String) = synchronized(lock) {
        require(id.matches(Regex("[a-z][a-z0-9_-]{0,39}")) && id !in ThemeRepository.bundled(app).themes.map { it.id }) { "内置主题不能移除" }
        val home = File(root, id)
        // 先移除注册入口，即使删除文件被打断，下次启动也不会加载半个主题。
        AtomicFile(File(home, "active")).delete()
        check(!File(home, "active").exists()) { "主题暂时无法移除，请稍后重试" }
        notifyChanged(app)
        check(!home.exists() || home.deleteRecursively()) { "主题已停用，部分文件暂时无法清理，请重试" }
    }

    fun installed(): List<ThemePack> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-z][a-z0-9_-]{0,39}")) }.mapNotNull { home ->
            runCatching {
                val revision = AtomicFile(File(home, "active")).readFully().toString(Charsets.UTF_8)
                require(revision.matches(Regex("[a-f0-9-]{36}")))
                val directory = File(home, revision)
                val config = File(directory, "theme.json")
                require(config.length() in 1..ThemeArchive.MAX_CONFIG_BYTES)
                ThemePackParser.parse(config.readText()).also { require(it.id == home.name) }
                    .copy(directory = directory, installedBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            }.getOrNull()
        }.sortedBy { it.name }
    }

    private fun validateMedia(file: File) {
        if (file.extension in setOf("png", "jpg", "jpeg", "webp")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "图片损坏或尺寸过大：${file.name}" }
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 256) }
            val bitmap = BitmapFactory.decodeFile(file.path, options)
            requireNotNull(bitmap) { "无法解码主题图片：${file.name}" }; bitmap.recycle()
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                require(duration != null && duration in 1..120_000) { "音频损坏或超过两分钟：${file.name}" }
            } finally { retriever.release() }
        }
    }

    companion object {
        private val lock = Any()
        private val updates = MutableStateFlow(0L)
        val changes = updates.asStateFlow()
        private fun notifyChanged(context: Context) {
            ThemeRepository.invalidate(); updates.value += 1
            com.shike.timetable.widget.TodayWidgetUpdates.request(context)
        }
    }
}
