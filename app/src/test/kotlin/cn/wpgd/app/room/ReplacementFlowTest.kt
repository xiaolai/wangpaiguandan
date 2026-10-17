package cn.wpgd.app.room

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.wire.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-016 through the app: when a player is away, a newcomer may ask to take the seat over; the host
 * sees the request at the table (mid-match), compares the code and approves; the newcomer gets the
 * seat with its cards, and the old player's capability no longer works.
 */
class ReplacementFlowTest {
    private val local = LocalVersion.of(TestProfiles.profiles.base.hash, TestProfiles.profiles.match.hash)

    @Test fun anAwaySeatIsTakenOverWithTheHostsApproval() {
        val rig = RoomRig(seed = 131)
        val host = rig.host("阿梅", RoomSettings("周末掼蛋", graceSeconds = 60))
        val zhou = rig.phone("老周")
        val (hostSeat) = rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val hand = zhou.table!!.view!!.deal!!.hand.map { it.id }.toSet()
        zhou.owner.shutdown() // 老周's phone is gone: the seat is away, grace running

        rig.until { rig.advertOf(host).replaceableSeats == setOf(Seat.EAST) }
        val choice = PlayOrWatch.of(rig.advertOf(host), local)
        assertEquals(listOf(Seat.EAST), choice.replaceSeats)
        assertFalse("an away seat is not offered as free", Seat.EAST in choice.playSeats)

        val lin = rig.phone("小林")
        assertTrue(lin.owner.joinRoom(rig.advertOf(host), Role.PLAYER, Seat.EAST, nickname("小林"), replace = true))
        rig.until { host.table!!.hostSide!!.pending.isNotEmpty() }
        val request = host.table!!.hostSide!!.pending.single()
        assertTrue(request.replace)
        assertEquals(Seat.EAST, request.seat)
        assertEquals("the host sees it at the table, mid-match", listOf(request), hostSeat.vm.state().pendingJoiners)
        assertEquals(lin.table!!.join!!.code, request.code)

        host.owner.approve(request.connectionId)
        rig.until { lin.table?.join?.phase == JoinPhase.ADMITTED && lin.table?.view?.deal != null }
        assertEquals(Seat.EAST, lin.table!!.seat)
        assertEquals("the cards stay with the seat", hand, lin.table!!.view!!.deal!!.hand.map { it.id }.toSet())
        rig.until { host.table!!.roomView!!.seats.single { it.seat == Seat.EAST }.nickname == "小林" }

        // 老周 comes back with his stored ticket: it no longer works, and the seat is not his any more.
        val back = rig.phone("老周", identity = zhou.identity)
        assertTrue(back.owner.joinRoom(rig.advertOf(host), Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { back.table?.join?.phase == JoinPhase.SEAT_TAKEN }
        assertEquals(Seat.EAST, lin.table!!.seat)
    }
}

/** The WI-09 rollback switch: with spectators off, watching is not offered and no hand can be followed. */
class SpectatorsSwitchTest {
    @Test fun switchedOffNobodyWatches() {
        val local = LocalVersion.of(TestProfiles.profiles.base.hash, TestProfiles.profiles.match.hash)
        val advert = RoomAdvert("t1", "周末掼蛋", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, local.baseProfileHash, local.matchProfileHash, "K".repeat(43), setOf(Seat.EAST), cn.wpgd.app.lan.LanEndpoint("10.0.0.2", 4000))
        assertTrue(PlayOrWatch.of(advert, local, spectators = true).canWatch)
        val off = PlayOrWatch.of(advert, local, spectators = false)
        assertFalse(off.canWatch)
        assertEquals("players are unaffected", listOf(Seat.EAST), off.playSeats)
        assertFalse(RoomSettings("周末掼蛋").policy(spectators = false).privateFollow)
        assertTrue(RoomSettings("周末掼蛋").policy(spectators = true).privateFollow)
    }
}
