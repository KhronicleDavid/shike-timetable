package com.shike.timetable.online

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** 无凭据的HTTPS客户端。限制重定向域名、响应长度、下载字节和SHA256。 */
class GitHubHttp(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    class Control {
        @Volatile private var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
            set(value) { field = value; if (cancelled) value?.disconnect() }
        fun check() { if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException() }
        fun cancel() { cancelled = true; connection?.disconnect() }
    }
    fun json(url: String, control: Control = Control(), expected: Artifact? = null): String {
        val connection = open(url, control)
        try {
            val length = connection.contentLengthLong
            require(length <= Distribution.MAX_JSON) { "发布信息过大" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while (true) {
                    control.check(); val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= Distribution.MAX_JSON) { "发布信息过大" }; output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            if (expected != null) verify(bytes.size.toLong(), hash(bytes), expected)
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect(); control.connection = null }
    }
    fun download(item: Artifact, destination: File, control: Control, progress: (Long) -> Unit) {
        Distribution.releaseUrl(item.url)
        require(item.bytes in 1..Distribution.MAX_THEME)
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs(); partial.delete(); destination.delete()
        try {
            val connection = open(item.url, control)
            try {
                val declared = connection.contentLengthLong
                require(declared == -1L || declared == item.bytes) { "文件大小与发布记录不一致，请刷新后重试" }
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L; var last = 0L
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        control.check(); val n = input.read(buffer); if (n < 0) break
                        count += n; require(count <= item.bytes) { "下载文件超出预期大小" }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n)
                        val now = System.nanoTime()
                        if (now - last > 150_000_000) { progress(count); last = now }
                    }
                    output.fd.sync()
                } }
                control.check(); verify(count, digest.digest().hex(), item)
                check(partial.renameTo(destination)) { "无法保存下载文件，请检查剩余空间" }
                progress(count)
            } finally { connection.disconnect(); control.connection = null }
        } catch (error: Exception) { destination.delete(); throw error }
        finally { partial.delete() }
    }
    private fun open(address: String, control: Control): HttpURLConnection {
        var next = URL(address)
        repeat(6) {
            control.check(); require(allowed(next)) { "GitHub下载跳转地址不受支持" }
            val connection = connect(next); control.connection = connection
            try {
                connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Shike-Android")
                connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream;q=0.9, */*;q=0.8")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.useCaches = false
                control.check()
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("GitHub下载地址暂时无效")
                    next = URL(next, location); connection.disconnect()
                } else {
                    if (status != 200) {
                        val message = when (status) {
                            403, 429 -> "GitHub访问次数受限，请稍后重试"
                            404 -> "GitHub暂未提供此文件，请刷新后重试"
                            else -> "GitHub连接失败（$status），请稍后重试"
                        }
                        throw IOException(message)
                    }
                    control.check(); return connection
                }
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        throw IOException("GitHub下载跳转次数过多")
    }
    private fun allowed(url: URL) = url.protocol == "https" && url.port == -1 && url.userInfo == null &&
        url.host in setOf("api.github.com", "raw.githubusercontent.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    companion object {
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun verify(size: Long, sha: String, expected: Artifact) {
            require(size == expected.bytes && sha.equals(expected.sha256, true)) { "文件校验未通过，请重新下载" }
        }
    }
}
