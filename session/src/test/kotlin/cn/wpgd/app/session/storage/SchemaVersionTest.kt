package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MATCH_EVENT_SCHEMA_VERSION
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.SessionHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GDN-084, audit A13: a save from a newer app version is preserved byte for byte (never read as
 * current, rewritten or deleted by retention); an old schema without a migration is quarantined and
 * kept. No old schema has a migration today: schemas 1 and 2 were pre-release (storage schema 3).
 */
class SchemaVersionTest {
    private fun savedTable(seed: Long): SavedTable {
        val saved = SavedTable(seed)
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { it.revision >= 12 }
        saved.crash()
        return saved
    }

    private val metaName = TableFiles(TABLE).meta

    private fun metaJson(saved: SavedTable): String =
        checkNotNull(LogFormat.unwrap(LogFormat.FileKind.META, checkNotNull(saved.storage.read(metaName)))).decodeToString()

    private fun putMeta(saved: SavedTable, json: String) = saved.storage.put(metaName, LogFormat.wrap(LogFormat.FileKind.META, json.encodeToByteArray()))

    @Test fun aFutureStorageSchemaIsPreservedNotDeleted() {
        val saved = savedTable(801)
        val future = metaJson(saved).replace("\"schema\":$STORAGE_SCHEMA_VERSION", "\"schema\":${STORAGE_SCHEMA_VERSION + 1}")
            .replace("\"status\":\"ACTIVE\"", "\"status\":\"SUSPENDED\"").replace("\"expiresAt\":null", "\"expiresAt\":${T0 + 1}") + " "
        putMeta(saved, future.trim().dropLast(1) + ",\"newField\":true}")
        val before = saved.storage.snapshot()

        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.FutureSchema>(r, "$r")
        assertEquals(STORAGE_SCHEMA_VERSION + 1, r.version)

        val repo = TableRepository(saved.storage)
        assertEquals(TableRepository.Condition.FUTURE_SCHEMA, repo.list().single().condition)
        assertEquals(emptyList(), repo.sweep(T0 + 1_000L * 3600 * 24 * 365 * 10, TestProfiles.base, TestProfiles.match), "retention never deletes a newer save")
        assertEquals(before, saved.storage.snapshot(), "not a byte changed")

        repo.delete(TABLE, UserConfirmation.CONFIRMED_BY_USER)
        assertEquals(emptyList(), saved.storage.list("tables/"), "only an explicit user action removes it")
    }

    @Test fun aFutureEventSchemaInTheLogIsPreservedNotDeleted() {
        val saved = savedTable(802)
        saved.storage.editRecord(12) { it.copy(matchEventSchema = MATCH_EVENT_SCHEMA_VERSION + 1) }
        val before = saved.storage.snapshot()
        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.FutureSchema>(r, "$r")
        assertTrue("record 12" in r.where)
        assertEquals(before, saved.storage.snapshot())
    }

    /** A newer record schema may add fields the strict current decoder refuses: still FutureSchema, untouched. */
    @Test fun aFutureEventSchemaWithANewFieldIsPreservedNotQuarantined() {
        val saved = savedTable(805)
        saved.storage.editRecordText(12) {
            it.replace("\"matchEventSchema\":$MATCH_EVENT_SCHEMA_VERSION", "\"matchEventSchema\":${MATCH_EVENT_SCHEMA_VERSION + 1}")
                .dropLast(1) + ",\"newField\":true}"
        }
        val before = saved.storage.snapshot()
        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.FutureSchema>(r, "$r")
        assertTrue("record 12" in r.where)
        assertEquals(before, saved.storage.snapshot(), "not a byte changed")
    }

