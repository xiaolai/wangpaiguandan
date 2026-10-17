package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.storage.TableRepository
import cn.wpgd.app.session.storage.TableStatus
import cn.wpgd.app.testCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The screens ask the owner to leave and then shut it down at once, from the main thread. The owner's
 * loop runs on its own thread here, so the leave request is still queued when shutdown starts.
 */
class OwnerShutdownTest {
    private class Hosting {
        val loop = ExecutorSessionLoop(onError = { })
        val storage = InMemoryStorage()
        val directory = InProcessDirectory()
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                crypto = testCrypto(41),
                loopFactory = { loop },
                // The room network's links run on the owner's own loop, like the real transports' callbacks.
                network = InMemoryRoomNetwork(loop, 41, directory),
                storage = storage,
                rooms = RoomStore(InMemoryKeyValueStore()),
                identity = IdentityRepository(InMemoryKeyValueStore()),
            ),
        )

        fun start(): Hosting {
            assertTrue(owner.hostRoom(nickname(), RoomSettings("周末掼蛋")))
            val deadline = System.currentTimeMillis() + 20_000
            while (owner.state.value.table?.hostSide?.saved != true || directory.announced.isEmpty()) {
                assertTrue("the room did not open: ${owner.state.value}", System.currentTimeMillis() < deadline)
                Thread.sleep(5)
            }
            return this
        }
    }

    @Test fun aSuspendAskedJustBeforeShutdownIsSaved() {
        val h = Hosting().start()
        val prompt = checkNotNull(h.owner.prepareLeave())
        h.owner.leaveHosted(prompt.suspend())
        h.owner.shutdown()

        val meta = checkNotNull(TableRepository(h.storage).list().single().meta)
        assertEquals("the queued suspend ran before the table closed", TableStatus.SUSPENDED, meta.status)
        assertEquals(prompt.expiresAt, meta.expiresAt)
        assertTrue(h.directory.announced.isEmpty())
        assertEquals(OwnerLifecycle.SHUT_DOWN, h.owner.state.value.lifecycle)
    }

    @Test fun aStalledLoopStillStopsAnnouncingTheRoom() {
        val h = Hosting().start()
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.loop.post {
            stalled.countDown()
            // Longer than shutdown waits for the loop; shutdown's interrupt ends it.
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(stalled.await(5, TimeUnit.SECONDS))

        h.owner.shutdown()

        assertTrue("the loop stopped", h.loop.terminated)
        assertEquals("the advert is withdrawn although the loop never ran the teardown", emptyList<RoomAdvert>(), h.directory.announced)
        assertEquals(OwnerLifecycle.SHUT_DOWN, h.owner.state.value.lifecycle)
        release.countDown()
    }

    /** Round 2 (#24): a stall that ignores the interrupt outlives shutdown; the teardown runs once the loop ends. */
    @Test fun aStallThatIgnoresTheInterruptIsNotReportedAsShutDownAndIsTornDownWhenItEnds() {
        val h = Hosting().start()
        val stalled = CountDownLatch(1)
        val release = AtomicBoolean(false)
        h.loop.post {
            stalled.countDown()
            while (!release.get()) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Ignores shutdown's interrupt, like a blocking call that does not honour it.
                }
            }
        }
        assertTrue(stalled.await(5, TimeUnit.SECONDS))

        h.owner.shutdown()

        assertFalse("the loop is still stalled", h.loop.terminated)
        assertEquals("nobody new finds a table that is going away", emptyList<RoomAdvert>(), h.directory.announced)
        assertEquals("not torn down, so not reported as shut down", OwnerLifecycle.FAILED, h.owner.state.value.lifecycle)

        release.set(true)
        val deadline = System.currentTimeMillis() + 10_000
        while (h.owner.state.value.lifecycle != OwnerLifecycle.SHUT_DOWN) {
            assertTrue("the teardown never ran after the loop ended: ${h.owner.state.value}", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        assertTrue(h.loop.terminated)
        assertEquals(0, h.owner.scheduledTasks)
        assertEquals(emptyList<RoomAdvert>(), h.directory.announced)
    }
    /** Round 3 #8: an interrupted caller's shutdown still ends the owner, and keeps its interrupt. */
    @Test fun anInterruptedCallerStillShutsTheOwnerDown() {
        val h = Hosting().start()
        Thread.currentThread().interrupt()
        try {
            h.owner.shutdown()
        } finally {
            assertTrue("the caller's interrupt is kept", Thread.interrupted())
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (h.owner.state.value.lifecycle != OwnerLifecycle.SHUT_DOWN) {
            assertTrue("the owner never shut down: ${h.owner.state.value}", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        assertEquals(emptyList<RoomAdvert>(), h.directory.announced)
    }
}
