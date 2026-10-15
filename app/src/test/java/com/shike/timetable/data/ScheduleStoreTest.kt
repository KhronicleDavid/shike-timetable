package com.shike.timetable.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScheduleStoreTest {
    @Test fun firstReadSeedsAndPersistsDemoOnlyOnce() {
        val preferences = MemoryPreferences()
        val store = ScheduleStore(preferences)
        assertEquals(DemoData.schedule, store.read())
        assertNotNull(preferences.getString("schedule_json", null))
        assertEquals(DemoData.schedule, ScheduleStore(preferences).read())
        assertNull(store.lastReadWarning)
    }

    @Test fun corruptedReadKeepsOriginalAndBackupWithoutCrashing() {
        val original = "损坏的原始课表 {"
        val preferences = MemoryPreferences().apply {
            edit().putString("schedule_json", original).apply()
        }
        val store = ScheduleStore(preferences)
        val recovered = store.read()
        assertTrue(recovered.courses.isEmpty())
        assertEquals(DemoData.settings, recovered.settings)
        assertNotNull(store.lastReadWarning)
        assertEquals(original, preferences.getString("schedule_json", null))
        assertEquals(original, store.readCorruptedBackup())
    }

    @Test fun savingAfterRecoveryRetainsBackupAndClearsWarning() {
        val original = "{\"unknown\":1}"
        val preferences = MemoryPreferences().apply {
            edit().putString("schedule_json", original).apply()
        }
        val store = ScheduleStore(preferences)
        store.read()
        store.save(DemoData.schedule)
        assertNull(store.lastReadWarning)
        assertEquals(DemoData.schedule, store.read())
        assertEquals(original, store.readCorruptedBackup())
    }

    @Test fun failedImportDoesNotChangePersistedSchedule() {
        val preferences = MemoryPreferences()
        val store = ScheduleStore(preferences)
        store.save(DemoData.schedule)
        val before = preferences.getAll().toMap()
        try {
            store.importJson("{\"schemaVersion\":999}")
            fail("应拒绝不完整的导入文件")
        } catch (_: IllegalArgumentException) {
            assertEquals(before, preferences.getAll())
        }
    }

    /** 仅模拟实际用到的本地键值行为，不需要模拟 Android 生命周期。 */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var shouldClear = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = value
                return this
            }

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = put(key, null)
            override fun clear(): SharedPreferences.Editor {
                shouldClear = true
                return this
            }
            override fun commit(): Boolean {
                if (shouldClear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
