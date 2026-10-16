package com.shike.timetable

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.ScheduleStore
import com.shike.timetable.theme.*
import com.shike.timetable.widget.TodayWidgetViews
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalThemeImportTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tests get() = instrumentation.context
    private val store get() = ExternalThemeStore(context)
    private val clean = object : ExternalResource() {
        override fun before() {
            instrumentation.runOnMainSync { CompanionVoicePlayback.cancel(context) }
            store.installed().forEach { store.remove(it.id) }
            AppearanceStore(context).apply { themeId = "sylvia"; voiceEnabled = true }
            context.getSharedPreferences(CompanionInteractionStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        }
        override fun after() {
            instrumentation.runOnMainSync { CompanionVoicePlayback.cancel(context) }
            AppearanceStore(context).themeId = "minimal"
            store.installed().forEach { store.remove(it.id) }
        }
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(clean).around(compose)

    @Test fun filePickerPreviewCancelImportAndReloadPreserveCoursesAndBundledApkIsMinimal() {
        val schedule = ScheduleStore(context).read()
        assertEquals(listOf("minimal"), ThemeRepository.bundled(context).themes.map { it.id })
        assertEquals("minimal", ThemeRepository.get(context).find(AppearanceStore(context).themeId).id)
        assertNull(context.assets.list("themes/himari")?.firstOrNull())
        compose.onNodeWithTag("companion_quote").assertDoesNotExist()
        val archive = ThemeTestContent.archive(context, tests, "himari")
        val exported = Uri.parse("content://com.shike.timetable.test.themepacks/himari.zip")
        context.contentResolver.openOutputStream(exported)!!.use { output -> archive.inputStream().use { it.copyTo(output) } }; archive.delete()
        compose.onNodeWithText("设置").performClick()
        pick("himari.zip")
        compose.waitUntil(60000) { exists("导入阳茉莉主题") }
        compose.onNodeWithText("取消").performClick()
        assertEquals(listOf("minimal"), ThemeRepository.get(context).themes.map { it.id })
        pick("himari.zip")
        compose.waitUntil(60000) { exists("安装并使用") }
        compose.onNodeWithText("安装并使用").performClick()
        compose.waitUntil(20000) { AppearanceStore(context).themeId == "himari" }
        context.contentResolver.delete(exported, null, null)
        val theme = ThemeRepository.get(context).find("himari")
        assertNotNull(theme.directory)
        assertEquals(114, theme.companion!!.voices.size)
        theme.companion.artworks.forEach { assertNotNull(ThemeImages.load(context, theme, it, 256)) }
        ThemeRepository.invalidate()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertIsDisplayed()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithTag("companion_quote").performClick()
        compose.waitUntil(15000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Playing }
        compose.onNodeWithTag("companion_quote").assertIsNotEnabled()
        compose.waitUntil(20000) { !CompanionVoicePlayback.isBusy }
        compose.onNodeWithTag("companion_quote").assertIsEnabled()
        assertEquals(schedule, ScheduleStore(context).read())
        snapshot("外部主题-阳茉莉")
    }

    @Test fun sameVersionReplacementRefreshesArtPaletteAndVoiceWhileInvalidPackAndRemovalAreSafe() {
        val schedule = ScheduleStore(context).read()
        writeTiny("first.zip", Color.RED, "第一版测试语录", "#365E98")
        compose.onNodeWithText("设置").performClick()
        pick("first.zip"); compose.waitUntil(20000) { exists("安装并使用") }
        compose.onNodeWithText("安装并使用").performClick()
        compose.waitUntil(20000) { AppearanceStore(context).themeId == "external_test" }
        val old = ThemeRepository.get(context).find("external_test")
        val before = ThemeImages.load(context, old, old.companion!!.artworks.single())!!
        assertEquals(Color.RED, before.getPixel(0, 0))
        writeTiny("broken.zip", Color.RED, "损坏包不应生效", "#365E98", broken = true)
        pick("broken.zip"); compose.waitUntil(20000) { exists("主题操作未完成") }
        compose.onNodeWithText("知道了").performClick()
        assertEquals(old.sourceKey, ThemeRepository.get(context).find("external_test").sourceKey)
        writeTiny("second.zip", Color.GREEN, "第二版测试语录", "#39766D")
        pick("second.zip"); compose.waitUntil(20000) { exists("替换并使用") }
        compose.onNodeWithText("替换并使用").performClick()
        compose.waitUntil(20000) { ThemeRepository.get(context).find("external_test").sourceKey != old.sourceKey }
        val changed = ThemeRepository.get(context).find("external_test")
        assertEquals(1, changed.version)
        assertEquals(Color.GREEN, ThemeImages.load(context, changed, changed.companion!!.artworks.single())!!.getPixel(0, 0))
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithText("第二版测试语录").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithText("第二版测试语录").assertIsDisplayed()
        instrumentation.runOnMainSync {
            val widget = TodayWidgetViews.build(context, 990, schedule).apply(context, FrameLayout(context))
            assertTrue(widget.findViewById<TextView>(R.id.widget_quote).text.contains("第二版测试语录"))
            assertEquals(Color.parseColor("#39766D"), widget.findViewById<TextView>(R.id.widget_quote).currentTextColor)
            val bitmap = (widget.findViewById<ImageView>(R.id.widget_portrait).drawable as android.graphics.drawable.BitmapDrawable).bitmap
            assertEquals(Color.GREEN, bitmap.getPixel(0, 0))
        }
        compose.onNodeWithTag("companion_quote").performClick()
        compose.waitUntil(15000) { CompanionVoicePlayback.sessions.value?.phase == VoicePhase.Playing }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("管理主题包").performScrollTo().performClick()
        compose.onNodeWithText("移除").performClick()
        compose.onNodeWithText("确认移除").performClick()
        compose.waitUntil(15000) { AppearanceStore(context).themeId == "minimal" && ThemeRepository.get(context).themes.size == 1 }
        assertFalse(CompanionVoicePlayback.isBusy)
        assertFalse(CompanionInteractionStore(context).showShuffleHint())
        assertEquals(schedule, ScheduleStore(context).read())
        snapshot("外部主题-管理后简约")
    }

    private fun pick(name: String) {
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://com.shike.timetable.test.themepacks/$name")))
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val monitor = instrumentation.addMonitor(filter, result, true)
        try {
            compose.onNodeWithText("导入主题包").performScrollTo().performClick()
            instrumentation.waitForIdleSync()
            assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun writeTiny(name: String, color: Int, line: String, primary: String, broken: Boolean = false) {
        val config = JSONObject(tests.assets.open("himari/theme.json").bufferedReader().use { it.readText() })
        config.put("id", "external_test").put("version", 1).put("name", "测试主题")
        config.getJSONObject("palettes").getJSONObject("light").put("primary", primary)
        config.getJSONObject("palettes").getJSONObject("dark").put("primary", primary)
        val content = config.getJSONObject("companion")
        content.put("artworks", JSONArray().put(JSONObject().put("id","single").put("label","测试插画").put("file","artwork/single.png")
            .put("periods", JSONArray(listOf("morning","noon","evening"))).put("scenes", JSONArray(listOf("greeting","study","rest","settings","empty")))))
        content.put("quotes", JSONObject().apply { listOf("morning","noon","evening").forEach { put(it, JSONArray(listOf(line))) } })
        content.put("voices", JSONArray().put(JSONObject().put("line",line).put("file","voice/test.ogg").put("text","テストです。").put("language","JA")))
        content.remove("contextLines")
        val bitmap = Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray(); bitmap.recycle()
        ZipOutputStream(context.contentResolver.openOutputStream(Uri.parse("content://com.shike.timetable.test.themepacks/$name"))!!).use { zip ->
            listOf("theme.json" to config.toString().toByteArray(), "artwork/single.png" to if (broken) byteArrayOf(0) else image,
                "voice/test.ogg" to tests.assets.open("himari/voice/morning_001.ogg").use { it.readBytes() }).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    private fun snapshot(name: String) {
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle(); SystemClock.sleep(200)
        val output = File(context.getExternalFilesDir(null), "visual-check/$name.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
