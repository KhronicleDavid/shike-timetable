package com.shike.timetable.ui

import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.shike.timetable.MainActivity
import com.shike.timetable.data.TermSettings
import com.shike.timetable.importer.EamsImportParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 WebView 上验证 DOM 捕获链路，全部页面和课程均为虚构，完全不访问学校网络。 */
@RunWith(AndroidJUnit4::class)
class SchoolCaptureSmokeTest {
    @Test fun desktopModeUsesDesktopLayoutAndCanReturnToMobileLayout() {
        withWebView { view ->
            val native = AtomicReference<String>()
            onMainThread {
                native.set(WebSettings.getDefaultUserAgent(view.context))
                assertEquals(desktopSchoolUserAgent(native.get()), view.settings.userAgentString)
                assertTrue(view.settings.supportZoom())
            }
            val page = COURSE_PAGE.replace("<meta charset=\"utf-8\">", "<meta charset=\"utf-8\"><meta name='viewport' content='width=device-width, initial-scale=1'>")
            loadFixture(view, EAMS_BASE, page)
            awaitDocument(view, requireFixture = true)
            awaitCondition(view, "window.matchMedia('(min-width: 1000px)').matches")
            assertEquals(desktopSchoolUserAgent(native.get()), JSONTokener(evaluate(view, "navigator.userAgent")).nextValue())

            onMainThread { applySchoolBrowserMode(view, false) }
            loadFixture(view, EAMS_BASE, page)
            awaitDocument(view, requireFixture = true)
            awaitCondition(view, "!window.matchMedia('(min-width: 1000px)').matches")
            assertEquals(native.get(), JSONTokener(evaluate(view, "navigator.userAgent")).nextValue())
            assertEquals(JSONObject.quote("width=device-width, initial-scale=1"), evaluate(view, "document.querySelector('meta[name=viewport]').content"))
        }
    }

