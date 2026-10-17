package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MATCH_EVENT_SCHEMA_VERSION
import cn.wpgd.app.core.match.MatchEvent
import cn.wpgd.app.core.match.MatchEventCodec
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.EVENT_SCHEMA_VERSION
import cn.wpgd.app.session.crypto.StoredKeyPair
import cn.wpgd.app.session.wire.B64
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The write side of one table (WI-06, GDN-083, GDN-091, GDN-093). The append-only event log is
 * the single recovery source; checkpoints are a cache.
 *
 * [commit] writes one revision in this order, each step durable before the next starts:
 * 1. the record (typed events, issuer's command id and receipt digest, state hash), then sync;
 * 2. its commit marker (CRC of that exact record), then sync;
 * 3. the high-water mark, by atomic replace.
 * Only when [commit] returns may the caller send anything that reveals the revision (receipt or
 * snapshot). If any step throws, the journal is [broken] and refuses further writes: what is
 * durable is unknown until a restart recovers it, so the room must pause.
 */
class TableJournal internal constructor(
    private val storage: Storage,
    internal val files: TableFiles,
    /** The log segment this journal appends to (observability for recovery tests). */
    internal val segment: Int,
    lastRevision: Long,
    highWaterMark: Long,
    meta: TableMeta,
) {
    val tableId: String get() = files.tableId

    var lastRevision: Long = lastRevision
        private set

    /** Highest revision whose receipt may have been sent. */
    var highWaterMark: Long = highWaterMark
        private set

    var meta: TableMeta = meta
        private set

    var broken: Boolean = false
        private set

    fun commit(revision: Long, events: List<MatchEvent>, stateHash: String, command: CommandRef?) {
        check(!broken) { "journal is broken after a failed write" }
        require(revision == lastRevision + 1) { "revision $revision does not follow $lastRevision" }
        require(events.isNotEmpty() && events.all { it.revision == revision }) { "one record holds one revision's events" }
        val payload = RecordPayload(MATCH_EVENT_SCHEMA_VERSION, EVENT_SCHEMA_VERSION, command, stateHash, MatchEventCodec.encode(events))
        val record = LogFormat.record(revision, StorageJson.json.encodeToString(RecordPayload.serializer(), payload).encodeToByteArray())
        val recordCrc = ByteBuffer.wrap(record, record.size - 4, 4).int
        val log = files.segment(segment)
        guarded {
            storage.append(log, record)
            storage.sync(log)
            storage.append(log, LogFormat.marker(revision, recordCrc))
            storage.sync(log)
            lastRevision = revision
            storage.replace(files.hwm, LogFormat.hwm(revision))
            highWaterMark = revision
        }
    }

    /** Raises the high-water mark to the last committed revision before anything of it is revealed. */
    internal fun promoteHighWaterMark() {
        if (highWaterMark >= lastRevision) return
        guarded { storage.replace(files.hwm, LogFormat.hwm(lastRevision)) }
        highWaterMark = lastRevision
    }

    /**
     * Writes a checkpoint of [state] (atomic replace), then removes every checkpoint older than the
     * previous one. A checkpoint is a cache: recovery uses one only as the start of log replay, after
     * matching its hash to the record of the same revision.
     */
    fun writeCheckpoint(state: MatchState, stateHash: String) {
        check(!broken) { "journal is broken after a failed write" }
        require(state.revision in 1..lastRevision) { "checkpoint ${state.revision} outside the log (1..$lastRevision): revision 0 has no record to match" }
        val payload = CheckpointPayload(MatchStateCodec.FORMAT, state.revision, state.baseProfileHash, state.matchProfileHash, stateHash, MatchStateCodec.encode(state))
        val bytes = LogFormat.wrap(LogFormat.FileKind.CHECKPOINT, StorageJson.json.encodeToString(CheckpointPayload.serializer(), payload).encodeToByteArray())
        storage.replace(files.checkpoint(state.revision), bytes)
        val revisions = storage.list(files.dir).mapNotNull { files.checkpointRevision(it) }.sortedDescending()
        for (old in revisions.drop(2)) storage.delete(files.checkpoint(old))
    }

    fun writeMembers(members: MembersFile) {
        check(!broken) { "journal is broken after a failed write" }
        guarded { storage.replace(files.members, TableMetaCodec.wrapMembers(members)) }
    }

    /** Replaces `meta`. Like every other write, a failure breaks the journal: the on-disk status is unknown. */
    fun writeMeta(next: TableMeta) {
        check(!broken) { "journal is broken after a failed write" }
        require(next.tableId == tableId) { "meta of another table" }
        guarded { storage.replace(files.meta, TableMetaCodec.wrap(next)) }
        meta = next
    }

    /** Marks the table quarantined on disk, so no later start resumes it and no sweep deletes it. */
    fun markQuarantined(reason: QuarantineReason, detail: String) = TableRecovery.writeQuarantine(storage, files, reason, detail)

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            broken = true
            throw e
        }
    }

    companion object {
        /**
         * Creates an empty table: the room key first (G2), then meta, the high-water mark (0) and an
         * empty member list. A crash before meta leaves a table whose meta is unreadable (quarantined).
         */
        fun create(storage: Storage, meta: TableMeta, hostKey: StoredKeyPair): TableJournal {
            val files = TableFiles(meta.tableId)
            require(storage.list(files.dir).isEmpty()) { "table ${meta.tableId} already exists" }
            require(meta.status == TableStatus.ACTIVE) { "a new table is active" }
            storage.replace(files.hostKey, TableMetaCodec.wrapHostKey(hostKey))
            storage.replace(files.meta, TableMetaCodec.wrap(meta))
            storage.replace(files.hwm, LogFormat.hwm(0))
            storage.replace(files.members, TableMetaCodec.wrapMembers(MembersFile(1, emptyList(), emptyList())))
            return TableJournal(storage, files, 1, 0, 0, meta)
        }
    }
}

