package com.shike.timetable.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import com.shike.timetable.theme.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.timetable.quotes.*
import com.shike.timetable.widget.TodayWidgetUpdates
import java.time.LocalDateTime

@OptIn(ExperimentalFoundationApi::class)
@Composable fun DailyQuoteCard(now: LocalDateTime, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { DailyQuoteStore(context) }
    val revision by DailyQuoteStore.changes.collectAsState()
    var quote by remember { mutableStateOf<DailyQuote?>(null) }
    LaunchedEffect(revision, now) { quote = store.current() }
    LaunchedEffect(quote?.id) { if (quote != null) TodayWidgetUpdates.request(context) }
    val item = quote ?: return
    fun copy() {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("每日一言", item.text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制正文", Toast.LENGTH_SHORT).show()
    }
    fun next() { store.next(); TodayWidgetUpdates.request(context) }
    Surface(modifier.fillMaxWidth().testTag("daily_quote_card"), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f).combinedClickable(onClick = ::next, onClickLabel = "换一条每日一言", onLongClick = ::copy,
            onLongClickLabel = "复制正文").padding(horizontal = 16.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("每日一言", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = ::next, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 36.dp)) { Text("换句", fontSize = 11.sp) }
                TextButton(onClick = ::copy, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 36.dp).testTag("daily_quote_copy")) { Text("复制", fontSize = 11.sp) }
            }
            Text(item.text, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag("daily_quote_text"))
            Text("—— ${item.credit}", color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .8f), fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 3.dp).testTag("daily_quote_credit"), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        if (LocalCompanionVisible.current) {
            val content = LocalAppTheme.current.companion
            val moment = LocalCompanionMoment.current
            val shuffle = LocalCompanionShuffle.current
            val busy = LocalVoicePhase.current != null
            Column(Modifier.width(104.dp).padding(end = 6.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(132.dp).clickable(enabled = !busy, onClickLabel = "切换角色陪伴语", onClick = { shuffle?.invoke() })
                    .testTag("companion_portrait_shuffle")) {
                    CompanionPortrait(Modifier.fillMaxSize(), artwork = content?.artwork(moment?.artwork.orEmpty()))
                }
                val status = LocalVoiceStatus.current
                if (status != null) Text(status, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.testTag("voice_status"))
                else if (!busy && LocalShuffleHint.current) Text("点点我 · 换句话", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(bottom = 6.dp).clickable { shuffle?.invoke() }.testTag("shuffle_hint"))
            }
        }
        }
    }
}

@Composable fun DailyQuoteSettings() {
    val context = LocalContext.current
    val store = remember(context) { DailyQuoteStore(context) }
    val revision by DailyQuoteStore.changes.collectAsState()
    val enabled = remember(revision) { store.enabled }
    var exact by remember { mutableStateOf(DailyQuoteScheduler.canScheduleExactly(context)) }
    var sources by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val owner = context as androidx.activity.ComponentActivity
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exact = DailyQuoteScheduler.canScheduleExactly(context); DailyQuoteScheduler.schedule(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每日一言", fontWeight = FontWeight.Medium)
                    Text("GalGame短句 · 应用与桌面同步\n每天08:00换句 · 单击换句，长按或按钮复制正文", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Switch(enabled, { value ->
                    store.enabled = value; if (value) store.current()
                    DailyQuoteScheduler.schedule(context); TodayWidgetUpdates.request(context)
                }, modifier = Modifier.testTag("daily_quote_enabled").semantics { contentDescription = "每日一言开关" })
            }
            TextButton(onClick = { sources = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("${DailyQuoteRepository.get(context).size}条 · 句库与出处", fontSize = 12.sp)
            }
            if (enabled && !exact && Build.VERSION.SDK_INT >= 31) {
                Text("允许闹钟与提醒后，桌面小组件可在08:00准点刷新；未允许时系统可能延后。", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp))
                TextButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:${context.packageName}"))) }.onFailure {
                        Toast.makeText(context, "请在系统设置中允许拾课的闹钟与提醒", Toast.LENGTH_LONG).show()
                    }
                }) { Text("允许08:00准点刷新", fontSize = 12.sp) }
            }
        }
    }
    if (sources) AlertDialog(onDismissRequest = { sources = false }, title = { Text("句库与出处") },
        text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("一言开源社区（AGPL-3.0）与公开作品短摘；含部分日文自译，作品名称后可查看原网页。", fontSize = 12.sp) }
            items(DailyQuoteRepository.get(context).size) { index ->
                val item = DailyQuoteRepository.get(context)[index]
                Column {
                    Text(item.text, fontSize = 13.sp)
                    TextButton(onClick = { runCatching { uri.openUri(item.sourceUrl) } }) { Text(item.credit, fontSize = 11.sp) }
                }
            }
        } }, confirmButton = { TextButton(onClick = { sources = false }) { Text("关闭") } })
}
