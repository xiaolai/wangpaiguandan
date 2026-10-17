package cn.wpgd.app.session.storage

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors
import kotlin.io.path.isRegularFile

/**
 * [Storage] on the local file system under [root] (on Android: a no-backup app directory).
 *
 * - [sync] is `FileChannel.force(true)` on the file, and the first sync of each file by this
 *   instance also forces its directory so the name itself survives a crash (also for a file an
 *   earlier run created and may have died before forcing).
 * - [replace] writes `<name>.tmp`, forces it, renames it over the target with `ATOMIC_MOVE`, then
 *   forces the directory.
 * - Every directory this instance writes under (the root, `tables/`, a table's directory) has its
 *   parent forced once before it is used, whether this instance created it or found it, so a
 *   durable file is never left under a directory entry that was not (a failed force is retried,
 *   and a directory a crashed run created without forcing is forced on the next run). Above the
 *   root the same holds for every ancestor this app could have created: each one whose parent it
 *   may write in. An ancestor in a directory the app cannot write (the platform's own) was not
 *   made by it, and is left alone.
 *
 * Not verified here: what the platform's `fsync` actually guarantees on a given phone's flash
 * (for example a drive cache that ignores flushes). The crash tests use [InMemoryStorage].
 */
class FileStorage internal constructor(
    rootDir: Path,
    /** Makes a directory's entries durable (needed after a create, rename or delete); a seam for tests. */
    private val forceDirectory: (Path) -> Unit,
) : Storage {
    constructor(root: Path) : this(root, ::forceDirectoryEntries)

    /** Absolute and normalized, so [path]'s containment check compares like with like. */
    private val root: Path = rootDir.toAbsolutePath().normalize()

    /**
     * Files whose directory entry this instance has made durable. A file not in it (new, or found on
     * disk from an earlier run that may have died before forcing its directory) gets its directory
     * forced on its first sync.
     */
    private val durableNames = HashSet<String>()

    /** Directories whose entry in their parent has been forced by this instance. */
    private val durableDirs = HashSet<Path>()

    init {
        createDirectories(root)
    }

    private fun path(name: String): Path = root.resolve(StorageNames.check(name)).normalize().also {
        require(it.startsWith(root)) { "name escapes the storage root: $name" }
    }

    override fun append(name: String, bytes: ByteArray) {
        val p = path(name)
        createDirectories(p.parent)
        FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            writeFully(ch, bytes)
        }
    }

    override fun sync(name: String) {
        val p = path(name)
        if (!Files.exists(p)) throw IOException("sync of a missing file: $name")
        FileChannel.open(p, StandardOpenOption.WRITE).use { it.force(true) }
        // Remember the name only once its directory entry is durable, so a failed force is retried.
        if (name !in durableNames) {
            forceDirectory(p.parent)
            durableNames += name
        }
    }

    override fun replace(name: String, bytes: ByteArray) {
        val p = path(name)
        createDirectories(p.parent)
        val tmp = p.resolveSibling(p.fileName.toString() + ".tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            writeFully(ch, bytes)
            ch.force(true)
        }
        Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        forceDirectory(p.parent)
        durableNames += name
    }

    override fun read(name: String): ByteArray? {
        val p = path(name)
        return if (p.isRegularFile()) Files.readAllBytes(p) else null
    }

    override fun delete(name: String) {
        val p = path(name)
        durableNames -= name
        if (Files.deleteIfExists(p)) forceDirectory(p.parent)
    }

    override fun list(prefix: String): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        // Collectors, not Stream.toList(): that member is Android API 34, and the app's minSdk is 26.
        try {
            Files.walk(root).use { stream ->
                return stream.filter { Files.isRegularFile(it) }
                    .map { root.relativize(it).joinToString("/") }
                    .filter { it.startsWith(prefix) }
                    .sorted()
                    .collect(Collectors.toList())
            }
        } catch (e: UncheckedIOException) {
            // The walk is lazy: a directory it cannot read fails mid-stream. Storage fails with IOException only.
            throw e.cause ?: IOException(e)
        }
    }

    private fun writeFully(ch: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) ch.write(buffer)
    }

    /**
     * Creates [dir] and any missing ancestors, and forces each one's parent once so its entry is
     * durable. A directory is remembered only after that force succeeds, so a failed force is
     * retried, and one found existing (created by an earlier run that failed or crashed before its
     * force) is forced too.
     */
    private fun createDirectories(dir: Path) {
        val d = dir.toAbsolutePath().normalize()
        if (d in durableDirs) return
        val parent = d.parent ?: throw IOException("no parent for $d")
        // Inside the root every level is checked. Above it: a missing ancestor, and an existing one an
        // earlier run may have created (its own parent is writable here), since nothing records
        // whether that run's force succeeded.
        val insideRoot = d != root && d.startsWith(root)
        if (insideRoot || !Files.isDirectory(parent) || parent.parent?.let(Files::isWritable) == true) createDirectories(parent)
        if (!Files.isDirectory(d)) {
            try {
                Files.createDirectory(d)
            } catch (_: FileAlreadyExistsException) {
                if (!Files.isDirectory(d)) throw IOException("not a directory: $d")
            }
        }
        forceDirectory(parent)
        durableDirs.add(d)
    }

    private companion object {
        fun forceDirectoryEntries(dir: Path) {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
