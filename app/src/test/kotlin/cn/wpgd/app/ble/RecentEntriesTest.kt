package cn.wpgd.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The radio's scanned-device cache: rotating addresses cost bounded memory, recent ones stay. */
class RecentEntriesTest {
    @Test fun rotatingAddressesAreEvictedLeastRecentlyUsedFirst() {
        val cache = RecentEntries<String, Int>(3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        assertEquals(1, cache.get("a")) // "a" is now the most recently used
        repeat(100) { cache.put("rotated-$it", it) }
        assertEquals(3, cache.size)
        assertNull(cache.get("a"))
        assertEquals(99, cache.get("rotated-99"))
    }
}
