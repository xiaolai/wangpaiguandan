package cn.wpgd.app.session.storage

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GDN-093 and the plan's kill-switch row "lost acknowledged action": once a receipt for r11 exists,
 * no restore may continue the room from r10. Every path to that state quarantines instead, and the
 * acknowledged command is never applied a second time.
 */
class AckedRevisionNeverLostTest {
    /** Plays to r10, then command C is acknowledged at r11. Returns the table, players, C's issuer and id. */
    private fun acknowledgedAtR11(seed: Long): Triple<SavedTable, Map<Seat, Table.Peer>, Pair<Table.Peer, String>> {
        val saved = SavedTable(seed)
        val players = saved.playersAt(10)
        val (actor, id) = saved.submitTurn(players)
        saved.t.until(5_000) { id in actor.client.replies }
        assertEquals(11, assertIs<Receipt>(actor.client.replies[id]).revision)
        assertEquals(11, readHwm(saved.storage))
        return Triple(saved, players, actor to id)
    }

    private fun assertRetryRejectedNeverReapplied(saved: SavedTable, actor: Table.Peer, id: String) {
        val p = saved.rejoin(actor)
        assertTrue(p.client.resend(id))
        saved.t.run(500)
        val reply = p.client.repeatedReplies.last()
        assertIs<RejectionReceipt>(reply, "retry of the acknowledged command: $reply")
        assertEquals(RejectionCode.ROOM_QUARANTINED, reply.code)
        assertIs<RoomStatus.Quarantined>(saved.host.status)
        assertEquals(0, saved.host.metrics.commits, "nothing is applied in quarantine")
        // Still quarantined after another restart: only the user can release it.
        saved.crash()
        assertIs<SessionHost.RestoreResult.Quarantined>(saved.restore())
    }

    @Test fun corruptRecordOfAnAcknowledgedRevisionQuarantinesAndTheRetryIsRejected() {
        val (saved, _, issuer) = acknowledgedAtR11(501)
        saved.crash()
        saved.storage.corruptFrame(LogFormat.RECORD, 11)
        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.Quarantined>(r, "$r")
        assertEquals(QuarantineReason.BELOW_HIGH_WATER_MARK, r.reason)
        assertRetryRejectedNeverReapplied(saved, issuer.first, issuer.second)
    }

    @Test fun corruptMarkerOnlyWithNoMemberOnlineQuarantinesBecauseTheHighWaterMarkSaysR11() {
        val (saved, players, issuer) = acknowledgedAtR11(502)
        players.values.forEach { it.client.close() } // nobody is online to say what they hold
        saved.crash()
        saved.storage.corruptFrame(LogFormat.MARKER, 11)
        val before = saved.storage.snapshot()
        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.Quarantined>(r, "$r")
        assertEquals(QuarantineReason.BELOW_HIGH_WATER_MARK, r.reason)
        // The record of r11 was not dropped: the log is byte for byte as it was (only a quarantine marker was added).
        val after = saved.storage.snapshot()
        for ((name, bytes) in before) assertEquals(bytes, after[name], "$name changed")
        assertEquals(setOf(TableFiles(TABLE).quarantine), after.keys - before.keys)
        // Retry C from a fresh client process that kept its capability and receipt.
        val actor = issuer.first
        val fresh = saved.t.joiner(saved.t.config(Role.PLAYER, "south-again", actor.client.seat, rejoin = actor.client.capability))
        saved.t.until(5_000) { fresh.client.phase == ClientPhase.ADMITTED }
        assertIs<RoomStatus.Quarantined>(saved.host.status)
        assertRetryRejectedNeverReapplied(saved, actor, issuer.second)
    }

    @Test fun aMemberHoldingReceiptR11ForcesQuarantineOfARoomRecoveredAtR10() {
        val saved = SavedTable(503)
        val players = saved.playersAt(10)
        val copyAtR10 = saved.storage.durableCopy() // for example a save restored from an older copy
        val (actor, id) = saved.submitTurn(players)
        saved.t.until(5_000) { id in actor.client.replies }
        assertEquals(11, actor.client.heldRevision)

        saved.crash()
        saved.storage = copyAtR10
        val r = saved.restored()
        assertEquals(10, r.host.revision, "the copy is internally consistent at r10")
        assertEquals(RoomStatus.Active, r.host.status)

        // The member rejoins; its heartbeat presents revision 11.
        val p = saved.rejoin(actor)
        saved.t.until(5_000) { saved.host.status is RoomStatus.Quarantined }
        assertEquals(QuarantineReason.MEMBER_AHEAD, (saved.host.status as RoomStatus.Quarantined).reason)
        assertTrue(p.client.resend(id))
        saved.t.run(500)
        assertEquals(RejectionCode.ROOM_QUARANTINED, assertIs<RejectionReceipt>(p.client.repeatedReplies.last()).code)
        assertEquals(10, saved.host.revision)
        // Every other member is refused too, and the quarantine survives a restart.
        val other = saved.rejoin(players.getValue(saved.host.state.deal!!.turn!!).takeIf { it !== actor } ?: players.values.first { it !== actor })
        val otherId = other.client.submit(cn.wpgd.app.session.wire.PassBody)
        if (otherId != null) {
            saved.t.until(5_000) { otherId in other.client.replies }
            assertEquals(RejectionCode.ROOM_QUARANTINED, assertIs<RejectionReceipt>(other.client.replies[otherId]).code)
        }
        saved.crash()
        assertIs<SessionHost.RestoreResult.Quarantined>(saved.restore())
    }

    @Test fun aFreshClientProcessPresentingAStoredReceiptAlsoForcesQuarantine() {
        val saved = SavedTable(504)
        val players = saved.playersAt(10)
        val copyAtR10 = saved.storage.durableCopy()
        val (actor, id) = saved.submitTurn(players)
        saved.t.until(5_000) { id in actor.client.replies }
        saved.crash()
        saved.storage = copyAtR10
        saved.restored()
        val fresh = saved.t.joiner(
            saved.t.config(Role.PLAYER, "south", actor.client.seat, rejoin = actor.client.capability).copy(heldRevision = 11),
        )
        saved.t.until(5_000) { fresh.client.phase == ClientPhase.ADMITTED }
        saved.t.until(5_000) { saved.host.status is RoomStatus.Quarantined }
        assertEquals(10, saved.host.revision)
    }
}
