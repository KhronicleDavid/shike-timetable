package com.shike.timetable

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import com.shike.timetable.theme.ExternalThemeStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 角色素材仅进入测试APK。正式基础APK不再携带这些测试数据。 */
class ThemeTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (InstrumentationRegistry.getArguments().getString("themeFixtures", "true") != "false") {
            ThemeTestContent.installAll(targetContext, context)
        }
        super.onStart()
    }
}

object ThemeTestContent {
    fun installAll(target: Context, tests: Context) {
        tests.assets.list("").orEmpty().filter { id ->
            tests.assets.list(id).orEmpty().contains("theme.json")
        }.forEach { id ->
            val archive = archive(target, tests, id)
            try {
                val store = ExternalThemeStore(target)
                store.install(store.prepare(archive))
            } finally { archive.delete() }
        }
    }
    fun archive(target: Context, tests: Context, id: String): File {
        val output = File(target.cacheDir, "fixture-$id.zip")
        ZipOutputStream(output.outputStream()).use { zip ->
            fun copy(path: String) {
                val children = tests.assets.list(path).orEmpty()
                if (children.isNotEmpty()) children.forEach { copy("$path/$it") }
                else {
                    zip.putNextEntry(ZipEntry(path.removePrefix("$id/")))
                    tests.assets.open(path).use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            copy(id)
        }
        return output
    }
}
