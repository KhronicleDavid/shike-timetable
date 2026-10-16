package com.shike.timetable.ui

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shike.timetable.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThemeImportModel(application: Application) : AndroidViewModel(application) {
    private val store = ExternalThemeStore(application)
    var pending by mutableStateOf<PreparedTheme?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var installed by mutableStateOf<ThemePack?>(null); private set

    fun prepare(uri: Uri) {
        if (busy || pending != null) return
        busy = true; error = null
        viewModelScope.launch {
            var prepared: PreparedTheme? = null
            try {
                withContext(Dispatchers.IO) { prepared = store.prepare(uri) }
                pending = prepared
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendly(e, "主题包无法读取或配置不完整，请检查ZIP文件和剩余空间") }
            finally { if (pending !== prepared) prepared?.let(store::discard); busy = false }
        }
    }
    /** 在线下载仍通过完整ZIP校验和同一套预览，条目身份必须与包内一致。 */
    fun prepareOnline(file: java.io.File, item: com.shike.timetable.online.OnlineTheme) {
        if (busy || pending != null) { file.delete(); return }
        busy = true; error = null
        viewModelScope.launch {
            var prepared: PreparedTheme? = null
            try {
                withContext(Dispatchers.IO) {
                    prepared = store.prepare(file)
                    require(prepared!!.theme.id == item.id && prepared!!.theme.version == item.version) { "主题内容与在线目录不一致，请刷新后重试" }
                }
                pending = prepared
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendly(e, "主题包无法读取或配置不完整，请重新下载") }
            finally { file.delete(); if (pending !== prepared) prepared?.let(store::discard); busy = false }
        }
    }
    fun install() {
        val prepared = pending ?: return
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                installed = withContext(Dispatchers.IO) { store.install(prepared) }
                pending = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendly(e, "主题安装失败，请检查剩余空间并重新导入"); dismiss() }
            finally { busy = false }
        }
    }
    fun remove(theme: ThemePack) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.remove(theme.id) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendly(e, "暂时无法移除主题") }
            finally { busy = false }
        }
    }
    fun consumeInstalled() { installed = null }
    fun dismissError() { error = null }
    fun dismiss() { pending?.let(store::discard); pending = null }
    override fun onCleared() { dismiss(); super.onCleared() }
    private fun friendly(error: Exception, fallback: String) = error.message?.takeIf { it.any { char -> char in '\u4e00'..'\u9fff' } }?.take(180) ?: fallback
}

@Composable fun ThemeImportDialogs(model: ThemeImportModel, themes: List<ThemePack>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    model.pending?.let { prepared ->
        val theme = prepared.theme
        val old = themes.firstOrNull { it.id == theme.id }
        AlertDialog(onDismissRequest = { if (!model.busy) model.dismiss() }, title = { Text("导入${theme.name}") },
            text = { Column {
                Text(theme.description)
                Text("版本 ${theme.version} · ${Formatter.formatShortFileSize(context, prepared.bytes)}")
                theme.companion?.let { Text("${it.artworks.size}幅插画 · ${it.allLines.size}条语录 · ${it.voices.size}段语音") }
                if (old != null) Text("将替换已安装的版本${old.version}，并切换到此主题。")
                else Text("导入后即可使用；原ZIP可删除，课表不会改变。")
            } },
            confirmButton = { TextButton(onClick = model::install, enabled = !model.busy) { Text(if (model.busy) "正在安装…" else if (old == null) "安装并使用" else "替换并使用") } },
            dismissButton = { TextButton(onClick = model::dismiss, enabled = !model.busy) { Text("取消") } })
    }
    model.error?.let { error -> AlertDialog(onDismissRequest = model::dismissError, title = { Text("主题操作未完成") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("知道了") } }) }
}
