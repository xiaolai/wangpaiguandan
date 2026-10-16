package cn.wpgd.app.room

import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.WallClock
import cn.wpgd.app.table.RuleProfiles
import cn.wpgd.app.session.storage.TableRepository
import cn.wpgd.app.session.storage.TableStatus
import cn.wpgd.app.session.storage.UserConfirmation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The hosted tables saved on this device (H3, H6, H7; GDN-090): what can be restored, until when, and
 * explicit deletion. Expired suspended or ended tables are swept on [list]; nothing else is ever
 * removed without the user's confirmation ([prepareDelete], [delete]). A sweep deletes only a table
 * that recovers cleanly under [profiles].
 */
class SavedTables(
    private val repository: TableRepository,
    private val rooms: RoomStore,
    private val identity: IdentityRepository,
    private val clock: WallClock,
    private val profiles: RuleProfiles,
) {
    enum class Kind {
        /** Readable, active (left by an accidental exit) or suspended: can be restored. */
        RESTORABLE,

        /** Quarantined or unreadable (H6): kept, never played; only deletion is offered. */
        DAMAGED,

        /** Saved by a newer app version (H7): kept untouched. */
        NEWER_VERSION,
    }

    data class Entry(
        val tableId: String,
        val tableName: String?,
        val kind: Kind,
        val status: TableStatus?,
        /** Wall-clock time of the last status change (suspend), when known. */
        val savedAt: Long?,
        /** When a suspended table is deleted automatically; null for an active one (it never expires). */
        val expiresAt: Long?,
    )

    /**
     * Saved tables, newest first, except [running] (a table being hosted right now is not offered
     * again) and ended tables (they cannot be resumed). Sweeps expired ones first.
     */
    fun list(running: String? = null): List<Entry> {
        val swept = repository.sweep(clock.nowMillis(), profiles.base, profiles.match)
        swept.forEach { forgetLocal(it) }
        return repository.list().filter { it.tableId != running }.mapNotNull { l ->
            val kind = when (l.condition) {
                TableRepository.Condition.READABLE -> Kind.RESTORABLE
                TableRepository.Condition.QUARANTINED, TableRepository.Condition.UNREADABLE -> Kind.DAMAGED
                TableRepository.Condition.FUTURE_SCHEMA -> Kind.NEWER_VERSION
            }
            if (kind == Kind.RESTORABLE && l.meta?.status == TableStatus.ENDED) return@mapNotNull null
            Entry(l.tableId, rooms.load(l.tableId)?.tableName, kind, l.meta?.status, l.meta?.statusAt, l.meta?.expiresAt)
        }.sortedByDescending { it.savedAt ?: 0 }
    }

    /** What deleting [tableId] loses, to show before the user confirms. */
    fun prepareDelete(tableId: String): DeletePrompt = DeletePrompt(tableId, rooms.load(tableId)?.tableName)

    /** The same, from an [entry] already read off the main thread: no storage access. */
    fun prepareDelete(entry: Entry): DeletePrompt = DeletePrompt(entry.tableId, entry.tableName)

    /** Removes the save, its settings and this device's rejoin ticket together. */
    fun delete(confirmation: DeleteConfirmation) {
        repository.delete(confirmation.tableId, UserConfirmation.CONFIRMED_BY_USER)
        forgetLocal(confirmation.tableId)
    }

    /**
     * Whether [entry] (listed earlier) can still be restored now: a suspended table whose expiry
     * passed while the list was shown cannot (the owner would refuse it).
     */
    fun restorableNow(entry: Entry): Boolean =
        entry.kind == Kind.RESTORABLE && entry.expiresAt.let { it == null || it > clock.nowMillis() }

    /** Milliseconds until the first of [entries] expires (0 when one already has); null when none expires. */
    fun untilNextExpiry(entries: List<Entry>): Long? =
        entries.mapNotNull { it.expiresAt }.minOrNull()?.let { maxOf(0L, it - clock.nowMillis()) }

    private fun forgetLocal(tableId: String) {
        rooms.forget(tableId)
        identity.forgetRejoin(tableId)
    }
}

/**
 * Shown before a saved table is deleted: the save and everyone's way back to their seats are lost.
 * [confirm] is the only way to obtain a [DeleteConfirmation].
 */
data class DeletePrompt(val tableId: String, val tableName: String?) {
    fun confirm(): DeleteConfirmation = DeleteConfirmation(tableId)
}

class DeleteConfirmation internal constructor(val tableId: String)

/**
 * The saved-tables screen's deletion: the open confirmation [prompt], the table whose deletion is
 * running ([inFlight]), and whether the last attempt [failed]. While one runs, nothing else may be
 * opened, cancelled or confirmed, and its completion applies only to its own prompt.
 */
data class DeleteState(val prompt: DeletePrompt? = null, val inFlight: String? = null, val failed: Boolean = false) {
    val busy: Boolean get() = inFlight != null

    fun open(p: DeletePrompt): DeleteState = if (busy) this else DeleteState(prompt = p)

    fun cancel(): DeleteState = if (busy) this else DeleteState()

    /** The confirmation to run, and the state while it runs; null when there is nothing to start. */
    fun start(): Pair<DeleteConfirmation, DeleteState>? {
        val p = prompt ?: return null
        if (busy) return null
        return p.confirm() to copy(inFlight = p.tableId, failed = false)
    }

    fun finished(tableId: String, ok: Boolean): DeleteState = when {
        inFlight != tableId -> this
        ok -> DeleteState()
        else -> copy(inFlight = null, failed = true)
    }
}

/**
 * What the host is told before leaving a hosted table (H9): suspending keeps the table for
 * [retentionMs], until [expiresAt] (wall clock); ending closes it for everyone and deletes the save.
 * The expiry stored on suspend is exactly the one shown here.
 */
data class LeavePrompt(val tableId: String, val saved: Boolean, val retentionMs: Long, val expiresAt: Long) {
    fun suspend(): LeaveChoice = LeaveChoice.Suspend(this)
    fun end(): LeaveChoice = LeaveChoice.End(this)
}

sealed interface LeaveChoice {
    val prompt: LeavePrompt

    class Suspend internal constructor(override val prompt: LeavePrompt) : LeaveChoice
    class End internal constructor(override val prompt: LeavePrompt) : LeaveChoice
}

/**
 * What the saved-tables screen shows (H3): the list is read on [io], never during composition, and a
 * storage failure is a state with a retry, never a crash.
 */
sealed interface SavedTablesLoad {
    data object Loading : SavedTablesLoad
    data class Loaded(val entries: List<SavedTables.Entry>) : SavedTablesLoad
    data object Failed : SavedTablesLoad

    companion object {
        suspend fun of(tables: SavedTables, io: CoroutineDispatcher): SavedTablesLoad =
            attempt(io) { tables.list() }?.let(::Loaded) ?: Failed

        /** Deletes on [io]; false when storage failed (the screen says so and offers a retry). */
        suspend fun delete(tables: SavedTables, confirmation: DeleteConfirmation, io: CoroutineDispatcher): Boolean =
            attempt(io) { tables.delete(confirmation) } != null

        private suspend fun <T : Any> attempt(io: CoroutineDispatcher, work: () -> T): T? = try {
            withContext(io) { work() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
