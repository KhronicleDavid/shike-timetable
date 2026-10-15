package com.shike.timetable.ui

import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 人工登录后手动开启的真实网络诊断；只输出资源状态和课表条数。 */
class SchoolLiveResourcesTest {
    @Test fun inspectRealSchoolResources() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSchool") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val variant = InstrumentationRegistry.getArguments().getString("variant") ?: "current"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { activity ->
                view = createSchoolWebView(activity, {}, {}, {})
                if (variant == "default-cache" || variant == "minimal") view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (variant == "native") applySchoolBrowserMode(view, false)
                if (variant == "minimal" || variant == "header-all" || variant == "header-normal") {
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            if (!isAllowedSchoolNavigation(request.url.toString())) return true
                            if ((variant == "header-all" || variant == "header-normal") && request.method == "GET") {
                                view.loadUrl(request.url.toString(), mapOf("X-Requested-With" to if (variant == "header-normal") "normal" else ""))
                                return true
                            }
                            return false
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                            Log.i("ShikeLiveResources", "header $variant main=${request.isForMainFrame} ${request.url.path} xrw=" + request.requestHeaders.entries.firstOrNull { it.key.equals("X-Requested-With", true) }?.value)
                            return null
                        }
                    }
                }
                activity.addContentView(view, ViewGroup.LayoutParams(-1, -1))
                if (variant == "table") view.loadUrl(SCHOOL_TABLE_URL)
                else if (variant == "full-page") view.loadUrl("https://eams.uestc.edu.cn/eams/home.action?ignoreHead=0")
                else if (variant == "header-normal") view.loadUrl("https://eams.uestc.edu.cn/eams/home.action", mapOf("X-Requested-With" to "normal"))
                else if (variant == "header-all") view.loadUrl("https://eams.uestc.edu.cn/eams/home.action", mapOf("X-Requested-With" to ""))
                else if (variant == "header-empty") view.loadUrl(SCHOOL_ENTRY_URL, mapOf("X-Requested-With" to ""))
                else view.loadUrl(SCHOOL_ENTRY_URL)
            }
            try {
                SystemClock.sleep(10_000)
                val result = AtomicReference<String>()
                val latch = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    view.evaluateJavascript("""
                        JSON.stringify({path:location.pathname,basePath:new URL(document.baseURI).pathname,
                          jquery:typeof window.jQuery,bg:typeof window.bg,headTags:document.head?Array.from(document.head.children).map(e=>e.tagName):[],
                          links:Array.from(document.querySelectorAll('link[href]')).map(function(e){
                            var u=new URL(e.href);return {rel:e.rel,url:u.protocol+'//'+u.host+u.pathname,loaded:!!e.sheet};
                          }),scripts:Array.from(document.querySelectorAll('script[src]')).map(function(e){
                            var u=new URL(e.src);return u.protocol+'//'+u.host+u.pathname;
                          }),inlineScriptCount:document.querySelectorAll('script:not([src])').length})
                    """.trimIndent()) { raw -> result.set(raw); latch.countDown() }
                }
                assertTrue(latch.await(10, TimeUnit.SECONDS))
                Log.i("ShikeLiveResources", "$variant ${JSONTokener(result.get()).nextValue()}")
                if (variant == "table") {
                    val captured = AtomicReference<String>()
                    val captureLatch = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        view.evaluateJavascript(EAMS_CAPTURE_SCRIPT) { raw -> captured.set(raw); captureLatch.countDown() }
                    }
                    assertTrue(captureLatch.await(10, TimeUnit.SECONDS))
                    val payload = org.json.JSONObject(JSONTokener(captured.get()).nextValue() as String)
                    assertTrue("应能读取实际课表", payload.isNull("error"))
                    val parsed = com.shike.timetable.importer.EamsImportParser.parseHtml(payload.getString("html"))
                    assertTrue(parsed.courses.isNotEmpty())
                    Log.i("ShikeLiveResources", "table-capture courses=${parsed.courses.size} maxWeek=${parsed.maxWeek}")
                }
                if (variant == "native-http") {
                    val address = "https://eams.uestc.edu.cn/eams/home.action"
                    val connection = java.net.URL(address).openConnection() as javax.net.ssl.HttpsURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 15_000
                        connection.setRequestProperty("User-Agent", desktopSchoolUserAgent(android.webkit.WebSettings.getDefaultUserAgent(instrumentation.targetContext)))
                        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        android.webkit.CookieManager.getInstance().getCookie(address)?.let { connection.setRequestProperty("Cookie", it) }
                        val status = connection.responseCode
                        val text = (if (status >= 400) connection.errorStream else connection.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val metadata = org.json.JSONObject().put("status", status)
                            .put("headCount", Regex("<head\\b",RegexOption.IGNORE_CASE).findAll(text).count())
                            .put("linkCount", Regex("<link\\b",RegexOption.IGNORE_CASE).findAll(text).count())
                            .put("externalScripts", Regex("<script[^>]*\\bsrc=",RegexOption.IGNORE_CASE).findAll(text).count())
                            .put("guard", text.contains("${'$'}_ts"))
                        Log.i("ShikeLiveResources", "native-http $metadata")
                    } finally { connection.disconnect() }
                }
                if (variant == "fetch-check") {
                    val fetchLatch = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        view.webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                if (message.message().startsWith("ShikeFetch:")) {
                                    Log.i("ShikeLiveResources", message.message()); fetchLatch.countDown()
                                }
                                return false
                            }
                        }
                        view.evaluateJavascript("""
                            Promise.all([{}, {'X-Requested-With':''}, {'X-Requested-With':'XMLHttpRequest'}].map(function(headers) {
                              return fetch(location.href,{credentials:'same-origin',headers:headers}).then(function(r){return r.text().then(function(t){
                                return {header:headers['X-Requested-With']===undefined?'absent':headers['X-Requested-With'],status:r.status,
                                  headCount:(t.match(/<head\b/gi)||[]).length,linkCount:(t.match(/<link\b/gi)||[]).length,
                                  externalScripts:(t.match(/<script[^>]*\bsrc=/gi)||[]).length,guard:t.indexOf('${'$'}_ts')>=0};
                              });});
                            })).then(function(results){console.log('ShikeFetch:'+JSON.stringify(results));});
                        """.trimIndent(), null)
                    }
                    assertTrue(fetchLatch.await(20, TimeUnit.SECONDS))
                }
            } finally {
                instrumentation.runOnMainSync {
                    (view.parent as? ViewGroup)?.removeView(view)
                    destroySchoolWebView(view)
                }
            }
        }
    }
}
