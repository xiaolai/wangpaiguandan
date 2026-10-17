package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MATCH_EVENT_SCHEMA_VERSION
import cn.wpgd.app.core.match.MatchEventCodec
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.state.EVENT_SCHEMA_VERSION
import cn.wpgd.app.session.crypto.StoredKeyPair
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** What recovery found. Only [Recovered] may be played on. */
sealed interface RecoveryResult {
    data class Recovered(val table: RecoveredTable) : RecoveryResult

    /**
     * The table is kept as it is and may not be played on until an explicit user action. [meta] and
     * [members] are given when readable, so the host can still tell rejoining members why.
     */
    data class Quarantined(
        val reason: QuarantineReason,
        val detail: String,
        val meta: TableMeta?,
        val members: MembersFile?,
        val committedRevision: Long?,
        val highWaterMark: Long?,
        /** The room key when readable, so a quarantined host still proves it is the same room to rejoining members. */
        val hostKey: StoredKeyPair? = null,
    ) : RecoveryResult

    /** Written by a newer version of the app: preserved byte for byte, never rewritten or deleted. */
    data class FutureSchema(val version: Int, val where: String) : RecoveryResult

    data object Missing : RecoveryResult
}

data class RecoveryReport(
    /** Revision of the checkpoint replay started from; null when replay started at revision 0. */
    val checkpointRevision: Long?,
    /** Checkpoints passed over because they failed their checksum. */
    val checkpointsSkipped: Int,
    val recordsReplayed: Int,
    /** Frames after the last committed revision were ignored (a torn or unmarked, never-acknowledged tail). */
    val tailDiscarded: Boolean,
    /** The high-water mark was below the last committed revision and was raised before anything was revealed. */
    val highWaterMarkPromoted: Boolean,
)

class RecoveredTable internal constructor(
    val meta: TableMeta,
    val members: MembersFile,
    /** The room key (G2), as stored; [cn.wpgd.app.session.host.SessionHost.restore] imports it. */
    val hostKey: StoredKeyPair,
    val state: MatchState,
    /** High-water mark as found on disk, before any promotion. */
    val highWaterMarkFound: Long,
    /** Every committed command: (revision, issuer, command id, receipt digest), oldest first. */
    val commands: List<Pair<Long, CommandRef>>,
    val journal: TableJournal,
    val report: RecoveryReport,
)

/**
 * The read side of one table (WI-06). Recovery replays the log up to the last revision whose record
 * and commit marker both verify: the last committed revision. It never goes below the high-water
 * mark: if the log ends lower, or the high-water mark itself is unreadable, acknowledged data may be
 * lost and the table is quarantined instead. A committed revision above the high-water mark (crash
 * after the marker, before the receipt) is kept, and the mark is raised before anything is revealed.
 */
object TableRecovery {
    fun recover(storage: Storage, tableId: String, base: RuleProfile, match: RuleProfile): RecoveryResult {
        val files = TableFiles(tableId)
        val names = storage.list(files.dir)
        return when (val e = examine(storage, files, names, base, match)) {
            is Examined.Ready -> open(storage, files, e)
            is Examined.Verdict -> e.result.also { r ->
                if (r is RecoveryResult.Quarantined && files.quarantine !in names) {
                    try {
                        writeQuarantine(storage, files, r.reason, r.detail)
                    } catch (_: IOException) {
                        // Still quarantined in memory; the next start re-derives the same verdict.
                    }
                }
            }
        }
    }

    /** What [examine] found: a verdict that is not [RecoveryResult.Recovered], or a table ready to open. */
    internal sealed interface Examined {
        class Verdict(val result: RecoveryResult) : Examined

        class Ready(
            val meta: TableMeta,
            val members: MembersFile,
            val hostKey: StoredKeyPair,
            val state: MatchState,
            val highWaterMark: Long,
            val lastCommitted: Long,
            val records: List<LogFormat.Frame>,
            val payloads: List<RecordPayload>,
            val dirtyTail: Boolean,
            val lastSegmentDirty: Boolean,
            val segments: List<Int>,
            val checkpointRevision: Long?,
            val checkpointsSkipped: Int,
        ) : Examined
    }

