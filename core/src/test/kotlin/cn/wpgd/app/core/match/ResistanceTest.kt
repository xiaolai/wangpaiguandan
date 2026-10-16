package cn.wpgd.app.core.match

import cn.wpgd.app.core.state.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** match-v1 section 6 (GDN-037): two big jokers in one hand versus split between partners. */
class ResistanceTest {
    private val r = m1Reducer()

    private fun singleWith(wJokers: List<String>, reducer: MatchReducer = r): MatchState {
        val w = low(27 - wJokers.size) + wJokers
        return reducer.reduce(matchAtTribute(reducer, listOf(S, E, N), handsWith(mapOf(W to w))), MatchCommand.StartTribute).accepted().state
    }

    private fun doubleWith(eJokers: List<String>, wJokers: List<String>, reducer: MatchReducer = r): MatchState {
        val e = low(27 - eJokers.size) + eJokers
        val w = low(27 - wJokers.size, skip = e) + wJokers
        return reducer.reduce(matchAtTribute(reducer, listOf(S, N), handsWith(mapOf(E to e, W to w))), MatchCommand.StartTribute).accepted().state
    }

    @Test fun singleDownWithBothBigJokersResistsAndFirstPlaceLeads() {
        val state = singleWith(listOf("0BJ", "1BJ"))
        assertTrue(r.mayResist(state))
        val out = r.reduce(state, MatchCommand.Resist(W)).accepted()
        val resisted = out.events.filterIsInstance<MatchEvent.Resisted>().single()
        assertEquals(MatchEvent.Resisted(out.state.revision, W, listOf(W), listOf("0BJ", "1BJ")), resisted)
        assertEquals(Visibility.Public, resisted.visibility)
        assertEquals(MatchPhase.READY, out.state.phase)
        assertEquals(S, out.state.leader)
        assertEquals(LeadReason.RESISTANCE_FIRST_PLACE, out.state.leadReason)
        assertEquals(state.deal!!.hands, out.state.deal!!.hands, "M6.5: no card changes hands")
    }

    // match-v1 M6.1: one big joker is not enough to resist; M4.3 it is the tribute.
    @Test fun singleDownWithOneBigJokerMustGiveIt() {
        val state = singleWith(listOf("0BJ"))
        assertFalse(r.mayResist(state))
        r.reduce(state, MatchCommand.Resist(W)).rejectedWith<MatchRejection.ResistanceNotAvailable>()
        assertEquals(listOf("0BJ"), r.legalTributeCards(state, W))
    }

    // match-v1 M6.2, M6.5: both big jokers in one hand resist; the 上游 leads.
    @Test fun doubleDownWithBothJokersInOneHandResists() {
        val state = doubleWith(listOf("0BJ", "1BJ"), emptyList())
        for (claimant in listOf(E, W)) {
            val out = r.reduce(state, MatchCommand.Resist(claimant)).accepted()
            assertEquals(listOf(E), out.events.filterIsInstance<MatchEvent.Resisted>().single().holders)
            assertEquals(S, out.state.leader)
        }
    }

    // match-v1 M6.2 (D30): split big jokers resist by default; M6.4 the holders are shown.
    @Test fun doubleDownWithJokersSplitBetweenPartnersResistsByDefault() {
        val state = doubleWith(listOf("0BJ"), listOf("1BJ"))
        assertTrue(r.mayResist(state))
        val out = r.reduce(state, MatchCommand.Resist(W)).accepted()
        val resisted = out.events.filterIsInstance<MatchEvent.Resisted>().single()
        assertEquals(listOf(E, W), resisted.holders)
        assertEquals(listOf("0BJ", "1BJ"), resisted.bigJokerIds)
        assertEquals(S, out.state.leader)
    }

    // match-v1 M6.2 (D30) variant; M4.7 equal tributes go clockwise.
    @Test fun splitJokersDoNotResistUnderTheOneHandVariant() {
        val oneHand = reducerWith("resistance.splitBetweenPartners" to "false")
        val state = doubleWith(listOf("0BJ"), listOf("1BJ"), oneHand)
        assertFalse(oneHand.mayResist(state))
        oneHand.reduce(state, MatchCommand.Resist(E)).rejectedWith<MatchRejection.ResistanceNotAvailable>()
        val done = oneHand.run(state, MatchCommand.GiveTribute(E, "0BJ"), MatchCommand.GiveTribute(W, "1BJ")).state
        assertEquals(MatchPhase.RETURN, done.phase)
        assertEquals(mapOf(E to S, W to N), done.tribute!!.routes, "two big jokers tie: clockwise")
    }

    // match-v1 M6.3 (D31): a given tribute waives the claim.
    @Test fun givingATributeWaivesTheClaim() {
        val state = doubleWith(listOf("0BJ"), listOf("1BJ"))
        val half = r.reduce(state, MatchCommand.GiveTribute(E, "0BJ")).accepted().state
        assertFalse(r.mayResist(half))
        r.reduce(half, MatchCommand.Resist(W)).rejectedWith<MatchRejection.ResistanceWaived>()
    }

    // match-v1 M6.3 (D31): resistance is the givers' right.
    @Test fun onlyAGiverMayClaim() {
        val state = singleWith(listOf("0BJ", "1BJ"))
        r.reduce(state, MatchCommand.Resist(S)).rejectedWith<MatchRejection.NotAGiver>()
        r.reduce(state, MatchCommand.Resist(N)).rejectedWith<MatchRejection.NotAGiver>()
    }

    // match-v1 M6.3 (D31): the automatic-resistance variant.
    @Test fun automaticModeResistsWhenTributeStarts() {
        val auto = reducerWith("resistance.mode" to "\"automatic\"")
        val start = matchAtTribute(auto, listOf(S, E, N), handsWith(mapOf(W to low(25) + listOf("0BJ", "1BJ"))))
        val out = auto.reduce(start, MatchCommand.StartTribute).accepted()
        val resisted = out.events.filterIsInstance<MatchEvent.Resisted>().single()
        assertNull(resisted.claimedBy)
        assertEquals(MatchPhase.READY, out.state.phase)
        assertEquals(S, out.state.leader)
        // Not eligible: tribute proceeds, and a claim is refused as meaningless.
        val plain = auto.reduce(matchAtTribute(auto, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0BJ"))), MatchCommand.StartTribute).accepted().state
        assertEquals(MatchPhase.TRIBUTE, plain.phase)
        auto.reduce(plain, MatchCommand.Resist(W)).rejectedWith<MatchRejection.ResistanceIsAutomatic>()
    }
}
