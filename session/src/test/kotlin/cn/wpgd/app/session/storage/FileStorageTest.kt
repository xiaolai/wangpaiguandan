package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.testCrypto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The real [FileStorage] on this machine's file system: the primitives behave as the contract says,
 * and a saved table round-trips through it. (Durability across power loss is not provable here.)
 */
class FileStorageTest {
    @Test fun primitivesFollowTheContract() {
        val dir = Files.createTempDirectory("wpgd-storage")
        try {
            val s = FileStorage(dir)
            s.append("tables/a/log.000001", byteArrayOf(1, 2))
            s.append("tables/a/log.000001", byteArrayOf(3))
            s.sync("tables/a/log.000001")
            assertContentEquals(byteArrayOf(1, 2, 3), s.read("tables/a/log.000001"))
            s.replace("tables/a/hwm", byteArrayOf(9))
            s.replace("tables/a/hwm", byteArrayOf(8, 7))
            assertContentEquals(byteArrayOf(8, 7), s.read("tables/a/hwm"))
            assertEquals(listOf("tables/a/hwm", "tables/a/log.000001"), s.list("tables/a/"))
            s.delete("tables/a/hwm")
            s.delete("tables/a/hwm")
            assertNull(s.read("tables/a/hwm"))
            assertFailsWith<IllegalArgumentException> { s.read("../escape") }
            assertFailsWith<IllegalArgumentException> { s.append("tables/a/../../x", byteArrayOf(1)) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A durable file under new directories needs each new directory's entry forced in its parent. */
    @Test fun newDirectoriesAreForcedInTheirParents() {
        val base = Files.createTempDirectory("wpgd-dirs")
        try {
            val forced = ArrayList<java.nio.file.Path>()
            val root = base.resolve("root")
            val s = FileStorage(root) { forced.add(it.toAbsolutePath()) }
            assertEquals(listOf(base.toAbsolutePath()), forced, "creating the root forces its parent")
            forced.clear()
            s.replace("tables/a/meta", byteArrayOf(1))
            val r = root.toAbsolutePath()
            assertEquals(listOf(r, r.resolve("tables"), r.resolve("tables/a")), forced)
            forced.clear()
            s.append("tables/b/log.000001", byteArrayOf(1))
            s.sync("tables/b/log.000001")
            assertEquals(listOf(r.resolve("tables"), r.resolve("tables/b")), forced)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /** A failed directory force must leave the new file pending, so a retried sync forces it again. */
    @Test fun aFailedDirectoryForceIsRetriedOnTheNextSync() {
        val base = Files.createTempDirectory("wpgd-retry")
        try {
            val forced = ArrayList<java.nio.file.Path>()
            var failNext = false
            val s = FileStorage(base.resolve("root")) {
                if (failNext) { failNext = false; throw java.io.IOException("force failed") }
                forced.add(it.toAbsolutePath())
            }
            s.append("a/log.000001", byteArrayOf(1))
            forced.clear()
            failNext = true
            assertFailsWith<java.io.IOException> { s.sync("a/log.000001") }
            s.sync("a/log.000001")
            assertEquals(listOf(base.resolve("root/a").toAbsolutePath()), forced)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test fun aSavedTableRoundTripsThroughTheFileSystem() {
        val dir = Files.createTempDirectory("wpgd-table")
        try {
            val storage = FileStorage(dir)
            val config = savedConfig(checkpointEvery = 8)
            val t = Table(seed = 1401, hostConfig = config, hostFactory = { s ->
                SessionHost.create(config, TestProfiles.base, TestProfiles.match, testCrypto(1401), s, storage, TABLE, T0)
            })
            Seat.entries.forEach { t.host.setAi(it, standIn) }
            t.host.advance(); t.host.advance()
            t.until(60_000) { t.host.revision >= 30 }
            val live = t.host.state
            t.crashHost()
            val r = SessionHost.restore(config, TestProfiles.base, TestProfiles.match, testCrypto(2), t.scheduler, FileStorage(dir), TABLE, T0)
            assertIs<SessionHost.RestoreResult.Restored>(r)
            assertEquals(MatchStateCodec.hash(live), MatchStateCodec.hash(r.host.state))
            assertEquals(24L, r.report.checkpointRevision)
            TableRepository(FileStorage(dir)).delete(TABLE, UserConfirmation.CONFIRMED_BY_USER)
            assertEquals(emptyList(), FileStorage(dir).list("tables/"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
