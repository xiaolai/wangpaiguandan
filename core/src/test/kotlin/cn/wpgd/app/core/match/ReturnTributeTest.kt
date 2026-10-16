package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.Visibility
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** match-v1 section 5 (GDN-036): the return limit, level cards, the all-high exception, privacy until the reveal. */
class ReturnTributeTest {
    /** Single tribute at level 2: W (下游) gives its top card [wTop] to S; S's fixed cards are [sHand]. */
    private fun atReturn(reducer: MatchReducer, sHand: List<String>, wTop: String = "0SA", level: Int = 2): MatchState {
        val w = low(26, skip = sHand) + wTop
        val start = matchAtTribute(reducer, listOf(S, E, N), handsWith(mapOf(S to sHand, W to w)), level = level)
        return reducer.run(start, MatchCommand.StartTribute, MatchCommand.GiveTribute(W, wTop)).state
    }

    /** Independent statement of M5.2–M5.4 under the defaults: rank ≤ 10, level cards and jokers excluded, else the lowest. */
    private fun expectedReturns(hand: Set<String>, level: Int): List<String> {
        val cards = hand.map { StandardDeck[it]!! }
        val within = cards.filter { !Ranks.isJoker(it.rank) && it.rank != level && it.rank <= 10 }
        if (within.isNotEmpty()) return within.map { it.id }.sorted()
        val value = { c: cn.wpgd.app.core.Card -> if (c.rank == level) 15 else c.rank }
        val lowest = cards.minOf(value)
        return cards.filter { value(it) == lowest }.map { it.id }.sorted()
    }

    @Test fun returnMustBeTenOrLowerAndTheHostAcceptsExactlyTheLegalCards() {
        val r = m1Reducer()
        val state = atReturn(r, listOf("0SJ", "0S10", "0S3", "1D2", "0H2", "0BJ"))
        val hand = state.deal!!.hands.getValue(S)
        val legal = r.legalReturnCards(state, S)
        assertEquals(expectedReturns(hand, 2), legal)
        assertTrue("0S10" in legal && "0S3" in legal)
        assertFalse("0SJ" in legal || "1D2" in legal || "0H2" in legal || "0BJ" in legal, "J, level cards (D29) and jokers are above the limit")
        // Host validator and client pre-check are the same code (GDN-036): accepted exactly when listed.
        for (card in hand) {
            val accepted = r.reduce(state, MatchCommand.ReturnTribute(S, card)) is MatchReduceResult.Accepted
            assertEquals(card in legal, accepted, card)
        }
        r.reduce(state, MatchCommand.ReturnTribute(S, "0SJ")).rejectedWith<MatchRejection.ReturnNotAllowed>()
    }

    @Test fun levelCardsCountByFaceOnlyUnderTheVariant() {
        val byFace = reducerWith("returnTribute.levelCardsByFace" to "true")
        val state = atReturn(byFace, listOf("0SJ", "1D2", "0H2"))
        val legal = byFace.legalReturnCards(state, S)
        assertTrue("1D2" in legal && "0H2" in legal, "face 2 meets the limit under the D29 variant")
        byFace.reduce(state, MatchCommand.ReturnTribute(S, "1D2")).accepted()
    }

    // match-v1 M5.4: the all-high exception; M5.3 (D29) under the face-value variant.
    @Test fun allHighHandReturnsItsLowestCard() {
        // S holds only J, Q, K, jokers and one level card (2 ranks above A at level 2); it receives an A.
        val high = StandardDeck.cards.filter { it.rank in 11..13 }.map { it.id }
        val sHand = high.take(23) + listOf("0LJ", "1LJ", "0BJ", "0S2")
        check(sHand.size == 27)
        val r = m1Reducer()
        val state = atReturn(r, sHand, wTop = "1DA")
        val hand = state.deal!!.hands.getValue(S)
        assertTrue(hand.none { StandardDeck[it]!!.let { c -> !Ranks.isJoker(c.rank) && c.rank != 2 && c.rank <= 10 } })
        val legal = r.legalReturnCards(state, S)
        assertEquals(expectedReturns(hand, 2), legal)
        assertTrue(legal.isNotEmpty() && legal.all { StandardDeck[it]!!.rank == 11 }, "the lowest are the jacks, not the level card: $legal")
        r.reduce(state, MatchCommand.ReturnTribute(S, legal.first())).accepted()
        r.reduce(state, MatchCommand.ReturnTribute(S, "0S2")).rejectedWith<MatchRejection.ReturnNotAllowed>()
        r.reduce(state, MatchCommand.ReturnTribute(S, "0BJ")).rejectedWith<MatchRejection.ReturnNotAllowed>()
        // Under the face-value variant, the level card is the one card within the limit.
        val byFace = reducerWith("returnTribute.levelCardsByFace" to "true")
        assertEquals(listOf("0S2"), byFace.legalReturnCards(atReturn(byFace, sHand, wTop = "1DA"), S))
    }

