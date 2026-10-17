package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.client.SessionClient
import cn.wpgd.app.session.lobby.ready
import cn.wpgd.app.session.wire.B64
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.Snapshot
import cn.wpgd.app.session.wire.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Findings 48 and 49: a client's view always belongs to its current seat. When the host moves a
 * player (live, or while it is offline and it is then readmitted) the old seat's view is dropped,
 * and if the new seat's snapshot is lost the heartbeat still fetches it.
 */
class SeatViewInvalidationTest {
    /** Drops everything on [peer]'s link from the first status naming [seat] as its own, for [ms] of virtual time. */
    private fun Table.loseWhatFollowsTheStatusFor(peer: Table.Peer, seat: Seat, ms: Long) {
        var armed = true
        peer.client.tap = { m ->
            if (armed && m is Status && m.room.yourSeat == seat) {
                armed = false
                peer.link.blackholed = true
                scheduler.schedule(ms) { peer.link.blackholed = false }
            }
        }
    }

    @Test fun aLiveSeatMoveWhoseSnapshotAndResyncAreLostIsStillRecovered() {
        val t = Table(seed = 931)
        t.player(Seat.SOUTH)
        val second = t.player(Seat.EAST)
        assertEquals(Seat.EAST, second.client.memberView?.seat)
        // Only snapshots are lost: the new seat's own, and the answer to the resync the move forces.
        var losing = true
        second.client.loseInbound = { m -> losing && m is Snapshot }
        assertTrue(t.host.swapSeats(Seat.EAST, Seat.NORTH))
        t.until(1_000) { second.client.seat == Seat.NORTH }
        t.run(400)
        losing = false
        assertTrue(second.client.memberView == null, "the old seat's view is gone")
        t.until(10_000) { second.client.memberView?.seat == Seat.NORTH }
        assertEquals(ClientPhase.ADMITTED, second.client.phase)
        assertEquals(0L, second.client.metrics.digestMismatches)
    }

    @Test fun aSeatMovedWhileOfflineDropsTheOldViewOnReadmission() {
        val t = Table(seed = 932)
        t.player(Seat.SOUTH)
        val second = t.player(Seat.EAST)
        assertEquals(Seat.EAST, second.client.memberView?.seat)
        second.link.disconnect()
        t.run(200)
        assertTrue(t.host.swapSeats(Seat.EAST, Seat.NORTH))
        // The view must never claim a seat other than the client's own, even between admission and snapshot.
        val mismatches = ArrayList<String>()
        second.client.onUpdate = {
            val v = second.client.memberView
            if (v != null && v.seat != second.client.seat) mismatches += "seat ${second.client.seat}, view ${v.seat}"
        }
        t.loseWhatFollowsTheStatusFor(second, Seat.NORTH, ms = 300)
        val back = t.reconnect(second)
        t.until(5_000) { back.client.phase == ClientPhase.ADMITTED && back.client.seat == Seat.NORTH }
        t.until(10_000) { back.client.memberView?.seat == Seat.NORTH }
        assertEquals(emptyList(), mismatches, "a view of the old seat was kept after readmission")
        // And it plays the new seat.
        assertTrue(t.ready(back) is Receipt)
    }

    /**
     * Finding 50: receipts whose snapshot has not arrived wait for it, but only a bounded number of
     * them; the oldest are given up and counted as unverified.
     */
    @Test fun receiptsWaitingForTheirSnapshotAreBounded() {
        val t = Table(seed = 933)
        val south = t.player(Seat.SOUTH)
        val c = south.client
        south.link.blackholed = true // no snapshot ever arrives from here on
        val extra = 10
        val n = SessionClient.DIGEST_HISTORY + extra
        val ids = (1..n).map { checkNotNull(c.submit(PassBody)) }
        val digest = B64.encode(ByteArray(32) { 7 })
        val before = c.metrics.unverifiedReceipts
        ids.forEachIndexed { i, id -> c.onMessage(Receipt(checkNotNull(c.epoch), id, c.revision + 1 + i, digest)) }
        assertEquals(SessionClient.DIGEST_HISTORY, c.deferredReceiptDigests, "deferred receipt digests are bounded")
        assertEquals(before + extra, c.metrics.unverifiedReceipts, "the evicted ones are counted as unverified")
    }
}
