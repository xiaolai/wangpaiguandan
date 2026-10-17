package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** match-v1 section 3 (GDN-041, audits A03 and A04): wins, losses, interruptions and resets. */
class PassingATest {
    private val A = Ranks.ACE
    private val NS = Team.NORTH_SOUTH
    private val EW = Team.EAST_WEST

    private fun settle(
        order: List<cn.wpgd.app.core.state.Seat>,
        dealLevel: Int,
        ns: Int,
        ew: Int,
        nsFailures: Int = 0,
        ewFailures: Int = 0,
        settings: MatchSettings = m1Settings(),
    ) = Settlement.settle(7, dealLevel, order, mapOf(NS to ns, EW to ew), mapOf(NS to nsFailures, EW to ewFailures), settings)

    // match-v1 M3.2 (D34), M3.3: passing A with partner 二游 or 三游 ends the match.
    @Test fun teamAtAPassesWithPartnerSecondOrThird() {
        for (order in listOf(listOf(S, N), listOf(S, E, N), listOf(N, W, S))) {
            val r = settle(order, A, ns = A, ew = 5)
            assertEquals(NS, r.matchWinner, "$order")
            assertTrue(r.outcome(NS).passedA && r.outcome(NS).aAttempt)
            assertFalse(r.outcome(NS).failedA)
        }
    }

    // match-v1 M3.4, M3.5: partner 下游 is a failed attempt; the team stays at A.
    @Test fun partnerLastIsAFailureAndTheTeamStaysAtA() {
        val r = settle(listOf(S, E, W), A, ns = A, ew = 5, nsFailures = 1)
        assertNull(r.matchWinner)
        assertEquals(A, r.outcome(NS).levelAfter)
        assertTrue(r.outcome(NS).failedA)
        assertEquals(2, r.outcome(NS).failuresAfter)
        assertEquals(A, r.nextLevel, "NS took 上游, so the next deal is at A again")
    }

    // match-v1 M3.5 (D36): an A deal won by the opponents is a failed attempt.
    @Test fun anADealWonByTheOpponentsIsAFailureByDefault() {
        val r = settle(listOf(E, S, W), A, ns = A, ew = 5)
        assertTrue(r.outcome(NS).failedA)
        assertEquals(1, r.outcome(NS).failuresAfter)
        assertEquals(A, r.outcome(NS).levelAfter)
        assertEquals(7, r.outcome(EW).levelAfter, "partner third: +2")
        assertEquals(7, r.nextLevel)
        val lenient = settle(listOf(E, S, W), A, ns = A, ew = 5, settings = settingsWith("passingA.opponentWinCounts" to "false"))
        assertFalse(lenient.outcome(NS).failedA)
        assertEquals(0, lenient.outcome(NS).failuresAfter)
    }

    @Test fun winningTheOtherTeamsDealDoesNotPassA() {
        // A04: NS is at A but the deal is at EW's level 7. A double-up is not a pass.
        val r = settle(listOf(S, N), 7, ns = A, ew = 7, nsFailures = 1)
        assertNull(r.matchWinner)
        val ns = r.outcome(NS)
        assertFalse(ns.aAttempt || ns.passedA || ns.failedA)
        assertTrue(ns.interruption)
        assertEquals(A, ns.levelAfter)
        assertEquals(1, ns.failuresAfter, "M3.6: an interruption neither counts nor resets")
        assertEquals(A, r.nextLevel, "the next deal is NS's qualifying deal at A")
        // The variant without D33 lets the off-level deal pass (the upstream defect, kept only to show the difference).
        assertEquals(NS, settle(listOf(S, N), 7, ns = A, ew = 7, settings = settingsWith("passingA.requireDealAtA" to "false")).matchWinner)
    }

    // match-v1 M3.6 (D37): interruptions neither add nor reset, except under the variant.
    @Test fun interruptionsResetOnlyUnderTheVariant() {
        val opponentsDeal = listOf(E, W)
        assertEquals(2, settle(opponentsDeal, 7, ns = A, ew = 7, nsFailures = 2).outcome(NS).failuresAfter)
        val consecutive = settingsWith("passingA.interruptionResets" to "true")
        assertEquals(0, settle(opponentsDeal, 7, ns = A, ew = 7, nsFailures = 2, settings = consecutive).outcome(NS).failuresAfter)
    }

