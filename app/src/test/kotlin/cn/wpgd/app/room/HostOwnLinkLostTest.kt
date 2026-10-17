package cn.wpgd.app.room

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Found by the emulator end-to-end run (tools/e2e/): a host's own player reaches its in-process host
 * over a local link with the same 5 s liveness limit as any member. If the host's session thread is
 * held up longer than that (a long GC, an overloaded device, Android freezing a background app), the
 * host's own client gives up on that link. The room's own steps (deal → tribute → play, settle the
 * ended deal) must not depend on that client: the other players' table must keep going, with the
 * room's absence policy covering the host's seat, and the host must get its seat back on reconnect.
 */
class HostOwnLinkLostTest {
    @Test fun theRoomKeepsGoingWhenTheHostsOwnLinkIsLost() {
        val rig = RoomRig(seed = 913)
        val host = rig.host("阿梅")
        val joiners = mapOf(Seat.EAST to rig.phone("老周"), Seat.NORTH to rig.phone("陈阿姨"), Seat.WEST to rig.phone("Lily"))
        val people = rig.startedRoom(host, joiners)

        // The host's own link goes dead: its own client times out (5 s) and the table carries on without it.
        host.owner.blackholeLink(true)
        rig.until(15_000) { host.table?.connection != ConnectionState.CONNECTED }
        val zhou = joiners.getValue(Seat.EAST)
        // Grace (30 s), then the room's AI stands in at the host's seat; the three players play on.
        val settled = rig.drive(people.drop(1), maxMs = 1_200_000) { zhou.table?.view?.match?.lastResult?.dealNumber == 1 }
        assertTrue("deal 1 was settled for the players although the host's own client was gone (phase ${zhou.table?.view?.match?.phase})", settled)
        assertEquals(MatchPhase.AWAITING_DEAL, zhou.table!!.view!!.match.phase)

        // The host user reconnects: back at its own seat, and the next deal can start.
        host.owner.blackholeLink(false)
        host.owner.reconnect()
        rig.until(30_000) { host.table?.connection == ConnectionState.CONNECTED && host.table?.view?.match?.lastResult?.dealNumber == 1 }
        assertTrue(host.owner.continueToNextDeal())
        rig.until(30_000) { (zhou.table?.view?.match?.dealNumber ?: 0) == 2 && zhou.table?.view?.match?.phase != MatchPhase.DEALT }
        assertNotEquals(MatchPhase.DEALT, zhou.table!!.view!!.match.phase)
    }
}
