package cn.wpgd.app.session.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** The crash-test storage models a file's directory entry separately from its length. */
class InMemoryStorageTest {
    @Test fun aDurablyReplacedEmptyFileSurvivesReboot() {
        val s = InMemoryStorage()
        s.replace("tables/a/empty", ByteArray(0))
        assertContentEquals(ByteArray(0), s.reboot().read("tables/a/empty"))
        assertContentEquals(ByteArray(0), s.durableCopy().read("tables/a/empty"))
    }

    @Test fun aSyncedEmptyFileSurvivesAndAnUnsyncedNewFileDoesNot() {
        val s = InMemoryStorage()
        s.append("tables/a/synced", ByteArray(0))
        s.sync("tables/a/synced")
        s.append("tables/a/unsynced", byteArrayOf(1))
        val after = s.reboot()
        assertContentEquals(ByteArray(0), after.read("tables/a/synced"))
        assertNull(after.read("tables/a/unsynced"))
        assertNull(s.durableCopy().read("tables/a/unsynced"))
    }
}
