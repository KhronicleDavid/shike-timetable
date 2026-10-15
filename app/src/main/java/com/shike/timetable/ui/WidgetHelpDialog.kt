package com.shike.timetable.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shike.timetable.widget.TodayWidgetUpdates

@Composable fun WidgetHelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("阳茉莉 · 桌面今日课表") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("在桌面直接看今日课程和教室，课程多时在列表内上下滑动。点击课程可以打开详情。")
            Text("默认约 4 × 3 格。长按小组件并拖动边缘，可以铺满横向并调节高度；建议高度约 220–520 dp，具体格数由手机桌面决定。")
            Text("阳茉莉按早晨、午间、夜晚切换插画和陪伴语。点她的立绘或语录，可以随机换一句，尽量避开刚说过的话。")
            Text("本次选择会保留到下一个半小时，应用内也会同步。修改课表后自动更新；右下角刷新只更新课表，不主动换语录。")
            Text("也可以长按桌面空白处 → 小组件 → 拾课 →「阳茉莉 · 今日课表」。")
        }
    }, confirmButton = { TextButton(onClick = {
        if (TodayWidgetUpdates.requestPin(context)) onDismiss()
        else Toast.makeText(context, "请长按桌面空白处，从小组件列表添加拾课。", Toast.LENGTH_LONG).show()
    }) { Text("添加到桌面") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
