package com.shike.timetable.online

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, update: AppUpdate) {
        require(file.isFile && file.length() == update.artifact.bytes && file.inputStream().use {
            val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768)
            while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) } == update.artifact.sha256
        }) { "安装包校验未通过，请重新下载" }
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val downloaded = manager.getPackageArchiveInfo(file.path, flags) ?: error("安装包无法识别，请重新下载")
        val current = manager.getPackageInfo(context.packageName, flags)
        val nextCode = if (Build.VERSION.SDK_INT >= 28) downloaded.longVersionCode else downloaded.versionCode.toLong()
        val currentCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        require(downloaded.packageName == context.packageName && nextCode == update.versionCode.toLong() &&
            downloaded.versionName == update.versionName && nextCode > currentCode) { "安装包与目标版本不符，或已安装此版本" }
        require(downloaded.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "此版本不支持当前Android系统" }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> {
            val values = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return values.orEmpty().map { GitHubHttp.hash(it.toByteArray()) }.toSet()
        }
        val original = signatures(current)
        require(original.isNotEmpty() && signatures(downloaded) == original) { "安装包签名与当前应用不同，已停止安装" }
    }
    fun permissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri("拾课更新", uri) }
    }
}
