package com.shike.timetable

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.online.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume
import java.io.File

/** 运行于本机专用的低版本测试构建，使用待发布的真实APK及不同签名的测试副本。 */
class UpdateInstallerTest {
    @Before fun requireExplicitInstallerFixtures() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("installerFixtures") == "true")
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name: String) = File(context.cacheDir,"updates/$name")
    private fun update(file: File) = AppUpdate("1.11.1",15,26,"测试",Artifact(
        "https://github.com/${Distribution.REPOSITORY}/releases/download/v1.11.1/shike-1.11.1-compact.apk", file.length(),GitHubHttp.hash(file.readBytes())))
    @Test fun actualSignedNewerApkPassesAndOnlyUpdateDirectoryIsShared() {
        val file=fixture("test-valid.apk"); assertTrue(file.isFile)
        UpdateInstaller.validate(context,file,update(file))
        val intent=UpdateInstaller.installIntent(context,file)
        assertEquals("application/vnd.android.package-archive",intent.type)
        assertEquals("${context.packageName}.updates",intent.data!!.authority)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val outside=File(context.filesDir,"test-private.json").apply { writeText("{}") }
        try { assertThrows(Exception::class.java) { UpdateInstaller.installIntent(context,outside) } }
        finally { outside.delete() }
    }
    @Test fun alteredDigestUnexpectedVersionOrWrongPackageNeverReachInstaller() {
        val file=fixture("test-valid.apk"); val valid=update(file)
        listOf(valid.copy(versionCode=16), valid.copy(versionName="bad"), valid.copy(artifact=valid.artifact.copy(sha256="0".repeat(64)))).forEach { item ->
            assertThrows(Exception::class.java) { UpdateInstaller.validate(context,file,item) }
        }
        val other = object : ContextWrapper(context) { override fun getPackageName() = "android" }
        assertThrows(Exception::class.java) { UpdateInstaller.validate(other,file,valid) }
    }
    @Test fun readyApkIsRevalidatedAcrossNewProcessModelAndTamperingIsRejected() {
        val store=PendingUpdateStore(context)
        val original=fixture("test-valid.apk"); val item=update(original); val cached=store.file(item)
        original.copyTo(cached,overwrite=true)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val models=androidx.lifecycle.ViewModelStore()
        try {
            store.save(item,true)
            lateinit var recovered: OnlineContentModel
            instrumentation.runOnMainSync {
                recovered=OnlineContentModel(context.applicationContext as android.app.Application)
                models.put("first",recovered)
            }
            fun await(model: OnlineContentModel) {
                val end=android.os.SystemClock.elapsedRealtime()+10000
                while (model.restoring && android.os.SystemClock.elapsedRealtime()<end) android.os.SystemClock.sleep(25)
                assertFalse(model.restoring)
            }
            await(recovered)
            assertEquals(cached,recovered.readyApk)
            assertEquals(item.versionCode,recovered.readyVersionCode)
            assertEquals(context.packageManager.canRequestPackageInstalls(),recovered.resumeInstall)
            instrumentation.runOnMainSync { models.clear() }
            // 缓存变动必须再次按摘要与签名拒绝，不能仅信任磁盘记录。
            cached.appendBytes(byteArrayOf(1))
            lateinit var rejected: OnlineContentModel
            instrumentation.runOnMainSync {
                rejected=OnlineContentModel(context.applicationContext as android.app.Application)
                models.put("second",rejected)
            }
            await(rejected)
            assertNull(rejected.readyApk); assertNull(store.load())
        } finally { instrumentation.runOnMainSync { models.clear() }; store.clear(); cached.delete() }
    }
    @Test fun samePackageAndVersionWithForeignSignatureIsRejected() {
        val file=fixture("test-other-signature.apk"); assertTrue(file.isFile)
        val error=assertThrows(Exception::class.java) { UpdateInstaller.validate(context,file,update(file)) }
        assertTrue(error.message!!.contains("签名"))
    }
}
