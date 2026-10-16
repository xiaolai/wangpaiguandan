package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Closing
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client's own connection lifecycle: timers, transport ownership, heartbeats and timer-driven updates. */
class ClientConnectionLifecycleTest {
    @Test fun aLongIdleConnectionHoldsOnlyItsScheduledTimers() {
        val t = Table(seed = 901)
        val south = t.player(Seat.SOUTH)
        t.run(120_000)
        assertEquals(ClientPhase.ADMITTED, south.client.phase)
        // One heartbeat tick is due; completed heartbeats and retries must not accumulate.
        assertTrue(south.client.scheduledTimers <= 2, "client holds ${south.client.scheduledTimers} timers after two idle minutes")
    }

    @Test fun reconnectingClosesThePreviousTransport() {
        val t = Table(seed = 902)
        val south = t.player(Seat.SOUTH)
        val old = south.link.clientEnd
        val again = t.reconnect(south)
        assertTrue(old.closed, "the previous connection's transport was left open")
        t.until(5_000) { again.client.phase == ClientPhase.ADMITTED }
    }

    @Test fun heartbeatsLeaveAnIdleLinkWithoutOtherTraffic() {
        val t = Table(seed = 903)
        // The client never hears from the host (every chunk to it is lost) and is patient about it,
        // so only its own heartbeats can keep the host's view of it alive.
        val south = t.joiner(t.config(Role.PLAYER, "south", Seat.SOUTH).copy(livenessTimeoutMs = 600_000))
        t.admit(south)
        south.link.setFaults(toHost = LinkFaults.RELIABLE, toClient = LinkFaults(dropRate = 1.0))
        t.run(t.hostConfig.livenessTimeoutMs * 3)
        assertTrue(t.host.members().single { it.seat == Seat.SOUTH }.connected, "the host timed out a client whose heartbeats were never written")
    }

    @Test fun aHeartbeatTimeoutNotifiesSubscribers() {
        val t = Table(seed = 904)
        val south = t.player(Seat.SOUTH)
        val seen = ArrayList<Pair<Boolean, ClientPhase>>()
        south.client.onUpdate = { seen += south.client.stale to south.client.phase }
        south.link.blackholed = true
        t.until(t.hostConfig.livenessTimeoutMs * 3) { south.client.stale }
        t.run(1_000)
        assertEquals(true to ClientPhase.CLOSED, seen.lastOrNull(), "subscribers never learned that the link went stale")
    }

    /** Evidence of a revision is evidence whether it came in a receipt or a snapshot. */
    @Test fun anAppliedSnapshotRaisesTheHeldRevision() {
        val t = Table(seed = 905)
        val players = t.fourPlayers()
        val watcher = t.observer("watcher")
        t.startDeal()
        t.until(5_000) { watcher.client.revision == t.host.revision }
        assertTrue(watcher.client.revision > 0)
        assertEquals(watcher.client.revision, watcher.client.heldRevision, "an observer only ever holds snapshots")
        assertTrue(players.values.all { it.client.heldRevision >= it.client.revision })
    }

    /** A new connection generation starts without the previous one's failure. */
    @Test fun reconnectingClearsThePreviousConnectionsFailure() {
        val t = Table(seed = 906)
        val south = t.player(Seat.SOUTH)
        south.client.onMessage(Closing(checkNotNull(south.client.epoch), CloseReason.SUPERSEDED))
        assertEquals(CloseReason.SUPERSEDED, south.client.closeReason)
        val again = t.reconnect(south)
        assertNull(again.client.closeReason, "the old close reason survived the reconnect")
        t.until(5_000) { again.client.phase == ClientPhase.ADMITTED }
        again.link.blackholed = true
        t.until(t.hostConfig.livenessTimeoutMs * 3) { again.client.phase == ClientPhase.CLOSED }
        assertEquals(CloseReason.HEARTBEAT_TIMEOUT, again.client.closeReason, "the later failure was not recorded")
    }
}
