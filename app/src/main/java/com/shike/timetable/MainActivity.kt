package com.shike.timetable

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.timetable.data.*
import com.shike.timetable.theme.*
import com.shike.timetable.ui.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shike.timetable.widget.*
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MainActivity : ComponentActivity() {
    private var widgetLaunch by mutableStateOf<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val backup = ViewModelProvider(this)[BackupViewModel::class.java]
        if (savedInstanceState == null && intent?.action == WIDGET_OPEN_ACTION) widgetLaunch = intent
        setContent { ShikeApp(backup, widgetLaunch) { widgetLaunch = null } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == WIDGET_OPEN_ACTION) widgetLaunch = intent
    }
}

/** 备份任务和待确认内容独立于 Activity，旋转屏幕时继续处理。 */
class BackupViewModel : ViewModel() {
    var pendingImport by mutableStateOf<ScheduleData?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set

    fun dismissImport() { pendingImport = null }
    fun consumeMessage() { resultMessage = null }

    fun exportBackup(context: Context, uri: Uri, data: ScheduleData) {
        val applicationContext = context.applicationContext
        startOperation({ "导出失败，请选择其他位置" }) {
            withContext(Dispatchers.IO) {
                val json = ScheduleJson.exportJson(data)
                val stream = applicationContext.contentResolver.openOutputStream(uri)
                    ?: error("无法打开文件")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
            resultMessage = "课表已导出"
        }
    }

    fun exportRecovery(context: Context, uri: Uri) {
        val applicationContext = context.applicationContext
        startOperation({ "导出失败，请选择其他位置" }) {
            withContext(Dispatchers.IO) {
                val original = ScheduleStore(applicationContext).readCorruptedBackup()
                    ?: error("没有原始备份")
                val stream = applicationContext.contentResolver.openOutputStream(uri)
                    ?: error("无法打开文件")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(original) }
            }
            resultMessage = "原始数据已导出"
        }
    }

    fun importBackup(context: Context, uri: Uri) {
        val applicationContext = context.applicationContext
        startOperation({ it.message ?: "无法读取课表备份" }) {
            val imported = withContext(Dispatchers.IO) {
                val stream = applicationContext.contentResolver.openInputStream(uri)
                    ?: error("无法打开文件")
                val json = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        text.append(buffer, 0, count)
                        require(text.length <= 2_000_000) { "备份文件过大" }
                    }
                    text.toString()
                }
                ScheduleJson.importJson(json)
            }
            pendingImport = imported
        }
    }

    private fun startOperation(failureMessage: (Exception) -> String, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                work()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                resultMessage = failureMessage(exception)
            } finally {
                busy = false
            }
        }
    }
}

