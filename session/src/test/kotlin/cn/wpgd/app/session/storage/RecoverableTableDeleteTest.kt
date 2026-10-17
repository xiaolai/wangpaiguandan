package cn.wpgd.app.session.storage

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.testCrypto
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GDN-090: an explicit delete removes the log, checkpoints, high-water mark, members (the rejoin
 * capabilities) and meta together. A crash part-way never leaves a table that restores to anything:
 * either the whole table survives, or the deletion the user asked for is finished at the next start.
 */
class RecoverableTableDeleteTest {
    private fun tableWithEverything(seed: Long): SavedTable {
        val saved = SavedTable(seed, config = savedConfig(checkpointEvery = 4))
        saved.playersAt(12)
        saved.crash()
        val names = saved.storage.list(TableFiles(TABLE).dir).map { it.substringAfterLast('/').substringBefore('.') }.toSet()
        assertTrue(names.containsAll(setOf("meta", "hwm", "members", "log", "checkpoint")), "$names")
        return saved
    }

    @Test fun explicitDeleteRemovesLogCheckpointsAndCapabilitiesTogether() {
        val saved = tableWithEverything(1101)
        // A second table in the same storage is untouched.
        SessionHost.create(saved.config, TestProfiles.base, TestProfiles.match, testCrypto(5), saved.t.scheduler, saved.storage, "other", T0)
        val otherBefore = saved.storage.snapshot().filterKeys { it.startsWith("tables/other/") }

        TableRepository(saved.storage).delete(TABLE, UserConfirmation.CONFIRMED_BY_USER)
        assertEquals(emptyList(), saved.storage.list(TableFiles(TABLE).dir))
        assertEquals(otherBefore, saved.storage.snapshot().filterKeys { it.startsWith("tables/other/") })
        assertEquals(SessionHost.RestoreResult.Missing, saved.restore())
    }

    @Test fun aDeletedTablesCapabilityAdmitsNobody() {
        val saved = SavedTable(1102)
        val players = saved.playersAt(6)
        val capability = checkNotNull(players.getValue(Seat.EAST).client.capability)
        saved.crash()
        TableRepository(saved.storage).delete(TABLE, UserConfirmation.CONFIRMED_BY_USER)
        // A new table under the same id starts empty: the old capability is unknown to it.
        saved.t.startHost { s -> SessionHost.create(saved.config, TestProfiles.base, TestProfiles.match, testCrypto(77), s, saved.storage, TABLE, T0) }
        val ghost = saved.t.joiner(saved.t.config(Role.PLAYER, "east", Seat.EAST, rejoin = capability))
        saved.t.until(5_000) { ghost.client.phase == ClientPhase.REJECTED }
    }

    @Test fun aCrashAtEveryStepOfADeleteLeavesAWholeTableOrNone() {
        val probe = tableWithEverything(1103)
        val start = probe.storage.step
        TableRepository(probe.storage).delete(TABLE, UserConfirmation.CONFIRMED_BY_USER)
        val steps = probe.storage.ops.filter { it.step > start }
        assertTrue(steps.size >= 8, "tombstone, then each file: $steps")

        for (op in steps) {
            for (after in listOf(false, true)) {
                val saved = tableWithEverything(1103)
                if (after) saved.storage.killAfter = op.step else saved.storage.killBefore = op.step
                runCatchingCrash { TableRepository(saved.storage).delete(TABLE, UserConfirmation.CONFIRMED_BY_USER) }
                val rebooted = saved.storage.reboot()
                saved.storage = rebooted
                val tombstoneWritten = rebooted.read(TableFiles(TABLE).tombstone) != null
                if (tombstoneWritten) {
                    // The user's deletion is finished by the next start, and nothing restores meanwhile.
                    assertEquals(SessionHost.RestoreResult.Missing, saved.restore())
                    assertEquals(emptyList(), TableRepository(rebooted).list())
                    assertEquals(emptyList(), rebooted.list(TableFiles(TABLE).dir))
                } else {
                    val listing = TableRepository(rebooted).list()
                    if (listing.isEmpty()) {
                        assertEquals(emptyList(), rebooted.list(TableFiles(TABLE).dir), "step ${op.step}")
                    } else {
                        assertEquals(TableRepository.Condition.READABLE, listing.single().condition, "step ${op.step} ${op.kind} after=$after")
                        assertIs<SessionHost.RestoreResult.Restored>(saved.restore())
                    }
                }
            }
        }
    }

    @Test fun aQuarantinedOrUnreadableTableIsDeletedOnlyOnExplicitRequest() {
        val saved = tableWithEverything(1104)
        saved.storage.corrupt(TableFiles(TABLE).hwm, 9)
        assertIs<SessionHost.RestoreResult.Quarantined>(saved.restore())
        saved.crash()
        assertEquals(emptyList(), TableRepository(saved.storage).sweep(Long.MAX_VALUE, TestProfiles.base, TestProfiles.match))
        assertTrue(saved.storage.list(TableFiles(TABLE).dir).isNotEmpty())
        TableRepository(saved.storage).delete(TABLE, UserConfirmation.CONFIRMED_BY_USER)
        assertEquals(emptyList(), saved.storage.list(TableFiles(TABLE).dir))
    }
}