    /** What the profile-free [preliminary] checks found: a verdict that ends recovery, or what replay needs. */
    internal sealed interface Preliminary {
        /** [meta] when it was readable; [marked]: the table carries a quarantine marker from an earlier run. */
        class Stop(val verdict: Examined.Verdict, val meta: TableMeta?, val marked: Boolean) : Preliminary

        class Passed(
            val meta: TableMeta,
            val members: MembersFile,
            val hostKey: StoredKeyPair,
            val highWaterMark: Long,
            val scan: LogScan.Ok,
            val payloads: List<RecordPayload>,
        ) : Preliminary
    }

    /**
     * The checks recovery makes before it needs the rule profiles, in order: meta, quarantine marker,
     * table id, high-water mark, members, room key, log, high-water mark against the log, record
     * envelopes. The one definition both recovery ([examine]) and listing ([TableRepository.inspect])
     * use, so a save recovery would quarantine is never listed as readable.
     */
    internal fun preliminary(storage: Storage, files: TableFiles, names: List<String>): Preliminary {
        if (names.isEmpty() || files.tombstone in names) return Preliminary.Stop(Examined.Verdict(RecoveryResult.Missing), null, marked = false)

        var meta: TableMeta? = null
        var members: MembersFile? = null
        var committed: Long? = null
        var hwm: Long? = null
        var hostKey: StoredKeyPair? = null
        fun quarantine(reason: QuarantineReason, detail: String) =
            Preliminary.Stop(Examined.Verdict(RecoveryResult.Quarantined(reason, detail, meta, members, committed, hwm, hostKey)), meta, marked = false)
        fun verdict(result: RecoveryResult) = Preliminary.Stop(Examined.Verdict(result), meta, marked = false)

        when (val metaRead = TableMetaCodec.read(storage.read(files.meta))) {
            is TableMetaCodec.Read.Future -> return verdict(RecoveryResult.FutureSchema(metaRead.version, "meta"))
            is TableMetaCodec.Read.Unsupported -> return quarantine(QuarantineReason.UNSUPPORTED_OLD_SCHEMA, "storage schema ${metaRead.version}")
            TableMetaCodec.Read.Unreadable -> return quarantine(QuarantineReason.META_UNREADABLE, "meta missing or fails its checksum")
            is TableMetaCodec.Read.Current -> meta = metaRead.meta
        }
        val m = checkNotNull(meta)
        members = TableMetaCodec.readMembers(storage.read(files.members))
        hostKey = TableMetaCodec.readHostKey(storage.read(files.hostKey))

        if (files.quarantine in names) {
            val q = StorageJson.decodeChecked(LogFormat.FileKind.QUARANTINE, storage.read(files.quarantine), QuarantinePayload.serializer())
            val previous = RecoveryResult.Quarantined(q?.reason ?: QuarantineReason.META_UNREADABLE, "previously quarantined: ${q?.detail ?: "unreadable marker"}", meta, members, null, null, hostKey)
            return Preliminary.Stop(Examined.Verdict(previous), meta, marked = true)
        }
        if (m.tableId != files.tableId) return quarantine(QuarantineReason.META_UNREADABLE, "meta names table ${m.tableId}")

        hwm = storage.read(files.hwm)?.let(LogFormat::readHwm) ?: return quarantine(QuarantineReason.HIGH_WATER_MARK_UNREADABLE, "high-water mark missing or fails its checksum")
        val foundMembers = members ?: return quarantine(QuarantineReason.MEMBERS_UNREADABLE, "members missing or fail their checksum")
        val foundKey = hostKey ?: return quarantine(QuarantineReason.HOST_KEY_UNREADABLE, "room key missing or fails its checksum")

        // ---- Scan the log: committed revisions, in order --------------------------------------------
        val scan = scanLog(storage, files, names)
        if (scan is LogScan.Inconsistent) return quarantine(QuarantineReason.LOG_INCONSISTENT, scan.detail)
        scan as LogScan.Ok
        val lastCommitted = scan.records.lastOrNull()?.revision ?: 0
        committed = lastCommitted
        val acknowledged = checkNotNull(hwm)
        if (lastCommitted < acknowledged) {
            return quarantine(QuarantineReason.BELOW_HIGH_WATER_MARK, "log ends at $lastCommitted, high-water mark is $acknowledged")
        }

        // ---- Decode record envelopes (cheap): schema, issuer, state hash ----------------------------
        val payloads = when (val e = decodeEnvelopes(scan.records)) {
            is Envelopes.Ok -> e.payloads
            is Envelopes.Future -> return verdict(RecoveryResult.FutureSchema(e.version, e.where))
            is Envelopes.OldSchema -> return quarantine(QuarantineReason.UNSUPPORTED_OLD_SCHEMA, e.detail)
            is Envelopes.Undecodable -> return quarantine(QuarantineReason.LOG_INCONSISTENT, e.detail)
        }
        return Preliminary.Passed(m, foundMembers, foundKey, acknowledged, scan, payloads)
    }

