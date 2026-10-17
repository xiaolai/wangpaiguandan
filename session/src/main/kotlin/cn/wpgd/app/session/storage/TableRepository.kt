package cn.wpgd.app.session.storage

import cn.wpgd.app.core.rules.RuleProfile

/** Proof that the user explicitly asked for a deletion (after a confirmation naming what is lost). */
enum class UserConfirmation { CONFIRMED_BY_USER }

/**
 * Every saved table in one [Storage]: listing, retention and deletion (GDN-090, audit A12, A22).
 *
 * - [sweep] removes a table only when it is SUSPENDED or ENDED, its stated `expiresAt` has come,
 *   and it passes every check recovery makes under the given rule profiles (checkpoint, replay and
 *   state invariants included), so a table recovery would quarantine is kept. An ACTIVE table
 *   (being played, or left by an accidental exit) never expires.
 * - An unreadable, quarantined or newer-schema table (in its meta or any committed record) is never
 *   removed except by [delete].
 * - [delete] removes the log, checkpoints, high-water mark, members (the capabilities) and meta
 *   together. It first writes a tombstone; a crash part-way leaves the tombstone, and the next
 *   [list] or [sweep] finishes the deletion the user asked for.
 */
class TableRepository(private val storage: Storage) {
    enum class Condition { READABLE, QUARANTINED, UNREADABLE, FUTURE_SCHEMA }

    data class Listing(val tableId: String, val condition: Condition, val meta: TableMeta?)

    fun tableIds(): List<String> = storage.list("tables/").mapNotNull(TableFiles::idOf).distinct()

    fun list(): List<Listing> {
        finishInterruptedDeletes()
        return tableIds().map(::inspect)
    }

    /** The table's condition by the same preliminary checks recovery makes ([TableRecovery.preliminary]). */
    fun inspect(tableId: String): Listing {
        val files = TableFiles(tableId)
        return when (val p = TableRecovery.preliminary(storage, files, storage.list(files.dir))) {
            is TableRecovery.Preliminary.Passed -> Listing(tableId, Condition.READABLE, p.meta)
            is TableRecovery.Preliminary.Stop -> {
                val condition = when {
                    p.marked -> Condition.QUARANTINED
                    p.verdict.result is RecoveryResult.FutureSchema -> Condition.FUTURE_SCHEMA
                    else -> Condition.UNREADABLE
                }
                Listing(tableId, condition, p.meta)
            }
        }
    }

    /**
     * Deletes the tables whose stated expiry has come and that recover cleanly under [base] and
     * [match] (without writing anything). Returns their ids.
     */
    fun sweep(now: Long, base: RuleProfile, match: RuleProfile): List<String> {
        finishInterruptedDeletes()
        val deleted = ArrayList<String>()
        for (l in tableIds().map(::inspect)) {
            val meta = l.meta ?: continue
            val expires = meta.expiresAt ?: continue
            if (l.condition != Condition.READABLE || meta.status == TableStatus.ACTIVE || now < expires) continue
            val files = TableFiles(l.tableId)
            if (TableRecovery.examine(storage, files, storage.list(files.dir), base, match) !is TableRecovery.Examined.Ready) continue
            remove(files)
            deleted += l.tableId
        }
        return deleted
    }

    /** Explicit user deletion: removes every file of the table, whatever its condition. */
    fun delete(tableId: String, confirmation: UserConfirmation) {
        check(confirmation == UserConfirmation.CONFIRMED_BY_USER)
        remove(TableFiles(tableId))
    }

    private fun remove(files: TableFiles) {
        storage.replace(files.tombstone, LogFormat.wrap(LogFormat.FileKind.TOMBSTONE, files.tableId.encodeToByteArray()))
        for (name in storage.list(files.dir)) if (name != files.tombstone) storage.delete(name)
        storage.delete(files.tombstone)
    }

    private fun finishInterruptedDeletes() {
        for (id in tableIds()) {
            val files = TableFiles(id)
            if (files.tombstone in storage.list(files.dir)) remove(files)
        }
    }
}
