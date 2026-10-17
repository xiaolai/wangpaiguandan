package cn.wpgd.app

import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.LocalStores
import cn.wpgd.app.room.InProcessDirectory
import cn.wpgd.app.room.LanRoomNetwork
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.SavedTablesLoad
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.storage.Storage
import cn.wpgd.app.session.storage.TableRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 3 #24, #25: the saved-tables list is reached from the menu while a hosted table still runs.
 * That table is not offered there, and deleting it is refused, because its session still writes the
 * save. A table being deleted is not restored, whichever screen asks.
 */
class SavedTableUseTest {
    private val directory = InProcessDirectory()
    private val inner = InMemoryStorage()
    private val deleteEntered = CountDownLatch(1)
    private val deleteRelease = CountDownLatch(1)
    @Volatile private var blockDeletes = false

    /** Deletion can be held inside storage, as a slow disk would hold it. */
    private val storage = object : Storage by inner {
        override fun delete(name: String) {
            if (blockDeletes) {
                deleteEntered.countDown()
                deleteRelease.await()
            }
            inner.delete(name)
        }
    }
    private val graph = AppGraph(
        LocalStores(InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore()),
        profiles = { TestProfiles.profiles },
        platform = { RoomPlatform(LanRoomNetwork(directory, bindAddress = InetAddress.getLoopbackAddress()), storage) },
    )

    @After fun tearDown() = graph.closeTable()

    private fun waitFor(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test fun aHostedTableStillRunningIsNeitherListedNorDeletable() {
        val owner = graph.roomOwner(RoomMedium.LAN)
        owner.hostRoom(nickname(), RoomSettings("周末掼蛋"))
        waitFor { owner.state.value.table?.hostSide?.saved == true }
        val id = checkNotNull(owner.state.value.table).tableId

        val tables = graph.savedTables()
        val loaded = runBlocking { SavedTablesLoad.of(tables, Dispatchers.Unconfined) } as SavedTablesLoad.Loaded
        assertEquals("the running table is not offered", emptyList<String>(), loaded.entries.map { it.tableId })
        assertFalse("deleting the running table is refused", runBlocking { SavedTablesLoad.delete(tables, tables.prepareDelete(id).confirm(), Dispatchers.Unconfined) })
        assertEquals("its save is untouched", listOf(id), TableRepository(storage).list().map { it.tableId })
    }

    @Test fun aTableBeingDeletedIsNotRestored() {
        val owner = graph.roomOwner(RoomMedium.LAN)
        owner.hostRoom(nickname(), RoomSettings("周末掼蛋"))
        waitFor { owner.state.value.table?.hostSide?.saved == true }
        val id = checkNotNull(owner.state.value.table).tableId
        owner.leaveHosted(checkNotNull(owner.prepareLeave()).suspend())
        graph.closeTable()
        assertTrue(owner.terminated)

        val tables = graph.savedTables()
        assertEquals("a closed table is offered again", listOf(id), tables.list().map { it.tableId })
        blockDeletes = true
        var deleted: Boolean? = null
        val deleting = Thread { deleted = runBlocking { SavedTablesLoad.delete(tables, tables.prepareDelete(id).confirm(), Dispatchers.Unconfined) } }.apply { start() }
        assertTrue(deleteEntered.await(10, TimeUnit.SECONDS))
        assertFalse("restoring the table being deleted is refused", graph.restoreSaved(id, RoomMedium.LAN, nickname()))
        assertEquals("no owner started for it", null, graph.runningOwner())
        deleteRelease.countDown()
        deleting.join(10_000)
        assertEquals(true, deleted)
        assertEquals(emptyList<String>(), TableRepository(storage).list().map { it.tableId })
    }
}
