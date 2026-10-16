package com.shike.timetable.ui

import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.timetable.BuildConfig
import com.shike.timetable.online.*
import com.shike.timetable.theme.ThemePack

@Composable fun OnlineSettings(model: OnlineContentModel, themes: List<ThemePack>, importer: ThemeImportModel) {
    val context = LocalContext.current
    val transfer by model.transfer.collectAsState()
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var showThemes by rememberSaveable { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* 安装结果由系统确认，返回后仍可重试。 */ }
    fun openInstaller(intent: Intent) { installer.launch(intent) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installApk({}, ::openInstaller, canPrompt = false)
    }
    fun install() { model.installApk({ permission.launch(it) }, ::openInstaller) }
    val visit = rememberSaveable { java.util.UUID.randomUUID().toString() }
    LaunchedEffect(visit) { model.enterSettings(visit) }
    LaunchedEffect(importer.pending) { if (importer.pending != null) showThemes = false }
    val update = model.update
    val hasUpdate = update != null && update.versionCode > BuildConfig.VERSION_CODE
    val ready = model.readyApk?.isFile == true && model.readyVersionCode == update?.versionCode
    Text("更新与资源", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 3.dp, bottom = 10.dp, top = 9.dp))
    OnlineCard {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("应用更新", fontWeight = FontWeight.Medium)
                    Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = model::checkUpdate, enabled = !model.checking) { Text(if (model.checking) "检查中…" else "检查更新") }
            }
            val status = when {
                model.checking -> "正在检查GitHub上的正式版本…"
                model.appError != null -> model.appError!!
                model.appChecked && hasUpdate -> "发现新版本 ${update!!.versionName}"
                model.appChecked -> "已是最新版本"
                else -> "进入设置时自动检查，也可手动检查"
            }
            Text(status, fontSize = 12.sp, color = if (model.appError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("update_status"))
            if (hasUpdate) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showUpdate = true }) { Text("更新内容") }
                    Button(onClick = { if (ready) install() else model.downloadUpdate() },
                        enabled = transfer == null && !model.installing && update!!.minSdk <= Build.VERSION.SDK_INT) {
                        Text(if (model.installing) "准备安装…" else if (ready) "安装更新" else "下载更新")
                    }
                }
                Text("${Formatter.formatShortFileSize(context, update!!.artifact.bytes)} · 保留课表和已安装主题", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (update.minSdk > Build.VERSION.SDK_INT) Text("此版本需要更高版本的Android系统", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            if (transfer != null && !showThemes && !showUpdate) DownloadProgress(transfer!!, model::cancelDownload)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = { showThemes = true; model.loadThemes() }, modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("online_themes")) {
            Column(Modifier.weight(1f)) {
                Text("在线主题资源", fontWeight = FontWeight.Medium)
                Text("从GitHub下载立绘、语录与语音", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("浏览 ›")
        }
    }
    if (showUpdate && update != null) AlertDialog(onDismissRequest = { showUpdate = false }, title = { Text("拾课 ${update.versionName}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(update.notes.ifBlank { "此版本暂未填写更新说明。" }, style = MaterialTheme.typography.bodyMedium)
            if (transfer != null) DownloadProgress(transfer!!, model::cancelDownload)
        } }, confirmButton = { TextButton(onClick = { if (ready) install() else model.downloadUpdate() },
            enabled = transfer == null && !model.installing && update.minSdk <= Build.VERSION.SDK_INT) { Text(if (ready) "安装更新" else "下载更新") } },
        dismissButton = { TextButton(onClick = { showUpdate = false }) { Text("关闭") } })
    if (showThemes) AlertDialog(onDismissRequest = { showThemes = false }, title = { Text("在线主题资源") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("资源来自拾课GitHub仓库，下载后确认安装；已导入主题可以离线使用。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.loadingThemes) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.themesError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            if (model.themesLoaded && model.themes.isEmpty()) Text("暂时没有可下载的主题")
            model.themes.forEach { item ->
                val installed = themes.firstOrNull { it.id == item.id }
                val available = item.minVersionCode <= BuildConfig.VERSION_CODE
                val alreadyInstalled = installed != null && installed.version >= item.version
                Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(item.name, fontWeight = FontWeight.Medium)
                        Text(item.description, style = MaterialTheme.typography.bodySmall)
                        Text("版本 ${item.version} · ${Formatter.formatShortFileSize(context, item.artifact.bytes)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (installed != null && installed.version < item.version) Text("可更新 · 已安装版本 ${installed.version}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        if (transfer?.label == item.name) DownloadProgress(transfer!!, model::cancelDownload)
                        Button(onClick = { model.downloadTheme(item) { file, expected -> importer.prepareOnline(file, expected); showThemes = false } },
                            enabled = available && !alreadyInstalled && transfer == null && !importer.busy && importer.pending == null,
                            modifier = Modifier.fillMaxWidth().testTag("download_theme_${item.id}")) {
                            Text(when { !available -> "请先更新应用"; alreadyInstalled -> "已安装"; installed != null -> "下载主题更新"; else -> "下载并安装" })
                        }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showThemes = false }) { Text("关闭") } },
        dismissButton = { TextButton(onClick = model::loadThemes, enabled = !model.loadingThemes) { Text("刷新列表") } })
}

@Composable private fun DownloadProgress(transfer: Transfer, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.testTag("download_progress")) {
        Text("正在下载 ${transfer.label}", fontSize = 12.sp)
        LinearProgressIndicator(progress = { (transfer.bytes.toFloat() / transfer.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            val context = LocalContext.current
            Text("${(100 * transfer.bytes / transfer.total)}% · ${Formatter.formatShortFileSize(context, transfer.bytes)} / ${Formatter.formatShortFileSize(context, transfer.total)}", fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("取消下载") }
        }
    }
}
@Composable private fun OnlineCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp)) { Column(content = content) }
}
@Composable fun OnlineMessages(model: OnlineContentModel) {
    model.error?.let { text -> AlertDialog(onDismissRequest = model::dismissMessage, title = { Text("操作未完成") },
        text = { Text(text) }, confirmButton = { TextButton(onClick = model::dismissMessage) { Text("知道了") } }) }
    model.notice?.let { text -> LaunchedEffect(text) { android.widget.Toast.makeText(model.getApplication(), text, android.widget.Toast.LENGTH_LONG).show(); model.dismissMessage() } }
}