@Composable private fun ShikeApp(backup: BackupViewModel, widgetLaunch: Intent?, onWidgetHandled: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ScheduleStore(context) }
    val appearance = remember { AppearanceStore(context) }
    val themes = remember { ThemeRepository.get(context) }
    var themeId by remember { mutableStateOf(appearance.themeId) }
    val theme = themes.find(themeId)
    var showMascot by remember { mutableStateOf(appearance.showCompanion) }
    var data by remember { mutableStateOf(store.read()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var schoolImport by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var clockTime by remember { mutableStateOf(LocalDateTime.now()) }
    val companionStore = remember { CompanionInteractionStore(context) }
    var companion by remember(theme.id) { mutableStateOf(companionStore.current(theme, clockTime)) }
    var showShuffleHint by remember { mutableStateOf(companionStore.showShuffleHint()) }
    val voiceSession by CompanionVoicePlayback.sessions.collectAsState()
    var voiceEnabled by remember { mutableStateOf(appearance.voiceEnabled) }
    LaunchedEffect(voiceSession) {
        companion = companionStore.current(theme, LocalDateTime.now())
        showShuffleHint = companionStore.showShuffleHint()
    }
    val today = clockTime.toLocalDate()
    val currentWeek = ScheduleRules.currentWeek(data.settings, today)
    var selectedWeek by rememberSaveable { mutableIntStateOf(currentWeek.coerceIn(1, data.settings.totalWeeks)) }
    var lastCurrentWeek by rememberSaveable { mutableIntStateOf(currentWeek) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var readWarning by remember { mutableStateOf(store.lastReadWarning) }
    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun save(next: ScheduleData): Boolean = runCatching { store.save(next); data = next; selectedWeek = selectedWeek.coerceIn(1, next.settings.totalWeeks); true }
        .getOrElse { message("保存失败，请重试"); false }
    fun edit(course: Course?) { editingId = course?.id; editor = true }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) backup.exportBackup(context, uri, data)
    }
    val exportRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) backup.exportRecovery(context, uri)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) backup.importBackup(context, uri)
    }
    LaunchedEffect(backup.resultMessage) {
        backup.resultMessage?.let { message(it); backup.consumeMessage() }
    }
    LaunchedEffect(theme.id) { while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        clockTime = LocalDateTime.now()
        companion = companionStore.current(theme, clockTime)
        showShuffleHint = companionStore.showShuffleHint()
    } }
    DisposableEffect(context, theme.id) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val interactionPreferences = context.getSharedPreferences(CompanionInteractionStore.PREFERENCES, Context.MODE_PRIVATE)
        val hintObserver = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CompanionInteractionStore.HINT_DISMISSED) showShuffleHint = companionStore.showShuffleHint()
            if (key == "choice_${theme.id}") companion = companionStore.current(theme, LocalDateTime.now())
        }
        interactionPreferences.registerOnSharedPreferenceChangeListener(hintObserver)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            clockTime = LocalDateTime.now()
            themeId = appearance.themeId
            showMascot = appearance.showCompanion
            voiceEnabled = appearance.voiceEnabled
            companion = companionStore.current(themes.find(appearance.themeId), clockTime)
            showShuffleHint = companionStore.showShuffleHint()
        } }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            interactionPreferences.unregisterOnSharedPreferenceChangeListener(hintObserver)
        }
    }
    LaunchedEffect(widgetLaunch) {
        if (widgetLaunch != null) {
            data = store.read()
            clockTime = LocalDateTime.now()
            companion = companionStore.current(theme, clockTime)
            showShuffleHint = companionStore.showShuffleHint()
            tab = 0
            selectedWeek = ScheduleRules.currentWeek(data.settings, clockTime.toLocalDate()).coerceIn(1, data.settings.totalWeeks)
            schoolImport = false
            val requested = data.courses.firstOrNull { it.id == widgetLaunch.getStringExtra(WIDGET_COURSE_ID) }
            editingId = requested?.id
            editor = requested != null
            onWidgetHandled()
        }
    }
    LaunchedEffect(currentWeek) {
        if (lastCurrentWeek != currentWeek) {
            selectedWeek = currentWeek.coerceIn(1, data.settings.totalWeeks)
            lastCurrentWeek = currentWeek
        }
    }
    ShikeTheme(data.settings.darkMode, theme, showMascot, companion = companion, showShuffleHint = showShuffleHint,
        voicePhase = voiceSession?.phase,
        onCompanionShuffle = {
            clockTime = LocalDateTime.now()
            CompanionVoicePlayback.shuffle(context)
            companion = companionStore.current(theme, clockTime)
            showShuffleHint = companionStore.showShuffleHint()
            TodayWidgetUpdates.request(context)
        }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (schoolImport) {
                SchoolImportScreen(data.settings, data.courses.size,
                    onImport = { imported ->
                        val saved = save(imported)
                        if (saved) {
                            schoolImport = false
                            tab = 0
                            selectedWeek = ScheduleRules.currentWeek(imported.settings, today).coerceIn(1, imported.settings.totalWeeks)
                            message(listOfNotNull("教务课表已导入", theme.companion?.contextLines?.get("import_saved")?.takeIf { showMascot }).joinToString("\n"))
                        }
                        saved
                    }, onBack = { schoolImport = false })
            } else if (editor) {
                BackHandler { editor = false }
                CourseEditorScreen(data.courses.find { it.id == editingId }, data.settings, data.courses,
                    onSave = { course ->
                        val next = data.copy(courses = data.courses.filterNot { it.id == course.id } + course)
                        if (save(next)) { editor = false; message(listOfNotNull("课程已保存", theme.companion?.contextLines?.get("course_saved")?.takeIf { showMascot }).joinToString("\n")) }
                    }, onDelete = { course -> if (save(data.copy(courses = data.courses.filterNot { it.id == course.id }))) { editor = false; message("课程已删除") } },
                    onBack = { editor = false })
            } else {
                Scaffold(containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { BottomTabs(tab) { tab = it } },
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                ) { padding ->
                    when (tab) {
                        0 -> WeekScreen(data, today, clockTime.toLocalTime(), currentWeek, selectedWeek,
                            onWeek = { selectedWeek = it.coerceIn(1, data.settings.totalWeeks) },
                            onPicker = { picker = true }, onAdd = { edit(null) }, onEdit = ::edit,
                            onWeekend = { save(data.copy(settings = data.settings.copy(showWeekend = true))) },
                            modifier = Modifier.padding(padding))
                        1 -> CoursesScreen(data, selectedWeek, { edit(null) }, ::edit, Modifier.padding(padding))
                        else -> SettingsScreen(data, { save(data.copy(settings = it)) },
                            onExport = { export.launch("拾课备份-${today}.json") },
                            onImport = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            onSchoolImport = { schoolImport = true },
                            onClear = { save(data.copy(courses = emptyList())) },
                            themes = themes.themes, onTheme = { selected ->
                                CompanionVoicePlayback.cancel(context)
                                appearance.themeId = selected.id; themeId = selected.id
                                clockTime = LocalDateTime.now(); companion = companionStore.current(selected, clockTime)
                                showShuffleHint = companionStore.showShuffleHint(); TodayWidgetUpdates.request(context)
                            },
                            showMascot = showMascot, onMascot = {
                                if (!it) CompanionVoicePlayback.cancel(context)
                                showMascot = it; appearance.showCompanion = it; TodayWidgetUpdates.request(context)
                            },
                            voiceEnabled = voiceEnabled, onVoice = {
                                if (!it) CompanionVoicePlayback.cancel(context)
                                voiceEnabled = it; appearance.voiceEnabled = it
                            },
                            modifier = Modifier.padding(padding))
                    }
                }
            }
            if (picker) WeekPicker(selectedWeek, data.settings.totalWeeks, currentWeek,
                onSelect = { selectedWeek = it; picker = false }, onDismiss = { picker = false })
            backup.pendingImport?.let { imported ->
                AlertDialog(onDismissRequest = { backup.dismissImport() }, title = { Text("导入课表备份") },
                    text = { Text("将导入 ${imported.courses.size} 门课程及学期设置，并替换当前课表。建议先导出一份备份。") },
                    confirmButton = { TextButton(onClick = { if (save(imported)) { backup.dismissImport(); message("课表已导入") } }) { Text("确认导入") } },
                    dismissButton = { TextButton(onClick = { backup.dismissImport() }) { Text("取消") } })
            }
            readWarning?.let { warning ->
                AlertDialog(onDismissRequest = { readWarning = null }, title = { Text("课表读取异常") },
                    text = { Text(warning) }, confirmButton = { TextButton(onClick = { readWarning = null }) { Text("知道了") } },
                    dismissButton = { TextButton(onClick = { exportRecovery.launch("拾课原始数据备份.json") }) { Text("导出原始备份") } })
            }
            if (backup.busy) {
                AlertDialog(onDismissRequest = {}, title = { Text("正在处理备份") }, text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("请稍候…")
                    }
                }, confirmButton = {})
            }
        }
    }
}

