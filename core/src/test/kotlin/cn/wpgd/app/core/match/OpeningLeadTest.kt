package cn.wpgd.app.core.match

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.StandardDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** match-v1 section 7 (GDN-038, audit A02): the opening lead follows the profile, never a random or caller choice. */
class OpeningLeadTest {
    private val r = m1Reducer()

    @Test fun firstDealIsLedByTheHolderOfTheCutCard() {
        val leaders = HashSet<cn.wpgd.app.core.state.Seat>()
        for (seed in 1L..200L) {
            val out = r.reduce(r.newMatch(), MatchCommand.DealNext(SplitMix64(seed))).accepted()
            val cut = out.events.filterIsInstance<MatchEvent.CutCardDrawn>().single()
            val card = StandardDeck[cut.cardId]!!
            assertTrue(!Ranks.isJoker(card.rank) && !(card.suit == Suit.HEART && card.rank == 2), "M7.1: ${cut.cardId} must have been redrawn")
            assertTrue(cut.cardId in out.state.deal!!.hands.getValue(cut.seat))
            assertEquals(cut.seat, out.state.leader)
            assertEquals(LeadReason.CUT_CARD, out.state.leadReason)
            // Deterministic: the same seed gives the same leader.
            assertEquals(cut, r.reduce(r.newMatch(), MatchCommand.DealNext(SplitMix64(seed))).accepted().events.filterIsInstance<MatchEvent.CutCardDrawn>().single())
            val opened = r.reduce(out.state, MatchCommand.OpenPlay).accepted().state
            assertEquals(cut.seat, opened.deal!!.leader)
            assertEquals(cut.seat, opened.deal.turn)
            leaders += cut.seat
        }
        assertEquals(4, leaders.size, "every seat leads some first deal")
    }

    // match-v1 M7.1 (D32): the East-leads variant of the first deal.
    @Test fun eastVariantLeadsTheFirstDeal() {
        val east = reducerWith("lead.firstDeal" to "\"east\"")
        val out = east.reduce(east.newMatch(), MatchCommand.DealNext(SplitMix64(9))).accepted()
        assertTrue(out.events.none { it is MatchEvent.CutCardDrawn })
        assertEquals(E, out.state.leader)
        assertEquals(LeadReason.FIXED_EAST, out.state.leadReason)
    }

    // match-v1 M7.2: after a single tribute the giver leads.
    @Test fun singleTributeGiverLeads() {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        val ready = r.run(start, MatchCommand.StartTribute, MatchCommand.GiveTribute(W, "0SA"), MatchCommand.ReturnTribute(S, r.legalReturnCards(r.run(start, MatchCommand.StartTribute, MatchCommand.GiveTribute(W, "0SA")).state, S).first())).state
        assertEquals(W, ready.leader)
        assertEquals(LeadReason.TRIBUTE_GIVER, ready.leadReason)
        assertEquals(W, r.reduce(ready, MatchCommand.OpenPlay).accepted().state.deal!!.turn)
    }

    private fun doubleReady(eTop: String, wTop: String): MatchState {
        val e = low(26) + eTop
        val w = low(26, skip = e) + wTop
        val routed = r.run(matchAtTribute(r, listOf(S, N), handsWith(mapOf(E to e, W to w))), MatchCommand.StartTribute, MatchCommand.GiveTribute(E, eTop), MatchCommand.GiveTribute(W, wTop)).state
        return r.run(routed, MatchCommand.ReturnTribute(S, r.legalReturnCards(routed, S).first()), MatchCommand.ReturnTribute(N, r.legalReturnCards(routed, N).first())).state
    }

    // match-v1 M7.3 (D27): after a double tribute the giver to the 上游 leads.
    @Test fun doubleTributeGiverToFirstPlaceLeads() {
        assertEquals(W, doubleReady(eTop = "0SK", wTop = "0BJ").leader)
        assertEquals(E, doubleReady(eTop = "0BJ", wTop = "0SK").leader)
        assertEquals(LeadReason.GIVER_TO_FIRST, doubleReady(eTop = "0BJ", wTop = "0SK").leadReason)
    }

    @Test fun equalDoubleTributeIsLedByFirstPlacesNextSeat() {
        // M7.4: clockwise routing sends 上游's 下家's card to 上游, so that seat leads.
        val ready = doubleReady(eTop = "0SA", wTop = "1CA")
        assertEquals(S.next, ready.leader)
        assertEquals(E, ready.leader)
    }

    // match-v1 M7.5: after resistance the 上游 leads.
    @Test fun resistanceIsLedByFirstPlace() {
        val state = r.reduce(matchAtTribute(r, listOf(E, S, W), handsWith(mapOf(N to low(25) + listOf("0BJ", "1BJ")))), MatchCommand.StartTribute).accepted().state
        val ready = r.reduce(state, MatchCommand.Resist(N)).accepted().state
        assertEquals(E, ready.leader)
    }

    // match-v1 M7.6: the lead is derived, never chosen by the caller (audit A02).
    @Test fun theCallerCannotChooseOrSkipTheLead() {
        val dealt = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        r.reduce(dealt, MatchCommand.OpenPlay).rejectedWith<MatchRejection.WrongPhase>()
        r.reduce(dealt, MatchCommand.InDeal(Command.Start(SplitMix64(1), S))).rejectedWith<MatchRejection.StartNotAllowed>()
        val tribute = r.reduce(dealt, MatchCommand.StartTribute).accepted().state
        r.reduce(tribute, MatchCommand.OpenPlay).rejectedWith<MatchRejection.WrongPhase>()
        r.reduce(tribute, MatchCommand.InDeal(Command.Pass(W))).rejectedWith<MatchRejection.WrongPhase>()
    }

    // match-v1 M7.2–M7.6 over simulated matches.
    @Test fun everyLaterDealInSimulatedMatchesOpensWithTheProfileLeader() {
        var checked = 0
        for (seed in 1L..30L) playMatch(r, seed) { step ->
            val began = step.events.filterIsInstance<MatchEvent.InDeal>().map { it.event }.filterIsInstance<cn.wpgd.app.core.state.DealEvent.PlayBegan>().singleOrNull()
                ?: return@playMatch
            val s = step.before
            if (s.dealNumber == 1) return@playMatch
            val t = s.tribute!!
            val previous = s.previous!!
            val first = previous.finishOrder.first()
            val expected = when {
                t.resistedBy != null -> first
                t.obligation.kind == TributeKind.SINGLE -> previous.lastSeat!!
                else -> t.giverTo(first)
            }
            assertEquals(expected, began.leader, "seed $seed deal ${s.dealNumber}")
            checked++
        }
        assertTrue(checked > 100, "only $checked later deals checked")
    }
}
