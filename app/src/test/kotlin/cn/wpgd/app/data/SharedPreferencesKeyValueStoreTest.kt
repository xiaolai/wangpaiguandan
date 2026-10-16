package cn.wpgd.app.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Round 3 #6: a synchronous write the platform did not persist must not return as if it had been. */
class SharedPreferencesKeyValueStoreTest {
    /** SharedPreferences whose commits report [commitOk]; applies record that they were used. */
    private class FakePrefs(var commitOk: Boolean) : SharedPreferences {
        val map = HashMap<String, String>()
        var commits = 0
        var applies = 0

        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in map
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = ArrayList<(MutableMap<String, String>) -> Unit>()
            private fun change(c: (MutableMap<String, String>) -> Unit): SharedPreferences.Editor {
                changes += c
                return this
            }
            override fun putString(key: String, value: String?) = change { m -> if (value == null) m.remove(key) else m.put(key, value) }
            override fun putStringSet(key: String, values: Set<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = change { m -> m.remove(key) }
            override fun clear() = change { m -> m.clear() }
            override fun commit(): Boolean {
                commits++
                if (commitOk) changes.forEach { it(map) }
                return commitOk
            }
            override fun apply() {
                applies++
                changes.forEach { it(map) }
            }
        }
    }

    private fun assertIoFailure(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun aFailedSynchronousCommitThrowsForEveryKindOfWrite() {
        val prefs = FakePrefs(commitOk = false)
        val store = SharedPreferencesKeyValueStore(prefs, synchronous = true)
        assertIoFailure { store.put("crashes", "1") }
        assertIoFailure { store.remove("crashes") }
        assertIoFailure { store.clear() }
        assertEquals(3, prefs.commits)
        assertTrue(prefs.map.isEmpty())
    }

    @Test fun aSuccessfulSynchronousCommitPersistsAndAnAsynchronousStoreApplies() {
        val prefs = FakePrefs(commitOk = true)
        SharedPreferencesKeyValueStore(prefs, synchronous = true).put("crashes", "1")
        assertEquals("1", prefs.map["crashes"])
        assertEquals(1, prefs.commits)
        val async = FakePrefs(commitOk = false)
        SharedPreferencesKeyValueStore(async).put("nickname", "阿梅")
        assertEquals(1, async.applies)
        assertEquals(0, async.commits)
        assertEquals("阿梅", async.map["nickname"])
    }
}
