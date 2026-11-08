package com.shike.timetable.quotes

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** 桌面按钮显式打开前台Activity，复制点击时显示的正文，不把出处写入剪贴板。 */
class QuoteCopyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onResume() {
        super.onResume()
        if (!DailyQuoteStore(this).enabled) { finish(); return }
        val id = intent.getStringExtra("quote_id")
        val quote = DailyQuoteRepository.get(this).firstOrNull { it.id == id }
        if (quote != null) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("每日一言", quote.text))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "已复制正文", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
