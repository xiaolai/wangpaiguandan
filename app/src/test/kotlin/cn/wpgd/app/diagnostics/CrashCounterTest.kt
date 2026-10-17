package cn.wpgd.app.diagnostics

import cn.wpgd.app.data.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** WI-12 kill-switch input (plan section 8): crashes are counted on the device, as a number only. */
class CrashCounterTest {
    @Test fun countSurvivesANewInstanceOverTheSameStore() {
        val store = InMemoryKeyValueStore()
        assertEquals(0, CrashCounter(store).count())
        CrashCounter(store).record()
        CrashCounter(store).record()
        assertEquals(2, CrashCounter(store).count())
        assertEquals("only the count is stored", setOf("crashes"), store.keys())
    }

    @Test fun handlerCountsThenHandsTheCrashOn() {
        val store = InMemoryKeyValueStore()
        val seen = ArrayList<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> seen += e }
        val handler = CrashCounter(store).handler(previous)
        val boom = IllegalStateException("boom")
        handler.uncaughtException(Thread.currentThread(), boom)
        assertEquals(1, CrashCounter(store).count())
        assertSame(boom, seen.single())
    }

    @Test fun unreadableCountStartsAgainFromZero() {
        val store = InMemoryKeyValueStore()
        store.put("crashes", "not a number")
        assertEquals(0, CrashCounter(store).count())
        CrashCounter(store).record()
        assertEquals(1, CrashCounter(store).count())
    }
}
