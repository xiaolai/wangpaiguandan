package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GDN-015: per-seat AI settings; a change is visible at once and applied only at the next deal boundary. */
class SeatAiSettingsTest {
    private val random = AiSettings(RandomLegalPolicy.id)
    private val bold = AiSettings(AiPolicies.BASELINE, AiStyle.BOLD)

    @Test fun changeDuringADealAppliesFromTheNextDeal() {
        val book = SeatAiSettings.uniform().request(S, random, currentDealNumber = 3)
        assertEquals(AiPolicies.DEFAULT_SETTINGS, book.effective(S, 3), "unchanged inside deal 3")
        assertEquals(random, book.pending(S, 3), "the change is visible while it waits")
        assertEquals(random, book.effective(S, 4))
        assertNull(book.pending(S, 4))
        assertEquals(AiPolicies.DEFAULT_SETTINGS, book.effective(E, 4), "other seats untouched")
    }

    @Test fun changeBeforeTheFirstDealAppliesToTheFirstDeal() {
        val book = SeatAiSettings.uniform().request(W, bold, currentDealNumber = 0)
        assertEquals(bold, book.effective(W, 1))
    }

    @Test fun aLaterRequestBeforeTheBoundaryReplacesTheWaitingOne() {
        val book = SeatAiSettings.uniform().request(S, random, 2).request(S, bold, 2)
        assertEquals(bold, book.pending(S, 2))
        assertEquals(bold, book.effective(S, 3))
        // A request in a later deal keeps the history: deal 3 still plays with the first change.
        val later = book.request(S, random, 5)
        assertEquals(bold, later.effective(S, 3))
        assertEquals(bold, later.effective(S, 5))
        assertEquals(random, later.effective(S, 6))
    }

    @Test fun unknownPoliciesAreRefusedAtTheRequest() {
        assertFailsWith<IllegalArgumentException> { SeatAiSettings.uniform().request(S, AiSettings(PolicyId("nope", "9")), 1) }
        assertFailsWith<IllegalArgumentException> { AiPolicies[PolicyId("nope", "9")] }
    }

    /** In a played match, a change made in the middle of deal 1 changes nothing until deal 2 is dealt. */
    @Test fun midDealChangeInAPlayedMatch() {
        val reducer = AiProfiles.reducer
        var book = SeatAiSettings.uniform()
        val used = ArrayList<Pair<Int, PolicyId>>()
        var southDecisions = 0
        val choosers = Seat.entries.associateWith { seat ->
            SeatChooser { state, s, input ->
                val settings = book.effective(s, state.dealNumber)
                val out = DecisionRunner.decide(AiPolicies[settings.policy], input, SplitMix64(seedOf(9, state.revision)), settings.style)
                if (seat == S) {
                    used += state.dealNumber to out.policy
                    if (++southDecisions == 5) {
                        book = book.request(S, random, state.dealNumber)
                        assertEquals(random, book.pending(S, state.dealNumber), "visible as pending")
                    }
                }
                out.action
            }
        }
        var state = reducer.newMatch()
        val stats = RunStats()
        while (state.dealNumber < 3 || state.phase != MatchPhase.AWAITING_DEAL) {
            state = step(reducer, state, { SplitMix64(77L + it) }, choosers, stats)
            if (state.phase == MatchPhase.OVER) break
        }
        assertTrue(southDecisions > 5)
        assertTrue(used.filter { it.first == 1 }.all { it.second == AiPolicies.BASELINE }, "deal 1 kept the old policy: $used")
        assertTrue(used.filter { it.first >= 2 }.let { it.isNotEmpty() && it.all { p -> p.second == RandomLegalPolicy.id } }, "deal 2 on uses the new one: $used")
    }
}
