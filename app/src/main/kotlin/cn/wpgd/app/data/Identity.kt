package cn.wpgd.app.data

import java.security.SecureRandom

/** This installation's local identity: a random id, never derived from the device or an account. */
data class LocalIdentity(val id: String)

/** The statement a reset confirmation must carry (GDN-008): the user was told what is lost. */
enum class ConfirmationStatement(val messageKey: String) {
    REJOIN_ACCESS_TO_CURRENT_TABLES_WILL_BE_LOST("identity.reset.rejoin_access_lost"),
}

/**
 * What the reset screen must show before the user confirms: the statement that rejoin access to
 * current tables will be lost, and how many tables that is now. The prompt is bound to the exact
 * [tables], so replacing one table with another (same count) still outdates it.
 */
data class IdentityResetPrompt(val statement: ConfirmationStatement, val tables: Set<String>) {
    val tablesLosingRejoin: Int get() = tables.size

    /** The user accepted this prompt. The only way to obtain a confirmation. */
    fun confirm(): IdentityResetConfirmation = IdentityResetConfirmation(statement, tables)
}

/** Proof that the user confirmed a specific [IdentityResetPrompt]. */
class IdentityResetConfirmation internal constructor(val statement: ConfirmationStatement, val tables: Set<String>) {
    val tablesLosingRejoin: Int get() = tables.size
}

sealed interface IdentityResetResult {
    data object Reset : IdentityResetResult

    /** No confirmation was given: nothing changed. */
    data object ConfirmationRequired : IdentityResetResult

    /** The confirmation was for another situation (a table was added or removed since): ask again. */
    data object ConfirmationOutdated : IdentityResetResult
}

/**
 * A rejoin ticket (G2): the member's capability and the fingerprint of the host key that issued it.
 * The capability is never sent; the host key is pinned, so a host showing another key gets nothing.
 */
data class RejoinTicket(val capability: String, val hostKey: String) {
    init {
        require(KEY.matches(capability) && KEY.matches(hostKey)) { "a ticket holds two base64url values" }
    }

    private companion object {
        val KEY = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * The local identity and the rejoin tickets (table id → [RejoinTicket]) it holds, in their own store.
 * Resetting needs an [IdentityResetConfirmation] that states rejoin access to current tables will be
 * lost and names exactly the current tables; it removes identity and tickets only (preferences, nickname
 * and history are other stores).
 */
class IdentityRepository(private val store: KeyValueStore, private val random: SecureRandom = SecureRandom()) {
    @Synchronized
    fun identity(): LocalIdentity {
        store.get(ID)?.let { return LocalIdentity(it) }
        val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        store.put(ID, id)
        return LocalIdentity(id)
    }

    @Synchronized
    fun rememberRejoin(tableId: String, capability: String, hostKey: String) {
        require(HistoryEntry.TABLE_ID.matches(tableId)) { "bad table id" }
        val ticket = RejoinTicket(capability, hostKey)
        store.put(TICKET + tableId, "$TICKET_FORMAT|${ticket.capability}|${ticket.hostKey}")
    }

    @Synchronized
    fun forgetRejoin(tableId: String) = store.remove(TICKET + tableId)

    @Synchronized
    fun rejoinTables(): Set<String> = store.keys().filter { it.startsWith(TICKET) }.map { it.removePrefix(TICKET) }.toSet()

    /**
     * The ticket for [tableId], or null. A ticket stored before host keys were pinned (a bare
     * capability, pre-release) has no pin, so it is not used: the member asks to join afresh.
     */
    @Synchronized
    fun rejoinTicket(tableId: String): RejoinTicket? {
        val parts = store.get(TICKET + tableId)?.split('|') ?: return null
        if (parts.size != 3 || parts[0] != TICKET_FORMAT) return null
        return try {
            RejoinTicket(parts[1], parts[2])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Synchronized
    fun rejoinCapability(tableId: String): String? = rejoinTicket(tableId)?.capability

    @Synchronized
    fun prepareReset(): IdentityResetPrompt = IdentityResetPrompt(ConfirmationStatement.REJOIN_ACCESS_TO_CURRENT_TABLES_WILL_BE_LOST, rejoinTables())

    @Synchronized
    fun reset(confirmation: IdentityResetConfirmation?): IdentityResetResult {
        if (confirmation == null || confirmation.statement != ConfirmationStatement.REJOIN_ACCESS_TO_CURRENT_TABLES_WILL_BE_LOST) {
            return IdentityResetResult.ConfirmationRequired
        }
        if (confirmation.tables != rejoinTables()) return IdentityResetResult.ConfirmationOutdated
        store.clear()
        return IdentityResetResult.Reset
    }

    private companion object {
        const val ID = "id"
        const val TICKET = "rejoin."
        const val TICKET_FORMAT = "t2"
    }
}
