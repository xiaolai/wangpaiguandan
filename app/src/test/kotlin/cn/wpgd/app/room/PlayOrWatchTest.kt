package cn.wpgd.app.room

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-003, GDN-065 (C4, C7, C8): play or watch is chosen before admission; a watcher never takes a
 * seat or changes the bots; a full table still offers watching; an incompatible room offers neither.
 */
class PlayOrWatchTest {
    private val local = LocalVersion.of(TestProfiles.profiles.base.hash, TestProfiles.profiles.match.hash)

    private fun advert(open: Set<Seat>, protocol: IntRange = PROTOCOL_VERSION..PROTOCOL_VERSION, base: String = local.baseProfileHash) = RoomAdvert(
        "t0123", "周末掼蛋", "阿梅", protocol.first, protocol.last, base, local.matchProfileHash, "K".repeat(43), open, LanEndpoint("10.0.0.2", 4000),
    )

    @Test fun theChoiceFollowsTheAdvert() {
        val open = PlayOrWatch.of(advert(setOf(Seat.EAST, Seat.WEST)), local)
        assertEquals(listOf(Seat.EAST, Seat.WEST), open.playSeats)
        assertTrue(open.canWatch)
        val full = PlayOrWatch.of(advert(emptySet()), local)
        assertEquals("C8: no seat to play", emptyList<Seat>(), full.playSeats)
        assertTrue("a full table still offers watching", full.canWatch)
        val newer = PlayOrWatch.of(advert(setOf(Seat.EAST), protocol = (PROTOCOL_VERSION + 1)..(PROTOCOL_VERSION + 2)), local)
        assertEquals(Compatibility.UPDATE_THIS_APP, newer.compatibility)
        assertFalse(newer.canWatch)
        assertEquals(emptyList<Seat>(), newer.playSeats)
        assertEquals(Compatibility.OTHER_RULES, PlayOrWatch.of(advert(setOf(Seat.EAST), base = "f".repeat(64)), local).compatibility)
    }

    @Test fun aWatcherTakesNoSeatAndChangesNoBot() {
        val rig = RoomRig(seed = 41)
        val host = rig.host("阿梅")
        host.owner.setAiSeat(Seat.WEST)
        rig.run(200)
        val before = host.table!!.roomView!!.seats
        val watcher = rig.join(rig.phone("陈阿姨"), host, seat = null)
        rig.run(300)
        assertEquals("the four seats are unchanged", before, host.table!!.roomView!!.seats)
        assertEquals(listOf("陈阿姨"), host.table!!.roomView!!.observers)
        assertEquals(Role.OBSERVER, watcher.table!!.role)
        assertNull(watcher.table!!.seat)
        assertNull("a watcher has no player view", watcher.table!!.view)
        assertNotNull(watcher.table!!.spectatorView)
        assertEquals(RoomScreen.WATCH, RoomScreen.of(watcher.state))
        assertEquals(Occupant.AI, host.table!!.roomView!!.seats.single { it.seat == Seat.WEST }.occupant)
    }

    @Test fun aTakenSeatIsRefusedAndWatchingIsStillPossible() {
        val rig = RoomRig(seed = 42)
        val host = rig.host("阿梅")
        rig.join(rig.phone("老周"), host, Seat.EAST)
        // A stale list still offered EAST to someone else.
        val late = rig.phone("小林")
        rig.ask(late, host, Seat.EAST)
        rig.until { late.table!!.join!!.phase == JoinPhase.SEAT_TAKEN }
        val watcher = rig.join(rig.phone("小林"), host, seat = null)
        assertEquals(JoinPhase.ADMITTED, watcher.table!!.join!!.phase)
    }

    @Test fun watchersAreCappedAtTwo() {
        val rig = RoomRig(seed = 43)
        val host = rig.host("阿梅")
        rig.join(rig.phone("一"), host, seat = null)
        rig.join(rig.phone("二"), host, seat = null)
        val third = rig.phone("三")
        rig.ask(third, host, seat = null)
        rig.until { third.table!!.join!!.phase == JoinPhase.FULL }
        assertEquals("G7: watchers full", JoinPhase.FULL, third.table!!.join!!.phase)
    }
}
