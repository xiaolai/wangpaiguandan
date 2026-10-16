package cn.wpgd.app.session.storage

import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.CloseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * GDN-090, audit A12, A22: a suspended table expires only at its stated time; an accidental exit
 * deletes nothing; retention never removes a quarantined or unreadable save.
 */
class RetentionPolicyTest {
    private val day = 24L * 3600 * 1000
    private val century = 100L * 365 * day

    @Test fun aSuspendedTableExpiresOnlyAtItsStatedTime() {
        val saved = SavedTable(1001)
        val players = saved.playersAt(8)
        val now = T0 + 5_000
        val expiresAt = now + 7 * day
        saved.host.suspend(now, expiresAt)
        assertEquals(RoomStatus.Suspended(expiresAt), saved.host.status)
        saved.t.run(1_000)
        assertEquals(CloseReason.SUSPENDED, players.getValue(Seat.SOUTH).client.closeReason)
        assertEquals(TableStatus.SUSPENDED, TableRepository(saved.storage).list().single().meta?.status)

        val repo = TableRepository(saved.storage)
        assertEquals(emptyList(), repo.sweep(expiresAt - 1, TestProfiles.base, TestProfiles.match), "one millisecond early")
        assertEquals(listOf(TABLE), TableRepository(saved.storage).list().map { it.tableId })
        assertEquals(listOf(TABLE), repo.sweep(expiresAt, TestProfiles.base, TestProfiles.match))
        assertEquals(emptyList(), saved.storage.list("tables/"))
    }

    @Test fun anAccidentalExitDeletesNothingAndTheTableResumes() {
        val saved = SavedTable(1002)
        saved.playersAt(8)
        saved.crash() // the app was killed: no suspend, no delete
        assertEquals(emptyList(), TableRepository(saved.storage).sweep(T0 + century, TestProfiles.base, TestProfiles.match))
        val r = saved.restored()
        assertEquals(8, r.host.revision)
    }

    @Test fun resumingASuspendedTableMakesItActiveAgain() {
        val saved = SavedTable(1003)
        saved.playersAt(8)
        saved.host.suspend(T0 + 1, T0 + day)
        saved.crash()
        saved.restored(now = T0 + 2)
        val meta = TableRepository(saved.storage).list().single().meta!!
        assertEquals(TableStatus.ACTIVE, meta.status)
        assertNull(meta.expiresAt)
        assertEquals(emptyList(), TableRepository(saved.storage).sweep(T0 + century, TestProfiles.base, TestProfiles.match))
    }

    @Test fun anEndedTableIsKeptUntilItsExpiry() {
        val saved = SavedTable(1004)
        saved.playersAt(8)
        saved.host.end(T0 + 1, T0 + day)
        assertEquals(RoomStatus.Ended(cn.wpgd.app.session.host.EndReason.HOST_ENDED), saved.host.status)
        val repo = TableRepository(saved.storage)
        assertEquals(emptyList(), repo.sweep(T0 + day - 1, TestProfiles.base, TestProfiles.match))
        assertEquals(listOf(TABLE), repo.sweep(T0 + day, TestProfiles.base, TestProfiles.match))
    }

    /** Envelopes that decode are not enough: a log that would not replay is kept like a quarantined one. */
    @Test fun retentionNeverRemovesASaveRecoveryWouldQuarantine() {
        val saved = SavedTable(1007)
        saved.playersAt(8)
        saved.host.suspend(T0 + 1, T0 + day)
        saved.crash()
        saved.storage.editRecord(5) { it.copy(stateHash = "0".repeat(64)) }
        assertEquals(TableRepository.Condition.READABLE, TableRepository(saved.storage).list().single().condition)
        assertEquals(emptyList(), TableRepository(saved.storage).sweep(T0 + century, TestProfiles.base, TestProfiles.match))
        assertEquals(TableRepository.Condition.READABLE, TableRepository(saved.storage).list().single().condition, "the sweep wrote nothing")
        assertEquals(QuarantineReason.REPLAY_FAILED, assertIs<SessionHost.RestoreResult.Quarantined>(saved.restore()).reason)
    }

    @Test fun retentionNeverRemovesAQuarantinedOrUnreadableSave() {
        // Quarantined.
        val q = SavedTable(1005)
        q.playersAt(8)
        q.host.suspend(T0 + 1, T0 + day)
        q.crash()
        q.storage.corruptFrame(LogFormat.MARKER, 8)
        assertIs<SessionHost.RestoreResult.Quarantined>(q.restore())
        assertEquals(TableRepository.Condition.QUARANTINED, TableRepository(q.storage).list().single().condition)
        assertEquals(emptyList(), TableRepository(q.storage).sweep(T0 + century, TestProfiles.base, TestProfiles.match))

        // Unreadable, and never even opened: its high-water mark fails its checksum.
        val u = SavedTable(1006)
        u.playersAt(8)
        u.host.suspend(T0 + 1, T0 + day)
        u.crash()
        u.storage.corrupt(TableFiles(TABLE).hwm, 10)
        assertEquals(TableRepository.Condition.UNREADABLE, TableRepository(u.storage).list().single().condition)
        assertEquals(emptyList(), TableRepository(u.storage).sweep(T0 + century, TestProfiles.base, TestProfiles.match))
        assertEquals(TableRepository.Condition.UNREADABLE, TableRepository(u.storage).list().single().condition)
    }
}