    /**
     * Every check recovery makes, writing nothing: the [preliminary] checks, then the rule profiles,
     * checkpoint, replay and the restored state's invariants. Retention uses it so a table recovery
     * would quarantine is never deleted automatically.
     */
    internal fun examine(storage: Storage, files: TableFiles, names: List<String>, base: RuleProfile, match: RuleProfile): Examined {
        val p = when (val pre = preliminary(storage, files, names)) {
            is Preliminary.Stop -> return pre.verdict
            is Preliminary.Passed -> pre
        }
        val m = p.meta
        val records = p.scan.records
        val lastCommitted = records.lastOrNull()?.revision ?: 0
        val payloads = p.payloads
        fun quarantine(reason: QuarantineReason, detail: String) =
            Examined.Verdict(RecoveryResult.Quarantined(reason, detail, m, p.members, lastCommitted, p.highWaterMark, p.hostKey))

        if (m.baseProfileHash != base.hash || m.matchProfileHash != match.hash) return quarantine(QuarantineReason.PROFILE_MISMATCH, "table was created under other rule profiles")

        // ---- Start of replay: the newest checkpoint that verifies, else revision 0 -----------------
        val start = when (val s = replayStart(storage, files, names, base, match, lastCommitted, payloads)) {
            is Stage.Failed -> return quarantine(s.reason, s.detail)
            is Stage.Done -> s.value
        }

        // ---- Replay the committed records after the start, checking every state hash ----------------
        val state = when (val r = replay(MatchReducer(base, match), start.state, records, payloads)) {
            is Stage.Failed -> return quarantine(r.reason, r.detail)
            is Stage.Done -> r.value
        }
        val problems = RestoreValidator.problems(state, base.hash, match.hash, lastCommitted)
        if (problems.isNotEmpty()) return quarantine(problems.reason(), problems.joinToString { it.detail })
        return Examined.Ready(
            m, p.members, p.hostKey, state, p.highWaterMark, lastCommitted, records, payloads,
            p.scan.dirtyTail, p.scan.lastSegmentDirty, p.scan.segments, start.revision, start.skipped,
        )
    }

    /** Ready: raise the high-water mark, append to a clean segment. */
    private fun open(storage: Storage, files: TableFiles, r: Examined.Ready): RecoveryResult.Recovered {
        val lastSegment = r.segments.lastOrNull() ?: 0
        val journal = TableJournal(storage, files, if (r.lastSegmentDirty || lastSegment == 0) lastSegment + 1 else lastSegment, r.lastCommitted, r.highWaterMark, r.meta)
        val promoted = r.lastCommitted > r.highWaterMark
        journal.promoteHighWaterMark()
        val commands = r.records.indices.mapNotNull { i -> r.payloads[i].command?.let { r.records[i].revision to it } }
        return RecoveryResult.Recovered(
            RecoveredTable(
                meta = r.meta,
                members = r.members,
                hostKey = r.hostKey,
                state = r.state,
                highWaterMarkFound = r.highWaterMark,
                commands = commands,
                journal = journal,
                report = RecoveryReport(r.checkpointRevision, r.checkpointsSkipped, r.records.size - (r.checkpointRevision ?: 0).toInt(), r.dirtyTail, promoted),
            ),
        )
    }

