package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.storage.Storage
import cn.wpgd.app.session.storage.TableRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Audit-fix #46, #47: the saved-tables screen's reads fail softly, and a save that expired on screen is not restored. */
class SavedTablesScreenStateTest {
    @Test fun aSaveThatExpiresWhileShownIsNoLongerRestorable() {
        val rig = RoomRig(seed = 131)
        val storage = InMemoryStorage()
        val rooms = RoomStore(InMemoryKeyValueStore())
        val host = rig.host("阿梅", storage = storage, rooms = rooms)
        host.owner.leaveHosted(checkNotNull(host.owner.prepareLeave()).suspend())
        rig.run(500)
        val tables = SavedTables(TableRepository(storage), rooms, host.identity, rig.clock, TestProfiles.profiles)
        val loaded = runBlocking { SavedTablesLoad.of(tables, Dispatchers.Unconfined) } as SavedTablesLoad.Loaded
        val entry = loaded.entries.single()
        assertTrue(tables.restorableNow(entry))
        assertEquals(entry.expiresAt!! - rig.clock.now, tables.untilNextExpiry(loaded.entries))

        rig.clock.now = entry.expiresAt!!
        assertFalse("expired while the list was on screen", tables.restorableNow(entry))
        assertEquals(0L, tables.untilNextExpiry(loaded.entries))
    }

    @Test fun aReadableSaveWithoutItsRoomSettingsIsDamagedNotRestorable() {
        // Round 3 #16: a crash between the table's creation and its settings being stored leaves a
        // readable save that restoring would fail on ("no settings saved"): only deletion is offered.
        val rig = RoomRig(seed = 134)
        val storage = InMemoryStorage()
        val rooms = RoomStore(InMemoryKeyValueStore())
        val host = rig.host("阿梅", storage = storage, rooms = rooms)
        host.owner.leaveHosted(checkNotNull(host.owner.prepareLeave()).suspend())
        rig.run(500)
        val id = TableRepository(storage).list().single().tableId
        rooms.forget(id)
        val tables = SavedTables(TableRepository(storage), rooms, host.identity, rig.clock, TestProfiles.profiles)
        val entry = tables.list().single()
        assertEquals(SavedTables.Kind.DAMAGED, entry.kind)
        assertFalse(tables.restorableNow(entry))
    }

    @Test fun aSaveKeptPastItsExpiryByTheSweepSchedulesNoReload() {
        // Audit #23/#28: a damaged or newer-version save keeps its expiry after the sweep; scheduling it
        // returned 0 forever and the screen reloaded storage every millisecond.
        val rig = RoomRig(seed = 133)
        val tables = SavedTables(TableRepository(InMemoryStorage()), RoomStore(InMemoryKeyValueStore()), rig.host("阿梅").identity, rig.clock, TestProfiles.profiles)
        val listedAt = rig.clock.now
        val damaged = SavedTables.Entry("t-d", null, SavedTables.Kind.DAMAGED, null, listedAt - 10_000, listedAt - 1_000, listedAt)
        val newer = SavedTables.Entry("t-n", null, SavedTables.Kind.NEWER_VERSION, null, listedAt - 10_000, listedAt, listedAt)
        assertEquals(null, tables.untilNextExpiry(listOf(damaged, newer)))
        val later = SavedTables.Entry("t-r", null, SavedTables.Kind.RESTORABLE, null, listedAt - 10_000, listedAt + 5_000, listedAt)
        assertEquals(5_000L, tables.untilNextExpiry(listOf(damaged, newer, later)))
        rig.clock.now = listedAt + 6_000
        assertEquals("expired since it was listed: reload once to sweep it", 0L, tables.untilNextExpiry(listOf(damaged, later)))
    }

    @Test fun aStorageFailureIsAStateNotACrash() {
        val inner = InMemoryStorage()
        val broken = object : Storage by inner {
            override fun list(prefix: String): List<String> = throw IOException("disk gone")
            override fun delete(name: String) = throw IOException("disk gone")
        }
        val rig = RoomRig(seed = 132)
        val tables = SavedTables(TableRepository(broken), RoomStore(InMemoryKeyValueStore()), rig.host("阿梅").identity, rig.clock, TestProfiles.profiles)
        assertEquals(SavedTablesLoad.Failed, runBlocking { SavedTablesLoad.of(tables, Dispatchers.Unconfined) })
        assertFalse(runBlocking { SavedTablesLoad.delete(tables, tables.prepareDelete("t-1").confirm(), Dispatchers.Unconfined) })
    }
}
