package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.storage.InMemoryStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `tributeProfile` kill switch (plan section 3, WI-04 rollback, section 8): switched off, no
 * profile is offered for a new table, so neither solo play nor a new room starts. A table saved
 * under its frozen profile still restores, and joining another host's room still works: the switch
 * never changes the rules inside an existing room.
 */
class TributeProfileSwitchTest {
    @Test fun switchedOffNoNewTableStartsButASavedTableRestoresAndRoomsCanBeJoined() {
        val rig = RoomRig(seed = 181)
        val storage = InMemoryStorage()
        val rooms = RoomStore(InMemoryKeyValueStore())
        val host = rig.host("阿梅", storage = storage, rooms = rooms)
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val id = host.table!!.tableId
        val profile = host.table!!.view!!.match.matchProfileHash
        host.owner.leaveHosted(checkNotNull(host.owner.prepareLeave()).suspend())
        rig.until { zhou.table?.join?.phase == JoinPhase.SUSPENDED }
        host.owner.shutdown()

        val off = rig.phone("阿梅", storage, rooms, host.identity, newTables = false)
        assertFalse("solo needs a profile", off.owner.startSolo(nickname("阿梅")))
        assertFalse("a new room needs a profile", off.owner.hostRoom(nickname("阿梅"), RoomSettings("新桌")))
        rig.run(1_000)
        assertNull(off.table)

        assertTrue("a saved table keeps its frozen profile", off.owner.restoreRoom(id, nickname("阿梅")))
        rig.until { off.table?.view?.deal != null && off.table?.hostSide != null }
        assertEquals(profile, off.table!!.view!!.match.matchProfileHash)

        val watcher = rig.phone("小王", newTables = false)
        rig.join(watcher, off, seat = null)
        assertEquals(JoinPhase.ADMITTED, watcher.table!!.join!!.phase)
    }
}
