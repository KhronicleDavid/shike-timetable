package com.shike.timetable.theme

import java.io.File
import java.util.zip.ZipFile

data class PreparedTheme(val theme: ThemePack, val directory: File, val bytes: Long)

/** 解包只接收数据文件，先完整验证，再交给安装流程发布；失败不影响已安装主题。 */
object ThemeArchive {
    const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    const val MAX_FILE_BYTES = 24L * 1024 * 1024
    const val MAX_CONFIG_BYTES = 1024L * 1024
    const val MAX_ENTRIES = 1024

    fun prepare(archive: File, staging: File, validateMedia: (File) -> Unit = {}): PreparedTheme {
        require(archive.length() in 1..MAX_ARCHIVE_BYTES) { "主题压缩包为空或超过128MB" }
        require(!staging.exists()) { "临时主题目录已存在" }
        check(staging.mkdirs()) { "无法创建主题临时目录" }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().take(MAX_ENTRIES + 1).toList()
                require(entries.size in 1..MAX_ENTRIES) { "主题包文件数量超过限制" }
                entries.forEach { entry ->
                    val path = entry.name.removeSuffix("/")
                    require(path.isNotEmpty() && path.length <= 180 && !path.startsWith('/') &&
                        '\\' !in path && ':' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "主题包包含不安全路径" }
                }
                val names = entries.map { it.name.removeSuffix("/").lowercase(java.util.Locale.ROOT) }
                require(names.distinct().size == names.size) { "主题包包含重复文件路径" }
                val configs = entries.filter { !it.isDirectory && (it.name == "theme.json" || it.name.endsWith("/theme.json")) }
                require(configs.size == 1) { "压缩包必须包含一个theme.json主题配置" }
                val prefix = configs.single().name.removeSuffix("theme.json")
                require(prefix.isEmpty() || prefix.count { it == '/' } == 1) { "主题包目录层级不正确" }
                var total = 0L
                for (entry in entries) {
                    if (entry.isDirectory && (entry.name == prefix || entry.name.startsWith(prefix))) continue
                    require(entry.name.startsWith(prefix) && !entry.isDirectory) { "压缩包包含主题目录以外的文件" }
                    val relative = entry.name.removePrefix(prefix)
                    require(relative == "theme.json" || relative.matches(Regex("(?:[a-zA-Z0-9_-]+/)+[a-zA-Z0-9_.-]+\\.(png|webp|jpg|jpeg|ogg|wav|mp3|m4a)"))) { "主题包只能包含配置、图片和音频" }
                    val target = File(staging, relative)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "主题文件路径越界" }
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    val limit = if (relative == "theme.json") MAX_CONFIG_BYTES else MAX_FILE_BYTES
                    require(entry.size <= limit) { "主题文件过大" }
                    zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var size = 0L
                        val crc = java.util.zip.CRC32()
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count; total += count
                            require(size <= limit && total <= MAX_EXPANDED_BYTES) { "主题解压大小超过限制" }
                            output.write(buffer, 0, count)
                            crc.update(buffer, 0, count)
                        }
                        require(size > 0) { "主题包含空文件" }
                        require((entry.size < 0 || size == entry.size) && (entry.crc < 0 || crc.value == entry.crc)) { "主题文件校验失败" }
                    } }
                }
                val theme = ThemePackParser.parse(File(staging, "theme.json").readText())
                val referenced = theme.companion?.let { content ->
                    content.artworks.flatMap { listOfNotNull(it.file, it.compactFile) } + content.voices.values.map { it.file }
                }.orEmpty().distinct()
                referenced.forEach { path -> require(File(staging, path).isFile) { "主题缺少资源：$path" } }
                staging.walkTopDown().filter { it.isFile && it.name != "theme.json" }.forEach(validateMedia)
                return PreparedTheme(theme, staging, total)
            }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }
}
