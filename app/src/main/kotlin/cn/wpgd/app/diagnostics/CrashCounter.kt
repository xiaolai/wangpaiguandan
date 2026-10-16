package cn.wpgd.app.diagnostics

import cn.wpgd.app.data.KeyValueStore

/**
 * Counts process crashes on this device (WI-12; plan section 8: "local crash/stall counters in
 * diagnostics" feed the kill switch). Stores one number and nothing about the crash: no message, no
 * stack trace (either can name cards or ids). The store is app-private and excluded from backup.
 */
class CrashCounter(private val store: KeyValueStore) {
    fun count(): Long = store.get(KEY)?.toLongOrNull()?.takeIf { it >= 0 } ?: 0

    @Synchronized
    fun record() {
        store.put(KEY, (count() + 1).toString())
    }

    /** An uncaught-exception handler that counts the crash, then hands it to [next] (the platform's, which ends the process). */
    fun handler(next: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, e ->
        try {
            record()
        } finally {
            next?.uncaughtException(thread, e)
        }
    }

    private companion object {
        const val KEY = "crashes"
    }
}
