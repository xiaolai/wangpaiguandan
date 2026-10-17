package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.wire.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-009: two hosts nearby, both named the same, are told apart by host name and table code; the
 * joiner joins exactly the room it chose, never the first one found; nothing auto-joins; an empty
 * search says so after the search window (C3).
 */
class TwoRoomsTest {
    @Test fun twoSameNamedRoomsAreDistinguishableAndTheChosenOneIsJoined() {
        val rig = RoomRig(seed = 21)
        val amei = rig.host("阿梅", RoomSettings("周末掼蛋"))
        val lily = rig.host("Lily", RoomSettings("周末掼蛋"))
        val finder = RoomFinder({ rig.network.browser() }, rig.scheduler)
        val joiner = rig.phone("老周")
        finder.start()
        rig.run(50)
        val rooms = finder.state.value.rooms
        assertEquals(2, rooms.size)
        assertEquals(setOf("周末掼蛋"), rooms.map { it.tableName }.toSet())
        assertNotEquals("the table codes differ", rooms[0].tableCode, rooms[1].tableCode)
        assertEquals(setOf("阿梅", "Lily"), rooms.map { it.hostName }.toSet())
        assertTrue(rooms.all { it.tableCode.matches(Regex("[0-9]{4}")) })
        assertNull("searching never joins anything", joiner.table)

        val chosen = rooms.single { it.hostName == "Lily" }
        assertEquals("the host's screen shows the same code", chosen.tableCode, lily.table!!.info!!.tableCode)
        assertTrue(joiner.owner.joinRoom(chosen, Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { lily.table!!.hostSide!!.pending.isNotEmpty() }
        assertEquals(lily.table!!.tableId, joiner.table!!.tableId)
        assertEquals("Lily", joiner.table!!.info!!.hostName)
        assertTrue("the other host never hears of it", amei.table!!.hostSide!!.pending.isEmpty())
        assertEquals("the joiner and the host compare the same code", joiner.table!!.join!!.code, lily.table!!.hostSide!!.pending.single().code)

        lily.owner.approve(lily.table!!.hostSide!!.pending.single().connectionId)
        rig.until { joiner.table!!.join!!.phase == JoinPhase.ADMITTED && joiner.table!!.roomView != null }
        assertEquals("老周", lily.table!!.roomView!!.seats.single { it.seat == Seat.EAST }.nickname)
        assertNull(amei.table!!.roomView!!.seats.single { it.seat == Seat.EAST }.nickname)

        // The advert follows the seats: EAST is no longer offered.
        rig.until { rig.advertOf(lily).openSeats == setOf(Seat.NORTH, Seat.WEST) }
        assertEquals(setOf(Seat.EAST, Seat.NORTH, Seat.WEST), rig.advertOf(amei).openSeats)
        finder.stop()
    }

    @Test fun anEmptySearchSaysSoAndKeepsSearching() {
        val rig = RoomRig(seed = 22)
        val finder = RoomFinder({ rig.network.browser() }, rig.scheduler, noneFoundMs = 8_000)
        finder.start()
        rig.run(7_000)
        assertTrue(finder.state.value.searching)
        assertFalse(finder.state.value.noneFound)
        rig.run(1_500)
        assertTrue("C3 after the search window", finder.state.value.noneFound)
        val amei = rig.host("阿梅")
        rig.run(50)
        assertEquals(1, finder.state.value.rooms.size)
        assertFalse("a room found later clears the empty state", finder.state.value.noneFound)
        // The window already elapsed: once that room is gone, the screen says none found again.
        amei.owner.shutdown()
        rig.run(50)
        assertEquals(emptyList<RoomAdvert>(), finder.state.value.rooms)
        assertTrue("an emptied list after the window is C3 again", finder.state.value.noneFound)
        finder.stop()
        assertEquals(FinderState.IDLE, finder.state.value)
    }

    @Test fun aRoomThatEndsDisappearsFromTheList() {
        val rig = RoomRig(seed = 23)
        val amei = rig.host("阿梅")
        val finder = RoomFinder({ rig.network.browser() }, rig.scheduler)
        finder.start()
        rig.run(50)
        assertEquals(1, finder.state.value.rooms.size)
        amei.owner.shutdown()
        rig.run(50)
        assertEquals(emptyList<RoomAdvert>(), finder.state.value.rooms)
    }
}
