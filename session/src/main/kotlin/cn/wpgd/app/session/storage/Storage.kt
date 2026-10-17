package cn.wpgd.app.session.storage

import java.io.IOException

/**
 * The only persistence primitives the session uses (WI-06). Names are relative paths of
 * `[A-Za-z0-9._-]` segments separated by `/`. Every failure is an [IOException]; callers treat
 * one as "this write may or may not be durable" and pause the room (never send a receipt after it).
 *
 * Durability contract, which [FileStorage] implements with `FileChannel.force` and an atomic
 * rename, and [InMemoryStorage] simulates step by step for crash tests:
 * - [append] adds bytes to the end of a file, creating it. They may be lost, wholly or in part,
 *   until [sync] on the same name returns.
 * - [replace] swaps the whole content atomically and durably: after a crash the file holds either
 *   the old or the new content, never a mix.
 * - [delete] is durable when it returns; deleting a missing file is not an error.
 */
interface Storage {
    fun append(name: String, bytes: ByteArray)

    fun sync(name: String)

    fun replace(name: String, bytes: ByteArray)

    /** The file's content, or null when it does not exist. */
    fun read(name: String): ByteArray?

    fun delete(name: String)

    /** Every name starting with [prefix], sorted. */
    fun list(prefix: String): List<String>
}

internal object StorageNames {
    private val segment = Regex("[A-Za-z0-9._-]+")

    fun check(name: String): String {
        val parts = name.split('/')
        require(parts.all { it.matches(segment) && it != "." && it != ".." }) { "bad storage name '$name'" }
        return name
    }
}
