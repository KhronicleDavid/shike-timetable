package com.shike.timetable.ui

import android.app.DatePickerDialog
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.shike.timetable.data.Course
import com.shike.timetable.data.ScheduleData
import com.shike.timetable.data.ScheduleRules
import com.shike.timetable.data.TermSettings
import com.shike.timetable.importer.EamsImportParser
import com.shike.timetable.importer.SchoolImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.time.DayOfWeek
import java.time.LocalDate

/** 不持有 Activity 或 WebView。已解析的预览在旋转后保留，原始网页不会落盘。 */
class SchoolImportViewModel : ViewModel() {
    var stage by mutableStateOf("welcome")
    var previewBackStage by mutableStateOf("welcome")
    var preview by mutableStateOf<SchoolImportResult?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var currentHost by mutableStateOf("eams.uestc.edu.cn")
    var resumeUrl by mutableStateOf(SCHOOL_ENTRY_URL)
    var progress by mutableIntStateOf(100)
    var desktopMode by mutableStateOf(true)
    var termName by mutableStateOf("")
    var startDate by mutableStateOf("")
    var weeksText by mutableStateOf("")
    var showWeekend by mutableStateOf(false)
    var showPeriods by mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var epoch = 0
    private var closed = false

    fun activate() { closed = false }

    fun locationChanged(url: String) {
        currentHost = schoolHost(url)
        if (canReadEamsPage(url)) {
            // 仅记住校内路径；认证票据、查询参数和片段不保留。
            resumeUrl = runCatching { val uri = URI(url); "https://eams.uestc.edu.cn${uri.rawPath}" }.getOrDefault(SCHOOL_ENTRY_URL)
        }
    }

    fun beginCapture(): Int {
        job?.cancel()
        error = null
        busy = true
        val token = ++epoch
        job = scope.launch {
            delay(15_000)
            if (!closed && token == epoch) {
                busy = false
                error = "页面读取超时，请刷新课表后重试，或从课表文件导入。"
            }
        }
        return token
    }

    /** 返回或切换页面后，旧网页回调和尚未完成的文件读取均不得改变当前页面。 */
    fun cancelCapture() {
        epoch++
        job?.cancel()
        job = null
        busy = false
    }

    fun captureResult(raw: String?, token: Int, settings: TermSettings): Boolean {
        if (closed || token != epoch) return false
        parse(settings, "web", token) {
            require(raw != null && raw != "null" && raw.length <= MAX_SCHOOL_FILE_BYTES * 6 + 1024) { "无法读取此页面，请刷新后重试。" }
            val decoded = JSONTokener(raw).nextValue() as? String
                ?: throw IllegalArgumentException("网页返回了无法识别的内容，请刷新后重试。")
            val payload = JSONObject(decoded)
            (payload.opt("error") as? String)?.takeIf { it.isNotBlank() }?.let { throw IllegalArgumentException(it) }
            (payload.opt("html") as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("页面中没有可读取的课表，请先打开「我的课表」。")
        }
        return true
    }

    fun fileSelected(settings: TermSettings, read: () -> String) {
        val back = if (stage == "preview") previewBackStage else stage
        parse(settings, back, beginCapture(), read)
    }

    private fun parse(settings: TermSettings, back: String, token: Int, read: () -> String) {
        job?.cancel()
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val html = read()
                    require(html.toByteArray(Charsets.UTF_8).size <= MAX_SCHOOL_FILE_BYTES) { "课表内容不能超过 2 MB。" }
                    EamsImportParser.parseHtml(html)
                }
                if (closed || token != epoch) return@launch
                require(result.courses.isNotEmpty()) { "没有找到可导入的课程，请检查是否选择了正确的学期。" }
                preview = result
                previewBackStage = back
                termName = result.suggestedTermName?.takeIf { it.isNotBlank() } ?: settings.name
                startDate = result.suggestedStartDate?.takeIf { value ->
                    runCatching { LocalDate.parse(value).dayOfWeek == DayOfWeek.MONDAY }.getOrDefault(false)
                } ?: settings.startDate
                weeksText = maxOf(settings.totalWeeks, result.maxWeek).toString()
                showWeekend = settings.showWeekend || result.courses.any { it.day >= 6 }
                showPeriods = false
                error = null
                stage = "preview"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                if (!closed && token == epoch) error = problem.message?.take(300)?.takeIf { it.isNotBlank() }
                    ?: "暂时无法解析课表，请重新打开课表或从文件导入。"
            } finally {
                if (token == epoch) busy = false
            }
        }
    }

    fun close() {
        closed = true
        epoch++
        job?.cancel()
        preview = null
        stage = "welcome"
        previewBackStage = "welcome"
        busy = false
        error = null
        resumeUrl = SCHOOL_ENTRY_URL
        currentHost = "eams.uestc.edu.cn"
        progress = 100
        desktopMode = true
        termName = ""
        startDate = ""
        weeksText = ""
    }

    override fun onCleared() { scope.cancel() }
}