    // match-v1 M3.7 (D35): no failure limit by default.
    @Test fun withNoLimitFailuresAccumulateAndTheTeamStaysAtA() {
        var failures = 0
        repeat(7) {
            val r = settle(listOf(S, E, W), A, ns = A, ew = 9, nsFailures = failures)
            assertFalse(r.outcome(NS).reset)
            assertEquals(A, r.outcome(NS).levelAfter)
            failures = r.outcome(NS).failuresAfter
        }
        assertEquals(7, failures)
    }

    @Test fun threeFailuresSendTheTeamBackToTwoUnderTheCupVariant() {
        val cup = settingsWith("passingA.maxFailures" to "3")
        val second = settle(listOf(S, E, W), A, ns = A, ew = 9, nsFailures = 1, settings = cup)
        assertEquals(2, second.outcome(NS).failuresAfter)
        assertFalse(second.outcome(NS).reset)
        // Third failure, NS took 上游 with partner last: back to 2, and the next deal is at 2 (M3.8 + M2.5).
        val third = settle(listOf(S, E, W), A, ns = A, ew = 9, nsFailures = 2, settings = cup)
        assertTrue(third.outcome(NS).reset)
        assertEquals(2, third.outcome(NS).levelAfter)
        assertEquals(0, third.outcome(NS).failuresAfter)
        assertEquals(9, third.outcome(EW).levelAfter)
        assertEquals(2, third.nextLevel)
        // Third failure by an opponent win: NS back to 2, EW goes up, the next deal is at EW's level.
        val lost = settle(listOf(W, S, E), A, ns = A, ew = 9, nsFailures = 2, settings = cup)
        assertTrue(lost.outcome(NS).reset)
        assertEquals(2, lost.outcome(NS).levelAfter)
        assertEquals(11, lost.nextLevel)
        // NJMU: two failures.
        val njmu = settingsWith("passingA.maxFailures" to "2")
        assertTrue(settle(listOf(S, E, W), A, ns = A, ew = 9, nsFailures = 1, settings = njmu).outcome(NS).reset)
    }

    // match-v1 M3.2 (D34): passing needs gain 2 or more unless the SEU reading is chosen.
    @Test fun passingNeedsGainTwoUnlessTheSeuReadingIsChosen() {
        assertNull(settle(listOf(S, E, W), A, ns = A, ew = 4).matchWinner)
        assertEquals(NS, settle(listOf(S, E, W), A, ns = A, ew = 4, settings = settingsWith("passingA.minGain" to "1")).matchWinner)
    }

    // match-v1 M3.1, M3.2, M3.5: both teams at A.
    @Test fun whenBothTeamsAreAtAEitherCanPassAndBothCanFail() {
        assertEquals(EW, settle(listOf(E, S, W), A, ns = A, ew = A).matchWinner)
        val r = settle(listOf(E, S, N), A, ns = A, ew = A, ewFailures = 1)
        assertNull(r.matchWinner)
        assertTrue(r.outcome(EW).failedA && r.outcome(NS).failedA)
        assertEquals(2, r.outcome(EW).failuresAfter)
        assertEquals(1, r.outcome(NS).failuresAfter)
    }

    // match-v1 M2.4, M3.5: reaching A by a gain is not an attempt.
    @Test fun reachingABySettlementIsNotAnAttempt() {
        val r = settle(listOf(S, N), 12, ns = 12, ew = 3)
        assertEquals(A, r.outcome(NS).levelAfter)
        assertFalse(r.outcome(NS).aAttempt)
        assertEquals(0, r.outcome(NS).failuresAfter)
    }

    // match-v1 M3.1, M3.3 (D33): a match ends only by passing A in a deal at A (audit A04).
    @Test fun aSimulatedMatchEndsOnlyByPassingAInADealAtA() {
        val reducer = m1Reducer()
        for (seed in 1L..12L) {
            val events = ArrayList<MatchEvent>()
            val final = playMatch(reducer, seed) { events += it.events }
            val ended = events.filterIsInstance<MatchEvent.MatchEnded>().single()
            val last = events.filterIsInstance<MatchEvent.DealSettled>().last().result
            assertEquals(ended.winner, final.winner)
            assertEquals(A, last.dealLevel, "seed $seed: A04")
            assertEquals(A, last.outcome(ended.winner).levelBefore)
            assertTrue(last.partnerPlace <= 3, "seed $seed: partner not last")
            assertEquals(MatchPhase.OVER, final.phase)
            reducer.reduce(final, MatchCommand.DealNext(SplitMix64(1))).rejectedWith<MatchRejection.MatchOver>()
            reducer.reduce(final, MatchCommand.Settle).rejectedWith<MatchRejection.MatchOver>()
        }
    }
}
