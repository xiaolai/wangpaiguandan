package cn.wpgd.app

import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.LocalStores
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.room.InProcessDirectory
import cn.wpgd.app.room.LanRoomNetwork
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.session.storage.InMemoryStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Round 3 #11: leaving a table from the screen must not hold the main thread while the owner waits
 * for its last words (a leaving host lingers about a second); the next table still starts only after
 * the old one has finished.
 */
class AppGraphCloseTest {
    private val directory = InProcessDirectory()
    private val graph = AppGraph(
        LocalStores(InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore()),
        profiles = { TestProfiles.profiles },
        platform = { RoomPlatform(LanRoomNetwork(directory, bindAddress = InetAddress.getLoopbackAddress()), InMemoryStorage()) },
    )

    @After fun tearDown() = graph.closeTable()

    private fun waitFor(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test fun aLeavingHostIsClosedOffTheCallersThreadAndTheNextTableWaitsForIt() {
        val owner = graph.roomOwner(RoomMedium.LAN)
        assertTrue(owner.hostRoom(nickname(), RoomSettings("周末掼蛋")))
        waitFor { owner.state.value.table?.hostSide?.saved == true && directory.announced.isNotEmpty() }
        owner.leaveHosted(checkNotNull(owner.prepareLeave()).suspend())

        val started = System.nanoTime()
        graph.closeTableInBackground()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the caller waited $tookMs ms for the shutdown", tookMs < 500)
        assertNull("the table is released at once", graph.openTable.value)

        val next = graph.owner()
        assertNotSame(owner, next)
        assertEquals("the old table finished before the new one started", OwnerLifecycle.SHUT_DOWN, owner.state.value.lifecycle)
        assertTrue(directory.announced.isEmpty())
    }
}