@Composable
fun SchoolImportScreen(
    settings: TermSettings,
    existingCourseCount: Int,
    onImport: (ScheduleData) -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val model = remember(activity) {
        ViewModelProvider(activity)["school-import", SchoolImportViewModel::class.java].also { it.activate() }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val applicationContext = context.applicationContext
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.fileSelected(settings) {
            val stream = applicationContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("无法打开所选文件，请重新选择。")
            readSchoolFile(stream)
        }
    }
    val pickFile = { filePicker.launch(arrayOf("text/html", "text/plain", "application/json", "application/octet-stream")) }
    val exit: () -> Unit = {
        model.close()
        destroySchoolWebView(webView)
        webView = null
        clearSchoolSession()
        onBack()
    }
    val goBack: () -> Unit = {
        when (model.stage) {
            "preview" -> {
                model.cancelCapture()
                model.error = null
                model.stage = model.previewBackStage
            }
            "web" -> {
                model.cancelCapture()
                if (webView?.canGoBack() == true) webView?.goBack() else model.stage = "welcome"
            }
            else -> exit()
        }
        Unit
    }
    BackHandler(onBack = goBack)
    DisposableEffect(activity, model) {
        onDispose {
            destroySchoolWebView(webView)
            webView = null
            if (!activity.isChangingConfigurations) {
                model.close()
                clearSchoolSession()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = goBack) { Text("‹ 返回") }
            Text(if (model.stage == "preview") "确认课表" else "从教务系统导入", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = exit) { Text("关闭") }
        }
        when (model.stage) {
            "web" -> SchoolBrowser(model, webView, { webView = it }, settings, pickFile)
            "preview" -> model.preview?.let { result ->
                SchoolPreview(model, result, settings, existingCourseCount, onConfirm = { imported ->
                    if (onImport(imported)) {
                        model.close()
                        destroySchoolWebView(webView)
                        webView = null
                        clearSchoolSession()
                    } else {
                        model.error = "课表保存失败，预览已保留，请重试。"
                    }
                })
            }
            else -> SchoolWelcome(model, onOpen = {
                model.error = null
                model.resumeUrl = SCHOOL_ENTRY_URL
                model.stage = "web"
            }, onFile = pickFile)
        }
    }
}

