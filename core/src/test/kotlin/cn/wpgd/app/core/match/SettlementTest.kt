package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Seat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** match-v1 sections 1 and 2 (GDN-040): every finishing order of four seats, gains, caps and placement labels. */
class SettlementTest {
    private val settings = m1Settings()

    private fun permutations(items: List<Seat>): List<List<Seat>> =
        if (items.size <= 1) listOf(items) else items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }

    /** The order the deal records: it stops at a double-up (M1.4), otherwise after three finishers. */
    private fun recorded(full: List<Seat>): List<Seat> = if (full[1] == full[0].partner) full.take(2) else full.take(3)

    @Test fun allTwentyFourFinishingOrdersSettleByThePartnersPlace() {
        val orders = permutations(Seat.entries)
        assertEquals(24, orders.size)
        val byGain = HashMap<Int, Int>()
        val levels = mapOf(Team.NORTH_SOUTH to 5, Team.EAST_WEST to 7)
        val failures = Team.entries.associateWith { 0 }
        for (full in orders) {
            val r = Settlement.settle(3, 5, recorded(full), levels, failures, settings)
            val winner = full[0].team
            // Independent expectation (M2.2): partner 2nd +3, 3rd +2, 4th +1.
            val expectedGain = mapOf(2 to 3, 3 to 2, 4 to 1).getValue(full.indexOf(full[0].partner) + 1)
            assertEquals(winner, r.winningTeam, "$full")
            assertEquals(expectedGain, r.gain, "$full")
            assertEquals(levels.getValue(winner) + expectedGain, r.outcome(winner).levelAfter, "$full")
            assertEquals(levels.getValue(winner.other), r.outcome(winner.other).levelAfter, "$full: M2.3 the other team is unchanged")
            assertEquals(r.outcome(winner).levelAfter, r.nextLevel, "$full: M2.5")
            assertNull(r.matchWinner)
            assertEquals("endEarly", r.completionPolicy, "A36: the policy is labelled")
            assertPlacements(full, r)
            byGain.merge(expectedGain, 1, Int::plus)
        }
        assertEquals(mapOf(3 to 8, 2 to 8, 1 to 8), byGain)
    }

    private fun assertPlacements(full: List<Seat>, r: DealResult) {
        assertEquals(Seat.entries.toSet(), r.placements.map { it.seat }.toSet(), "$full")
        val doubleUp = full[1] == full[0].partner
        if (doubleUp) {
            assertEquals(Completion.DOUBLE_UP_ENDED_EARLY, r.completion)
            assertEquals(full.take(2), r.finishOrder)
            for (seat in full.drop(2)) {
                val p = r.placements.single { it.seat == seat }
                // A36: the two 双下 places are inferred (3–4), never presented as played.
                assertEquals(Placement(seat, 3, 4, PlaceBasis.INFERRED_DOUBLE_DOWN), p, "$full")
            }
        } else {
            assertEquals(Completion.THREE_FINISHED, r.completion)
            assertEquals(Placement(full[3], 4, 4, PlaceBasis.LAST_BY_ELIMINATION), r.placements.single { it.seat == full[3] })
            assertEquals(full[3], r.lastSeat)
        }
        r.finishOrder.forEachIndexed { i, s -> assertEquals(Placement(s, i + 1, i + 1, PlaceBasis.PLAYED), r.placements.single { it.seat == s }) }
    }

    @Test fun gainsStopAtA() {
        val failures = Team.entries.associateWith { 0 }
        // M2.4: Q +3 is A, K +2 is A, A stays A when it does not pass.
        for ((start, order) in listOf(
            12 to listOf(Seat.SOUTH, Seat.NORTH), 13 to listOf(Seat.SOUTH, Seat.NORTH),
            13 to listOf(Seat.SOUTH, Seat.EAST, Seat.NORTH), 11 to listOf(Seat.SOUTH, Seat.EAST, Seat.WEST),
        )) {
            val r = Settlement.settle(9, start, order, mapOf(Team.NORTH_SOUTH to start, Team.EAST_WEST to 2), failures, settings)
            assertEquals(minOf(Ranks.ACE, start + r.gain), r.outcome(Team.NORTH_SOUTH).levelAfter, "from $start")
            assertTrue(r.outcome(Team.NORTH_SOUTH).levelAfter <= Ranks.ACE)
            assertNull(r.matchWinner, "reaching A by gain is not passing A (HA17:294)")
        }
    }

    // match-v1 M1.4: a deal ends only after three finishers or an early double-up; a double-up ends it (D38), so
    // no third seat can follow two partners.
    @Test fun settlementRejectsAnOrderThatCannotEndADeal() {
        val levels = Team.entries.associateWith { 2 }
        val failures = Team.entries.associateWith { 0 }
        for (bad in listOf(
            listOf(Seat.SOUTH), listOf(Seat.SOUTH, Seat.EAST), listOf(Seat.SOUTH, Seat.SOUTH, Seat.EAST),
            listOf(Seat.SOUTH, Seat.NORTH, Seat.EAST), listOf(Seat.EAST, Seat.WEST, Seat.NORTH),
        )) {
            kotlin.test.assertFailsWith<IllegalArgumentException>("$bad") { Settlement.settle(1, 2, bad, levels, failures, settings) }
        }
    }

    // match-v1 M2.2: the gain table (partner 二游/三游/下游) is profile data.
    @Test fun gainsComeFromTheProfile() {
        val custom = settingsWith("level.gainPartnerSecond" to "4", "level.gainPartnerThird" to "3", "level.gainPartnerFourth" to "2")
        val levels = Team.entries.associateWith { 2 }
        val failures = Team.entries.associateWith { 0 }
        assertEquals(4, Settlement.settle(1, 2, listOf(Seat.EAST, Seat.WEST), levels, failures, custom).gain)
        assertEquals(3, Settlement.settle(1, 2, listOf(Seat.EAST, Seat.SOUTH, Seat.WEST), levels, failures, custom).gain)
        assertEquals(2, Settlement.settle(1, 2, listOf(Seat.EAST, Seat.SOUTH, Seat.NORTH), levels, failures, custom).gain)
    }

    // match-v1 M2.1–M2.5: the reducer applies the same settlement, levels and next deal level.
    @Test fun reducerSettlesAPlayedDealExactlyAsTheSettlementFunction() {
        val r = m1Reducer()
        for (seed in 1L..5L) {
            val ended = playMatch(r, seed, stopWhen = { it.phase == MatchPhase.DEAL_ENDED })
            val settled = r.reduce(ended, MatchCommand.Settle).accepted()
            val expected = Settlement.settle(ended.dealNumber, ended.dealLevel, ended.deal!!.finishOrder, ended.levels, ended.aFailures, r.settings)
            assertEquals(listOf<MatchEvent>(MatchEvent.DealSettled(settled.state.revision, expected)), settled.events)
            assertEquals(expected.teams.associate { it.team to it.levelAfter }, settled.state.levels)
            assertEquals(expected.nextLevel, settled.state.dealLevel)
            assertEquals(MatchPhase.AWAITING_DEAL, settled.state.phase)
        }
    }
}
