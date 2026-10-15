package com.shike.timetable.ui

import android.content.Context
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal const val SCHOOL_ENTRY_URL = "https://eams.uestc.edu.cn/eams/"
internal const val SCHOOL_TABLE_URL = "https://eams.uestc.edu.cn/eams/courseTableForStd!courseTable.action"
internal const val MAX_SCHOOL_FILE_BYTES = 2 * 1024 * 1024

/** 保留当前 WebView 内核版本，让学校按桌面 Chrome 提供页面。 */
internal fun desktopSchoolUserAgent(nativeAgent: String): String = nativeAgent
    .replaceFirst(Regex("\\([^)]*\\)"), "(Windows NT 10.0; Win64; x64)")
    .replace(Regex("\\s+Version/[\\d.]+"), "")
    .replace(Regex("\\s+Mobile(?=\\s|$)"), "")

internal fun applySchoolBrowserMode(view: WebView, desktop: Boolean) {
    val nativeAgent = WebSettings.getDefaultUserAgent(view.context)
    view.settings.userAgentString = if (desktop) desktopSchoolUserAgent(nativeAgent) else nativeAgent
    view.settings.useWideViewPort = true
    view.settings.loadWithOverviewMode = true
    view.settings.setSupportZoom(true)
    view.setInitialScale(0)
}

/** 仅调整教务页面的布局；学校登录页面沿用自己的视口。 */
internal val SCHOOL_DESKTOP_VIEWPORT_SCRIPT = """
    (function() {
      if (location.protocol !== 'https:' || location.hostname !== 'eams.uestc.edu.cn' || location.pathname.indexOf('/eams/') !== 0) return;
      var meta = document.querySelector('meta[name="viewport"]');
      if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; document.head.appendChild(meta); }
      meta.content = 'width=1280, user-scalable=yes';
    })();
""".trimIndent()

/** 顶层页面只能在学校的 HTTPS 域名内打开。 */
internal fun isAllowedSchoolNavigation(url: String): Boolean = runCatching {
    if (url.contains('\\') || url.any { it.isISOControl() }) return@runCatching false
    val uri = URI(url)
    val host = uri.host?.lowercase() ?: return@runCatching false
    uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "uestc.edu.cn" || host.endsWith(".uestc.edu.cn"))
}.getOrDefault(false)

internal fun canReadEamsPage(url: String): Boolean = isAllowedSchoolNavigation(url) && runCatching {
    val uri = URI(url)
    uri.host.equals("eams.uestc.edu.cn", ignoreCase = true) && uri.path.startsWith("/eams/")
}.getOrDefault(false)

internal fun schoolHost(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

/** 文件始终设定字节上限，旧版教务系统导出的 GBK HTML 也能读取。 */
internal fun readSchoolFile(stream: InputStream): String {
    val bytes = stream.use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            require(output.size() + read <= MAX_SCHOOL_FILE_BYTES) { "课表文件不能超过 2 MB，请仅导出课表页面。" }
            output.write(chunk, 0, read)
        }
        output.toByteArray()
    }
    require(bytes.isNotEmpty()) { "所选文件为空，请重新选择课表文件。" }
    val prefix = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
    val charset = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        Regex("charset\\s*=\\s*['\"]?\\s*(gb2312|gbk|gb18030)", RegexOption.IGNORE_CASE).containsMatchIn(prefix) -> Charset.forName("GB18030")
        else -> Charsets.UTF_8
    }
    return try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: Exception) {
        throw IllegalArgumentException("文件文字编码无法识别，请以 UTF-8 或 GBK 保存课表 HTML 后重试。")
    }
}

