package cn.wpgd.app.session.storage

import cn.wpgd.app.session.Table
import cn.wpgd.app.session.wire.Receipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GDN-083, GDN-093: the process dies at every write step of one revision (record, its marker, the
 * high-water mark, and the checkpoint written with it), before or after the step, with unsynced bytes
 * dropped, kept, or torn. Restore yields the last committed revision, never below the high-water
 * mark, and no receipt was ever sent for a revision that was not durable.
 */
class EventLogCrashTest {
    private val seed = 401L

    /** Plays to r10 with a checkpoint cadence that writes one at r11, so r11's steps include a checkpoint. */
    private class Scenario(val saved: SavedTable, val players: Map<cn.wpgd.app.core.state.Seat, Table.Peer>)

    private fun scenario(): Scenario {
        val saved = SavedTable(seed, config = savedConfig(checkpointEvery = 11))
        val players = saved.playersAt(10)
        return Scenario(saved, players)
    }

    /** The storage steps of committing r11 (and its checkpoint), from a run without faults. */
    private fun stepsOfR11(): List<InMemoryStorage.Op> {
        val s = scenario()
        val before = s.saved.storage.step
        val (actor, id) = s.saved.submitTurn(s.players)
        s.saved.t.until(5_000) { id in actor.client.replies }
        assertEquals(11, s.saved.host.revision)
        return s.saved.storage.ops.filter { it.step > before }
    }

    @Test fun theStepsOfOneRevisionAreRecordMarkerHighWaterMarkThenCheckpoint() {
        val kinds = stepsOfR11().map { it.kind to it.name.substringAfterLast('/').substringBefore('.') }
        val W = InMemoryStorage.Kind.WRITE
        val S = InMemoryStorage.Kind.SYNC
        assertEquals(
            listOf(
                W to "log", S to "log", W to "log", S to "log",
                InMemoryStorage.Kind.REPLACE_WRITE to "hwm", InMemoryStorage.Kind.REPLACE_SYNC to "hwm", InMemoryStorage.Kind.REPLACE_COMMIT to "hwm",
                InMemoryStorage.Kind.REPLACE_WRITE to "checkpoint", InMemoryStorage.Kind.REPLACE_SYNC to "checkpoint", InMemoryStorage.Kind.REPLACE_COMMIT to "checkpoint",
            ),
            kinds,
        )
    }

    @Test fun killAtEveryWriteStepRestoresTheLastCommittedRevisionNeverBelowTheHighWaterMark() {
        val steps = stepsOfR11()
        val markerSync = steps[3].step
        val hwmCommit = steps[6].step
        var runs = 0
        for (op in steps) {
            for (after in listOf(false, true)) {
                for (torn in InMemoryStorage.Torn.entries) {
                    val s = scenario()
                    s.saved.storage.torn = torn
                    if (after) s.saved.storage.killAfter = op.step else s.saved.storage.killBefore = op.step
                    val (actor, id) = s.saved.submitTurn(s.players)
                    val crash = runCatchingCrash { s.saved.t.until(5_000) { id in actor.client.replies } }
                    assertNotNull(crash, "step ${op.step} ${op.kind} after=$after: the process should have died")
                    val receiptSent = actor.client.replies[id] is Receipt
                    val hwmDurable = op.step > hwmCommit || (after && op.step == hwmCommit)
                    if (receiptSent) assertTrue(hwmDurable, "receipt sent before the high-water mark was durable (step ${op.step} ${op.kind})")

                    s.saved.crash()
                    val hwm = checkNotNull(readHwm(s.saved.storage))
                    val restored = s.saved.restored()
                    val revision = restored.host.revision
                    assertTrue(revision >= hwm, "restored $revision below the high-water mark $hwm")
                    assertTrue(revision == 10L || revision == 11L, "restored $revision")
                    val markerDurable = op.step > markerSync || (after && op.step == markerSync)
                    when (torn) {
                        InMemoryStorage.Torn.DROP_UNSYNCED -> assertEquals(if (markerDurable) 11L else 10L, revision, "step ${op.step} ${op.kind} after=$after")
                        InMemoryStorage.Torn.KEEP_UNSYNCED -> {
                            val markerWritten = op.step > steps[2].step || (after && op.step == steps[2].step)
                            assertEquals(if (markerWritten) 11L else 10L, revision, "step ${op.step} ${op.kind} after=$after kept")
                        }
                        InMemoryStorage.Torn.RANDOM_PREFIX -> if (markerDurable) assertEquals(11L, revision)
                    }
                    // Never quarantined: the write order guarantees the log reaches the high-water mark.
                    assertEquals(cn.wpgd.app.session.host.RoomStatus.Active, restored.host.status)
                    assertTrue(checkNotNull(readHwm(s.saved.storage)) >= revision, "the high-water mark is raised before anything is revealed")
                    assertCardsConserved(restored.host.state)

                    // The issuer rejoins and retries the same command id: applied at most once.
                    val p = s.saved.rejoin(actor)
                    p.client.resend(id)
                    s.saved.t.run(500)
                    val reply = (p.client.repeatedReplies.lastOrNull() ?: p.client.replies[id])
                    assertIs<Receipt>(reply, "retry after restore at $revision: $reply")
                    assertEquals(11, s.saved.host.revision, "the command is applied exactly once")
                    assertEquals(1, committedCommandIds(s.saved.storage.fork()).count { it == id })
                    runs++
                }
            }
        }
        println("EventLogCrashTest: ${steps.size} write steps x before/after x ${InMemoryStorage.Torn.entries.size} torn modes = $runs crash runs")
    }

