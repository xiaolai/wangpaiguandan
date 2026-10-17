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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch

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

        // Audit #4: starting the next table does not hold the caller either; the new owner waits on its own loop.
        val askedAt = System.nanoTime()
        val next = graph.owner()
        val askMs = (System.nanoTime() - askedAt) / 1_000_000
        assertTrue("the caller waited $askMs ms for the next owner", askMs < 500)
        assertNotSame(owner, next)
        assertTrue(next.startSolo(nickname()))
        waitFor { next.state.value.table != null }
        assertEquals("the old table finished before the new one started", OwnerLifecycle.SHUT_DOWN, owner.state.value.lifecycle)
        assertTrue(owner.terminated)
        assertTrue(directory.announced.isEmpty())
    }

    /** Holds [owner]'s session loop in a task that ignores interrupts until [release] opens: a stalled loop. */
    private fun stall(owner: cn.wpgd.app.owner.SessionOwner, release: CountDownLatch) = owner.withHost {
        while (true) {
            try {
                release.await()
                return@withHost
            } catch (_: InterruptedException) {
                // A stalled task ignores the shutdown's interrupt.
            }
        }
    }

    /**
     * Audit #3: an owner whose loop stalls returns from shutdown while its loop still runs, and is torn
     * down only when that loop ends. The next table must not start before then.
     */
    @Test fun theNextTableWaitsForAStalledOwnerToActuallyStop() {
        val old = graph.owner()
        assertTrue(old.startSolo(nickname()))
        waitFor { old.state.value.table != null }
        val release = CountDownLatch(1)
        try {
            stall(old, release)
            graph.closeTableInBackground()
            val next = graph.owner()
            assertTrue(next.startSolo(nickname()))
            // The old owner's shutdown gives up on the loop and reports the stall.
            waitFor { old.state.value.lifecycle == OwnerLifecycle.FAILED }
            Thread.sleep(200)
            assertFalse(old.terminated)
            assertNull("a table started while the old loop still ran", next.state.value.table)

            release.countDown()
            waitFor { next.state.value.table != null }
            assertTrue(old.terminated)
            assertEquals(OwnerLifecycle.SHUT_DOWN, old.state.value.lifecycle)
        } finally {
            release.countDown()
        }
    }

    /** Audit #3: a loop that never stops fails the next owner loudly instead of letting two tables run. */
    @Test fun aNewOwnerFailsWhenTheOldOneNeverStops() {
        val impatient = AppGraph(
            LocalStores(InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore()),
            profiles = { TestProfiles.profiles },
            platform = { error("solo play needs no room platform") },
            retireWaitMs = 300,
        )
        val old = impatient.owner()
        assertTrue(old.startSolo(nickname()))
        waitFor { old.state.value.table != null }
        val release = CountDownLatch(1)
        try {
            stall(old, release)
            impatient.closeTableInBackground()
            val next = impatient.owner()
            assertTrue(next.startSolo(nickname()))
            waitFor { next.state.value.lifecycle == OwnerLifecycle.FAILED }
            assertNull(next.state.value.table)
            assertTrue(next.state.value.failure.orEmpty(), next.state.value.failure.orEmpty().contains("has not finished closing"))
        } finally {
            release.countDown()
            impatient.closeTable()
        }
    }
}
