package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.host.LobbyGate
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.RoomMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-013 (WI-09): an unready human blocks the start; empty seats show and need an explicit AI; a
 * rules change resets everyone's ready; duplicate names are harmless. Host and joiner phones run
 * their own owners over the room network.
 */
class LobbyStateTest {
    private fun lobby(p: Phone, seen: Long? = null) = checkNotNull(LobbyUi.of(p.state, seen)) { "no lobby: ${p.state}" }

    @Test fun anUnreadyHumanBlocksTheStartAndEmptySeatsNeedAnExplicitAi() {
        val rig = RoomRig(seed = 11)
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        rig.run(300)

        var l = lobby(host)
        assertTrue("the host chose the settings, so it is ready", l.seats.single { it.seat == Seat.SOUTH }.ready)
        assertEquals(listOf(Occupant.HUMAN, Occupant.HUMAN, Occupant.EMPTY, Occupant.EMPTY), l.seats.map { it.occupant })
        assertEquals(
            setOf(LobbyGate.Blocker(Seat.EAST, LobbyGate.Reason.NOT_READY), LobbyGate.Blocker(Seat.NORTH, LobbyGate.Reason.EMPTY_SEAT), LobbyGate.Blocker(Seat.WEST, LobbyGate.Reason.EMPTY_SEAT)),
            l.startBlockers.toSet(),
        )
        assertFalse(l.canStart)
        host.owner.startMatch()
        rig.run(300)
        assertEquals("a blocked start deals nothing", 0, host.table!!.view!!.match.dealNumber)
        assertEquals(RoomScreen.LOBBY, RoomScreen.of(host.state))

        host.owner.setAiSeat(Seat.NORTH)
        host.owner.setAiSeat(Seat.WEST)
        rig.run(300)
        l = lobby(host)
        assertEquals("empty seats became AI seats explicitly", Occupant.AI, l.seats.single { it.seat == Seat.NORTH }.occupant)
        assertEquals(listOf(LobbyGate.Blocker(Seat.EAST, LobbyGate.Reason.NOT_READY)), l.startBlockers)
        assertEquals("the joiner's lobby shows the same blockers", l.startBlockers, lobby(zhou).startBlockers)
        assertFalse(lobby(zhou).canStart)

        zhou.owner.setReady(true)
        rig.until { lobby(host).canStart && lobby(zhou).youReady }
        host.owner.startMatch()
        rig.until { zhou.table?.view?.match?.dealNumber == 1 }
        assertEquals(RoomScreen.TABLE, RoomScreen.of(zhou.state))
        assertEquals(RoomScreen.TABLE, RoomScreen.of(host.state))
    }

    @Test fun aRulesChangeResetsReadyAndTellsThePlayer() {
        val rig = RoomRig(seed = 12)
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        zhou.owner.setReady(true)
        rig.until { lobby(host).seats.single { it.seat == Seat.EAST }.ready }
        val seen = lobby(zhou).settingsRevision

        host.owner.updateSettings(RoomSettings("周末掼蛋", mode = RoomMode.COMPETITIVE))
        rig.until { lobby(zhou).settings.policy.mode == RoomMode.COMPETITIVE }
        val l = lobby(zhou, seen)
        assertFalse("a rules change resets ready", l.youReady)
        assertTrue("D2: the player is told the rules changed", l.rulesChanged)
        assertFalse(l.settings.policy.hints)
        rig.until { lobby(host).seats.single { it.seat == Seat.SOUTH }.ready }

        zhou.owner.setReady(true)
        rig.until { lobby(zhou, seen).youReady }
        assertFalse(lobby(zhou, l.settingsRevision).rulesChanged)
    }

    @Test fun duplicateNamesAreHarmless() {
        val rig = RoomRig(seed = 13)
        val host = rig.host("阿梅")
        val twin = rig.join(rig.phone("阿梅"), host, Seat.NORTH)
        rig.run(300)
        val fromTwin = lobby(twin)
        assertEquals(listOf("阿梅", null, "阿梅", null), fromTwin.seats.map { it.nickname })
        assertEquals("you are the seat the host gave you, not a name", listOf(Seat.NORTH), fromTwin.seats.filter { it.isYou }.map { it.seat })
        assertEquals(listOf(Seat.SOUTH), lobby(host).seats.filter { it.isYou }.map { it.seat })

        twin.owner.setReady(true)
        rig.until { lobby(host).seats.single { it.seat == Seat.NORTH }.ready }
        host.owner.setReady(false)
        rig.until { !lobby(twin).seats.single { it.seat == Seat.SOUTH }.ready }
        assertTrue("one name's ready never moves the other's", lobby(twin).seats.single { it.seat == Seat.NORTH }.ready)
    }
}