@Suppress("SetJavaScriptEnabled", "DEPRECATION")
internal fun createSchoolWebView(
    context: Context,
    onProgress: (Int) -> Unit,
    onLocation: (String) -> Unit,
    onError: (String) -> Unit,
    desktopMode: Boolean = true,
): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        saveFormData = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
    }
    applySchoolBrowserMode(this, desktopMode)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val https = request.url.scheme.equals("https", ignoreCase = true)
            val blocked = !https || (request.isForMainFrame && !isAllowedSchoolNavigation(url))
            if (blocked && request.isForMainFrame) onError("已阻止跳转到学校域名之外的页面，请从学校教务入口继续。")
            return blocked
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val blocked = !isAllowedSchoolNavigation(url)
            if (blocked) onError("已阻止不安全或非学校域名的跳转，请从学校教务入口继续。")
            return blocked
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (!isAllowedSchoolNavigation(url)) {
                view.stopLoading()
                onError("只能打开电子科技大学的 HTTPS 页面。")
                return
            }
            onLocation(url)
            onProgress(0)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // 主框架 POST 和重定向也必须经过同样的域名限制。
            if (request.isForMainFrame && !isAllowedSchoolNavigation(request.url.toString())) {
                view.post { onError("已阻止非学校域名或不安全的页面请求，请从教务入口继续。") }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            if (request.url.scheme.equals("file", true) || request.url.scheme.equals("content", true)) {
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            if (needsSchoolPageLoader(request.url.toString(), request.method, request.isForMainFrame)) {
                return try {
                    loadSchoolPage(request) { destination -> view.post { view.loadUrl(destination) } }
                } catch (_: Exception) {
                    view.post { onError("教务页面加载失败，请检查网络后刷新。") }
                    WebResourceResponse("text/plain", "UTF-8", 502, "Bad Gateway", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
            return null
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (isAllowedSchoolNavigation(url)) onLocation(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (isAllowedSchoolNavigation(url)) onLocation(url)
            if (canReadEamsPage(url) && view.settings.userAgentString.contains("(Windows NT 10.0; Win64; x64)")) {
                view.evaluateJavascript(SCHOOL_DESKTOP_VIEWPORT_SCRIPT, null)
            }
            onProgress(100)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            onProgress(100)
            onError("学校页面的安全证书验证失败，已停止连接。请稍后重试或从课表文件导入。")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                onProgress(100)
                onError("网页暂时无法打开，请检查网络后刷新，或从课表文件导入。")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) onError("学校服务器暂时无法提供页面（${errorResponse.statusCode}），请稍后刷新。")
        }
    }
}

internal fun destroySchoolWebView(webView: WebView?) {
    webView ?: return
    runCatching {
        webView.stopLoading()
        webView.clearHistory()
        webView.clearFormData()
        webView.clearCache(true)
        webView.removeAllViews()
        webView.destroy()
    }
}

/** 仅清除本应用 WebView 的会话，不涉及系统浏览器或其他应用。 */
internal fun clearSchoolSession() {
    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
    WebStorage.getInstance().deleteAllData()
}

/** 只读取已渲染 DOM；脚本文本可用于解析 TaskActivity，从不执行这些文本。 */
internal val EAMS_CAPTURE_SCRIPT = """
    (function() {
      var limit = 2097152;
      function result(error, html) { return JSON.stringify({error: error || null, html: html || null}); }
      if (location.protocol !== 'https:' || location.hostname !== 'eams.uestc.edu.cn' || location.pathname.indexOf('/eams/') !== 0)
        return result('请先回到教务系统，在「我的课表」页面读取。');
      if (document.querySelector('input[type="password"]'))
        return result('当前是学校登录页面，请自行完成登录，再打开「我的课表」。');
      var documents = [], seen = [], candidates = [];
      function visit(win, depth) {
        if (depth > 4 || documents.length >= 20) return;
        try {
          var doc = win.document;
          if (win.location.origin !== location.origin || win.location.pathname.indexOf('/eams/') !== 0 || seen.indexOf(doc) >= 0) return;
          seen.push(doc); documents.push(doc);
          for (var i = 0; i < win.frames.length && i < 20; i++) visit(win.frames[i], depth + 1);
        } catch (_) {}
      }
      visit(window, 0);
      for (var i = 0; i < documents.length; i++) {
        var doc = documents[i];
        if (doc.querySelector('input[type="password"]')) continue;
        var cells = doc.querySelectorAll('td[id^="TD"]'), filled = 0;
        for (var c = 0; c < cells.length; c++) {
          if (/^TD\d+_\d+$/.test(cells[c].id) && (cells[c].getAttribute('title') || cells[c].textContent || '').trim()) filled++;
        }
        var scripts = doc.querySelectorAll('script'), activities = 0;
        for (var s = 0; s < scripts.length; s++) {
          var matches = (scripts[s].textContent || '').match(/new\s+TaskActivity\s*\(/g);
          if (matches) activities += matches.length;
        }
        var table = doc.querySelector('table#courseTable, table[id^="courseTable"]');
        var tableText = table ? table.textContent : '';
        var recognizable = filled > 0 || activities > 0 || (table && /星期[一二三四五六日天]/.test(tableText) && /节次|周次|第.*节/.test(tableText));
        if (recognizable) candidates.push({doc: doc, score: (filled > 0 ? 100000 : 0) + filled * 100 + activities});
      }
      if (!candidates.length)
        return result('尚未找到课表。请完成学校登录，选择学期并打开「我的课表」。访问验证或空白页可先刷新，或从课表文件导入。');
      candidates.sort(function(a, b) { return b.score - a.score; });
      var clone = candidates[0].doc.documentElement.cloneNode(true);
      var remove = clone.querySelectorAll('input, textarea, select, iframe, frame');
      for (var r = 0; r < remove.length; r++) remove[r].remove();
      var scriptNodes = clone.querySelectorAll('script');
      for (var j = 0; j < scriptNodes.length; j++) {
        if (!/new\s+TaskActivity|new\s+CourseTable|marshalTable|unitCount/.test(scriptNodes[j].textContent || '')) scriptNodes[j].remove();
      }
      var html = clone.outerHTML;
      if (new Blob([html]).size > limit) return result('课表页面超过 2 MB，请导出较小的课表 HTML 文件后导入。');
      return result(null, html);
    })();
""".trimIndent()
