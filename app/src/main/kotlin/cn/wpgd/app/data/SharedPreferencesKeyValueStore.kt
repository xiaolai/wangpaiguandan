package cn.wpgd.app.data

import android.content.SharedPreferences
import java.io.IOException

/**
 * [KeyValueStore] over one private SharedPreferences file (no extra dependency). Every file is
 * excluded from cloud backup and device transfer by `data_extraction_rules.xml` (P7). With
 * [synchronous], each write is on disk before it returns (WI-12: the crash counter writes from a
 * crash handler, after which the process ends), and a write the platform could not persist throws
 * [IOException] instead of returning as if it had been.
 */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences, private val synchronous: Boolean = false) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) = write("put") { putString(key, value) }

    override fun remove(key: String) = write("remove") { remove(key) }

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    override fun clear() = write("clear") { clear() }

    private inline fun write(what: String, change: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit().apply(change)
        if (!synchronous) {
            editor.apply()
        } else if (!editor.commit()) {
            throw IOException("$what was not written to disk")
        }
    }
}