/** Reads and writes `meta`, `members` and `hostkey`. */
internal object TableMetaCodec {
    sealed interface Read {
        data class Current(val meta: TableMeta) : Read
        data class Future(val version: Int) : Read
        data class Unsupported(val version: Int) : Read
        data object Unreadable : Read
    }

    fun wrap(meta: TableMeta): ByteArray =
        LogFormat.wrap(LogFormat.FileKind.META, StorageJson.json.encodeToString(TableMeta.serializer(), meta).encodeToByteArray())

    fun wrapMembers(m: MembersFile): ByteArray =
        LogFormat.wrap(LogFormat.FileKind.MEMBERS, StorageJson.json.encodeToString(MembersFile.serializer(), m).encodeToByteArray())

    fun readMembers(bytes: ByteArray?): MembersFile? = StorageJson.decodeChecked(LogFormat.FileKind.MEMBERS, bytes, MembersFile.serializer())

    fun wrapHostKey(k: StoredKeyPair): ByteArray = LogFormat.wrap(
        LogFormat.FileKind.HOST_KEY,
        StorageJson.json.encodeToString(StoredHostKey.serializer(), StoredHostKey(B64.encode(k.publicKey), B64.encode(k.privateKey))).encodeToByteArray(),
    )

    /** The stored room key, or null when missing, failing its checksum or not base64url. Its halves are checked by the crypto import. */
    fun readHostKey(bytes: ByteArray?): StoredKeyPair? {
        val s = StorageJson.decodeChecked(LogFormat.FileKind.HOST_KEY, bytes, StoredHostKey.serializer()) ?: return null
        val public = B64.decode(s.publicKey) ?: return null
        val private = B64.decode(s.privateKey) ?: return null
        return StoredKeyPair(public, private)
    }

    fun read(bytes: ByteArray?): Read {
        val payload = bytes?.let { LogFormat.unwrap(LogFormat.FileKind.META, it) } ?: return Read.Unreadable
        return try {
            val text = payload.decodeToString()
            val element = StorageJson.json.parseToJsonElement(text)
            val schema = (element as? kotlinx.serialization.json.JsonObject)?.get("schema")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: return Read.Unreadable
            when {
                schema > STORAGE_SCHEMA_VERSION -> Read.Future(schema)
                schema == STORAGE_SCHEMA_VERSION -> Read.Current(StorageJson.json.decodeFromString(TableMeta.serializer(), text))
                else -> Read.Unsupported(schema)
            }
        } catch (_: IllegalArgumentException) {
            Read.Unreadable
        }
    }
}
