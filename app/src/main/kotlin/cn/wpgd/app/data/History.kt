package cn.wpgd.app.data

import cn.wpgd.app.core.match.DealResult
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat

/** One settled deal, as kept in local history. Public table facts only: no cards, no seeds. */
data class HistoryEntry(
    val tableId: String,
    /**
     * Which match of the table (from 1); deal numbers restart in every match, so a deal is named by
     * (table, match number, deal number). Null only for an entry recorded before match numbers
     * existed (history format v1).
     */
    val matchNumber: Int?,
    val dealNumber: Int,
    val dealLevel: Int,
    /** True when this device's team won the deal; null for a spectator. */
    val ownTeamWon: Boolean?,
    val gain: Int,
    val matchOver: Boolean,
    val settledAtMillis: Long,
) {
    init {
        require(TABLE_ID.matches(tableId)) { "table id must match ${TABLE_ID.pattern}" }
        require(matchNumber == null || matchNumber >= 1) { "match number $matchNumber" }
    }

    companion object {
        val TABLE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun of(tableId: String, matchNumber: Int, result: DealResult, seat: Seat?, settledAtMillis: Long) = HistoryEntry(
            tableId = tableId,
            matchNumber = matchNumber,
            dealNumber = result.dealNumber,
            dealLevel = result.dealLevel,
            ownTeamWon = seat?.let { it.team == result.winningTeam },
            gain = result.gain,
            matchOver = result.matchWinner != null,
            settledAtMillis = settledAtMillis,
        )
    }
}

/**
 * Local deal history in its own store (GDN-008). [deleteAll] removes history only; preferences,
 * nickname and identity live in other stores and are untouched. Bounded to [MAX_ENTRIES], oldest
 * dropped first. Entries that do not parse are skipped (and counted), never guessed.
 */
class HistoryRepository(private val store: KeyValueStore) {
    var unreadable = 0
        private set

    @Synchronized
    fun append(entry: HistoryEntry) {
        // The same settled deal seen again (a republish, a rejoin, a restore) is kept once. A deal is named
        // by (table, match number, deal number): a rematch numbers its deals from 1 again under the next
        // match number, so its deal 1 is a new entry even for a player who missed the match boundary.
        if (entries().any { it.tableId == entry.tableId && it.matchNumber == entry.matchNumber && it.dealNumber == entry.dealNumber }) return
        // Above every stored entry as well as the counter: a missing, malformed or stale counter (a crash
        // between the entry write and the counter write) never hands out a number an entry still holds.
        val highest = entryKeys().mapNotNull { it.removePrefix(PREFIX).toLongOrNull() }.maxOrNull()
        val seq = maxOf(store.get(NEXT)?.toLongOrNull() ?: 0L, highest?.plus(1) ?: 0L)
        store.put(key(seq), encode(entry))
        store.put(NEXT, (seq + 1).toString())
        val keys = entryKeys()
        if (keys.size > MAX_ENTRIES) keys.take(keys.size - MAX_ENTRIES).forEach(store::remove)
    }

    /** Oldest first. */
    @Synchronized
    fun entries(): List<HistoryEntry> {
        var bad = 0
        val out = entryKeys().mapNotNull { k -> store.get(k)?.let(::decode) ?: run { bad++; null } }
        unreadable = bad
        return out
    }

    @Synchronized
    fun deleteAll() = store.clear()

    private fun entryKeys(): List<String> = store.keys().filter { it.startsWith(PREFIX) }.sortedBy { it.removePrefix(PREFIX).toLongOrNull() ?: Long.MAX_VALUE }

    private fun key(seq: Long) = "$PREFIX$seq"

    /**
     * v2 always carries a match number. An entry without one (read from v1, appended again) is written
     * in v1, the one format that has none, so it reads back exactly instead of being refused (audit #10).
     */
    private fun encode(e: HistoryEntry): String {
        val tail = listOf(e.dealNumber, e.dealLevel, e.ownTeamWon?.toString() ?: "-", e.gain, e.matchOver, e.settledAtMillis)
        val head = if (e.matchNumber == null) listOf(VERSION_1, e.tableId) else listOf(VERSION, e.tableId, e.matchNumber)
        return (head + tail).joinToString(SEP)
    }

    /** Format v2 carries the match number; a v1 entry (before match numbers) reads with none, never a guessed one. */
    private fun decode(s: String): HistoryEntry? {
        val v = s.split(SEP)
        val f = when {
            v.size == 9 && v[0] == VERSION -> v
            v.size == 8 && v[0] == VERSION_1 -> listOf(v[0], v[1], "-") + v.drop(2)
            else -> return null
        }
        return try {
            HistoryEntry(
                tableId = f[1],
                matchNumber = if (f[2] == "-" && v[0] == VERSION_1) null else f[2].toInt(),
                dealNumber = f[3].toInt(),
                dealLevel = f[4].toInt(),
                ownTeamWon = when (f[5]) { "true" -> true; "false" -> false; "-" -> null; else -> return null },
                gain = f[6].toInt(),
                matchOver = when (f[7]) { "true" -> true; "false" -> false; else -> return null },
                settledAtMillis = f[8].toLong(),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private const val VERSION = "v2"
        private const val VERSION_1 = "v1"
        private const val SEP = "|"
        private const val PREFIX = "deal."
        private const val NEXT = "next"
    }
}