@Composable private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp).padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("课表", "课程", "设置").forEachIndexed { i, label ->
                val color = if (selected == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(54.dp, 32.dp).clip(RoundedCornerShape(12.dp)).background(if (selected == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent), contentAlignment = Alignment.Center) {
                        AppGlyph(i, color, Modifier.size(21.dp))
                    }
                    Text(label, fontSize = 11.sp, fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal, color = color)
                }
            }
        }
    }
}

@Composable fun AppGlyph(kind: Int, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = Stroke(width = w * .075f)
        when (kind) {
            0 -> {
                drawRoundRect(tint, Offset(w * .13f, h * .2f), Size(w * .74f, h * .68f), androidx.compose.ui.geometry.CornerRadius(w * .12f), style = stroke)
                drawLine(tint, Offset(w * .13f, h * .4f), Offset(w * .87f, h * .4f), stroke.width)
                drawLine(tint, Offset(w * .33f, h * .1f), Offset(w * .33f, h * .29f), stroke.width)
                drawLine(tint, Offset(w * .67f, h * .1f), Offset(w * .67f, h * .29f), stroke.width)
                drawCircle(tint, w * .045f, Offset(w * .35f, h * .57f)); drawCircle(tint, w * .045f, Offset(w * .65f, h * .57f))
                drawCircle(tint, w * .045f, Offset(w * .35f, h * .73f))
            }
            1 -> {
                drawRoundRect(tint, Offset(w * .2f, h * .1f), Size(w * .65f, h * .8f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = stroke)
                drawLine(tint, Offset(w * .12f, h * .3f), Offset(w * .28f, h * .3f), stroke.width)
                drawLine(tint, Offset(w * .12f, h * .7f), Offset(w * .28f, h * .7f), stroke.width)
                drawLine(tint, Offset(w * .39f, h * .37f), Offset(w * .7f, h * .37f), stroke.width)
                drawLine(tint, Offset(w * .39f, h * .59f), Offset(w * .64f, h * .59f), stroke.width)
            }
            2 -> {
                drawCircle(tint, w * .35f, Offset(w * .5f, h * .5f), style = stroke)
                drawCircle(tint, w * .12f, Offset(w * .5f, h * .5f), style = stroke)
                repeat(8) { i -> val a = i * Math.PI / 4; val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
                    drawLine(tint, Offset(w * (.5f + dx * .34f), h * (.5f + dy * .34f)), Offset(w * (.5f + dx * .46f), h * (.5f + dy * .46f)), stroke.width) }
            }
            3 -> { drawLine(tint, Offset(w * .2f, h * .5f), Offset(w * .8f, h * .5f), stroke.width); drawLine(tint, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .8f), stroke.width) }
            4, 5 -> { val sign = if (kind == 4) -1 else 1; val path = Path().apply { moveTo(w * (.5f - sign * .15f), h * .25f); lineTo(w * (.5f + sign * .15f), h * .5f); lineTo(w * (.5f - sign * .15f), h * .75f) }; drawPath(path, tint, style = stroke) }
        }
    }
}

