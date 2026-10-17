package cn.wpgd.app.core.match

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.state.DealEvent
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** low(n): n ids of rank 3..9 (plain cards at level 2), in deck order, skipping [skip]. */
internal fun low(n: Int, skip: Collection<String> = emptyList()): List<String> =
    StandardDeck.cards.filter { it.suit != Suit.JOKER && it.rank in 3..9 && it.id !in skip }.map { it.id }.take(n).also { check(it.size == n) }

/** match-v1 section 4 (GDN-034, GDN-035): single and double tribute, ties, no card loss. */
class TributeTest {
    private val r = m1Reducer()

    /** Previous deal S, E, N finished; W is 下游 and owes S. W's hand is fixed in full. */
    private fun single(wHand: List<String>, sHand: List<String> = listOf("0BJ", "1BJ", "0LJ", "1LJ")): MatchState {
        check(wHand.size == 27)
        return r.reduce(matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to wHand, S to sHand))), MatchCommand.StartTribute).accepted().state
    }

    /** Previous deal S, N double-up; E and W owe. Both hands fixed in full. */
    private fun double(eTop: String, wTop: String, reducer: MatchReducer = r): MatchState {
        val e = low(26) + eTop
        val w = low(26, skip = e) + wTop
        return reducer.reduce(matchAtTribute(reducer, listOf(S, N), handsWith(mapOf(E to e, W to w))), MatchCommand.StartTribute).accepted().state
    }

    @Test fun singleTributeIsTheHighestNonWildcardCardAndGoesToFirstPlace() {
        // Level 2: 0S2 is a level card (above A, M4.3); 0H2 is a wildcard and never tribute.
        val state = single(low(24) + listOf("0S2", "0SA", "0H2"))
        val started = state.tribute!!.obligation
        assertEquals(TributeObligation(TributeKind.SINGLE, listOf(W), listOf(S)), started)
        assertEquals(listOf("0S2"), r.legalTributeCards(state, W))
        r.reduce(state, MatchCommand.GiveTribute(W, "0SA")).rejectedWith<MatchRejection.NotHighestCard>()
        r.reduce(state, MatchCommand.GiveTribute(W, "0H2")).rejectedWith<MatchRejection.WildcardNotTributable>()
        r.reduce(state, MatchCommand.GiveTribute(W, "0BJ")).rejectedWith<MatchRejection.UnownedCard>()
        r.reduce(state, MatchCommand.GiveTribute(E, "0S3")).rejectedWith<MatchRejection.NotAGiver>()

        val given = r.reduce(state, MatchCommand.GiveTribute(W, "0S2")).accepted()
        assertEquals(MatchPhase.RETURN, given.state.phase)
        val hands = given.state.deal!!.hands
        assertTrue("0S2" in hands.getValue(S) && "0S2" !in hands.getValue(W))
        assertEquals(28, hands.getValue(S).size)
        assertEquals(26, hands.getValue(W).size)
        assertEquals(listOf(CardTransfer(W, S, "0S2")), given.events.filterIsInstance<MatchEvent.TributeRouted>().single().transfers)
        r.reduce(given.state, MatchCommand.GiveTribute(W, "0SA")).rejectedWith<MatchRejection.WrongPhase>()
    }

    // match-v1 M4.3, M4.4 (D25): among cards of the top rank, the giver chooses.
    @Test fun giverChoosesAmongCardsOfTheTopRank() {
        val state = single(low(25) + listOf("0SA", "1CA"))
        assertEquals(listOf("0SA", "1CA"), r.legalTributeCards(state, W))
        for (card in listOf("0SA", "1CA")) {
            val out = r.reduce(state, MatchCommand.GiveTribute(W, card)).accepted().state
            assertTrue(card in out.deal!!.hands.getValue(S))
        }
    }

    @Test fun doubleTributeSendsTheHigherCardToFirstPlaceAndTheLowerToItsPartner() {
        for (wFirst in listOf(false, true)) {
            val state = double(eTop = "0BJ", wTop = "0SA")
            assertEquals(TributeObligation(TributeKind.DOUBLE, listOf(E, W), listOf(S, N)), state.tribute!!.obligation)
            val order = if (wFirst) listOf(W to "0SA", E to "0BJ") else listOf(E to "0BJ", W to "0SA")
            val half = r.reduce(state, MatchCommand.GiveTribute(order[0].first, order[0].second)).accepted()
            // M4.9: nothing moves until both are given.
            assertEquals(state.deal!!.hands, half.state.deal!!.hands)
            assertEquals(MatchPhase.TRIBUTE, half.state.phase)
            r.reduce(half.state, MatchCommand.GiveTribute(order[0].first, order[0].second)).rejectedWith<MatchRejection.AlreadyGave>()
            val done = r.reduce(half.state, MatchCommand.GiveTribute(order[1].first, order[1].second)).accepted().state
            assertTrue("0BJ" in done.deal!!.hands.getValue(S), "the big joker goes to 上游")
            assertTrue("0SA" in done.deal.hands.getValue(N), "the ace goes to 上游's partner")
            assertEquals(mapOf(E to S, W to N), done.tribute!!.routes)
        }
        val reversed = double(eTop = "0SK", wTop = "1BJ")
        val done = r.run(reversed, MatchCommand.GiveTribute(E, "0SK"), MatchCommand.GiveTribute(W, "1BJ")).state
        assertEquals(mapOf(W to S, E to N), done.tribute!!.routes)
    }

    /** M4.9: before both double tributes are given, the public view names no recipient; never the giver itself. */
    @Test fun anUnroutedTributeShowsNoRecipient() {
        val projector = MatchProjector(p1, MatchProfiles.m1)
        val state = double(eTop = "0BJ", wTop = "0SA")
        val half = r.reduce(state, MatchCommand.GiveTribute(E, "0BJ")).accepted().state
        val given = projector.memberView(half, S).match.tribute!!.given.single()
        assertEquals(E, given.from)
        assertEquals(null, given.to, "the recipient is unknown until both tributes are given")
        val done = r.reduce(half, MatchCommand.GiveTribute(W, "0SA")).accepted().state
        assertEquals(setOf(E to S, W to N), projector.memberView(done, S).match.tribute!!.given.map { it.from to it.to }.toSet())
    }

    @Test fun equalTributesGoClockwiseEachGiverToItsUpperSeat() {
        // M4.7: clockwise means to the giver's 上家 (left): E gives to S, W gives to N.
        assertEquals(S, E.upper)
        assertEquals(N, W.upper)
        for ((eTop, wTop) in listOf("0SA" to "1DA", "0LJ" to "1LJ", "0SK" to "1HK")) {
            val state = double(eTop, wTop)
            val done = r.run(state, MatchCommand.GiveTribute(W, wTop), MatchCommand.GiveTribute(E, eTop)).state
            assertEquals(mapOf(E to S, W to N), done.tribute!!.routes, "$eTop vs $wTop")
            assertTrue(eTop in done.deal!!.hands.getValue(S) && wTop in done.deal.hands.getValue(N))
        }
    }

    // match-v1 M4.7 (D26): equal-rank level cards tie whatever their suits; clockwise routing.
    @Test fun equalLevelCardsTieRegardlessOfSuit() {
        // Two non-heart level cards rank equal (R1.7): clockwise routing.
        val state = double(eTop = "0D2", wTop = "1S2")
        val done = r.run(state, MatchCommand.GiveTribute(E, "0D2"), MatchCommand.GiveTribute(W, "1S2")).state
        assertEquals(mapOf(E to S, W to N), done.tribute!!.routes)
    }

    // match-v1 M4.2, M4.5, M5.1: every tribute reaches its receiver and every return its giver.
    @Test fun noCardIsLostThroughTributeAndReturn() {
        for (seed in 1L..40L) {
            val state = r.advanceTo(playMatch(r, seed, stopWhen = { it.dealNumber == 2 && it.phase == MatchPhase.DEALT }), MatchPhase.READY, seed)
            assertEquals(108, state.allCardIds().size)
            assertEquals(StandardDeck.ids, state.allCardIds().toSet())
            for (seat in listOf(S, E, N, W)) assertEquals(27, state.deal!!.hands.getValue(seat).size, "seed $seed $seat")
            val t = state.tribute!!
            if (t.resistedBy == null) {
                for ((giver, receiver) in t.routes) {
                    assertTrue(t.given.getValue(giver) in state.deal!!.hands.getValue(receiver), "seed $seed tribute reached $receiver")
                    assertTrue(t.returns.getValue(receiver) in state.deal.hands.getValue(giver), "seed $seed return reached $giver")
                }
            }
        }
    }

    // match-v1 M4.8: which card was given is public.
    @Test fun tributeIsPublicAndTheCardMoveIsHostOnly() {
        val state = single(low(25) + listOf("0SA", "1CA"))
        val events = r.reduce(state, MatchCommand.GiveTribute(W, "0SA")).accepted().events
        assertEquals(Visibility.Public, events.filterIsInstance<MatchEvent.TributeGiven>().single().visibility)
        assertEquals(Visibility.Public, events.filterIsInstance<MatchEvent.TributeRouted>().single().visibility)
        val moved = events.filterIsInstance<MatchEvent.InDeal>().single()
        assertTrue(moved.event is DealEvent.CardMoved)
        assertEquals(Visibility.HostOnly, moved.visibility)
    }

    // match-v1 M4.1: no tribute before the first deal.
    @Test fun theFirstDealHasNoTribute() {
        val dealt = r.reduce(r.newMatch(), MatchCommand.DealNext(SplitMix64(3))).accepted().state
        assertEquals(MatchPhase.READY, dealt.phase)
        r.reduce(dealt, MatchCommand.StartTribute).rejectedWith<MatchRejection.WrongPhase>()
        r.reduce(dealt, MatchCommand.GiveTribute(S, dealt.deal!!.hands.getValue(S).first())).rejectedWith<MatchRejection.WrongPhase>()
    }
}
