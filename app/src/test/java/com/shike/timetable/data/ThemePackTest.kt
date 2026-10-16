package com.shike.timetable.data

import com.shike.timetable.theme.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

class ThemePackTest {
    private val assets = File("src/main/assets")
    private fun json() = JSONObject(File(assets, "themes/himari/theme.json").readText())

    @Test fun bundledThemesHaveAllAssetsAndIndependentPalettes() {
        val repository = ThemeRepository.read { File(assets, it).readText() }
        assertEquals(repository.defaultId, repository.find("removed_theme").id)
        repository.themes.forEach { theme ->
            assertEquals(6, theme.light.courseAccents.size)
            assertEquals(6, theme.dark.courseAccents.size)
            theme.companion?.artworks?.forEach {
                assertTrue("缺少素材 ${theme.id}/${it.file}", File(assets, theme.asset(it)).isFile)
                assertTrue("缺少紧凑素材 ${theme.id}/${it.compactFile}", File(assets, theme.asset(it, compact = true)).isFile)
            }
        }
    }

    @Test fun compactArtworkIsOptionalAndUsesTheSamePathRestrictions() {
        val initial = ThemePackParser.parse(json().toString())
        val original = initial.companion!!.artworks.first()
        assertEquals(initial.asset(original), initial.asset(original, compact = true))
        val config = json()
        val artwork = config.getJSONObject("companion").getJSONArray("artworks").getJSONObject(0)
        artwork.put("compactFile", "artwork/small.png")
        val changed = ThemePackParser.parse(config.toString())
        assertEquals("themes/himari/artwork/small.png", changed.asset(changed.companion!!.artworks.first(), compact = true))
        assertEquals(initial.asset(original), changed.asset(changed.companion.artworks.first()))
        listOf("../secret.png", "/absolute.png", "artwork/../../secret.png", "", "https://example.com/image.png").forEach { path ->
            artwork.put("compactFile", path)
            assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(config.toString()) }
        }
    }

    @Test fun aPoolCanShrinkToOneLineAndPictureWithoutCrashing() {
        val json = json()
        val content = json.getJSONObject("companion")
        val art = content.getJSONArray("artworks").getJSONObject(0)
        art.put("periods", listOf("morning", "noon", "evening"))
        content.put("artworks", listOf(art))
        content.put("quotes", JSONObject().put("morning", listOf("早晨的专属句子"))
            .put("noon", listOf("午间的专属句子")).put("evening", listOf("夜晚的专属句子")))
        val theme = ThemePackParser.parse(json.toString())
        val moments = listOf(8, 13, 21).map { LocalDateTime.of(2026, 10, 15, it, 0) }
        moments.forEach { moment ->
            var current = CompanionEngine.at(theme, moment)
            repeat(10) { current = CompanionEngine.randomNext(theme, moment, current, listOf(current!!.line), Random(it)) }
            assertEquals(theme.companion!!.quotes.getValue(DayPart.at(moment.toLocalTime())).single(), current!!.line)
        }
    }

    @Test fun missingPeriodDuplicateArtAndUnsafePathsAreRejected() {
        val missing = json().apply { getJSONObject("companion").getJSONObject("quotes").put("noon", emptyList<String>()) }
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(missing.toString()) }
        val duplicate = json().apply {
            val arts = getJSONObject("companion").getJSONArray("artworks")
            arts.put(arts.getJSONObject(0))
        }
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(duplicate.toString()) }
        val unsafe = json().apply { getJSONObject("companion").getJSONArray("artworks").getJSONObject(0).put("file", "../private.png") }
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(unsafe.toString()) }
    }

    @Test fun addingAndRemovingContentNeedsOnlyConfigurationChanges() {
        val json = json()
        val content = json.getJSONObject("companion")
        val initialQuotes = content.getJSONObject("quotes").getJSONArray("morning").length()
        content.getJSONObject("quotes").getJSONArray("morning").put("早、早上好，今天也从容出发吧。")
        val arts = content.getJSONArray("artworks")
        val morningIndexes = (0 until arts.length()).filter { arts.getJSONObject(it).getJSONArray("periods").toList().contains("morning") }
        val removed = arts.getJSONObject(morningIndexes.first()).getString("id")
        if (morningIndexes.size == 1) arts.put(JSONObject(arts.getJSONObject(morningIndexes.first()).toString()).put("id", "replacement_morning"))
        arts.remove(morningIndexes.first())
        val theme = ThemePackParser.parse(json.toString())
        assertEquals(initialQuotes + 1, theme.companion!!.quotes.getValue(DayPart.Morning).size)
        assertEquals(maxOf(1, morningIndexes.size - 1), theme.companion.pictures(DayPart.Morning).size)
        assertFalse(theme.companion.artworks.any { it.id == removed })
        val moment = LocalDateTime.of(2026, 10, 15, 8, 0)
        repeat(30) {
            val choice = CompanionEngine.at(theme, moment.plusMinutes(it.toLong()))!!
            assertTrue(choice.artwork in theme.companion.pictures(DayPart.Morning).map { art -> art.id })
        }
    }

    @Test fun voiceFilesCoverAllHimariTimePoolsAndOtherThemesRemainOptional() {
        val repository = ThemeRepository.read { File(assets, it).readText() }
        val content = repository.find("himari").companion!!
        assertEquals(114, content.voices.size)
        content.quotes.values.flatten().forEach { line ->
            val clip = content.voices.getValue(line)
            assertEquals("JA", clip.language)
            val file = File(assets, "themes/himari/${clip.file}")
            assertTrue(file.isFile)
            assertEquals("OggS", file.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
        }
        assertTrue(repository.find("sagiri").companion!!.voices.isEmpty())
        val old = json().apply { getJSONObject("companion").remove("voices") }
        assertTrue(ThemePackParser.parse(old.toString()).companion!!.voices.isEmpty())
    }

    @Test fun unsafeAndDuplicateVoiceMappingsAreRejected() {
        val config = json()
        val content = config.getJSONObject("companion")
        val voices = org.json.JSONArray().put(JSONObject().put("line", content.getJSONObject("quotes").getJSONArray("morning").getString(0))
            .put("file", "voice/test.ogg").put("text", "おはよう").put("language", "JA"))
        content.put("voices", voices)
        listOf("../private.ogg", "/voice.ogg", "https://example.com/test.mp3", "voice/../../private.wav", "voice/file.png").forEach { path ->
            voices.getJSONObject(0).put("file", path)
            assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(config.toString()) }
        }
        voices.getJSONObject(0).put("file", "voice/test.ogg")
        voices.put(voices.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(config.toString()) }
    }

    @Test fun sylviaPoolsHaveIndependentTextAndCompleteVoiceMappings() {
        val theme = ThemePackParser.parse(File(assets, "themes/sylvia/theme.json").readText())
        val content = theme.companion!!
        assertEquals(2, theme.version)
        assertEquals(114, content.voices.size)
        assertEquals(120, content.allLines.size)
        assertTrue(content.allLines.intersect(ThemePackParser.parse(json().toString()).companion!!.allLines.toSet()).isEmpty())
        content.quotes.values.forEach { pool ->
            assertEquals(38, pool.size)
            pool.forEach { line ->
                val clip = content.voices.getValue(line)
                assertEquals("JA", clip.language)
                val file = File(assets, "themes/sylvia/${clip.file}")
                assertEquals("OggS", file.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
            }
        }
    }

    @Test fun artworkOnlyThemeMatchesHimariCountAndDoesNotInventDialogue() {
        val config = JSONObject(File(assets, "themes/sylvia/theme.json").readText())
        config.getJSONObject("companion").apply { remove("quotes"); remove("contextLines"); remove("voices") }
        val sylvia = ThemePackParser.parse(config.toString())
        val content = sylvia.companion!!
        assertEquals(ThemePackParser.parse(json().toString()).companion!!.artworks.size, content.artworks.size)
        assertEquals(16, content.artworks.size)
        assertFalse(content.hasDialogue)
        assertTrue(content.allLines.isEmpty())
        assertTrue(content.voices.isEmpty())
        listOf(8, 13, 21, 2).forEach { hour ->
            val time = LocalDateTime.of(2026, 10, 16, hour, 12)
            val part = DayPart.at(time.toLocalTime())
            assertEquals(4, content.pictures(part).size)
            var current = CompanionEngine.at(sylvia, time)!!
            repeat(40) { seed ->
                val next = CompanionEngine.randomNext(sylvia, time, current, emptyList(), Random(seed))!!
                assertEquals("", next.line)
                assertEquals(part, next.part)
                assertNotEquals(current.artwork, next.artwork)
                assertTrue(next.artwork in content.pictures(part).map { it.id })
                current = next
            }
        }
    }

    @Test fun omittedDialogueIsAllowedButMalformedOrPartiallyEmptyPoolsAreRejected() {
        val config = json()
        val content = config.getJSONObject("companion")
        content.remove("quotes")
        assertFalse(ThemePackParser.parse(config.toString()).companion!!.hasDialogue)
        content.put("quotes", "not an object")
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(config.toString()) }
        content.put("quotes", JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(config.toString()) }
        content.put("quotes", JSONObject().put("morning", listOf("早晨")))
        assertThrows(IllegalArgumentException::class.java) { ThemePackParser.parse(config.toString()) }
    }
}