@Composable
private fun SchoolWelcome(model: SchoolImportViewModel, onOpen: () -> Unit, onFile: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("把学校课表\n带进拾课", style = MaterialTheme.typography.headlineLarge)
        CompanionCard("新学期，一起准备好", "登录学校官网，核对后再导入。", label = "${LocalAppTheme.current.companion?.shortName.orEmpty()} · 学期准备", scene = "study")
        Text("在学校官网完成登录，打开课表后即可读取。导入前会显示完整预览，由你确认课程和学期。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImportCard {
            Text("电子科技大学", style = MaterialTheme.typography.titleLarge)
            Text("教务管理系统 · EAMS", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            Text("eams.uestc.edu.cn/eams/", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 13.dp))
        }
        ImportCard {
            Text("登录和读取", fontWeight = FontWeight.SemiBold)
            Text("1. 打开学校教务入口，在网页内自行登录。\n2. 进入「我的课表」，选择要导入的学期。\n3. 点击「读取课表」，核对预览后确认导入。", modifier = Modifier.padding(top = 10.dp))
        }
        Text("拾课不采集账号或密码。登录会话仅在本机用于连接学校官网，关闭导入后会清理；不会写入课表备份。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        model.error?.let { ImportMessage(it, isError = true) }
        Button(onClick = onOpen, enabled = !model.busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("打开学校教务系统") }
        OutlinedButton(onClick = onFile, enabled = !model.busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("从课表文件导入") }
        Text("网页打不开或停在访问验证时，可在浏览器中保存课表 HTML，再选择文件导入。支持 HTML、文本和受支持的课表 JSON，最大 2 MB。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        if (model.busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("正在读取课表…", modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun ColumnScope.SchoolBrowser(
    model: SchoolImportViewModel,
    webView: WebView?,
    onWebView: (WebView?) -> Unit,
    settings: TermSettings,
    onFile: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("学校官网 · ${model.currentHost}", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                FilterChip(selected = model.desktopMode, onClick = {
                    model.cancelCapture()
                    model.error = null
                    model.desktopMode = !model.desktopMode
                    webView?.let { view ->
                        view.stopLoading()
                        applySchoolBrowserMode(view, model.desktopMode)
                        view.reload()
                    }
                }, enabled = !model.busy, label = { Text("电脑版") })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                TextButton(onClick = { model.cancelCapture(); model.error = null; webView?.loadUrl(SCHOOL_ENTRY_URL) }, enabled = !model.busy) { Text("教务入口") }
                TextButton(onClick = { model.cancelCapture(); model.error = null; webView?.loadUrl(SCHOOL_TABLE_URL) }, enabled = !model.busy) { Text("我的课表") }
                TextButton(onClick = { model.cancelCapture(); model.error = null; webView?.reload() }, enabled = !model.busy) { Text("刷新") }
                TextButton(onClick = onFile, enabled = !model.busy) { Text("文件导入") }
            }
        }
    }
    if (model.progress < 100) LinearProgressIndicator(progress = { model.progress / 100f }, modifier = Modifier.fillMaxWidth())
    AndroidView(
        factory = { ctx ->
            createSchoolWebView(ctx, onProgress = { model.progress = it }, onLocation = model::locationChanged,
                onError = { model.error = it }, desktopMode = model.desktopMode)
                .also { view -> onWebView(view); view.loadUrl(model.resumeUrl) }
        },
        modifier = Modifier.fillMaxWidth().weight(1f),
        onRelease = { view ->
            destroySchoolWebView(view)
            onWebView(null)
        },
    )
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            model.error?.let { ImportMessage(it, isError = true) }
            Text("请先登录并打开「我的课表」。访问验证停留或页面空白时，可刷新或从文件导入。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                val view = webView
                if (view == null || !canReadEamsPage(view.url.orEmpty())) {
                    model.error = "请先回到教务系统，打开「我的课表」后读取。"
                } else {
                    val token = model.beginCapture()
                    view.evaluateJavascript(EAMS_CAPTURE_SCRIPT) { raw -> model.captureResult(raw, token, settings) }
                }
            }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                if (model.busy) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(9.dp)) }
                Text(if (model.busy) "正在读取…" else "读取课表")
            }
        }
    }
}

