package com.shike.timetable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.ScheduleStore
import com.shike.timetable.online.*
import com.shike.timetable.theme.*
import com.shike.timetable.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 独立测试页面与公开虚构主题，不使用学校会话或个人课表。 */
class OnlineContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application
    private val prefix = "https://github.com/${Distribution.REPOSITORY}/releases/download/v-test/"
    private class Response(url: URL, val body: ByteArray, val status: Int = 200, val slow: Boolean = false) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): InputStream = if (!slow) ByteArrayInputStream(body) else object : ByteArrayInputStream(body) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(25); return super.read(buffer, offset, minOf(length, 512)) }
        }
    }
    private class Feed(val files: Map<String,ByteArray>, var slow: Boolean = false) {
        val counts = ConcurrentHashMap<String,AtomicInteger>()
        var offline = false
        val http = GitHubHttp { url ->
            counts.computeIfAbsent(url.toString()) { AtomicInteger() }.incrementAndGet()
            val body = files[url.toString()]
            Response(url, body ?: byteArrayOf(), if (offline) 503 else if (body == null) 404 else 200, slow && url.path.endsWith(".zip"))
        }
    }
    private fun themeZip(version: Int = 4): ByteArray {
        val config = JSONObject(context.assets.open("themes/minimal/theme.json").bufferedReader().use { it.readText() })
            .put("id","online_test").put("name","在线测试主题").put("description","公开虚构素材，仅供测试").put("version",version)
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("theme.json")); zip.write(config.toString().toByteArray()); zip.closeEntry()
        } }.toByteArray()
    }
    private fun feed(zip: ByteArray = themeZip(), slow: Boolean = false): Feed {
        val manifest = JSONObject().put("schemaVersion",1).put("tag","v-test").put("versionName","99.0.0")
            .put("versionCode",BuildConfig.VERSION_CODE+1).put("minSdk",26).put("assetName","update.apk").toString().toByteArray()
        val apk = "invalid-apk-for-validation-test".toByteArray()
        fun asset(name: String, bytes: ByteArray) = JSONObject().put("name",name).put("state","uploaded")
            .put("browser_download_url",prefix+name).put("size",bytes.size).put("digest","sha256:"+GitHubHttp.hash(bytes))
        val release = JSONObject().put("draft",false).put("prerelease",false).put("tag_name","v-test").put("body","新增在线主题下载与更新说明。")
            .put("assets",JSONArray().put(asset("shike-update.json",manifest)).put(asset("update.apk",apk)))
        val catalog = JSONObject().put("schemaVersion",1).put("themes",JSONArray().put(JSONObject().put("id","online_test").put("version",4)
            .put("name","在线测试主题").put("description","测试用简约配色").put("notes","下载后确认安装").put("minVersionCode",13)
            .put("url",prefix+"theme.zip").put("bytes",zip.size).put("sha256",GitHubHttp.hash(zip))))
        return Feed(mapOf(Distribution.LATEST to release.toString().toByteArray(), Distribution.THEMES to catalog.toString().toByteArray(),
            prefix+"shike-update.json" to manifest, prefix+"update.apk" to apk, prefix+"theme.zip" to zip),slow)
    }
    private fun launch(feed: Feed): Triple<OnlineContentModel,ThemeImportModel,ViewModelStore> {
        lateinit var model: OnlineContentModel; lateinit var importer: ThemeImportModel
        val models=ViewModelStore()
        compose.runOnUiThread { model=OnlineContentModel(app,feed.http); importer=ThemeImportModel(app); models.put("online",model); models.put("importer",importer) }
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { OnlineSettings(model,ThemeRepository.get(context).themes,importer) }
            ThemeImportDialogs(importer,ThemeRepository.get(context).themes); OnlineMessages(model)
        } }
        return Triple(model,importer,models)
    }
    @Test fun automaticManualChecksNotesOfflineRecoveryAndInvalidApk() {
        val feed=feed(); val (model,_,models)=launch(feed)
        try {
            compose.waitUntil(10000) { model.appChecked && !model.loadingThemes }
            assertEquals(1,feed.counts[Distribution.LATEST]!!.get())
            compose.onNodeWithText("发现新版本 99.0.0").assertIsDisplayed()
            compose.onNodeWithText("更新内容").performClick()
            compose.onNodeWithText("新增在线主题下载与更新说明。").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            feed.offline=true
            compose.onNodeWithText("检查更新").performClick()
            compose.waitUntil(10000) { model.appError != null }
            compose.onNodeWithTag("update_status").assertTextContains("GitHub连接失败",substring=true)
            assertFalse(model.appChecked)
            feed.offline=false
            compose.onNodeWithText("检查更新").performClick()
            compose.waitUntil(10000) { model.appChecked }
            compose.onNodeWithText("下载更新").performClick()
            compose.waitUntil(10000) { model.error != null }
            assertNull(model.readyApk)
            compose.onNodeWithText("操作未完成").assertIsDisplayed()
            val visits=feed.counts[Distribution.LATEST]!!.get()
            compose.runOnUiThread { model.enterSettings("same-visit") }
            compose.waitUntil(10000) { !model.checking }
            compose.runOnUiThread { model.enterSettings("same-visit") }
            assertEquals(visits+1,feed.counts[Distribution.LATEST]!!.get())
        } finally { compose.runOnUiThread { models.clear() } }
    }
    @Test fun onlineZipPreviewCancelThenInstallKeepsSchedule() {
        val store=ExternalThemeStore(context); store.installed().filter { it.id=="online_test" }.forEach { store.remove(it.id) }
        val local=java.io.File(context.cacheDir,"online-old.zip").apply { writeBytes(themeZip(3)) }
        try { store.install(store.prepare(local)) } finally { local.delete() }
        val before=ScheduleStore(context).read()
        val (model,importer,models)=launch(feed())
        try {
            compose.waitUntil(10000) { model.themesLoaded }
            fun download() {
                compose.onNodeWithTag("online_themes").performScrollTo().performClick()
                compose.onNodeWithTag("download_theme_online_test").performScrollTo().performClick()
                compose.waitUntil(10000) { importer.pending != null }
            }
            download(); compose.onNodeWithText("导入在线测试主题").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            assertEquals(3,store.installed().single { it.id=="online_test" }.version)
            download(); compose.onNodeWithText("替换并使用").performClick()
            compose.waitUntil(10000) { importer.installed != null }
            assertEquals(4,store.installed().single { it.id=="online_test" }.version)
            assertEquals(before,ScheduleStore(context).read())
        } finally { compose.runOnUiThread { models.clear() }; store.installed().filter { it.id=="online_test" }.forEach { store.remove(it.id) } }
    }
    @Test fun cancellationAndCorruptThemeDoNotChangeInstalledThemes() {
        val invalid=ByteArray(512_000) { (it%251).toByte() }
        val source=feed(invalid,true)
        val (model,importer,models)=launch(source)
        val before=ExternalThemeStore(context).installed().map { it.id to it.version }
        try {
            compose.waitUntil(10000) { model.themesLoaded }
            compose.onNodeWithTag("online_themes").performScrollTo().performClick()
            compose.onNodeWithTag("download_theme_online_test").performScrollTo().performClick()
            compose.waitUntil(10000) { model.transfer.value?.bytes?.let { it > 0 } == true }
            compose.onNodeWithText("取消下载").performScrollTo().performClick()
            compose.waitUntil(10000) { model.transfer.value == null }
            assertNull(importer.pending)
            assertFalse(java.io.File(context.cacheDir,"online-themes/online_test-4.zip.part").exists())
            source.slow=false
            compose.onNodeWithTag("download_theme_online_test").performScrollTo().performClick()
            compose.waitUntil(10000) { importer.error != null }
            assertNull(importer.pending)
            assertEquals(before,ExternalThemeStore(context).installed().map { it.id to it.version })
        } finally { compose.runOnUiThread { models.clear() } }
    }
}
