package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.testCrypto
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.stream.Collectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            // Ancestors above the temporary directory this test may write in are forced too (#64): only their tail is fixed here.
            assertEquals(base.toAbsolutePath(), forced.last(), "creating the root forces its parent")
            assertTrue(forced.all { base.toAbsolutePath().startsWith(it) }, "only ancestors are forced: $forced")
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

    /**
     * Finding 54: a run that appended to a new file and died before its sync left a name whose
     * directory entry may not be durable. The next instance's first sync of that file forces its
     * directory too, although that instance did not create the file; later syncs do not.
     */
    @Test fun theFirstSyncOfAFileFoundOnReopenForcesItsDirectory() {
        val base = Files.createTempDirectory("wpgd-reopen")
        try {
            val root = base.resolve("root")
            FileStorage(root) { }.append("a/log.000001", byteArrayOf(1)) // the process dies before sync
            val forced = ArrayList<Path>()
            val reopened = FileStorage(root) { forced.add(it.toAbsolutePath()) }
            reopened.append("a/log.000001", byteArrayOf(2))
            forced.clear()
            reopened.sync("a/log.000001")
            assertEquals(listOf(root.resolve("a").toAbsolutePath()), forced, "the directory of a file found on reopen was not forced")
            forced.clear()
            reopened.append("a/log.000001", byteArrayOf(3))
            reopened.sync("a/log.000001")
            assertEquals(emptyList(), forced, "the directory is forced once per file per instance")
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

    /**
     * A directory created before its parent force failed is still forced on the next write, by the
     * same instance and by a new one over the same root (a failed ancestor force used to be lost).
     */
    @Test fun aFailedParentForceOfANewDirectoryIsRetried() {
        val base = Files.createTempDirectory("wpgd-dir-retry")
        try {
            val forced = ArrayList<Path>()
            var failNext = false
            val force: (Path) -> Unit = {
                if (failNext) { failNext = false; throw IOException("force failed") }
                forced.add(it.toAbsolutePath())
            }
            val root = base.resolve("root")
            val s = FileStorage(root, force)
            val r = root.toAbsolutePath()
            failNext = true
            assertFailsWith<IOException> { s.replace("tables/a", byteArrayOf(1)) }
            assertTrue(Files.isDirectory(r.resolve("tables")), "the directory was created before the force failed")
            forced.clear()
            s.replace("tables/a", byteArrayOf(2))
            assertEquals(listOf(r, r.resolve("tables")), forced, "the same instance retries the parent force")

            forced.clear()
            val reopened = FileStorage(root, force)
            reopened.replace("tables/b", byteArrayOf(3))
            assertEquals(listOf(base.toAbsolutePath(), r, r.resolve("tables")), forced.takeLast(3), "a new instance forces the directories it writes under")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /**
     * #64, round 2: an ancestor above the root whose entry could not be forced when it was created
     * is forced when the storage is opened again, although it now exists.
     */
    @Test fun aFailedForceAboveTheRootIsRetriedOnReopen() {
        val base = Files.createTempDirectory("wpgd-above-root").toAbsolutePath()
        try {
            val forced = ArrayList<Path>()
            var failBase = true
            val force: (Path) -> Unit = {
                val p = it.toAbsolutePath()
                if (failBase && p == base) { failBase = false; throw IOException("force failed") }
                forced.add(p)
            }
            val root = base.resolve("a/b/root")
            // Creating `a` (above the root) must force its entry in `base`; that force fails.
            assertFailsWith<IOException> { FileStorage(root, force) }
            assertTrue(Files.isDirectory(base.resolve("a")), "the ancestor was created before its force failed")
            forced.clear()
            FileStorage(root, force)
            assertTrue(base in forced, "reopening skipped the ancestor whose creation was never forced: $forced")
            val chain = listOf(base, base.resolve("a"), base.resolve("a/b"))
            assertEquals(chain, forced.takeLast(3), "each level of the chain is forced, top down")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /** A relative root with a "." segment still accepts valid names (the root used to be compared unnormalized). */
    @Test fun aRelativeRootWithADotSegmentAcceptsValidNames() {
        val base = Files.createTempDirectory("wpgd-relative")
        try {
            val relative = Path.of("").toAbsolutePath().relativize(base.toAbsolutePath())
            val s = FileStorage(Path.of(".").resolve(relative).resolve("root"))
            s.replace("tables/a/meta", byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), s.read("tables/a/meta"))
            assertEquals(listOf("tables/a/meta"), s.list("tables/"))
            assertFailsWith<IllegalArgumentException> { s.read("tables/../../escape") }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /** A directory the lazy walk cannot read fails [Storage.list] with an IOException, as the contract says. */
    @Test fun anUnreadableDirectoryFailsListWithAnIOException() {
        assumeTrue("POSIX permissions needed", "posix" in FileSystems.getDefault().supportedFileAttributeViews())
        assumeTrue("permissions do not bind root", System.getProperty("user.name") != "root")
        val base = Files.createTempDirectory("wpgd-unreadable")
        val locked = base.resolve("tables/a")
        try {
            val s = FileStorage(base)
            s.replace("tables/a/meta", byteArrayOf(1))
            Files.setPosixFilePermissions(locked, emptySet())
            assertFailsWith<IOException> { s.list("tables/") }
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
            base.toFile().deleteRecursively()
        }
    }

    /**
     * The app's minSdk is 26 and it does not desugar the core library: compiled session classes must
     * not call `java.util.stream.Stream.toList()` (Android API 34), which fails at run time on older phones.
     */
    @Test fun sessionClassesCallNoStreamToList() {
        val classes = Path.of(FileStorage::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(Files.isDirectory(classes), "session classes directory not found: $classes")
        val files = Files.walk(classes).use { w ->
            w.filter { it.toString().endsWith(".class") }.map { it.toString() }.collect(Collectors.toList())
        }
        assertTrue(files.size > 50, "too few classes found to scan (${files.size})")
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap")
        assertTrue(Files.isExecutable(javap), "javap not found at $javap")
        val process = ProcessBuilder(listOf(javap.toString(), "-c", "-p") + files).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "javap failed:\n${out.take(2000)}")
        assertTrue("java/util/stream/Stream.filter" in out, "positive control: the disassembly must show the stream calls")
        assertFalse("java/util/stream/Stream.toList" in out, "a session class calls Stream.toList() (Android API 34)")
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
