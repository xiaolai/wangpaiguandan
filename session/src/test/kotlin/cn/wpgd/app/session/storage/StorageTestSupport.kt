package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.session.Bot
import cn.wpgd.app.session.ROOM
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.SeatPolicy
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.simpleBody
import cn.wpgd.app.session.testCrypto
import cn.wpgd.app.session.turn
import cn.wpgd.app.session.wire.GiveTributeBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.ReturnTributeBody
import cn.wpgd.app.session.wire.WireAssignment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Wall clock of the tests (retention times). */
internal const val T0 = 1_760_000_000_000L
internal const val TABLE = "t1"

/** The AI that stands in for absent humans and plays AI seats: lowest lead, pass when it may, first legal tribute or return. */
internal val standIn = SeatPolicy { _, o ->
    when {
        o.tributeCards.isNotEmpty() -> GiveTributeBody(o.tributeCards.first())
        o.returnCards.isNotEmpty() -> ReturnTributeBody(o.returnCards.first())
        o.mayPass -> PassBody
        else -> o.plays.minBy { it.cards.size }.let { a ->
            PlayBody(a.cards.map { it.id }, a.declaration?.map { WireAssignment(it.rank, it.suit) }, a.combination.type)
        }
    }
}

internal fun policy(graceMs: Long = 3_000, after: AbsencePolicy = AbsencePolicy.SUBSTITUTE, afterLeave: AbsencePolicy = after) =
    RecoveryPolicy(graceMs, after, afterLeave, standIn)

internal fun savedConfig(recovery: RecoveryPolicy = policy(), checkpointEvery: Int = 64) =
    HostConfig(roomId = ROOM, recovery = recovery, checkpointEvery = checkpointEvery)

/**
 * A saved table on [storage] driven over the in-memory network. [restart] crashes the host process
 * (unsynced bytes handled by the storage's torn mode), reboots the storage and restores the host.
 */
internal class SavedTable(val seed: Long, var storage: InMemoryStorage = InMemoryStorage(seed), val config: HostConfig = savedConfig()) {
    private var generation = 0
    val t: Table = Table(seed = seed, hostConfig = config, hostFactory = { s ->
        SessionHost.create(config, TestProfiles.base, TestProfiles.match, testCrypto(seed), s, storage, TABLE, T0)
    })
    val host: SessionHost get() = t.host

    /** The process dies now; returns the storage a new process sees. */
    fun crash(): InMemoryStorage {
        t.crashHost()
        storage = storage.reboot()
        return storage
    }

    /** Starts a host from [storage] (after [crash], or on a storage prepared by the test). */
    fun restore(now: Long = T0): SessionHost.RestoreResult {
        generation++
        var result: SessionHost.RestoreResult? = null
        t.startHost { s ->
            val r = SessionHost.restore(config, TestProfiles.base, TestProfiles.match, testCrypto(seed * 7919 + generation), s, storage, TABLE, now)
            result = r
            when (r) {
                is SessionHost.RestoreResult.Restored -> r.host
                is SessionHost.RestoreResult.Quarantined -> r.host
                else -> SessionHost(config, TestProfiles.base, TestProfiles.match, testCrypto(seed), s)
            }
        }
        return checkNotNull(result)
    }

    fun restored(now: Long = T0): SessionHost.RestoreResult.Restored {
        val r = restore(now)
        return assertIs<SessionHost.RestoreResult.Restored>(r, "expected a clean restore, got $r")
    }

    /** Rejoins [peer] (by its capability) and waits until it holds the host's snapshot. */
    fun rejoin(peer: Table.Peer): Table.Peer {
        val p = t.reconnect(peer)
        t.until(10_000) { p.client.phase == ClientPhase.ADMITTED }
        t.run(50)
        return p
    }
}

/**
 * Four admitted players; SOUTH's [Bot] and the others' bots play until the host reaches [revision]
 * (start of play counts), then every bot stops and the table quiesces.
 */
internal fun SavedTable.playersAt(revision: Long): Map<Seat, Table.Peer> {
    val players = t.fourPlayers()
    t.startDeal()
    playTo(players, revision)
    return players
}

internal fun SavedTable.playTo(players: Map<Seat, Table.Peer>, revision: Long) {
    val bots = players.values.map { Bot(t, it) }
    bots.forEach { it.poke() }
    t.until(5 * 60_000L) { host.revision >= revision }
    bots.forEach { it.enabled = false }
    t.run(500)
    check(host.revision == revision) { "overshot: ${host.revision} != $revision" }
}

/**
 * Drives a table whose acting seats are AIs: the host steps (deal, open play, settle, start tribute)
 * are applied as soon as they are due, the AI tasks run in virtual time. Stops when [done] holds.
 */
internal fun SavedTable.driveUntil(maxMs: Long = 60 * 60_000L, done: (MatchState) -> Boolean) {
    val end = t.scheduler.now + maxMs
    while (!done(host.state)) {
        check(t.scheduler.now < end) { "not reached within $maxMs ms (revision ${host.revision}, phase ${host.state.phase})" }
        if (host.state.phase in HOST_STEPS) {
            host.advance()
            continue
        }
        t.scheduler.runUntil(1_000) { done(host.state) || host.state.phase in HOST_STEPS }
    }
}

