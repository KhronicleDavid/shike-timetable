package com.shike.timetable.theme

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CompanionInteractionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("shike_companion_interaction_test", Context.MODE_PRIVATE)
    private val theme = ThemeRepository.get(context).find("himari")
    private val content = theme.companion!!
    private val moment = LocalDateTime.of(2026, 10, 15, 8, 10)
    @Before fun before() { preferences.edit().clear().commit() }
    @After fun after() { preferences.edit().clear().commit() }

    @Test fun selectionAndFirstTapHintPersistAcrossStoresPeriodsAndThemes() {
        val first = CompanionInteractionStore(preferences)
        val second = CompanionInteractionStore(preferences)
        assertTrue(first.showShuffleHint())
        val initial = first.current(theme, moment)!!
        assertTrue(first.showShuffleHint())
        val seen = mutableListOf(initial.line)
        var previousArt = initial.artwork
        repeat(15) {
            val next = first.shuffle(theme, moment)!!
            if (content.quotes.getValue(DayPart.Morning).size > 5) assertFalse(next.line in seen.takeLast(5))
            if (content.pictures(DayPart.Morning).size > 1) assertNotEquals(previousArt, next.artwork)
            assertEquals(next, second.current(theme, moment.plusMinutes(5)))
            assertFalse(second.showShuffleHint())
            seen += next.line; previousArt = next.artwork
        }
        val other = theme.copy(id = "other", companion = content.copy(shortName = "另一角色"))
        val original = second.current(theme, moment)
        second.shuffle(other, moment)
        assertEquals(original, first.current(theme, moment))
        assertFalse(first.showShuffleHint())
        val noon = moment.withHour(11)
        assertEquals(CompanionEngine.at(theme, noon), first.current(theme, noon))
        assertFalse(first.showShuffleHint())
        assertEquals(CompanionEngine.at(theme, moment.plusDays(1)), first.current(theme, moment.plusDays(1)))
        assertFalse(first.showShuffleHint())
    }

    @Test fun malformedRemovedContentAndLegacyPreferenceRecover() {
        val store = CompanionInteractionStore(preferences)
        preferences.edit().putString("choice_himari", "{bad").putString("shuffle_hint_dismissed", "bad").commit()
        assertEquals(CompanionEngine.at(theme, moment), store.current(theme, moment))
        assertTrue(store.showShuffleHint())
        val manual = store.shuffle(theme, moment)!!
        val changed = theme.copy(companion = content.copy(
            quotes = content.quotes.mapValues { (_, values) -> values.filterNot { it == manual.line }.ifEmpty { listOf("内容更新后的测试语录") } },
            artworks = content.artworks.filterNot { it.id == manual.artwork }.let { remaining ->
                if (remaining.any { DayPart.Morning in it.periods }) remaining
                else remaining + content.artwork(manual.artwork)!!.copy(id = "replacement_art")
            }))
        val recovered = store.current(changed, moment)!!
        assertNotEquals(manual.line, recovered.line)
        assertNotEquals(manual.artwork, recovered.artwork)
        preferences.edit().clear().putLong("bucket", CompanionEngine.bucketAt(moment)).putString("part", "Morning")
            .putString("line", manual.line).putString("artwork", manual.artwork).commit()
        assertEquals(manual, store.current(theme, moment))
        assertNull(store.current(ThemeRepository.get(context).find("minimal"), moment))
    }

    @Test fun concurrentStoresKeepACompleteCurrentPeriodChoice() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..24).map { Callable { CompanionInteractionStore(preferences).shuffle(theme, moment)!! } }).map { it.get() }
            results.forEach {
                assertTrue(it.line in content.quotes.getValue(DayPart.Morning))
                assertTrue(it.artwork in content.pictures(DayPart.Morning).map { art -> art.id })
            }
            assertTrue(CompanionInteractionStore(preferences).current(theme, moment) in results)
            assertFalse(CompanionInteractionStore(preferences).showShuffleHint())
        } finally { executor.shutdownNow() }
    }

    @Test fun appearanceMigratesTheLegacySwitchAndKeepsThemePreferenceIndependent() {
        preferences.edit().putBoolean("show_himari", false).commit()
        val appearance = AppearanceStore(preferences)
        assertFalse(appearance.showCompanion)
        appearance.themeId = "minimal"
        assertFalse(AppearanceStore(preferences).showCompanion)
        appearance.showCompanion = true
        assertTrue(AppearanceStore(preferences).showCompanion)
        assertEquals("minimal", AppearanceStore(preferences).themeId)
    }
}
