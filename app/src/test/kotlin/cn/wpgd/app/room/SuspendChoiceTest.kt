package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.storage.TableRepository
import cn.wpgd.app.session.storage.TableStatus
import cn.wpgd.app.session.wire.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H9, GDN-090: leaving a hosted table asks suspend or end, and says how long a suspended table is
 * kept. The expiry stored is the one shown; an outdated prompt does nothing; a suspended table is
 * restored with everyone's seat (H3–H5); deleting a save needs its own confirmation.
 */
class SuspendChoiceTest {
    private val hour = 60 * 60 * 1_000L

    private class Saved(val rig: RoomRig, val storage: InMemoryStorage, val rooms: RoomStore, val host: Phone) {
        fun tables() = SavedTables(TableRepository(storage), rooms, host.identity, rig.clock, TestProfiles.profiles)
    }

    private fun saved(seed: Long): Saved {
        val rig = RoomRig(seed)
        val storage = InMemoryStorage()
        val rooms = RoomStore(InMemoryKeyValueStore())
        return Saved(rig, storage, rooms, rig.host("阿梅", storage = storage, rooms = rooms))
    }

    @Test fun suspendKeepsTheTableUntilTheExpiryShown() {
        val s = saved(101)
        val (rig, host) = s.rig to s.host
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val id = host.table!!.tableId
        val prompt = checkNotNull(host.owner.prepareLeave())
        assertTrue(prompt.saved)
        assertEquals(12 * hour, prompt.retentionMs)
        assertEquals(rig.clock.now + 12 * hour, prompt.expiresAt)
        assertNull("joined members have no host leave prompt", zhou.owner.prepareLeave())

        // A prompt for another table, or one shown so long ago that its expiry passed, does nothing.
        host.owner.leaveHosted(LeavePrompt("t-other", true, prompt.retentionMs, prompt.expiresAt).suspend())
        val stale = prompt.copy(expiresAt = rig.clock.now)
        host.owner.leaveHosted(stale.suspend())
        rig.run(500)
        assertEquals(JoinPhase.ADMITTED, zhou.table!!.join!!.phase)
        assertTrue(rig.network.directory.announced.any { it.roomId == id })

        rig.clock.now += 1_000
        host.owner.leaveHosted(prompt.suspend())
        rig.until { zhou.table?.join?.phase == JoinPhase.SUSPENDED }
        assertTrue("no longer announced", rig.network.directory.announced.none { it.roomId == id })
        val meta = TableRepository(s.storage).list().single().meta!!
        assertEquals(TableStatus.SUSPENDED, meta.status)
        assertEquals("the stored expiry is the one the host was shown", prompt.expiresAt, meta.expiresAt)
        val entry = s.tables().list().single()
        assertEquals(SavedTables.Kind.RESTORABLE, entry.kind)
        assertEquals("周末掼蛋", entry.tableName)
        assertEquals(prompt.expiresAt, entry.expiresAt)

        // After the expiry the save is swept, with its settings and the rejoin ticket.
        rig.clock.now = prompt.expiresAt + 1
        assertEquals(emptyList<SavedTables.Entry>(), s.tables().list())
        assertNull(s.rooms.load(id))
        assertNull(host.identity.rejoinCapability(id))
    }

    @Test fun aSuspendedTableIsRestoredWithEveryonesSeat() {
        val s = saved(102)
        val (rig, host) = s.rig to s.host
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val id = host.table!!.tableId
        val hand = zhou.table!!.view!!.deal!!.hand
        host.owner.leaveHosted(checkNotNull(host.owner.prepareLeave()).suspend())
        rig.until { zhou.table?.join?.phase == JoinPhase.SUSPENDED }
        host.owner.shutdown()
        zhou.owner.shutdown()

        // H3: the host's device lists the table and restores it.
        val again = rig.phone("阿梅", s.storage, s.rooms, host.identity)
        assertEquals(listOf(id), s.tables().list().map { it.tableId })
        assertTrue(again.owner.restoreRoom(id, nickname("阿梅")))
        rig.until { again.table?.view?.deal != null && again.table?.hostSide != null }
        assertEquals(Seat.SOUTH, again.table!!.seat)
        assertEquals("周末掼蛋", again.table!!.info!!.tableName)
        assertEquals("a running table is not offered for restore", emptyList<String>(), s.tables().list(running = id).map { it.tableId })

        // H5: the returning player's device rejoins its own seat with its ticket, without approval.
        val back = rig.phone("老周", identity = zhou.identity)
        assertTrue(back.owner.joinRoom(rig.advertOf(again), Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { back.table?.join?.phase == JoinPhase.ADMITTED && back.table?.view?.deal != null }
        assertEquals(Seat.EAST, back.table!!.seat)
        assertEquals(hand.map { it.id }.toSet(), back.table!!.view!!.deal!!.hand.map { it.id }.toSet())
        assertTrue("nobody had to approve the returning player", again.table!!.hostSide!!.pending.isEmpty())
    }

    @Test fun deletingASaveNeedsItsOwnConfirmation() {
        val s = saved(103)
        val id = s.host.table!!.tableId
        s.host.owner.leaveHosted(checkNotNull(s.host.owner.prepareLeave()).suspend())
        s.rig.run(500)
        val tables = s.tables()
        val prompt = tables.prepareDelete(id)
        assertEquals("周末掼蛋", prompt.tableName)
        assertEquals(1, tables.list().size)
        tables.delete(prompt.confirm())
        assertEquals(emptyList<SavedTables.Entry>(), tables.list())
        assertTrue(TableRepository(s.storage).list().isEmpty())
        assertNull(s.rooms.load(id))
    }
}

/** H9 "end": the table closes for everyone and its save is gone; it cannot be resumed. */
class ConsequenceConfirmTest {
    @Test fun endingClosesTheTableForEveryoneAndDeletesTheSave() {
        val rig = RoomRig(seed = 111)
        val storage = InMemoryStorage()
        val rooms = RoomStore(InMemoryKeyValueStore())
        val host = rig.host("阿梅", storage = storage, rooms = rooms)
        val zhou = rig.phone("老周")
        val chen = rig.phone("陈阿姨")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou), watchers = listOf(chen))
        val id = host.table!!.tableId
        assertNotNull(host.identity.rejoinCapability(id))

        host.owner.leaveHosted(checkNotNull(host.owner.prepareLeave()).end())
        rig.until { zhou.table?.join?.phase == JoinPhase.ENDED && chen.table?.join?.phase == JoinPhase.ENDED }
        rig.run(10_000)
        assertEquals("ended members do not try to rejoin", JoinPhase.ENDED, zhou.table!!.join!!.phase)
        assertEquals(0L, zhou.owner.metrics.rejoinAttempts.get())
        assertTrue(TableRepository(storage).list().isEmpty())
        assertNull(rooms.load(id))
        assertNull(host.identity.rejoinCapability(id))
        assertTrue(rig.network.directory.announced.isEmpty())
        assertFalse(SavedTables(TableRepository(storage), rooms, host.identity, rig.clock, TestProfiles.profiles).list().any { it.tableId == id })
    }
}