private val HOST_STEPS = setOf(MatchPhase.AWAITING_DEAL, MatchPhase.DEALT, MatchPhase.READY, MatchPhase.DEAL_ENDED)

/** The player whose turn it is submits its simplest play. Returns (peer, command id). */
internal fun SavedTable.submitTurn(players: Map<Seat, Table.Peer>): Pair<Table.Peer, String> {
    val actor = players.getValue(t.turn())
    val id = checkNotNull(actor.client.submit(simpleBody(checkNotNull(actor.client.memberView)))) { "cannot submit" }
    return actor to id
}

/** Frames of every log segment of [TABLE], in order, with the segment name. */
internal fun InMemoryStorage.logFrames(): List<Pair<String, LogFormat.Frame>> {
    val files = TableFiles(TABLE)
    return list(files.dir).filter { files.segmentNumber(it) != null }.flatMap { name ->
        LogFormat.parse(checkNotNull(read(name))).frames.map { name to it }
    }
}

/** Flips a byte inside the payload of the record (or marker) of [revision]. */
internal fun InMemoryStorage.corruptFrame(kind: Byte, revision: Long) {
    val (name, frame) = logFrames().last { it.second.kind == kind && it.second.revision == revision }
    corrupt(name, frame.offset + 13 + frame.payload.size / 2)
}

internal fun recordPayloads(storage: InMemoryStorage): List<Pair<Long, RecordPayload>> {
    val frames = storage.logFrames().map { it.second }
    val committed = frames.filter { it.kind == LogFormat.MARKER }.map { it.revision }.toSet()
    return frames.filter { it.kind == LogFormat.RECORD && it.revision in committed }
        .distinctBy { it.revision }
        .map { it.revision to StorageJson.json.decodeFromString(RecordPayload.serializer(), it.payload.decodeToString()) }
}

/** Every command id committed in the log; a duplicate means a command was applied twice. */
internal fun committedCommandIds(storage: InMemoryStorage): List<String> = recordPayloads(storage).mapNotNull { it.second.command?.commandId }

internal fun readHwm(storage: Storage): Long? = storage.read(TableFiles(TABLE).hwm)?.let(LogFormat::readHwm)

internal fun assertCardsConserved(state: MatchState) {
    val deal = state.deal ?: return
    val all = deal.hands.values.flatten() + deal.played
    assertEquals(108, all.size, "card count")
    assertEquals(StandardDeck.ids, all.toSet(), "card ids")
}

/** Rewrites the checkpoint at [revision] with [edit] applied to its state JSON, re-checksummed (valid bytes, bad content). */
internal fun InMemoryStorage.editCheckpoint(revision: Long, edit: (JsonObject) -> JsonObject) {
    val files = TableFiles(TABLE)
    val name = files.checkpoint(revision)
    val payload = StorageJson.json.decodeFromString(CheckpointPayload.serializer(), checkNotNull(LogFormat.unwrap(LogFormat.FileKind.CHECKPOINT, checkNotNull(read(name)))).decodeToString())
    val edited = payload.copy(state = edit(payload.state.jsonObject))
    put(name, LogFormat.wrap(LogFormat.FileKind.CHECKPOINT, StorageJson.json.encodeToString(CheckpointPayload.serializer(), edited).encodeToByteArray()))
}

/** Replaces the record of [revision] (and its marker) with [edit] applied, re-checksummed. */
internal fun InMemoryStorage.editRecord(revision: Long, updateMarker: Boolean = true, edit: (RecordPayload) -> RecordPayload) =
    editRecordText(revision, updateMarker) { text ->
        StorageJson.json.encodeToString(RecordPayload.serializer(), edit(StorageJson.json.decodeFromString(RecordPayload.serializer(), text)))
    }

/** Replaces the record of [revision] (and its marker) with [edit] applied to its JSON text, re-checksummed. */
internal fun InMemoryStorage.editRecordText(revision: Long, updateMarker: Boolean = true, edit: (String) -> String) {
    val files = TableFiles(TABLE)
    for (name in list(files.dir).filter { files.segmentNumber(it) != null }) {
        val bytes = checkNotNull(read(name))
        val frames = LogFormat.parse(bytes).frames
        val rec = frames.firstOrNull { it.kind == LogFormat.RECORD && it.revision == revision } ?: continue
        val newRecord = LogFormat.record(revision, edit(rec.payload.decodeToString()).encodeToByteArray())
        val newCrc = ByteBuffer.wrap(newRecord, newRecord.size - 4, 4).int
        val out = ArrayList<Byte>()
        for (f in frames) {
            val replacement = when {
                f === rec -> newRecord
                f.kind == LogFormat.MARKER && f.revision == revision && updateMarker -> LogFormat.marker(revision, newCrc)
                else -> bytes.copyOfRange(f.offset, f.offset + f.size)
            }
            out.addAll(replacement.toList())
        }
        put(name, out.toByteArray())
        return
    }
    fail("no record $revision")
}

internal fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))

internal fun str(s: String) = JsonPrimitive(s)

internal fun stateHash(state: MatchState) = MatchStateCodec.hash(state)

internal fun assertPhaseOneOf(state: MatchState, vararg phases: MatchPhase) =
    assertTrue(state.phase in phases, "phase ${state.phase}")