    @Test fun returningDuringCaptureInvalidatesTheOldWebCallback() {
        val html = "<table id='courseTable'><tr><td id='TD0_0' rowspan='2' title='虚构教师 虚构课程(DEMO101);(连1-3,虚构教室)'></td></tr></table>"
        assertEquals("回归测试使用可解析的有效课表", 1, EamsImportParser.parseHtml(html).courses.size)
        val callback = JSONObject.quote(JSONObject().put("error", JSONObject.NULL).put("html", html).toString())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[SchoolImportViewModel::class.java]
                model.activate()
                model.stage = "web"
                val token = model.beginCapture()
                assertTrue(model.busy)
                model.cancelCapture()
                model.stage = "welcome"
                assertFalse("旧网页回调应被立即拒绝，不启动后台解析", model.captureResult(callback, token, TermSettings()))
                assertFalse("返回后不应继续显示读取状态", model.busy)
                assertEquals("旧回调不能重新打开预览", "welcome", model.stage)
                assertEquals(null, model.preview)
                model.close()
            }
        }
    }

    @Test fun renderedDomIsCapturedThenParsedWithoutReadingFormValues() {
        withWebView { view ->
            loadFixture(view, EAMS_BASE, COURSE_PAGE)
            awaitDocument(view, requireFixture = true)
            val payload = capture(view)
            assertTrue(payload.isNull("error"))
            val html = payload.getString("html")
            assertTrue("应读取脚本更新后已渲染的 TD 标题", html.contains("虚构教师 虚构课程(DEMO101)"))
            assertFalse("不读取用户名框", html.contains("fixture-user-attribute"))
            assertFalse("不读取用户实际输入的值", html.contains("fixture-user-typed"))
            assertFalse("不读取 textarea", html.contains("fixture-private-note"))
            assertFalse("不保留表单输入节点", html.contains("<input"))
            assertFalse("不保留文本输入节点", html.contains("<textarea"))

            val result = EamsImportParser.parseHtml(html)
            assertEquals(2, result.courses.size)
            assertEquals(9, result.maxWeek)
            result.courses.forEach { course ->
                assertEquals("虚构课程", course.name)
                assertEquals("虚构教师", course.teacher)
                assertEquals(1, course.day)
                assertEquals(1, course.startPeriod)
                assertEquals(2, course.endPeriod)
            }
            assertEquals(
                setOf("虚构教室 A|1|3|all", "虚构教室 B|5|9|odd"),
                result.courses.map { "${it.room}|${it.startWeek}|${it.endWeek}|${it.weekPattern}" }.toSet(),
            )
        }
    }

    @Test fun passwordLoginFormRefusesCaptureEvenWhenThePageContainsACourseTable() {
        withWebView { view ->
            val page = COURSE_PAGE.replace("</body>", "<form><input type='password' value='fixture-password-secret'></form></body>")
            loadFixture(view, EAMS_BASE, page)
            awaitDocument(view, requireFixture = true)
            val payload = capture(view)
            assertTrue(payload.getString("error").contains("登录"))
            assertTrue("登录页面不返回 HTML", payload.isNull("html"))
            assertFalse(payload.toString().contains("fixture-password-secret"))
        }
    }

    @Test fun nonSchoolOriginRefusesCapture() {
        withWebView { view ->
            loadFixture(view, "https://outside.invalid/eams/demo.html", COURSE_PAGE)
            // 安全 WebView 可以直接阻止此页面；等待空白页或拒绝后的页面 DOM 就绪即可。
            awaitDocument(view, requireFixture = false)
            val payload = capture(view)
            assertTrue(payload.getString("error").contains("教务系统"))
            assertTrue("学校域名之外不返回 HTML", payload.isNull("html"))
        }
    }

    private fun withWebView(test: (WebView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { activity ->
                view = createSchoolWebView(activity, onProgress = {}, onLocation = {}, onError = {})
                activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            try {
                test(view)
            } finally {
                onMainThread {
                    (view.parent as? ViewGroup)?.removeView(view)
                    destroySchoolWebView(view)
                    clearSchoolSession()
                }
            }
        }
    }

    private fun loadFixture(view: WebView, origin: String, html: String) {
        onMainThread { view.loadDataWithBaseURL(origin, html, "text/html", "UTF-8", null) }
    }

    /** 等待发生在测试工作线程；任何 WebView API 始终在主线程调用。 */
    private fun awaitDocument(view: WebView, requireFixture: Boolean) {
        val script = if (requireFixture) {
            "document.readyState === 'complete' && !!document.querySelector('[data-shike-fixture=\"capture-test\"]')"
        } else "document.readyState === 'complete'"
        awaitCondition(view, script)
    }

    private fun awaitCondition(view: WebView, script: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (evaluate(view, script) == "true") return
            SystemClock.sleep(100)
        }
        throw AssertionError("虚构测试页面未在 15 秒内完成 DOM 加载")
    }

    private fun capture(view: WebView): JSONObject {
        val raw = evaluate(view, EAMS_CAPTURE_SCRIPT)
        val json = JSONTokener(raw).nextValue() as? String
            ?: throw AssertionError("evaluateJavascript 没有返回编码后的 JSON 字符串")
        return JSONObject(json)
    }

    private fun evaluate(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        onMainThread {
            view.evaluateJavascript(script) { raw ->
                result.set(raw)
                latch.countDown()
            }
        }
        assertTrue("WebView JavaScript 回调应在 10 秒内返回", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun onMainThread(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private companion object {
        const val EAMS_BASE = "https://eams.uestc.edu.cn/eams/demo.html"
        val COURSE_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"></head>
            <body data-shike-fixture="capture-test">
              <table id="courseTable"><tbody><tr><td id="TD0_0" rowspan="2"></td></tr></tbody></table>
              <input id="fixture-user" type="text" value="fixture-user-attribute">
              <textarea>fixture-private-note</textarea>
              <script>var unitCount = 12;</script>
              <script>
                document.getElementById('TD0_0').setAttribute('title',
                  '虚构教师 虚构课程(DEMO101);(连1-3,虚构教室 A);(单5-9,虚构教室 B)');
                document.getElementById('TD0_0').textContent = '虚构课程';
                document.getElementById('fixture-user').value = 'fixture-user-typed';
              </script>
            </body></html>
        """.trimIndent()
    }
}
