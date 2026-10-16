package com.shike.timetable.theme

import android.content.Context
import android.media.MediaPlayer
import java.io.File
import java.io.InputStream

/** 页面、小组件与播放服务共用来源解析，外部包不执行代码或网页。 */
object ThemeFiles {
    fun open(context: Context, theme: ThemePack, relative: String): InputStream =
        theme.directory?.let { resolve(it, relative).inputStream() } ?: context.assets.open("themes/${theme.id}/$relative")

    fun setDataSource(context: Context, theme: ThemePack, clip: ThemeVoice, player: MediaPlayer) {
        val directory = theme.directory
        if (directory != null) resolve(directory, clip.file).inputStream().use { player.setDataSource(it.fd) }
        else context.assets.openFd("themes/${theme.id}/${clip.file}").use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
    }

    private fun resolve(directory: File, relative: String): File {
        val file = File(directory, relative).canonicalFile
        require(file.path.startsWith(directory.canonicalPath + File.separator)) { "主题资源路径越界" }
        return file
    }
}