@Composable
private fun SchoolPreview(
    model: SchoolImportViewModel,
    result: SchoolImportResult,
    settings: TermSettings,
    existingCourseCount: Int,
    onConfirm: (ScheduleData) -> Unit,
) {
    val context = LocalContext.current
    val distinctNames = result.courses.map { it.name }.distinct().size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("课表已读取", style = MaterialTheme.typography.headlineSmall)
        Text("$distinctNames 门课程 · ${result.courses.size} 条排课 · 最晚第 ${result.maxWeek} 周", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text("来源：${result.sourceLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        result.warnings.forEach { warning -> ImportMessage(warning) }
        ImportCard {
            Text("核对学期", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(model.termName, { model.termName = it; model.error = null }, label = { Text("学期名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("请核对第 1 周的周一。课表的周次会从这一天开始计算。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 13.dp, bottom = 5.dp))
            OutlinedButton(onClick = {
                val date = runCatching { LocalDate.parse(model.startDate) }.getOrDefault(LocalDate.now())
                DatePickerDialog(context, { _, year, month, day ->
                    val chosen = LocalDate.of(year, month + 1, day)
                    if (chosen.dayOfWeek != DayOfWeek.MONDAY) model.error = "请选择第 1 周的周一。"
                    else { model.startDate = chosen.toString(); model.error = null }
                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("第 1 周周一：${model.startDate}") }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(model.weeksText, {
                if (it.length <= 2 && it.all(Char::isDigit)) model.weeksText = it
                model.error = null
            }, label = { Text("学期总周数（至少 ${result.maxWeek} 周）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(model.showWeekend, { model.showWeekend = it })
                Text("显示周末", modifier = Modifier.weight(1f))
            }
            if (!model.showWeekend && result.courses.any { it.day >= 6 }) Text("有周末课程；关闭后这些课程仍会保存，但周课表中不会显示。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        ImportCard {
            Text("保留当前作息", fontWeight = FontWeight.SemiBold)
            Text("导入的是星期、节次和周次。具体上课时间沿用拾课现有设置，请与学校作息核对；导入后也可以在设置中调整。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { model.showPeriods = !model.showPeriods }) { Text(if (model.showPeriods) "收起当前作息" else "查看当前作息") }
            if (model.showPeriods) settings.periods.forEachIndexed { index, period ->
                Text("第 ${index + 1} 节    ${period.start}–${period.end}", fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        Text("课程预览", style = MaterialTheme.typography.titleMedium)
        result.courses.sortedWith(compareBy<Course>({ it.name }, { it.day }, { it.startPeriod }, { it.startWeek })).forEach { course ->
            ImportCard {
                Text(course.name, fontWeight = FontWeight.SemiBold)
                listOf(course.teacher, course.room).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                val day = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(course.day - 1) { "未知星期" }
                val periods = if (course.startPeriod == course.endPeriod) "第 ${course.startPeriod} 节" else "第 ${course.startPeriod}–${course.endPeriod} 节"
                val weeks = if (course.startWeek == course.endWeek) "第 ${course.startWeek} 周" else "${course.startWeek}–${course.endWeek} 周"
                val pattern = when (course.weekPattern) { "odd" -> " · 单周"; "even" -> " · 双周"; else -> "" }
                Text("$day · $periods · $weeks$pattern", fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
        ImportMessage(if (existingCourseCount > 0) "确认导入后，将替换当前 $existingCourseCount 条排课记录。建议先导出备份。" else "确认后，将把以上 ${result.courses.size} 条排课加入当前课表。")
        model.error?.let { ImportMessage(it, isError = true) }
        Button(onClick = {
            val nextSettings = settings.copy(name = model.termName.trim(), startDate = model.startDate, totalWeeks = model.weeksText.toIntOrNull() ?: 0, showWeekend = model.showWeekend)
            model.error = when {
                nextSettings.totalWeeks < result.maxWeek -> "课程延伸至第 ${result.maxWeek} 周，学期总周数不能小于此值。"
                else -> ScheduleRules.settingsValidationError(nextSettings)
                    ?: result.courses.firstNotNullOfOrNull { course -> ScheduleRules.validationError(course, nextSettings.totalWeeks) }
                    ?: if (result.courses.any { it.endPeriod > settings.periods.size }) "当前作息的节数不足，请先在设置中调整。" else null
            }
            if (model.error == null) onConfirm(ScheduleData(result.courses, nextSettings))
        }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (existingCourseCount > 0) "确认替换并导入" else "确认导入课表")
        }
        Spacer(Modifier.height(15.dp))
    }
}

@Composable
private fun ImportCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun ImportMessage(text: String, isError: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}
