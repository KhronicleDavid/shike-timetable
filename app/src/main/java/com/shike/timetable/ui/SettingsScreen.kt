package com.shike.timetable.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.timetable.data.*
import com.shike.timetable.theme.ThemePack
import java.time.LocalDate

@Composable fun SettingsScreen(data: ScheduleData, onSettings: (TermSettings) -> Unit, onExport: () -> Unit,
    onImport: () -> Unit, onSchoolImport: () -> Unit, onClear: () -> Unit,
    themes: List<ThemePack>, onTheme: (ThemePack) -> Unit,
    modifier: Modifier = Modifier, showMascot: Boolean = true, onMascot: (Boolean) -> Unit = {},
    voiceEnabled: Boolean = true, onVoice: (Boolean) -> Unit = {}) {
    var dialog by rememberSaveable { mutableStateOf("") }
    val theme = LocalAppTheme.current
    val companion = theme.companion
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 19.dp))
        Text("让课表更适合你", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp, bottom = 18.dp))
        if (LocalCompanionVisible.current) CompanionCard("温柔陪伴，从容上课", "${companion?.name}\n${companion?.tagline}", label = "拾课 × ${companion?.shortName}", modifier = Modifier.padding(bottom = 15.dp))
        SectionLabel("学期与作息")
        SettingsCard {
            SettingRow("当前学期", "${data.settings.name}\n${data.settings.startDate} 开学 · ${data.settings.totalWeeks} 周", { dialog = "term" })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("上课时间", "${data.settings.periods.size} 节课 · 自定义每日作息", { dialog = "periods" })
        }
        SectionLabel("外观")
        SettingsCard {
            SettingRow("整体主题", "${theme.name}\n${theme.description}", { dialog = "theme" })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (companion != null) {
                SettingRow("${companion.shortName}的小小画册", "${companion.artworks.size} 幅插画 · 早晨、午间、夜晚", { dialog = "gallery" })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            SettingRow("桌面今日课表", "课程与教室 · 随整体主题切换外观", { dialog = "widget" })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("显示周末", fontWeight = FontWeight.Medium); Text("在周课表中显示周六和周日", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                Switch(data.settings.showWeekend, { onSettings(data.settings.copy(showWeekend = it)) }, modifier = Modifier.semantics { contentDescription = "显示周末" })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(17.dp)) {
                Text("明暗模式", fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                        FilterChip(data.settings.darkMode == value, { onSettings(data.settings.copy(darkMode = value)) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (companion != null) "${companion.shortName}陪伴" else "角色陪伴", fontWeight = FontWeight.Medium)
                    Text(if (companion != null) "在应用和桌面小组件中显示吉祥物" else "当前主题不包含角色插画与语录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Switch(showMascot && companion != null, onMascot, enabled = companion != null,
                    modifier = Modifier.semantics { contentDescription = if (companion != null) "${companion.shortName}陪伴" else "角色陪伴" })
            }
        }
        if (!companion?.voices.isNullOrEmpty()) SettingsCard {
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${companion?.shortName}语音", fontWeight = FontWeight.Medium)
                    Text("点击换句时播放日语 · 播完才能切换\n应用与小组件共用 · 离线播放", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Switch(voiceEnabled, onVoice, modifier = Modifier.semantics { contentDescription = "角色语音" })
            }
        }
        SectionLabel("课表数据")
        SettingsCard {
            SettingRow("从教务导入", "电子科技大学 · 登录后读取课表", onSchoolImport)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("导出备份", "保存课程、学期与作息设置", onExport)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("导入备份", "从拾课 JSON 备份恢复", onImport)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("清空课表", "删除所有课程，保留学期设置", { dialog = "clear" }, danger = true)
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (companion != null) ThemeFlower(Modifier.size(18.dp))
                Text("拾课  1.7.0", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 7.dp))
            }
            Text("本机保存 · 无广告", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f), fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
    when (dialog) {
        "widget" -> WidgetHelpDialog { dialog = "" }
        "gallery" -> CompanionGallery { dialog = "" }
        "theme" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("选择整体主题") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                themes.forEach { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onTheme(option); dialog = "" }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(theme.id == option.id, onClick = null)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(option.name, style = MaterialTheme.typography.titleMedium)
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("关闭") } })
        "term" -> TermDialog(data, { onSettings(it); dialog = "" }, { dialog = "" })
        "periods" -> PeriodsDialog(data.settings, { onSettings(it); dialog = "" }, { dialog = "" })
        "clear" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("清空所有课程？") }, text = { Text("这会删除 ${data.courses.size} 门课程。建议先导出备份，删除后无法撤销。") },
            confirmButton = { TextButton(onClick = { onClear(); dialog = "" }) { Text("确认清空", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("取消") } })
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 3.dp, bottom = 10.dp, top = 9.dp))
}
@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp)) { Column(content = content) }
}
@Composable private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable private fun TermDialog(data: ScheduleData, onSave: (TermSettings) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(data.settings.name) }
    var date by rememberSaveable { mutableStateOf(data.settings.startDate) }
    var weeks by rememberSaveable { mutableStateOf(data.settings.totalWeeks.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("学期设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text("学期名称") }, singleLine = true)
            Text("第 1 周从周一开始，自动计算当前教学周。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            OutlinedButton(onClick = {
                val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString(); error = null }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("开学日期：$date") }
            OutlinedTextField(weeks, { if (it.length <= 2 && it.all(Char::isDigit)) weeks = it; error = null }, label = { Text("学期总周数（1–30）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = {
        val next = data.settings.copy(name = name.trim(), startDate = date, totalWeeks = weeks.toIntOrNull() ?: 0)
        val lastCourseWeek = data.courses.maxOfOrNull { it.endWeek } ?: 1
        error = ScheduleRules.settingsValidationError(next) ?: if (next.totalWeeks < lastCourseWeek) "已有课程延伸至第 $lastCourseWeek 周，学期总周数不能小于此值" else null
        if (error == null) onSave(next)
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun PeriodsDialog(settings: TermSettings, onSave: (TermSettings) -> Unit, onDismiss: () -> Unit) {
    var starts by rememberSaveable { mutableStateOf(settings.periods.map { it.start }) }
    var ends by rememberSaveable { mutableStateOf(settings.periods.map { it.end }) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("每日作息") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("采用 24 小时制，例如 08:00。每节课必须按时间顺序排列。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            starts.indices.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}", modifier = Modifier.width(17.dp), fontSize = 12.sp)
                    OutlinedTextField(starts[i], { value -> starts = starts.toMutableList().apply { this[i] = value }; error = null }, label = { Text("上课", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    OutlinedTextField(ends[i], { value -> ends = ends.toMutableList().apply { this[i] = value }; error = null }, label = { Text("下课", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = {
        val next = settings.copy(periods = starts.indices.map { PeriodTime(starts[it].trim(), ends[it].trim()) })
        error = ScheduleRules.settingsValidationError(next)
        if (error == null) onSave(next)
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
