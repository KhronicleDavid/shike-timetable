package com.shike.timetable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.timetable.data.Course
import com.shike.timetable.data.ScheduleRules
import com.shike.timetable.data.TermSettings
import java.util.UUID

@Composable
fun CourseEditorScreen(
    course: Course?,
    settings: TermSettings,
    courses: List<Course>,
    onSave: (Course) -> Unit,
    onDelete: ((Course) -> Unit)?,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val totalWeeks = settings.totalWeeks.coerceAtLeast(1)
    val periodCount = settings.periods.size.coerceAtLeast(1)
    val draftId = rememberSaveable(course?.id) { course?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable(course?.id) { mutableStateOf(course?.name.orEmpty()) }
    var room by rememberSaveable(course?.id) { mutableStateOf(course?.room.orEmpty()) }
    var teacher by rememberSaveable(course?.id) { mutableStateOf(course?.teacher.orEmpty()) }
    var day by rememberSaveable(course?.id) { mutableStateOf(course?.day ?: 1) }
    var startPeriod by rememberSaveable(course?.id) {
        mutableStateOf((course?.startPeriod ?: 1).toString())
    }
    var endPeriod by rememberSaveable(course?.id) {
        mutableStateOf((course?.endPeriod ?: minOf(2, periodCount)).toString())
    }
    var startWeek by rememberSaveable(course?.id) {
        mutableStateOf((course?.startWeek ?: 1).toString())
    }
    var endWeek by rememberSaveable(course?.id) {
        mutableStateOf((course?.endWeek ?: totalWeeks).toString())
    }
    var weekPattern by rememberSaveable(course?.id) {
        mutableStateOf(course?.weekPattern ?: "all")
    }
    var colorIndex by rememberSaveable(course?.id) {
        mutableStateOf((course?.colorIndex ?: 0).coerceIn(0, 5))
    }
    var note by rememberSaveable(course?.id) { mutableStateOf(course?.note.orEmpty()) }
    var errorMessage by rememberSaveable(course?.id) { mutableStateOf<String?>(null) }
    var pendingConflict by remember { mutableStateOf<Course?>(null) }
    var showDeleteDialog by rememberSaveable(course?.id) { mutableStateOf(false) }

    fun saveDraft() {
        focusManager.clearFocus()
        val firstPeriod = startPeriod.toIntOrNull()
        val lastPeriod = endPeriod.toIntOrNull()
        val firstWeek = startWeek.toIntOrNull()
        val lastWeek = endWeek.toIntOrNull()
        val inputError = when {
            name.isBlank() -> "请填写课程名称"
            firstPeriod == null || lastPeriod == null -> "请输入完整的起始节次和结束节次"
            firstPeriod !in 1..periodCount || lastPeriod !in 1..periodCount ->
                "节次应在第 1 至第 $periodCount 节之间"
            firstPeriod > lastPeriod -> "结束节次不能早于起始节次"
            firstWeek == null || lastWeek == null -> "请输入完整的起始周次和结束周次"
            firstWeek !in 1..totalWeeks || lastWeek !in 1..totalWeeks ->
                "周次应在第 1 至第 $totalWeeks 周之间"
            firstWeek > lastWeek -> "结束周次不能早于起始周次"
            else -> null
        }
        if (inputError != null) {
            errorMessage = inputError
            return
        }
        val candidate = Course(
            id = draftId,
            name = name.trim(),
            teacher = teacher.trim(),
            room = room.trim(),
            day = day,
            startPeriod = firstPeriod!!,
            endPeriod = lastPeriod!!,
            startWeek = firstWeek!!,
            endWeek = lastWeek!!,
            weekPattern = weekPattern,
            colorIndex = colorIndex,
            note = note.trim(),
        )
        val validationError = ScheduleRules.validationError(candidate, settings.totalWeeks)
        if (validationError != null) {
            errorMessage = validationError
            return
        }
        errorMessage = null
        if (ScheduleRules.conflicts(candidate, courses.filter { it.id != candidate.id })) {
            pendingConflict = candidate
        } else {
            onSave(candidate)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) {
                Text("‹", color = colors.onBackground, fontSize = 36.sp)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = if (course == null) "添加课程" else "编辑课程",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
                Text(
                    text = "安排好每一段学习时光",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (course != null && onDelete != null) {
                TextButton(onClick = { showDeleteDialog = true }) {
                    Text("删除", color = colors.error)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EditorSection(title = "课程信息", subtitle = if (LocalCompanionVisible.current && name.isNotBlank() && room.isBlank()) LocalAppTheme.current.companion?.contextLines?.get("classroom_pending") ?: "从一门新课程开始" else "从一门新课程开始") {
                EditorTextField(
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = "课程名称 *",
                    placeholder = "例如：高等数学",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorTextField(
                        value = room,
                        onValueChange = { room = it; errorMessage = null },
                        label = "教室",
                        placeholder = "例如：A-201",
                        modifier = Modifier.weight(1f),
                    )
                    EditorTextField(
                        value = teacher,
                        onValueChange = { teacher = it; errorMessage = null },
                        label = "教师",
                        placeholder = "教师姓名",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            EditorSection(title = "上课时间", subtitle = "选择星期和连续节次") {
                Text("每周上课日", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                        EditorChoice(
                            text = label,
                            isSelected = day == index + 1,
                            onClick = { day = index + 1; errorMessage = null },
                            modifier = Modifier.weight(1f),
                            accessibilityLabel = "星期$label",
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorNumberField(
                        value = startPeriod,
                        onValueChange = { startPeriod = it; errorMessage = null },
                        label = "起始节次",
                        unit = "节",
                        modifier = Modifier.weight(1f),
                    )
                    EditorNumberField(
                        value = endPeriod,
                        onValueChange = { endPeriod = it; errorMessage = null },
                        label = "结束节次",
                        unit = "节",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "每天共 $periodCount 节 · 可在设置中调整作息",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            EditorSection(title = "授课周次", subtitle = "本学期共 $totalWeeks 周") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorNumberField(
                        value = startWeek,
                        onValueChange = { startWeek = it; errorMessage = null },
                        label = "起始周次",
                        unit = "周",
                        modifier = Modifier.weight(1f),
                    )
                    EditorNumberField(
                        value = endWeek,
                        onValueChange = { endWeek = it; errorMessage = null },
                        label = "结束周次",
                        unit = "周",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "每周", "odd" to "单周", "even" to "双周").forEach { (key, label) ->
                        EditorChoice(
                            text = label,
                            isSelected = weekPattern == key,
                            onClick = { weekPattern = key; errorMessage = null },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            EditorSection(title = "课程配色", subtitle = "让课表一眼就能找到重点") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CourseAccents.forEachIndexed { index, color ->
                        val isSelected = index == colorIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clickable(role = Role.RadioButton) { colorIndex = index; errorMessage = null }
                                .semantics {
                                    selected = isSelected
                                    contentDescription = "课程配色 ${index + 1}"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .border(2.dp, if (isSelected) color else Color.Transparent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier.size(30.dp).background(color, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Text("✓", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            EditorSection(title = "备注", subtitle = "记下教材、作业或其他提醒") {
                EditorTextField(
                    value = note,
                    onValueChange = { note = it; errorMessage = null },
                    label = "课程备注",
                    placeholder = "给这门课留一点小提醒…",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                )
            }
        }

        Surface(color = colors.background) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                errorMessage?.let { message ->
                    Surface(color = colors.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            text = message,
                            color = colors.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                Button(
                    onClick = { saveDraft() },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                ) {
                    Text("保存课程", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    pendingConflict?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text("上课时间有重叠") },
            text = { Text("这门课程与已有课程的上课时间重叠。请确认安排后再保存。") },
            confirmButton = {
                TextButton(onClick = { pendingConflict = null; onSave(candidate) }) {
                    Text("仍然保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConflict = null }) { Text("返回修改") }
            },
        )
    }

    if (showDeleteDialog && course != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除这门课程？") },
            text = { Text("“${course.name}”会从课表中移除。") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete(course) }) {
                    Text("删除课程", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("保留课程") }
            },
        )
    }
}

@Composable
private fun EditorSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(1.dp))
            content()
        }
    }
}

@Composable
private fun EditorChoice(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = text,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .height(42.dp)
            .semantics { selected = isSelected; contentDescription = accessibilityLabel },
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) colors.primary else colors.surfaceContainerLow,
        contentColor = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outlineVariant,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
        ),
    )
}

@Composable
private fun EditorNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(3)) },
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        suffix = { Text(unit, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outlineVariant,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
        ),
    )
}
