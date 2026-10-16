package cn.wpgd.app.session.lobby

import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.Receipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * GDN-003, GDN-065: a watcher is an independent read-only member. Joining (in the lobby or in the
 * middle of a deal) changes no seat, no bot assignment and no game state, and never blocks the start.
 */
class WatcherSeatTest {
    @Test fun aWatcherJoiningMidDealChangesNoSeatAndNoState() {
        val t = Table(seed = 921)
        val south = t.player(Seat.SOUTH)
        Seat.entries.drop(1).forEach { assertEquals(true, t.host.setAi(it, IDLE_AI)) }
        assertIs<Receipt>(t.ready(south))
        assertIs<SessionHost.StartResult.Started>(t.host.start())
        t.run(200)
        val revision = t.host.revision
        val hash = MatchStateCodec.hash(t.host.state)
        val seats = t.host.room().seats
        val controls = Seat.entries.map { t.host.seatControl(it) }

        val watcher = t.observer("watcher")
        t.run(500)
        assertEquals(revision, t.host.revision, "joining commits nothing")
        assertEquals(hash, MatchStateCodec.hash(t.host.state), "the deal is unchanged")
        assertEquals(controls, Seat.entries.map { t.host.seatControl(it) }, "bot assignment unchanged")
        assertEquals(seats, t.host.room().seats, "the four seats are unchanged")
        assertEquals(listOf("watcher"), t.host.room().observers)
        assertNull(watcher.client.seat)
        assertNotNull(watcher.client.spectatorView, "the watcher sees the public table")
        assertNull(watcher.client.memberView)
    }

    @Test fun aWatcherInTheLobbyNeitherTakesASeatNorBlocksTheStart() {
        val t = Table(seed = 922)
        val south = t.player(Seat.SOUTH)
        t.observer("watcher")
        Seat.entries.drop(1).forEach { assertEquals(true, t.host.setAi(it, IDLE_AI)) }
        assertEquals(listOf(Occupant.HUMAN, Occupant.AI, Occupant.AI, Occupant.AI), t.host.room().seats.map { it.occupant })
        assertIs<Receipt>(t.ready(south))
        assertIs<SessionHost.StartResult.Started>(t.host.start())
    }
}

/** H8: every member sees that the host paused the room, and that it resumed. */
class RoomPausedVisibleTest {
    @Test fun membersSeeAHostPause() {
        val t = Table(seed = 923)
        val south = t.player(Seat.SOUTH)
        val watcher = t.observer("watcher")
        t.host.pause()
        t.run(200)
        kotlin.test.assertTrue(south.client.room!!.paused)
        kotlin.test.assertTrue(watcher.client.room!!.paused)
        t.host.resume()
        t.run(200)
        kotlin.test.assertFalse(south.client.room!!.paused)
    }
}
