package cn.wpgd.app.room

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.lan.LanEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Rooms announced from several threads at once: every browser ends on the directory's latest list. */
class InProcessDirectoryTest {
    private fun advert(id: String) = RoomAdvert(id, "周末掼蛋", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, "a".repeat(64), "b".repeat(64), "K".repeat(43), emptySet(), LanEndpoint("mem", 1), emptySet())

    @Test fun browsersNeverEndOnAnOlderList() {
        repeat(50) { round ->
            val directory = InProcessDirectory()
            val last = AtomicReference<List<RoomAdvert>>(emptyList())
            directory.browser().start { last.set(it) }
            val go = CountDownLatch(1)
            val writers = (0 until 4).map { w ->
                thread {
                    go.await()
                    repeat(100) { i -> directory.announce(advert("r$round-$w-$i")) }
                }
            }
            go.countDown()
            writers.forEach { it.join() }
            assertEquals("round $round", directory.announced.map { it.roomId }.toSet(), last.get().map { it.roomId }.toSet())
        }
    }

    @Test fun aChangeMadeFromInsideACallbackReachesEveryBrowserAfterTheListBeforeIt() {
        val directory = InProcessDirectory()
        val first = ArrayList<List<String>>()
        val second = ArrayList<List<String>>()
        directory.browser().start { rooms ->
            first += rooms.map { it.roomId }
            // Reacting to a room by announcing another (on the delivering thread).
            if (rooms.any { it.roomId == "a" } && rooms.none { it.roomId == "b" }) directory.announce(advert("b"))
        }
        directory.browser().start { rooms -> second += rooms.map { it.roomId } }
        directory.announce(advert("a"))
        assertEquals(listOf(emptyList(), listOf("a"), listOf("a", "b")), first)
        assertEquals("the newer list arrived before the older one", listOf(emptyList(), listOf("a"), listOf("a", "b")), second)
    }
}
