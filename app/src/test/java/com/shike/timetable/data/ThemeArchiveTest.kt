package com.shike.timetable.data

import com.shike.timetable.theme.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun config() = File("../theme-packs/sylvia/theme.json").readText()
    private fun zip(entries: List<Pair<String, ByteArray>>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }
    private fun entries(prefix: String = ""): List<Pair<String, ByteArray>> {
        val c = JSONObject(config()).getJSONObject("companion")
        val paths = mutableSetOf<String>()
        val arts = c.getJSONArray("artworks")
        repeat(arts.length()) { i ->
            val art = arts.getJSONObject(i); paths += art.getString("file")
            if (art.has("compactFile")) paths += art.getString("compactFile")
        }
        val voices = c.getJSONArray("voices")
        repeat(voices.length()) { paths += voices.getJSONObject(it).getString("file") }
        return listOf(prefix+"theme.json" to config().toByteArray()) + paths.map { prefix+it to byteArrayOf(1,2,3) }
    }
    @Test fun completeAndSingleFolderArchivesPreserveAllReferences() {
        listOf("", "my-theme/").forEach { prefix ->
            val result = ThemeArchive.prepare(zip(entries(prefix)), File(temp.root, "staging-${prefix.length}"))
            assertEquals("sylvia", result.theme.id)
            assertEquals(114, result.theme.companion!!.voices.size)
            assertTrue(File(result.directory, "voice/morning_001.ogg").isFile)
        }
    }
    @Test fun traversalAbsoluteWindowsAndDuplicatePathsNeverEscapeStaging() {
        listOf("../escape.png", "/absolute.png", "C:/escape.png", "artwork\\escape.png", "artwork/./x.png", "artwork//x.png").forEachIndexed { index, path ->
            val staging = File(temp.root, "bad-$index")
            assertThrows(IllegalArgumentException::class.java) { ThemeArchive.prepare(zip(entries()+ (path to byteArrayOf(1))), staging) }
            assertFalse(staging.exists())
        }
        assertThrows(IllegalArgumentException::class.java) { ThemeArchive.prepare(zip(entries()+ ("THEME.JSON" to byteArrayOf(1))), File(temp.root,"duplicate")) }
        assertFalse(File(temp.root.parentFile,"escape.png").exists())
    }
    @Test fun missingReferencedFilesExecutableContentAndInvalidMediaRejectWholeImport() {
        val cases = listOf(entries().filterNot { it.first=="voice/morning_001.ogg" }, entries()+("payload.js" to byteArrayOf(1)))
        cases.forEachIndexed { i, files ->
            assertThrows(IllegalArgumentException::class.java) { ThemeArchive.prepare(zip(files), File(temp.root,"missing-$i")) }
        }
        val staging = File(temp.root,"invalid-media")
        assertThrows(IllegalArgumentException::class.java) { ThemeArchive.prepare(zip(entries()), staging) { throw IllegalArgumentException("媒体损坏") } }
        assertFalse(staging.exists())
    }
    @Test fun zipBombConfigAndUnexpectedSiblingFilesAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { ThemeArchive.prepare(zip(listOf("theme.json" to ByteArray(1_048_577){32})), File(temp.root,"bomb")) }
        assertThrows(IllegalArgumentException::class.java) { ThemeArchive.prepare(zip(entries("folder/")+("other/file.png" to byteArrayOf(1))), File(temp.root,"sibling")) }
        assertThrows(IllegalArgumentException::class.java) { ThemeArchive.prepare(zip((0..1024).map { "artwork/$it.png" to byteArrayOf(1) }), File(temp.root,"too-many")) }
        val oversizedName = JSONObject(config()).put("name", "名".repeat(1000)).toString()
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(oversizedName) }
    }
}