    @Test fun killBeforeTheMarkerOfR11IsDurableGivesR10() {
        val steps = stepsOfR11()
        val s = scenario()
        s.saved.storage.killBefore = steps[3].step // the marker's sync
        val (actor, id) = s.saved.submitTurn(s.players)
        assertNotNull(runCatchingCrash { s.saved.t.until(5_000) { id in actor.client.replies } })
        assertNull(actor.client.replies[id], "no receipt for a revision that is not durable")
        s.saved.crash()
        val r = s.saved.restored()
        assertEquals(10, r.host.revision)
        assertEquals(10, r.report.let { readHwm(s.saved.storage) })
        assertTrue(r.report.tailDiscarded, "the unmarked record of r11 is not committed")
    }

    @Test fun killAfterTheMarkerBeforeTheReceiptGivesR11WithNoQuarantine() {
        val steps = stepsOfR11()
        val s = scenario()
        s.saved.storage.killAfter = steps[3].step // marker durable, high-water mark not yet written
        val (actor, id) = s.saved.submitTurn(s.players)
        assertNotNull(runCatchingCrash { s.saved.t.until(5_000) { id in actor.client.replies } })
        assertNull(actor.client.replies[id])
        s.saved.crash()
        assertEquals(10, readHwm(s.saved.storage))
        val r = s.saved.restored()
        assertEquals(11, r.host.revision)
        assertEquals(cn.wpgd.app.session.host.RoomStatus.Active, r.host.status)
        assertTrue(r.report.highWaterMarkPromoted)
        assertEquals(11, readHwm(s.saved.storage))
        // The retry returns the original receipt (command-id dedupe), not a second application.
        val p = s.saved.rejoin(actor)
        p.client.resend(id)
        s.saved.t.run(500)
        val receipt = assertIs<Receipt>(p.client.replies[id] ?: p.client.repeatedReplies.last())
        assertEquals(11, receipt.revision)
        assertEquals(11, s.saved.host.revision)
        assertEquals(0, p.client.metrics.digestMismatches, "the original receipt's digest matches the member's view")
    }

    /** A marker commits only the exact record it was written for (its payload is that record's CRC). */
    @Test fun aMarkerDoesNotCommitARecordThatWasReplacedUnderIt() {
        val steps = stepsOfR11()
        val s = scenario()
        s.saved.storage.killAfter = steps[3].step // r11 committed, high-water mark still 10
        val (actor, id) = s.saved.submitTurn(s.players)
        assertNotNull(runCatchingCrash { s.saved.t.until(5_000) { id in actor.client.replies } })
        s.saved.crash()
        // A different, well-formed record now sits where r11 was; the marker still names the old one.
        s.saved.storage.editRecord(11, updateMarker = false) { it.copy(command = it.command?.copy(commandId = "someone-else")) }
        val r = s.saved.restore()
        if (r is cn.wpgd.app.session.host.SessionHost.RestoreResult.Restored) {
            assertEquals(10, r.host.revision, "the marker of r11 must not commit a record it was not written for")
        } else {
            assertIs<cn.wpgd.app.session.host.SessionHost.RestoreResult.Quarantined>(r)
        }
    }
}

/** Runs [block]; returns the [SimulatedCrash] it threw, or null when it finished. */
internal fun runCatchingCrash(block: () -> Unit): SimulatedCrash? = try {
    block()
    null
} catch (c: SimulatedCrash) {
    c
}
