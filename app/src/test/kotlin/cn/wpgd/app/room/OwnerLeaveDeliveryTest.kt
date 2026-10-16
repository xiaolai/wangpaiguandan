package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.SessionLoop
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.testCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Audit-fix #15 (round 2): the screen asks to leave a joined room and shuts the owner down at once,
 * while the link is backpressured. The leave must still reach the host before the transport closes.
 */
class OwnerLeaveDeliveryTest {
    /** One real thread shared by two owners; shutting one owner's loop leaves the thread to the other. */
    private class SharedLoop(private val base: SessionLoop) : SessionLoop {
        @Volatile private var stopped = false
        override val now: Long get() = base.now
        override val isOnLoop: Boolean get() = base.isOnLoop
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable =
            if (stopped) Cancellable { } else base.schedule(delayMs) { if (!stopped) task() }
        override fun shutdown() {
            stopped = true
        }
        override val terminated: Boolean get() = stopped
        override fun whenTerminated(action: () -> Unit) {
            check(stopped) { "not used before shutdown in this test" }
            action()
        }
    }

    private val base = ExecutorSessionLoop("shared", onError = { throw AssertionError("session task failed", it) })
    private val directory = InProcessDirectory()
    private val network = InMemoryRoomNetwork(base, 15, directory)
    private val owners = ArrayList<SessionOwner>()

    private fun owner(n: Long) = SessionOwner(
        OwnerDependencies(
            profiles = TestProfiles.profiles,
            crypto = testCrypto(150 + n),
            loopFactory = { SharedLoop(base) },
            network = network,
            identity = IdentityRepository(InMemoryKeyValueStore()),
        ),
    ).also { owners += it }

    @After fun tearDown() {
        owners.forEach { it.shutdown() }
        base.shutdown()
    }

    private fun until(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!done()) {
            assertTrue(what, System.currentTimeMillis() < deadline)
            Thread.sleep(2)
        }
    }

    private fun onBase(block: () -> Unit) {
        val ran = CountDownLatch(1)
        base.post { block(); ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    /** A host with EAST joined and seated. */
    private fun room(): Triple<SessionOwner, SessionOwner, RoomAdvert> {
        val host = owner(1)
        assertTrue(host.hostRoom(nickname("阿梅"), RoomSettings("周末掼蛋")))
        until("the room opens") { directory.announced.isNotEmpty() && host.state.value.table?.hostSide != null }
        val advert = directory.announced.single()
        val east = owner(2)
        assertTrue(east.joinRoom(advert, Role.PLAYER, Seat.EAST, nickname("老周")))
        until("east asks") { host.state.value.table?.hostSide?.pending?.isNotEmpty() == true }
        host.approve(host.state.value.table!!.hostSide!!.pending.single().connectionId)
        until("east is admitted") { east.state.value.table?.join?.phase == JoinPhase.ADMITTED }
        until("the host seats east") { host.eastSeat() == Occupant.HUMAN }
        return Triple(host, east, advert)
    }

    private fun SessionOwner.eastSeat(): Occupant? = state.value.table?.roomView?.seats?.single { it.seat == Seat.EAST }?.occupant

    @Test fun aLeaveQueuedBehindBackpressureReachesTheHostBeforeShutdownCloses() {
        val (host, east, advert) = room()
        // A slow link with room for one chunk: the ready fills it, so the leave behind it is refused.
        onBase { network.linksTo(advert.endpoint).single().setFaults(toHost = LinkFaults(latencyMs = 300, window = 1), toClient = LinkFaults.RELIABLE) }
        east.setReady(true)
        east.leaveRoom()
        east.shutdown()
        assertEquals(OwnerLifecycle.SHUT_DOWN, east.state.value.lifecycle)

        // The host learns of a departure, not of a lost connection (which would keep the seat as ABSENT).
        until("the host saw the leave, seat is ${host.eastSeat()}") { host.eastSeat().let { it != Occupant.HUMAN && it != Occupant.ABSENT } }
    }

    @Test fun aHostEndingTheRoomJustBeforeShutdownStillTellsItsMembers() {
        val (host, east, _) = room()
        host.leaveHosted(checkNotNull(host.prepareLeave()).end())
        host.shutdown()
        assertEquals(OwnerLifecycle.SHUT_DOWN, host.state.value.lifecycle)
        until("east was told the room ended: ${east.state.value.table?.room}") { east.state.value.table?.room == RoomCondition.ENDED }
    }
}
