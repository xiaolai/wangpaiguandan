package cn.wpgd.app.data

/**
 * Win counts (胜场统计) derived from the local history, with no field of its own. Only deals this
 * device played count: a spectator's entry has no team of ours ([HistoryEntry.ownTeamWon] null).
 *
 * - A deal is won by the team of its head finisher (头游): [HistoryEntry.ownTeamWon].
 * - A match is the deals of one (table, match number). It is won by the team that won its
 *   match-ending deal ([HistoryEntry.matchOver]): passing A requires winning that deal (Settlement).
 *   A match with no match-ending deal played here (in progress, left, or ended while watching) is
 *   played but won by nobody: [matchesUndecided].
 * - A v1 entry has no match number, so a table's v1 deals are one run, closed at each match end.
 *
 * Counts cover what the history still holds ([HistoryRepository.MAX_ENTRIES], oldest dropped first).
 */
data class HistoryStats(
    val matchesPlayed: Int,
    val matchesWonByOwn: Int,
    val matchesWonByOpponents: Int,
    val dealsWonByOwn: Int,
    val dealsWonByOpponents: Int,
) {
    init {
        require(minOf(matchesPlayed, matchesWonByOwn, matchesWonByOpponents, dealsWonByOwn, dealsWonByOpponents) >= 0) { "negative count in $this" }
        require(matchesWonByOwn + matchesWonByOpponents <= matchesPlayed) { "more matches won than played in $this" }
    }

    /** Played matches that no team has won here. */
    val matchesUndecided: Int get() = matchesPlayed - matchesWonByOwn - matchesWonByOpponents

    val isEmpty: Boolean get() = matchesPlayed == 0

    companion object {
        val EMPTY = HistoryStats(0, 0, 0, 0, 0)

        /** [entries] oldest first, as [HistoryRepository.entries] returns them. */
        fun from(entries: List<HistoryEntry>): HistoryStats {
            val played = entries.filter { it.ownTeamWon != null }
            val matches = played.groupBy { it.tableId to it.matchNumber }.flatMap { (key, deals) ->
                if (key.second != null) listOf(deals) else splitAtMatchEnds(deals)
            }
            val winners = matches.mapNotNull { deals -> deals.firstOrNull { it.matchOver }?.ownTeamWon }
            return HistoryStats(
                matchesPlayed = matches.size,
                matchesWonByOwn = winners.count { it },
                matchesWonByOpponents = winners.count { !it },
                dealsWonByOwn = played.count { it.ownTeamWon == true },
                dealsWonByOpponents = played.count { it.ownTeamWon == false },
            )
        }

        /** One table's v1 deals, in order: each match-ending deal closes a match. */
        private fun splitAtMatchEnds(deals: List<HistoryEntry>): List<List<HistoryEntry>> {
            val out = mutableListOf<List<HistoryEntry>>()
            var current = mutableListOf<HistoryEntry>()
            for (d in deals) {
                current += d
                if (d.matchOver) {
                    out += current
                    current = mutableListOf()
                }
            }
            if (current.isNotEmpty()) out += current
            return out
        }
    }
}
