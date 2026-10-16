package cn.wpgd.app.room

import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * GDN-012: partners are chosen by the host, whatever order people were discovered or joined in;
 * seats move only in the lobby, and the moved player's own device follows.
 */
class SeatArrangementTest {
    @Test fun theHostChoosesPartnersRegardlessOfJoinOrder() {
        val rig = RoomRig(seed = 31)
        val host = rig.host("阿梅")
        // Discovery order: 老周 joins first and takes the opponents' seat EAST; 小林 joins second.
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        val lin = rig.join(rig.phone("小林"), host, Seat.NORTH)
        rig.run(300)
        assertEquals(Seat.SOUTH.partner, lin.table!!.roomView!!.yourSeat)

        // The host wants 老周 as partner: swap EAST and NORTH.
        host.owner.swapSeats(Seat.EAST, Seat.NORTH)
        rig.until { zhou.table?.roomView?.yourSeat == Seat.NORTH && lin.table?.roomView?.yourSeat == Seat.EAST }
        val l = checkNotNull(LobbyUi.of(zhou.state, null))
        assertEquals(listOf(Seat.NORTH), l.seats.filter { it.isYou }.map { it.seat })
        assertEquals("老周 is now on the host's team", Seat.SOUTH.team, l.seats.single { it.isYou }.team)
        assertEquals(listOf("阿梅", "小林", "老周", null), l.seats.map { it.nickname })
        assertFalse("a moved player readies again", l.youReady)

        host.owner.setAiSeat(Seat.WEST)
        zhou.owner.setReady(true)
        lin.owner.setReady(true)
        rig.until { LobbyUi.of(host.state, null)?.canStart == true }
        host.owner.startMatch()
        rig.until { zhou.table?.view?.deal != null && lin.table?.view?.deal != null }
        assertEquals(Seat.NORTH, zhou.table!!.view!!.seat)
        assertEquals(Seat.EAST, lin.table!!.view!!.seat)
        assertEquals(27, zhou.table!!.view!!.deal!!.hand.size)

        host.owner.swapSeats(Seat.NORTH, Seat.EAST)
        rig.run(300)
        assertEquals("no seat changes once the match started", Seat.NORTH, zhou.table!!.view!!.seat)
    }
}
