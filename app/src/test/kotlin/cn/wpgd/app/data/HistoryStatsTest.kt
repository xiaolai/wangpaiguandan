package cn.wpgd.app.data

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.match.MatchSettings
import cn.wpgd.app.core.match.Settlement
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Win counts (胜场统计) derived from the stored history only: no new stored field. */
class HistoryStatsTest {
    private var clock = 0L

    /** One played deal: [won] is this device's team winning it (its head finisher, 头游, is on our team). */
    private fun deal(n: Int, won: Boolean, matchOver: Boolean = false, match: Int? = 1, table: String = "solo-ab12", gain: Int = 2) =
        HistoryEntry(table, match, n, 2, won, gain, matchOver, ++clock * 1000)

    /** One watched deal: a spectator has no team, so its entry carries no winner of ours. */
    private fun watched(n: Int, matchOver: Boolean = false, table: String = "room-w1") =
        HistoryEntry(table, 1, n, 2, null, 2, matchOver, ++clock * 1000)

    @Test fun emptyHistoryCountsNothing() {
        assertEquals(HistoryStats(0, 0, 0, 0, 0), HistoryStats.from(emptyList()))
        assertEquals(0, HistoryStats.EMPTY.matchesUndecided)
        assertTrue(HistoryStats.EMPTY.isEmpty)
    }

    @Test fun aMatchInProgressIsPlayedButWonByNobody() {
        val stats = HistoryStats.from(listOf(deal(1, won = true), deal(2, won = false), deal(3, won = true)))
        assertEquals(HistoryStats(matchesPlayed = 1, matchesWonByOwn = 0, matchesWonByOpponents = 0, dealsWonByOwn = 2, dealsWonByOpponents = 1), stats)
        assertEquals(1, stats.matchesUndecided)
    }

    @Test fun aFinishedMatchGoesToTheTeamThatWonItsLastDeal() {
        // Passing A needs the deal won (Settlement: passedA implies won), so the team that won the
        // match-ending deal is the match winner.
        val ours = listOf(deal(1, won = true), deal(2, won = true, matchOver = true))
        val theirs = listOf(deal(1, won = true, match = 2), deal(2, won = false, match = 2), deal(3, won = false, matchOver = true, match = 2))
        val stats = HistoryStats.from(ours + theirs)
        assertEquals(HistoryStats(matchesPlayed = 2, matchesWonByOwn = 1, matchesWonByOpponents = 1, dealsWonByOwn = 3, dealsWonByOpponents = 2), stats)
        assertEquals(0, stats.matchesUndecided)
    }

    @Test fun theMatchWinnerAlwaysWonTheEndingDeal() {
        // Guards the derivation above against a change in Settlement: if a team could pass A without
        // winning the deal, matchOver plus ownTeamWon would no longer name the match winner.
        val settings = MatchSettings.of(TestProfiles.profiles.match)
        val levels = mapOf(Team.NORTH_SOUTH to Ranks.ACE, Team.EAST_WEST to Ranks.ACE)
        val failures = mapOf(Team.NORTH_SOUTH to 0, Team.EAST_WEST to 0)
        val doubleUp = Settlement.settle(9, Ranks.ACE, listOf(Seat.SOUTH, Seat.NORTH), levels, failures, settings)
        assertEquals(doubleUp.winningTeam, doubleUp.matchWinner)
        val single = Settlement.settle(9, Ranks.ACE, listOf(Seat.EAST, Seat.SOUTH, Seat.NORTH), levels, failures, settings)
        assertTrue(single.matchWinner == null || single.matchWinner == single.winningTeam)
        // Recorded from either side, the ending entry says who won the match.
        val ours = HistoryEntry.of("solo-ab12", 1, doubleUp, Seat.SOUTH, 1)
        val theirs = HistoryEntry.of("solo-ab12", 1, doubleUp, Seat.EAST, 1)
        assertEquals(1, HistoryStats.from(listOf(ours)).matchesWonByOwn)
        assertEquals(1, HistoryStats.from(listOf(theirs)).matchesWonByOpponents)
    }

    @Test fun aDoubleUpDealIsOneDealWonAndCanEndTheMatch() {
        // 双上: the largest gain, still one deal won by the head finisher's team.
        val stats = HistoryStats.from(listOf(deal(1, won = false), deal(2, won = true, gain = 3), deal(3, won = true, gain = 3, matchOver = true)))
        assertEquals(HistoryStats(matchesPlayed = 1, matchesWonByOwn = 1, matchesWonByOpponents = 0, dealsWonByOwn = 2, dealsWonByOpponents = 1), stats)
    }

    @Test fun interruptedMatchesAreNeverCountedAsWon() {
        // Left after deal 2 (no match-ending deal recorded), then a rematch at the same table, then
        // another table abandoned after one deal.
        val left = listOf(deal(1, won = true), deal(2, won = true))
        val rematch = listOf(deal(1, won = true, match = 2), deal(2, won = true, matchOver = true, match = 2))
        val abandoned = listOf(deal(1, won = true, table = "room-x9"))
        val stats = HistoryStats.from(left + rematch + abandoned)
        assertEquals(HistoryStats(matchesPlayed = 3, matchesWonByOwn = 1, matchesWonByOpponents = 0, dealsWonByOwn = 5, dealsWonByOpponents = 0), stats)
        assertEquals(2, stats.matchesUndecided)
    }

    @Test fun watchedDealsCountForNoTeam() {
        // A spectator has no team: its deals and matches are left out, even a watched match end.
        val stats = HistoryStats.from(listOf(watched(1), watched(2, matchOver = true), deal(1, won = true)))
        assertEquals(HistoryStats(matchesPlayed = 1, matchesWonByOwn = 0, matchesWonByOpponents = 0, dealsWonByOwn = 1, dealsWonByOpponents = 0), stats)
        // Seated after watching part of a match: the match counts, with only the deals played.
        val joined = HistoryStats.from(listOf(watched(1, table = "room-j1"), deal(2, won = false, matchOver = true, table = "room-j1")))
        assertEquals(HistoryStats(matchesPlayed = 1, matchesWonByOwn = 0, matchesWonByOpponents = 1, dealsWonByOwn = 0, dealsWonByOpponents = 1), joined)
    }

    @Test fun v1EntriesWithoutAMatchNumberSplitAtEachMatchEnd() {
        // History format v1 had no match number: a table's deals are one run, and each match-ending
        // deal closes a match. The deals after the last one are a match not yet decided.
        val v1 = listOf(
            deal(1, won = true, match = null), deal(2, won = true, matchOver = true, match = null),
            deal(1, won = false, match = null), deal(2, won = false, matchOver = true, match = null),
            deal(1, won = true, match = null),
        )
        val stats = HistoryStats.from(v1)
        assertEquals(HistoryStats(matchesPlayed = 3, matchesWonByOwn = 1, matchesWonByOpponents = 1, dealsWonByOwn = 3, dealsWonByOpponents = 2), stats)
        assertNull(v1.first().matchNumber)
    }

    @Test fun readsTheSameStatsBackFromTheStore() {
        val store = InMemoryKeyValueStore()
        listOf(deal(1, won = true), deal(2, won = true, matchOver = true), deal(1, won = false, match = 2)).forEach(HistoryRepository(store)::append)
        val stats = HistoryStats.from(HistoryRepository(store).entries())
        assertEquals(HistoryStats(matchesPlayed = 2, matchesWonByOwn = 1, matchesWonByOpponents = 0, dealsWonByOwn = 2, dealsWonByOpponents = 1), stats)
    }
}
