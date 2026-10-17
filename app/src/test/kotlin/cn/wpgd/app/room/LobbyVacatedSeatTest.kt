package cn.wpgd.app.room

import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.wire.Occupant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3 #26, #27: a player who leaves the lobby leaves an ownerless stand-in or waiting seat. It
 * blocks the start, so the host is offered an AI for it, and the seat says what it is instead of
 * looking empty.
 */
class LobbyVacatedSeatTest {
    private fun lobby(p: Phone) = checkNotNull(LobbyUi.of(p.state, null)) { "no lobby: ${p.state}" }

    @Test fun aSeatLeftInTheLobbyIsShownAsSuchAndCanTakeAnAi() {
        val rig = RoomRig(seed = 14)
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        rig.run(300)
        assertFalse("a seated human takes no AI", lobby(host).seats.single { it.seat == Seat.EAST }.canPutAi)
        assertTrue("an empty seat takes an AI", lobby(host).seats.single { it.seat == Seat.NORTH }.canPutAi)
        assertFalse("only the host assigns seats", lobby(zhou).seats.single { it.seat == Seat.NORTH }.canPutAi)

        zhou.owner.leaveRoom()
        rig.until { lobby(host).seats.single { it.seat == Seat.EAST }.occupant.let { it == Occupant.STAND_IN || it == Occupant.AWAITING_PLAYER } }
        val east = lobby(host).seats.single { it.seat == Seat.EAST }
        assertTrue("the vacated seat is offered an AI", east.canPutAi)
        assertNotEquals("the seat says it is held for or by a stand-in", LobbySeatStatus.NONE, east.status)

        host.owner.setAiSeat(Seat.EAST)
        rig.until { lobby(host).seats.single { it.seat == Seat.EAST }.occupant == Occupant.AI }
        assertEquals(LobbySeatStatus.NONE, lobby(host).seats.single { it.seat == Seat.EAST }.status)
    }

    @Test fun everyOccupantHasItsOwnStatus() {
        fun seat(o: Occupant, ready: Boolean = false, grace: Int? = null) = LobbySeatUi(Seat.EAST, Seat.EAST.team, o, "老周", ready, false, grace)
        assertEquals(LobbySeatStatus.READY, seat(Occupant.HUMAN, ready = true).status)
        assertEquals(LobbySeatStatus.NOT_READY, seat(Occupant.HUMAN).status)
        assertEquals(LobbySeatStatus.AWAY, seat(Occupant.ABSENT, grace = 30).status)
        assertEquals(LobbySeatStatus.STAND_IN, seat(Occupant.STAND_IN).status)
        assertEquals(LobbySeatStatus.AWAITING_PLAYER, seat(Occupant.AWAITING_PLAYER).status)
        assertEquals(LobbySeatStatus.NONE, seat(Occupant.AI).status)
        assertEquals(LobbySeatStatus.NONE, seat(Occupant.EMPTY).status)
    }
}
