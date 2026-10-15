package com.shike.timetable.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 独立测试偏好：不读取、清理或修改用户课表。 */
class HimariInteractionStoreTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("shike_companion_interaction_test", Context.MODE_PRIVATE)
    private val moment = LocalDateTime.of(2026, 10, 15, 8, 10)

    @Before fun before() { preferences.edit().clear().commit() }
    @After fun after() { preferences.edit().clear().commit() }

    @Test fun selectedCompanionIsSharedAndReturnsToTheScheduleAtTheNextBucket() {
        val first = HimariInteractionStore(preferences)
        val second = HimariInteractionStore(preferences)
        val initial = first.current(moment)
        val seen = mutableListOf(initial.line)
        var previousArtwork = initial.artwork
        repeat(15) {
            val next = first.shuffle(moment)
            assertFalse(next.line in seen.takeLast(5))
            assertNotEquals(previousArtwork, next.artwork)
            assertEquals(next, second.current(moment.plusMinutes(5)))
            seen += next.line
            previousArtwork = next.artwork
        }
        assertEquals(HimariCompanion.at(moment.withMinute(30)), second.current(moment.withMinute(30)))
        val tomorrow = moment.plusDays(1)
        assertEquals(HimariCompanion.at(tomorrow), first.current(tomorrow))
        val noon = tomorrow.withHour(11)
        assertEquals(HimariCompanion.at(noon), first.current(noon))
    }

    @Test fun badTypesUnknownQuotesAndMalformedHistoryRecoverWithoutLeakingText() {
        preferences.edit().putString("bucket", "invalid").putBoolean("part", true)
            .putString("artwork", "missing_drawable").putString("line", "未知外来文字")
            .putString("recent", "{bad").commit()
        val store = HimariInteractionStore(preferences)
        assertEquals(HimariCompanion.at(moment), store.current(moment))
        assertTrue(store.shuffle(moment).line in HimariCompanion.morningLines)
        preferences.edit().putString("line", HimariCompanion.eveningLines.first())
            .putString("artwork", HimariCompanion.artworks.getValue(HimariDayPart.Evening).first()).commit()
        assertEquals(HimariCompanion.at(moment), store.current(moment))
    }

    @Test fun concurrentStoresKeepACompleteValidChoice() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..24).map {
                Callable { HimariInteractionStore(preferences).shuffle(moment) }
            }).map { it.get() }
            results.forEach {
                assertTrue(it.line in HimariCompanion.morningLines)
                assertTrue(it.artwork in HimariCompanion.artworks.getValue(HimariDayPart.Morning))
            }
            assertTrue(HimariInteractionStore(preferences).current(moment) in results)
        } finally { executor.shutdownNow() }
    }
}
