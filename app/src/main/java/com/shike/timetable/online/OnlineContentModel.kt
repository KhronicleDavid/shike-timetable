package com.shike.timetable.online

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shike.timetable.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class OnlineContentModel internal constructor(application: Application, private val http: GitHubHttp) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, GitHubHttp())
    private val app = application
    private var appControl: GitHubHttp.Control? = null
    private var themesControl: GitHubHttp.Control? = null
    private var downloadControl: GitHubHttp.Control? = null
    private var downloadJob: Job? = null
    private val progress = MutableStateFlow<Transfer?>(null)
    val transfer = progress.asStateFlow()
    var checking by mutableStateOf(false); private set
    var appChecked by mutableStateOf(false); private set
    var appError by mutableStateOf<String?>(null); private set
    var update by mutableStateOf<AppUpdate?>(null); private set
    var loadingThemes by mutableStateOf(false); private set
    var themesLoaded by mutableStateOf(false); private set
    var themesError by mutableStateOf<String?>(null); private set
    var themes by mutableStateOf<List<OnlineTheme>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var readyApk by mutableStateOf<File?>(null); private set
    var installing by mutableStateOf(false); private set
    private var readyUpdate: AppUpdate? = null
    val readyVersionCode: Int? get() = readyUpdate?.versionCode

    private var settingsVisit: String? = null
    fun enterSettings(visit: String) {
        if (settingsVisit == visit) return
        settingsVisit = visit; checkUpdate()
        if (!themesLoaded) loadThemes()
    }
    fun checkUpdate() {
        if (checking) return
        checking = true; appChecked = false; appError = null
        val control = GitHubHttp.Control(); appControl = control
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val release = Distribution.release(http.json(Distribution.LATEST, control))
                    val manifest = release.files.singleOrNull { it.name == "shike-update.json" } ?: error("当前发布尚未提供在线更新信息，请稍后重试")
                    require(manifest.artifact.bytes <= Distribution.MAX_JSON)
                    Distribution.update(http.json(manifest.artifact.url, control, manifest.artifact), release)
                }
                update = result; appChecked = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { appError = friendly(e, "无法连接GitHub，请检查网络后重试") }
            finally { checking = false; appControl = null }
        }
    }
    fun loadThemes() {
        if (loadingThemes) return
        loadingThemes = true; themesError = null
        val control = GitHubHttp.Control(); themesControl = control
        viewModelScope.launch {
            try {
                themes = withContext(Dispatchers.IO) { Distribution.themes(http.json(Distribution.THEMES, control)) }
                themesLoaded = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { themesError = friendly(e, "无法加载GitHub主题目录，请检查网络后重试") }
            finally { loadingThemes = false; themesControl = null }
        }
    }
    fun downloadUpdate() {
        val item = update ?: return
        if (item.versionCode <= BuildConfig.VERSION_CODE) return
        if (item.minSdk > Build.VERSION.SDK_INT) { error = "此版本需要Android API ${item.minSdk}或以上系统"; return }
        startDownload("拾课 ${item.versionName}", item.artifact, File(app.cacheDir, "updates/shike-${item.versionCode}.apk")) { file ->
            withContext(Dispatchers.IO) { UpdateInstaller.validate(app, file, item) }
            readyApk?.takeIf { it != file }?.delete(); readyApk = file; readyUpdate = item
            notice = "更新已下载，点击安装更新即可继续"
        }
    }
    fun downloadTheme(item: OnlineTheme, onReady: (File, OnlineTheme) -> Unit) {
        if (item.minVersionCode > BuildConfig.VERSION_CODE) { error = "请先更新拾课，再安装此主题"; return }
        startDownload(item.name, item.artifact, File(app.cacheDir, "online-themes/${item.id}-${item.version}.zip")) { file -> onReady(file, item) }
    }
    private fun startDownload(label: String, artifact: Artifact, file: File, onReady: suspend (File) -> Unit) {
        if (progress.value != null || installing) return
        error = null; notice = null
        val control = GitHubHttp.Control(); downloadControl = control
        progress.value = Transfer(label, 0, artifact.bytes)
        downloadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { http.download(artifact, file, control) { bytes -> progress.value = Transfer(label, bytes, artifact.bytes) } }
                control.check(); onReady(file)
            } catch (e: CancellationException) { file.delete(); notice = "已取消下载" }
            catch (e: Exception) { file.delete(); if (!isActive) notice = "已取消下载" else error = friendly(e, "下载未完成，请检查网络与剩余空间后重试") }
            finally { progress.value = null; downloadControl = null; downloadJob = null }
        }
    }
    fun cancelDownload() { downloadControl?.cancel(); downloadJob?.cancel() }
    fun installApk(openPermission: (Intent) -> Unit, openInstaller: (Intent) -> Unit, canPrompt: Boolean = true) {
        val file = readyApk ?: return; val item = readyUpdate ?: return
        if (installing || progress.value != null) return
        installing = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { UpdateInstaller.validate(app, file, item) }
                if (!app.packageManager.canRequestPackageInstalls()) {
                    if (canPrompt) openPermission(UpdateInstaller.permissionIntent(app))
                    else error = "尚未允许安装更新，可再次点击安装更新并在系统中允许"
                } else openInstaller(UpdateInstaller.installIntent(app, file))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendly(e, "无法打开安装界面，请重新下载或稍后重试") }
            finally { installing = false }
        }
    }
    fun dismissMessage() { error = null; notice = null }
    override fun onCleared() {
        appControl?.cancel(); themesControl?.cancel(); downloadControl?.cancel(); super.onCleared()
    }
    companion object {
        private fun friendly(e: Exception, fallback: String) = e.message?.takeIf { it.any { c -> c in '\u4e00'..'\u9fff' } }?.take(180) ?: fallback
    }
}
data class Transfer(val label: String, val bytes: Long, val total: Long)
