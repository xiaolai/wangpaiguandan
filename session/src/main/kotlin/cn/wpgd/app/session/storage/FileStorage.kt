package cn.wpgd.app.session.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.isRegularFile

/**
 * [Storage] on the local file system under [root] (on Android: a no-backup app directory).
 *
 * - [sync] is `FileChannel.force(true)` on the file, and the first sync of a new file also forces
 *   its directory so the name itself survives a crash.
 * - [replace] writes `<name>.tmp`, forces it, renames it over the target with `ATOMIC_MOVE`, then
 *   forces the directory.
 * - Every directory this class creates (the root, `tables/`, a table's directory) has its parent
 *   forced right after, so a durable file is never left under a directory entry that was not.
 *
 * Not verified here: what the platform's `fsync` actually guarantees on a given phone's flash
 * (for example a drive cache that ignores flushes). The crash tests use [InMemoryStorage].
 */
class FileStorage internal constructor(
    private val root: Path,
    /** Makes a directory's entries durable (needed after a create, rename or delete); a seam for tests. */
    private val forceDirectory: (Path) -> Unit,
) : Storage {
    constructor(root: Path) : this(root, ::forceDirectoryEntries)

    private val unsyncedNew = HashSet<String>()

    init {
        createDirectories(root)
    }

    private fun path(name: String): Path = root.resolve(StorageNames.check(name)).normalize().also {
        require(it.startsWith(root)) { "name escapes the storage root: $name" }
    }

    override fun append(name: String, bytes: ByteArray) {
        val p = path(name)
        createDirectories(p.parent)
        if (!Files.exists(p)) unsyncedNew += name
        FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            writeFully(ch, bytes)
        }
    }

    override fun sync(name: String) {
        val p = path(name)
        if (!Files.exists(p)) throw IOException("sync of a missing file: $name")
        FileChannel.open(p, StandardOpenOption.WRITE).use { it.force(true) }
        // Forget the new name only once its directory entry is durable, so a failed force is retried.
        if (name in unsyncedNew) {
            forceDirectory(p.parent)
            unsyncedNew -= name
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
    }

    override fun read(name: String): ByteArray? {
        val p = path(name)
        return if (p.isRegularFile()) Files.readAllBytes(p) else null
    }

    override fun delete(name: String) {
        val p = path(name)
        if (Files.deleteIfExists(p)) forceDirectory(p.parent)
        unsyncedNew -= name
    }

    override fun list(prefix: String): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { stream ->
            return stream.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).joinToString("/") }
                .filter { it.startsWith(prefix) }
                .sorted()
                .toList()
        }
    }

    private fun writeFully(ch: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) ch.write(buffer)
    }

    /** Creates [dir] and any missing ancestors, forcing each new directory's parent so its entry is durable. */
    private fun createDirectories(dir: Path) {
        if (Files.isDirectory(dir)) return
        val parent = dir.toAbsolutePath().parent ?: throw IOException("no parent for $dir")
        createDirectories(parent)
        try {
            Files.createDirectory(dir)
        } catch (_: FileAlreadyExistsException) {
            if (!Files.isDirectory(dir)) throw IOException("not a directory: $dir")
        }
        forceDirectory(parent)
    }

    private companion object {
        fun forceDirectoryEntries(dir: Path) {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
