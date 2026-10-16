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
    private fun update(file: File) = AppUpdate("1.11.0",14,26,"测试",Artifact(
        "https://github.com/${Distribution.REPOSITORY}/releases/download/v1.11.0/shike-1.11.0-compact.apk", file.length(),GitHubHttp.hash(file.readBytes())))
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
        listOf(valid.copy(versionCode=15), valid.copy(versionName="bad"), valid.copy(artifact=valid.artifact.copy(sha256="0".repeat(64)))).forEach { item ->
            assertThrows(Exception::class.java) { UpdateInstaller.validate(context,file,item) }
        }
        val other = object : ContextWrapper(context) { override fun getPackageName() = "android" }
        assertThrows(Exception::class.java) { UpdateInstaller.validate(other,file,valid) }
    }
    @Test fun samePackageAndVersionWithForeignSignatureIsRejected() {
        val file=fixture("test-other-signature.apk"); assertTrue(file.isFile)
        val error=assertThrows(Exception::class.java) { UpdateInstaller.validate(context,file,update(file)) }
        assertTrue(error.message!!.contains("签名"))
    }
}