@Composable private fun PageTitle(title: String, subtitle: String, onAdd: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineLarge)
                if (title == "拾课" && LocalCompanionVisible.current) ThemeFlower(Modifier.padding(start = 9.dp).size(22.dp))
            }
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (title == "我的课程" && LocalCompanionVisible.current) CompanionPortrait(Modifier.padding(end = 8.dp).size(56.dp, 70.dp), scene = "study")
        if (onAdd != null) FilledIconButton(onClick = onAdd, modifier = Modifier.size(42.dp).semantics { contentDescription = "添加课程" }, shape = RoundedCornerShape(15.dp)) {
            AppGlyph(3, MaterialTheme.colorScheme.onPrimary, Modifier.size(23.dp))
        }
    }
}

@Composable private fun WeekScreen(data: ScheduleData, today: LocalDate, now: LocalTime, currentWeek: Int, week: Int,
    onWeek: (Int) -> Unit, onPicker: () -> Unit, onAdd: () -> Unit, onEdit: (Course) -> Unit,
    onWeekend: () -> Unit, modifier: Modifier = Modifier) {
    val active = ScheduleRules.coursesForWeek(data.courses, week)
    val todayCourses = ScheduleRules.coursesForWeek(data.courses, currentWeek).filter { it.day == today.dayOfWeek.value }.sortedBy { it.startPeriod }
    val next = todayCourses.firstOrNull { runCatching { LocalTime.parse(data.settings.periods[it.endPeriod - 1].end) > now }.getOrDefault(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageTitle("拾课", if (LocalCompanionVisible.current) "${LocalAppTheme.current.companion?.shortName}陪你，${LocalAppTheme.current.companion?.tagline}" else "把时间留给热爱", onAdd)
        CompanionCard(
            title = when { currentWeek < 1 -> "新学期，准备出发"; currentWeek > data.settings.totalWeeks -> "学期已结束，好好休息"; todayCourses.isEmpty() -> "今天没有课\n自由安排时间"; next == null -> "今日课程已结束"; else -> "今天 · ${todayCourses.size} 门课" },
            detail = next?.let { "${data.settings.periods[it.startPeriod - 1].start}  ${it.name}${if (it.room.isBlank()) "" else " · ${it.room}"}" }
                ?: "${today.monthValue}月${today.dayOfMonth}日  ·  ${listOf("周一","周二","周三","周四","周五","周六","周日")[today.dayOfWeek.value - 1]}\n留一点时间，做喜欢的事。",
            label = null
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPicker), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("第 $week 周", style = MaterialTheme.typography.titleMedium); Text("  ▾", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(data.settings.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (week != currentWeek && currentWeek in 1..data.settings.totalWeeks) TextButton(onClick = { onWeek(currentWeek) }, contentPadding = PaddingValues(horizontal = 9.dp)) { Text("本周", fontSize = 12.sp) }
            IconButton(onClick = { onWeek(week - 1) }, enabled = week > 1, modifier = Modifier.size(44.dp).semantics { contentDescription = "上一周" }) { AppGlyph(4, if (week > 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, Modifier.size(20.dp)) }
            IconButton(onClick = { onWeek(week + 1) }, enabled = week < data.settings.totalWeeks, modifier = Modifier.size(44.dp).semantics { contentDescription = "下一周" }) { AppGlyph(5, if (week < data.settings.totalWeeks) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, Modifier.size(20.dp)) }
        }
        val days = if (data.settings.showWeekend) 7 else 5
        if (active.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
            Text("${ScheduleRules.dateForDay(data.settings, week, 1).monthValue}月", Modifier.width(30.dp).padding(top = 17.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (1..days).forEach { day ->
                val date = ScheduleRules.dateForDay(data.settings, week, day); val isToday = date == today
                Column(Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(listOf("一","二","三","四","五","六","日")[day - 1], fontSize = 11.sp, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(date.dayOfMonth.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        if (active.isEmpty()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val illustrationSize = (maxHeight - 140.dp * LocalDensity.current.fontScale).coerceIn(48.dp, 180.dp)
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    CompanionEmptyState("这一周，留白也很美", if (LocalCompanionVisible.current) LocalAppTheme.current.companion?.contextLines?.get(if (todayCourses.isEmpty()) "free_day" else "empty_week") ?: "添加课程，开始规划你的学期" else "添加课程，开始规划你的学期", illustrationSize = illustrationSize) {
                        TextButton(onClick = onAdd) { Text("添加第一门课") }
                    }
                }
            }
        } else {
            TimetableGrid(active, data.settings, days, today, week, onEdit, Modifier.weight(1f))
        }
        if (!data.settings.showWeekend && active.any { it.day > 5 }) {
            TextButton(onClick = onWeekend, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) { Text("本周还有周末课程 · 点此显示", fontSize = 11.sp) }
        } else Text("${active.size} 次课程  ·  ${active.sumOf { it.endPeriod - it.startPeriod + 1 }} 节课时", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
    }
}

private data class Placement(val course: Course, val lane: Int)
private fun placeCourses(courses: List<Course>): Pair<List<Placement>, Int> {
    val endByLane = mutableListOf<Int>()
    val placements = courses.sortedWith(compareBy<Course> { it.startPeriod }.thenBy { it.endPeriod }).map { course ->
        val empty = endByLane.indexOfFirst { it < course.startPeriod }
        val lane = if (empty < 0) { endByLane.add(course.endPeriod); endByLane.lastIndex } else { endByLane[empty] = course.endPeriod; empty }
        Placement(course, lane)
    }
    return placements to endByLane.size.coerceAtLeast(1)
}

@Composable private fun TimetableGrid(courses: List<Course>, settings: TermSettings, days: Int, today: LocalDate, week: Int, onEdit: (Course) -> Unit, modifier: Modifier) {
    val rowHeight = 48.dp
    Row(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(Modifier.width(30.dp)) { settings.periods.forEachIndexed { i, period ->
            Column(Modifier.height(rowHeight).fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.Start) {
                Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(period.start, fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f))
            }
        } }
        (1..days).forEach { day ->
            val isToday = ScheduleRules.dateForDay(settings, week, day) == today
            BoxWithConstraints(Modifier.weight(1f).height(rowHeight * settings.periods.size).padding(horizontal = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = .035f) else MaterialTheme.colorScheme.surface.copy(alpha = .75f))) {
                Column { repeat(settings.periods.size) { Box(Modifier.fillMaxWidth().height(rowHeight).border(.4.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) } }
                val (placed, lanes) = placeCourses(courses.filter { it.day == day })
                val width = maxWidth / lanes
                placed.forEach { (course, lane) ->
                    val accent = CourseAccents[course.colorIndex.coerceIn(0, 5)]
                    val dark = MaterialTheme.colorScheme.background.luminance() < .2f
                    val bg = if (dark) androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, accent, .23f)
                        else androidx.compose.ui.graphics.lerp(accent, MaterialTheme.colorScheme.surface, .86f)
                    Column(Modifier.offset(x = width * lane, y = rowHeight * (course.startPeriod - 1) + 2.dp).width(width - 2.dp)
                        .height(rowHeight * (course.endPeriod - course.startPeriod + 1) - 4.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                        .clickable { onEdit(course) }.semantics {
                            role = Role.Button
                            contentDescription = buildString {
                                append(course.name)
                                append("，星期${listOf("一", "二", "三", "四", "五", "六", "日")[course.day - 1]}")
                                append("，第 ${course.startPeriod} 至 ${course.endPeriod} 节")
                                append(if (course.room.isBlank()) "，教室待定" else "，教室 ${course.room}")
                                if (course.teacher.isNotBlank()) append("，教师 ${course.teacher}")
                            }
                        }.padding(horizontal = if (days == 7) 4.dp else 6.dp, vertical = 7.dp)) {
                        Box(Modifier.width(13.dp).height(3.dp).clip(CircleShape).background(accent))
                        Text(course.name, fontSize = if (days == 7) 10.sp else 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (dark) androidx.compose.ui.graphics.lerp(accent, Color.White, .48f) else androidx.compose.ui.graphics.lerp(accent, Color.Black, .24f),
                            maxLines = (course.endPeriod - course.startPeriod + 1).coerceIn(1, 3), overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        Spacer(Modifier.weight(1f))
                        if (course.room.isNotBlank() && course.endPeriod > course.startPeriod) Text(course.room, fontSize = 9.sp, lineHeight = 12.sp, color = if (dark) Color.White.copy(alpha = .75f) else androidx.compose.ui.graphics.lerp(accent, Color.Black, .2f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable private fun CoursesScreen(data: ScheduleData, week: Int, onAdd: () -> Unit, onEdit: (Course) -> Unit, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyThisWeek by rememberSaveable { mutableStateOf(false) }
    val filtered = (if (onlyThisWeek) ScheduleRules.coursesForWeek(data.courses, week) else data.courses)
        .filter { query.isBlank() || listOf(it.name, it.room, it.teacher).any { s -> s.contains(query, ignoreCase = true) } }.sortedWith(compareBy<Course> { it.day }.thenBy { it.startPeriod })
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageTitle("我的课程", if (LocalCompanionVisible.current) "${LocalAppTheme.current.companion?.shortName}的课程手记" else "每一份热爱，都值得安排", onAdd)
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索课程、教师或教室", fontSize = 13.sp) }, singleLine = true,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surface))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!onlyThisWeek, { onlyThisWeek = false }, label = { Text("全部课程") }); Spacer(Modifier.width(8.dp))
            FilterChip(onlyThisWeek, { onlyThisWeek = true }, label = { Text("第 $week 周") })
            Spacer(Modifier.weight(1f)); Text("${filtered.size} 门", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            CompanionEmptyState(if (query.isNotBlank()) "没有找到相关课程" else "还没有课程，点击右上角添加",
                if (LocalCompanionVisible.current) LocalAppTheme.current.companion?.contextLines?.get(if (query.isNotBlank()) "search_empty" else "empty_week") ?: "添加课程，开始规划你的学期"
                    else if (query.isNotBlank()) "试试课程名、教师或教室的其他关键词" else "从一门课开始，慢慢填满这个学期")
        }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            filtered.groupBy { it.day }.forEach { (day, dayCourses) ->
                item { Text(listOf("星期一","星期二","星期三","星期四","星期五","星期六","星期日")[day - 1], fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 1.dp)) }
                items(dayCourses, key = { it.id }) { course -> CourseListCard(course, onEdit) }
            }
        }
    }
}

@Composable private fun CourseListCard(course: Course, onEdit: (Course) -> Unit) {
    val accent = CourseAccents[course.colorIndex]
    Surface(onClick = { onEdit(course) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(49.dp).clip(CircleShape).background(accent))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(course.room.ifBlank { "待定教室" }, course.teacher).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Text("第 ${course.startWeek}–${course.endWeek} 周${when (course.weekPattern) { "odd" -> " · 单周"; "even" -> " · 双周"; else -> "" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            val labelColor = if (MaterialTheme.colorScheme.background.luminance() < .2f) androidx.compose.ui.graphics.lerp(accent, Color.White, .5f) else accent
            Surface(shape = RoundedCornerShape(9.dp), color = accent.copy(alpha = .1f)) { Text("${course.startPeriod}–${course.endPeriod}节", color = labelColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
        }
    }
}

@Composable private fun WeekPicker(selected: Int, total: Int, current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择教学周") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..total).chunked(4).forEach { group -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.forEach { week ->
                    Surface(onClick = { onSelect(week) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), color = if (week == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$week", color = if (week == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                            if (week == current) Text("本周", fontSize = 9.sp, color = if (week == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                        }
                    }
                }; repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