    /** Retention reads the record schemas too: an expired save with a newer event schema is never swept. */
    @Test fun anExpiredSaveWithAFutureEventSchemaIsNotSwept() {
        val saved = SavedTable(806)
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { it.revision >= 12 }
        assertTrue(saved.host.suspend(T0 + 1, T0 + 2))
        saved.crash()
        saved.storage.editRecord(12) { it.copy(matchEventSchema = MATCH_EVENT_SCHEMA_VERSION + 1) }
        val repo = TableRepository(saved.storage)
        assertEquals(TableRepository.Condition.FUTURE_SCHEMA, repo.inspect(TABLE).condition)
        assertEquals(emptyList(), repo.sweep(T0 + 1_000L * 3600 * 24 * 365, TestProfiles.base, TestProfiles.match))
        assertTrue(saved.storage.list(TableFiles(TABLE).dir).isNotEmpty())
    }

    /**
     * Pre-release reset (storage schema 3, 2026-10-16): schemas 1 and 2 were never released and have
     * no migration (their records hash match state format 1, and they hold no room key). A save in
     * either is quarantined and kept byte for byte until the user deletes it; retention never removes it.
     */
    @Test fun preReleaseSchemasOneAndTwoAreQuarantinedAndKept() {
        for ((seed, old) in listOf(807L to 1, 808L to 2)) {
            val saved = savedTable(seed)
            putMeta(saved, metaJson(saved).replace("\"schema\":$STORAGE_SCHEMA_VERSION", "\"schema\":$old"))
            val before = saved.storage.snapshot()
            val r = saved.restore()
            assertIs<SessionHost.RestoreResult.Quarantined>(r, "schema $old: $r")
            assertEquals(QuarantineReason.UNSUPPORTED_OLD_SCHEMA, r.reason)
            assertTrue("storage schema $old" in r.detail, r.detail)
            assertEquals(before.filterKeys { !it.endsWith("/quarantine") }, saved.storage.snapshot().filterKeys { !it.endsWith("/quarantine") }, "schema $old: nothing but the quarantine marker is written")
            assertEquals(emptyList(), TableRepository(saved.storage).sweep(T0 + 1_000L * 3600 * 24 * 365 * 10, TestProfiles.base, TestProfiles.match))
        }
    }

    /** G2: the room key file is part of a table; without it rejoin proofs cannot be checked, so the table is quarantined. */
    @Test fun aMissingOrCorruptRoomKeyQuarantinesTheTable() {
        val missing = savedTable(809)
        missing.storage.delete(TableFiles(TABLE).hostKey)
        val r1 = missing.restore()
        assertIs<SessionHost.RestoreResult.Quarantined>(r1, "$r1")
        assertEquals(QuarantineReason.HOST_KEY_UNREADABLE, r1.reason)

        val corrupt = savedTable(810)
        val bytes = checkNotNull(corrupt.storage.read(TableFiles(TABLE).hostKey))
        corrupt.storage.put(TableFiles(TABLE).hostKey, bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        val r2 = corrupt.restore()
        assertIs<SessionHost.RestoreResult.Quarantined>(r2, "$r2")
        assertEquals(QuarantineReason.HOST_KEY_UNREADABLE, r2.reason)
    }

    /** A restored table answers with the same room key, so pinned members recognise it. */
    @Test fun aRestoredTableKeepsItsRoomKey() {
        val saved = SavedTable(811)
        val before = saved.host.hostKeyFingerprint
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { it.revision >= 3 }
        saved.crash()
        assertEquals(before, saved.restored().host.hostKeyFingerprint)
    }

    @Test fun anOldSchemaWithoutAMigrationIsQuarantinedAndKept() {
        val saved = savedTable(804)
        putMeta(saved, metaJson(saved).replace("\"schema\":$STORAGE_SCHEMA_VERSION", "\"schema\":0"))
        val r = saved.restore()
        assertIs<SessionHost.RestoreResult.Quarantined>(r)
        assertEquals(QuarantineReason.UNSUPPORTED_OLD_SCHEMA, r.reason)
        assertTrue(saved.storage.list(TableFiles(TABLE).dir).size > 3, "files kept")
    }
}