    /** What the record envelopes say, decided by their schema fields before the strict decode. */
    internal sealed interface Envelopes {
        class Ok(val payloads: List<RecordPayload>) : Envelopes
        class Future(val version: Int, val where: String) : Envelopes
        class OldSchema(val detail: String) : Envelopes
        class Undecodable(val detail: String) : Envelopes
    }

    /**
     * Decodes every committed record's envelope. The schema fields are read from the JSON object
     * first, so a newer schema (which may add fields the strict current decoder refuses) is reported
     * as [Envelopes.Future] and the save is left untouched.
     */
    internal fun decodeEnvelopes(records: List<LogFormat.Frame>): Envelopes {
        val payloads = ArrayList<RecordPayload>(records.size)
        for (f in records) {
            val text = f.payload.decodeToString()
            val obj = try {
                StorageJson.json.parseToJsonElement(text) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
            val matchSchema = obj?.intField("matchEventSchema")
            val dealSchema = obj?.intField("dealEventSchema")
            if (matchSchema == null || dealSchema == null) return Envelopes.Undecodable("record ${f.revision} does not decode")
            if (matchSchema > MATCH_EVENT_SCHEMA_VERSION || dealSchema > EVENT_SCHEMA_VERSION) {
                return Envelopes.Future(maxOf(matchSchema, dealSchema), "record ${f.revision}")
            }
            if (matchSchema < MATCH_EVENT_SCHEMA_VERSION || dealSchema < EVENT_SCHEMA_VERSION) {
                return Envelopes.OldSchema("record ${f.revision} event schema $matchSchema/$dealSchema")
            }
            payloads += try {
                StorageJson.json.decodeFromString(RecordPayload.serializer(), text)
            } catch (_: IllegalArgumentException) {
                return Envelopes.Undecodable("record ${f.revision} does not decode")
            }
        }
        return Envelopes.Ok(payloads)
    }

    private fun JsonObject.intField(name: String): Int? = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    /** One recovery stage's outcome: its value, or the quarantine verdict that ends recovery. */
    private sealed interface Stage<out T> {
        class Done<T>(val value: T) : Stage<T>
        class Failed(val reason: QuarantineReason, val detail: String) : Stage<Nothing>
    }

    /** Replay starts from [state]: the checkpoint at [revision], or the new match when null. */
    private class ReplayStart(val state: MatchState, val revision: Long?, val skipped: Int)

    /** The newest checkpoint that verifies against the log, else the new match at revision 0. */
    private fun replayStart(
        storage: Storage, files: TableFiles, names: List<String>, base: RuleProfile, match: RuleProfile,
        lastCommitted: Long, payloads: List<RecordPayload>,
    ): Stage<ReplayStart> {
        var skipped = 0
        val checkpoints = names.mapNotNull { files.checkpointRevision(it) }.sortedDescending()
        for (rev in checkpoints) {
            val cp = StorageJson.decodeChecked(LogFormat.FileKind.CHECKPOINT, storage.read(files.checkpoint(rev)), CheckpointPayload.serializer())
            // Revision 0 has no log record to match: replay starts there anyway, so such a file is useless.
            if (cp == null || cp.revision != rev || rev < 1) { skipped++; continue }
            if (rev > lastCommitted) return Stage.Failed(QuarantineReason.CHECKPOINT_AHEAD_OF_LOG, "checkpoint $rev, log ends at $lastCommitted")
            if (cp.format != MatchStateCodec.FORMAT) { skipped++; continue }
            if (cp.baseProfileHash != base.hash || cp.matchProfileHash != match.hash) return Stage.Failed(QuarantineReason.PROFILE_MISMATCH, "checkpoint $rev")
            val restored = try {
                MatchStateCodec.decode(cp.state)
            } catch (_: IllegalArgumentException) {
                return Stage.Failed(QuarantineReason.VALIDATION_FAILED, "checkpoint $rev state does not decode")
            }
            val problems = RestoreValidator.problems(restored, base.hash, match.hash, rev)
            if (problems.isNotEmpty()) return Stage.Failed(problems.reason(), "checkpoint $rev: ${problems.joinToString { it.detail }}")
            val hash = MatchStateCodec.hash(restored)
            if (hash != cp.stateHash || hash != payloads[(rev - 1).toInt()].stateHash) {
                return Stage.Failed(QuarantineReason.REPLAY_FAILED, "checkpoint $rev does not match the log")
            }
            return Stage.Done(ReplayStart(restored, rev, skipped))
        }
        return Stage.Done(ReplayStart(MatchReducer(base, match).newMatch(), null, skipped))
    }

    /**
     * Replays the committed records after [from]'s revision through [reducer] (so each settlement is
     * checked against the match profile), checking every revision and state hash.
     */
    private fun replay(reducer: MatchReducer, from: MatchState, records: List<LogFormat.Frame>, payloads: List<RecordPayload>): Stage<MatchState> {
        var state = from
        for (i in from.revision.toInt() until records.size) {
            val rev = records[i].revision
            state = try {
                reducer.replay(state, MatchEventCodec.decode(payloads[i].events))
            } catch (e: IllegalArgumentException) {
                return Stage.Failed(QuarantineReason.REPLAY_FAILED, "record $rev: ${e.message}")
            } catch (e: IllegalStateException) {
                return Stage.Failed(QuarantineReason.REPLAY_FAILED, "record $rev: ${e.message}")
            }
            if (state.revision != rev) return Stage.Failed(QuarantineReason.REPLAY_FAILED, "record $rev replays to revision ${state.revision}")
            if (MatchStateCodec.hash(state) != payloads[i].stateHash) return Stage.Failed(QuarantineReason.REPLAY_FAILED, "state hash differs at revision $rev")
        }
        return Stage.Done(state)
    }

    internal sealed interface LogScan {
        /**
         * [dirtyTail]: some segment ended in frames past its last committed record (the report's flag).
         * [lastSegmentDirty]: the latest segment did, so appending must start a new one.
         */
        class Ok(val records: List<LogFormat.Frame>, val dirtyTail: Boolean, val lastSegmentDirty: Boolean, val segments: List<Int>) : LogScan
        class Inconsistent(val detail: String) : LogScan
    }

    /**
     * Committed records of every segment, in order. Within a segment, frames are read up to the first
     * torn or corrupt one; a marker commits only the record directly before it in the same segment.
     */
    internal fun scanLog(storage: Storage, files: TableFiles, names: List<String>): LogScan {
        val segments = names.mapNotNull { files.segmentNumber(it) }.sorted()
        val records = ArrayList<LogFormat.Frame>()
        var dirtyTail = false
        var lastSegmentDirty = false
        for (seg in segments) {
            val parsed = LogFormat.parse(storage.read(files.segment(seg)) ?: ByteArray(0))
            var candidate: LogFormat.Frame? = null
            for (f in parsed.frames) {
                val last = records.lastOrNull()?.revision ?: 0
                if (f.kind == LogFormat.RECORD) {
                    if (f.revision != last + 1) return LogScan.Inconsistent("record ${f.revision} after committed $last")
                    candidate = f
                } else {
                    val c = candidate
                    if (c == null || c.revision != f.revision || LogFormat.markerTarget(f) != c.crc) {
                        return LogScan.Inconsistent("marker ${f.revision} does not commit the record before it")
                    }
                    records += c
                    candidate = null
                }
            }
            lastSegmentDirty = candidate != null || parsed.stop != null
            if (lastSegmentDirty) dirtyTail = true
        }
        return LogScan.Ok(records, dirtyTail, lastSegmentDirty, segments)
    }

    internal fun writeQuarantine(storage: Storage, files: TableFiles, reason: QuarantineReason, detail: String) {
        val payload = StorageJson.json.encodeToString(QuarantinePayload.serializer(), QuarantinePayload(reason, detail)).encodeToByteArray()
        storage.replace(files.quarantine, LogFormat.wrap(LogFormat.FileKind.QUARANTINE, payload))
    }

    private fun List<RestoreValidator.Problem>.reason(): QuarantineReason =
        if (any { it.kind == RestoreValidator.Kind.PROFILE_HASH }) QuarantineReason.PROFILE_MISMATCH else QuarantineReason.VALIDATION_FAILED
}
