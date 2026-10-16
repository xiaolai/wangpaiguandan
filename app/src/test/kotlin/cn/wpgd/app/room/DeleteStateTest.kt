package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.KeyValueStore
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.storage.TableRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** H3: a running deletion owns the confirmation; its completion never touches another prompt. */
class DeleteStateTest {
    private val a = DeletePrompt("t-a", "甲")
    private val b = DeletePrompt("t-b", "乙")

    @Test fun whileADeletionRunsNoOtherPromptCanBeOpenedOrTheRunningOneCancelled() {
        val (confirmation, running) = DeleteState().open(a).start()!!
        assertEquals("t-a", confirmation.tableId)
        assertTrue(running.busy)
        assertSame(running, running.cancel())
        assertSame(running, running.open(b))
        assertNull("a second confirmation would start a concurrent deletion", running.start())
    }

    @Test fun aCompletionAppliesOnlyToItsOwnDeletion() {
        val (_, running) = DeleteState().open(a).start()!!
        assertSame(running, running.finished("t-b", ok = true))
        assertEquals(DeleteState(), running.finished("t-a", ok = true))
        val failed = running.finished("t-a", ok = false)
        assertEquals(a, failed.prompt)
        assertTrue(failed.failed)
        assertFalse(failed.busy)
        // After the failure the user may retry, cancel or choose another table.
        assertEquals(DeleteState(prompt = b), failed.open(b))
        assertFalse(failed.start()!!.second.failed)
    }

    @Test fun thePromptIsBuiltFromTheListedEntryWithoutReadingStorage() {
        val broken = object : KeyValueStore by InMemoryKeyValueStore() {
            override fun get(key: String): String? = throw IOException("disk gone")
        }
        val rig = RoomRig(seed = 133)
        val tables = SavedTables(TableRepository(InMemoryStorage()), RoomStore(broken), rig.host("阿梅").identity, rig.clock, TestProfiles.profiles)
        val entry = SavedTables.Entry("t-a", "甲", SavedTables.Kind.DAMAGED, null, null, null)
        assertEquals(a, tables.prepareDelete(entry))
    }
}
