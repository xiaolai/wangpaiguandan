package cn.wpgd.app.session.storage

import java.io.IOException
import java.util.TreeMap
import kotlin.random.Random

/** The process "died" at a planned write step. An [Error], so no `catch (e: Exception)` can swallow it. */
class SimulatedCrash(val step: Int) : Error("simulated process death at storage step $step")

/**
 * Deterministic in-memory [Storage] for crash and failure tests (WI-06). Every primitive is split
 * into numbered steps, counted from 1 across the whole storage:
 *
 * | Call | Steps |
 * |---|---|
 * | [append] | `WRITE` (bytes land in the volatile page cache) |
 * | [sync] | `SYNC` (volatile bytes of that file become durable) |
 * | [replace] | `REPLACE_WRITE` (temp file), `REPLACE_SYNC`, `REPLACE_COMMIT` (rename + directory sync) |
 * | [delete] | `DELETE` |
 *
 * [killBefore] / [killAfter] make step N throw [SimulatedCrash] before or after it takes effect;
 * from then on every call throws. [reboot] returns the storage a new process would see: durable
 * bytes, plus whatever [torn] keeps of each file's unsynced tail (nothing, all of it, or a random
 * prefix: a torn write). A replace that did not reach `REPLACE_COMMIT` leaves the old content.
 *
 * [failAt] makes step N throw an [IOException] with no effect (a disk error the process survives).
 * [ops] records every step taken, so a test can find the step numbers of one commit.
 */
class InMemoryStorage private constructor(
    private val files: TreeMap<String, FileData>,
    private val random: Random,
) : Storage {
    constructor(seed: Long = 1) : this(TreeMap(), Random(seed))

    /**
     * [entryDurable]: the file's directory entry survives a crash (it was replaced, synced or put),
     * independent of its length: an empty file that was replaced durably still exists after reboot.
     */
    private class FileData(var durable: ByteArray, var volatile: ByteArray = ByteArray(0), var entryDurable: Boolean = true) {
        fun content() = durable + volatile
        fun copy() = FileData(durable.copyOf(), volatile.copyOf(), entryDurable)
    }

    enum class Kind { WRITE, SYNC, REPLACE_WRITE, REPLACE_SYNC, REPLACE_COMMIT, DELETE }

    data class Op(val step: Int, val kind: Kind, val name: String)

    /** What a crash keeps of a file's unsynced appended bytes. */
    enum class Torn { DROP_UNSYNCED, KEEP_UNSYNCED, RANDOM_PREFIX }

    var torn: Torn = Torn.DROP_UNSYNCED

    var step: Int = 0
        private set

    private val opsLog = ArrayList<Op>()
    val ops: List<Op> get() = opsLog

    var killBefore: Int? = null
    var killAfter: Int? = null

    /** Step numbers that fail with an [IOException] and change nothing. */
    val failAt = HashSet<Int>()

    /** Fails every step matching this predicate (kind, name). */
    var failWhen: ((Kind, String) -> Boolean)? = null

    var crashed: Boolean = false
        private set

    /** The step the planned crash hit, and whether it was before or after that step took effect. */
    var crashedAt: Pair<Op, Boolean>? = null
        private set

    private inline fun <T> stepOf(kind: Kind, name: String, effect: () -> T): T {
        if (crashed) throw SimulatedCrash(step)
        val n = ++step
        if (killBefore == n) crash(Op(n, kind, name), after = false)
        if (n in failAt || failWhen?.invoke(kind, name) == true) {
            opsLog += Op(n, kind, name)
            throw IOException("injected storage failure at step $n ($kind $name)")
        }
        val result = effect()
        opsLog += Op(n, kind, name)
        if (killAfter == n) crash(Op(n, kind, name), after = true)
        return result
    }

    private fun crash(op: Op, after: Boolean): Nothing {
        crashed = true
        crashedAt = op to after
        throw SimulatedCrash(op.step)
    }

    override fun append(name: String, bytes: ByteArray) {
        StorageNames.check(name)
        stepOf(Kind.WRITE, name) {
            val f = files.getOrPut(name) { FileData(ByteArray(0), entryDurable = false) }
            f.volatile += bytes
        }
    }

    override fun sync(name: String) {
        StorageNames.check(name)
        stepOf(Kind.SYNC, name) {
            val f = files[name] ?: throw IOException("sync of a missing file: $name")
            f.durable += f.volatile
            f.volatile = ByteArray(0)
            f.entryDurable = true
        }
    }

    override fun replace(name: String, bytes: ByteArray) {
        StorageNames.check(name)
        val copy = bytes.copyOf()
        // The temp file is never read back: until REPLACE_COMMIT the old content is what any reader sees.
        stepOf(Kind.REPLACE_WRITE, name) {}
        stepOf(Kind.REPLACE_SYNC, name) {}
        stepOf(Kind.REPLACE_COMMIT, name) { files[name] = FileData(copy) }
    }

    override fun read(name: String): ByteArray? {
        if (crashed) throw SimulatedCrash(step)
        return files[StorageNames.check(name)]?.content()
    }

    override fun delete(name: String) {
        StorageNames.check(name)
        stepOf(Kind.DELETE, name) { files.remove(name) }
    }

    override fun list(prefix: String): List<String> {
        if (crashed) throw SimulatedCrash(step)
        return files.keys.filter { it.startsWith(prefix) }
    }

    /** What a restarted process sees after a crash (or a clean stop): see the class comment. */
    fun reboot(): InMemoryStorage {
        val out = TreeMap<String, FileData>()
        for ((name, f) in files) {
            val keep = when (torn) {
                Torn.DROP_UNSYNCED -> 0
                Torn.KEEP_UNSYNCED -> f.volatile.size
                Torn.RANDOM_PREFIX -> if (f.volatile.isEmpty()) 0 else random.nextInt(f.volatile.size + 1)
            }
            val bytes = f.durable + f.volatile.copyOf(keep)
            // A file created by an append that never synced has no durable directory entry.
            if (f.entryDurable || bytes.isNotEmpty()) out[name] = FileData(bytes)
        }
        crashed = true
        return InMemoryStorage(out, random).also { it.torn = torn }
    }

    /** A copy of the durable state, as if the files were copied to another device (no volatile bytes). */
    fun durableCopy(): InMemoryStorage {
        val out = TreeMap<String, FileData>()
        for ((name, f) in files) if (f.entryDurable) out[name] = FileData(f.durable.copyOf())
        return InMemoryStorage(out, Random(0))
    }

    // ---- Test tools: corrupt or craft files directly (no step is counted) --------------------------

    /** Flips every bit of the byte at [offset] (durable content). */
    fun corrupt(name: String, offset: Int) {
        val f = checkNotNull(files[name]) { "no file $name" }
        check(f.volatile.isEmpty()) { "corrupt only durable files" }
        f.durable = f.durable.copyOf().also { it[offset] = (it[offset].toInt() xor 0xFF).toByte() }
    }

    /** Sets a file's durable content directly. */
    fun put(name: String, bytes: ByteArray) {
        files[StorageNames.check(name)] = FileData(bytes.copyOf())
    }

    /** Every file's current content (durable plus volatile), for byte-for-byte comparisons. */
    fun snapshot(): Map<String, List<Byte>> = files.mapValues { it.value.content().toList() }

    /** Copy of the whole storage (durable and volatile), sharing nothing. */
    fun fork(): InMemoryStorage = InMemoryStorage(TreeMap(files.mapValues { it.value.copy() }), Random(0))
}
