package com.shike.timetable.ui

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/** 旧版 WebView 附加的 X-Requested-With 会让 EAMS 把整页当作局部页面返回。 */
internal fun needsSchoolPageLoader(url: String, method: String, mainFrame: Boolean): Boolean =
    mainFrame && method == "GET" && canReadEamsPage(url)

/** 仅加载教务整页 GET；登录 POST、AJAX 和子资源仍交给 WebView。 */
internal fun loadSchoolPage(
    request: WebResourceRequest,
    onRedirect: (String) -> Unit,
): WebResourceResponse {
    val address = request.url.toString()
    val connection = URL(address).openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        val forwarded = setOf("user-agent", "accept", "accept-language", "referer", "cache-control")
        request.requestHeaders.forEach { (name, value) ->
            if (name.lowercase() in forwarded) connection.setRequestProperty(name, value)
        }
        connection.setRequestProperty("Accept-Encoding", "identity")
        // 会话仅发回当前学校官网；不记录、导出或交给其他域名。
        val cookies = CookieManager.getInstance()
        cookies.getCookie(address)?.let { connection.setRequestProperty("Cookie", it) }
        val status = connection.responseCode
        connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
            .flatMap { it.value }.forEach { cookies.setCookie(address, it) }
        if (status in 300..399) {
            val location = connection.getHeaderField("Location")
            val destination = location?.let { URI(address).resolve(it).toString() }
            require(destination != null && isAllowedSchoolNavigation(destination)) { "教务页面跳转地址不安全。" }
            // 交给浏览器更新地址和认证状态，不在网络层携带 Cookie 跟随跨域跳转。
            onRedirect(destination)
            connection.disconnect()
            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        val contentType = connection.contentType.orEmpty()
        val mime = contentType.substringBefore(';').ifBlank { "text/html" }
        val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1) ?: "UTF-8"
        val raw = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?: ByteArrayInputStream(ByteArray(0))
        val compressed = connection.contentEncoding.equals("gzip", true)
        val decoded = if (compressed) GZIPInputStream(raw) else raw
        val stream = object : FilterInputStream(decoded) {
            override fun close() {
                try { super.close() } finally { connection.disconnect() }
            }
        }
        val headers = connection.headerFields.entries
            .filter { it.key != null && !it.key.equals("Set-Cookie", true) &&
                !(compressed && (it.key.equals("Content-Encoding", true) || it.key.equals("Content-Length", true))) }
            .associate { it.key!! to it.value.joinToString(", ") }
        return WebResourceResponse(mime, charset, status, connection.responseMessage?.ifBlank { "Response" } ?: "Response", headers, stream)
    } catch (error: Exception) {
        connection.disconnect()
        throw error
    }
}
