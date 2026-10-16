package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchEventCodec
import cn.wpgd.app.core.match.MatchEvents
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.SessionHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GDN-091, GDN-093: actions acknowledged across two deals (with the tribute between them), host
 * restarted, state rebuilt from the persisted typed events alone. It matches the live host and the
 * checkpoint's state hash, and the checkpoint is only ever a starting point for log replay.
 */
class EventLogRebuildTest {
    @Test fun twoDealsRebuildFromTypedEventsAloneAndMatchTheCheckpoint() {
        val saved = SavedTable(601, config = savedConfig(checkpointEvery = 16))
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { s -> s.dealNumber == 2 && s.phase == MatchPhase.PLAYING && s.revision % 16 == 5L }
        val live = saved.host.state
        val liveHash = MatchStateCodec.hash(live)
        assertTrue(live.previous != null, "the first deal was settled")

        saved.crash()
        val files = TableFiles(TABLE)
        val checkpoints = saved.storage.list(files.dir).mapNotNull { files.checkpointRevision(it) }
        assertEquals(2, checkpoints.size, "the current and the previous checkpoint are kept: $checkpoints")

        // 1. Restore with checkpoints: replay starts at the newest one.
        val withCheckpoint = saved.storage.fork()
        val reference = saved.storage.fork() // read-only copy for the checks below
        // 2. Restore from the log alone: every checkpoint removed.
        val logOnly = saved.storage.fork().also { s -> checkpoints.forEach { s.delete(files.checkpoint(it)) } }

        saved.storage = withCheckpoint
        val a = saved.restored()
        assertEquals(checkpoints.max(), a.report.checkpointRevision)
        saved.crash()
        saved.storage = logOnly
        val b = saved.restored()
        assertNull(b.report.checkpointRevision)
        assertEquals(live.revision.toInt(), b.report.recordsReplayed)

        assertEquals(liveHash, MatchStateCodec.hash(a.host.state))
        assertEquals(liveHash, MatchStateCodec.hash(b.host.state))
        assertEquals(live, b.host.state)

        // The checkpoint's stored hash equals the state the typed events alone give at its revision.
        val reducer = MatchReducer(TestProfiles.base, TestProfiles.match)
        for (cp in checkpoints) {
            val payload = StorageJson.json.decodeFromString(
                CheckpointPayload.serializer(),
                checkNotNull(LogFormat.unwrap(LogFormat.FileKind.CHECKPOINT, checkNotNull(reference.read(files.checkpoint(cp))))).decodeToString(),
            )
            val events = recordPayloads(reference).filter { it.first <= cp }.flatMap { MatchEventCodec.decode(it.second.events) }
            val replayed = MatchEvents.replay(reducer.newMatch(), events)
            assertEquals(payload.stateHash, MatchStateCodec.hash(replayed), "checkpoint $cp")
        }
        println("EventLogRebuild: revision ${live.revision}, deals ${live.dealNumber}, checkpoints $checkpoints, " +
            "replayed ${a.report.recordsReplayed} records after the checkpoint vs ${b.report.recordsReplayed} from the log alone")
    }

    @Test fun aCheckpointWithoutItsLogIsNeverRestoredOnItsOwn() {
        val saved = SavedTable(602, config = savedConfig(checkpointEvery = 8))
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { it.revision >= 20 }
        saved.crash()
        val files = TableFiles(TABLE)
        assertNotNull(saved.storage.list(files.dir).firstOrNull { files.checkpointRevision(it) != null })
        for (seg in saved.storage.list(files.dir).filter { files.segmentNumber(it) != null }) saved.storage.delete(seg)
        val r = saved.restore()
        assertTrue(r is SessionHost.RestoreResult.Quarantined && r.reason == QuarantineReason.BELOW_HIGH_WATER_MARK, "$r")
    }

    @Test fun aCheckpointThatFailsItsChecksumIsSkippedForThePreviousOne() {
        val saved = SavedTable(603, config = savedConfig(checkpointEvery = 8))
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { it.revision >= 20 }
        val liveHash = MatchStateCodec.hash(saved.host.state)
        saved.crash()
        val files = TableFiles(TABLE)
        val newest = saved.storage.list(files.dir).mapNotNull { files.checkpointRevision(it) }.max()
        saved.storage.corrupt(files.checkpoint(newest), 20)
        val r = saved.restored()
        assertEquals(1, r.report.checkpointsSkipped)
        assertEquals(newest - 8, r.report.checkpointRevision)
        assertEquals(liveHash, MatchStateCodec.hash(r.host.state))
    }

    /** Revision 0 has no log record: a checkpoint there is never written, and one found on disk is passed over. */
    @Test fun aRevisionZeroCheckpointIsRefusedAndSkipped() {
        val saved = SavedTable(602)
        saved.crash()
        val files = TableFiles(TABLE)
        val initial = MatchReducer(TestProfiles.base, TestProfiles.match).newMatch()
        val payload = CheckpointPayload(MatchStateCodec.FORMAT, 0, TestProfiles.base.hash, TestProfiles.match.hash, MatchStateCodec.hash(initial), MatchStateCodec.encode(initial))
        saved.storage.put(files.checkpoint(0), LogFormat.wrap(LogFormat.FileKind.CHECKPOINT, StorageJson.json.encodeToString(CheckpointPayload.serializer(), payload).encodeToByteArray()))

        val recovered = TableRecovery.recover(saved.storage, TABLE, TestProfiles.base, TestProfiles.match)
        assertIs<RecoveryResult.Recovered>(recovered, "$recovered")
        assertEquals(0, recovered.table.state.revision)
        assertEquals(1, recovered.table.report.checkpointsSkipped)
        assertFailsWith<IllegalArgumentException> { recovered.table.journal.writeCheckpoint(initial, MatchStateCodec.hash(initial)) }
    }
}