    // match-v1 M5.2 (D28): the no-limit variant of the return limit.
    @Test fun noLimitVariantAllowsAnyCard() {
        val any = reducerWith("returnTribute.maxRank" to "null")
        val state = atReturn(any, listOf("0BJ", "0SJ"))
        assertEquals(state.deal!!.hands.getValue(S).sorted(), any.legalReturnCards(state, S))
        any.reduce(state, MatchCommand.ReturnTribute(S, "0BJ")).accepted()
    }

    @Test fun onlyAReceiverReturnsAndOnlyOnce() {
        val r = m1Reducer()
        val state = atReturn(r, listOf("0S3", "0S4"))
        r.reduce(state, MatchCommand.ReturnTribute(N, state.deal!!.hands.getValue(N).first())).rejectedWith<MatchRejection.NotAReturner>()
        r.reduce(state, MatchCommand.ReturnTribute(W, "0SA")).rejectedWith<MatchRejection.NotAReturner>()
        r.reduce(state, MatchCommand.ReturnTribute(S, state.deal.hands.getValue(W).first())).rejectedWith<MatchRejection.UnownedCard>()
        val single = r.reduce(state, MatchCommand.ReturnTribute(S, "0S3")).accepted()
        // A single tribute has one return: it is revealed and moved at once, and the giver leads (M7.2).
        assertEquals(MatchPhase.READY, single.state.phase)
        assertTrue("0S3" in single.state.deal!!.hands.getValue(W))
        r.reduce(single.state, MatchCommand.ReturnTribute(S, "0S4")).rejectedWith<MatchRejection.WrongPhase>()
    }

    // match-v1 M5.5, M5.6: returns stay private until revealed together.
    @Test fun aReturnIsKnownOnlyToTheReturnerUntilBothAreRevealed() {
        val r = m1Reducer()
        val e = low(26, skip = listOf("0S3", "0S4")) + "0BJ"
        val w = low(26, skip = e + listOf("0S3", "0S4")) + "0SA"
        val start = matchAtTribute(r, listOf(S, N), handsWith(mapOf(E to e, W to w, S to listOf("0S3", "0S4"))))
        val routed = r.run(start, MatchCommand.StartTribute, MatchCommand.GiveTribute(E, "0BJ"), MatchCommand.GiveTribute(W, "0SA")).state
        assertEquals(MatchPhase.RETURN, routed.phase)
        val projector = MatchProjector(p1, MatchProfiles.m1)
        val a = r.reduce(routed, MatchCommand.ReturnTribute(S, "0S3")).accepted()
        val b = r.reduce(routed, MatchCommand.ReturnTribute(S, "0S4")).accepted()
        val chosen = a.events.filterIsInstance<MatchEvent.ReturnChosen>().single()
        assertEquals(Visibility.SeatPrivate(S), chosen.visibility)
        assertEquals(Visibility.Public, a.events.filterIsInstance<MatchEvent.ReturnMade>().single().visibility)
        // P1: which card S put down changes no other member's bytes, nor the spectator's.
        for (viewer in listOf(E, N, W)) {
            assertContentEquals(MatchProjectionCodec.encode(projector.memberView(a.state, viewer)), MatchProjectionCodec.encode(projector.memberView(b.state, viewer)), "$viewer")
        }
        assertContentEquals(MatchProjectionCodec.encode(projector.spectatorView(a.state)), MatchProjectionCodec.encode(projector.spectatorView(b.state)))
        assertEquals("0S3", projector.memberView(a.state, S).ownReturn?.id)
        assertNull(projector.memberView(a.state, E).ownReturn)
        // Nothing has moved yet.
        assertEquals(routed.deal!!.hands, a.state.deal!!.hands)

        val nCard = r.legalReturnCards(a.state, N).first()
        val done = r.reduce(a.state, MatchCommand.ReturnTribute(N, nCard)).accepted()
        val revealed = done.events.filterIsInstance<MatchEvent.ReturnsRevealed>().single()
        assertEquals(Visibility.Public, revealed.visibility)
        assertEquals(setOf(CardTransfer(S, E, "0S3"), CardTransfer(N, W, nCard)), revealed.transfers.toSet())
        assertTrue("0S3" in done.state.deal!!.hands.getValue(E) && nCard in done.state.deal.hands.getValue(W))
        val publicView = projector.spectatorView(done.state).match.tribute
        assertNotNull(publicView)
        assertEquals(2, publicView.returnsRevealed.size)
    }
}
