package cn.wpgd.app.core.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Replaying a malformed match log throws instead of producing a plausible state. */
class MalformedMatchLogTest {
    private val r = m1Reducer()

    /** Previous deal S, N double-up; E gives the big joker (to S), W the ace (to N). */
    private fun started(): MatchState {
        val e = low(26, skip = listOf("0S3", "0S4")) + "0BJ"
        val w = low(26, skip = e + listOf("0S3", "0S4")) + "0SA"
        val start = matchAtTribute(r, listOf(S, N), handsWith(mapOf(E to e, W to w, S to listOf("0S3", "0S4"))))
        return r.run(start, MatchCommand.StartTribute).state
    }

    private fun MatchState.apply(e: MatchEvent) = MatchEvents.apply(this, e)

    // M4.6: the routing event must send each tribute to the receiver the rules give, each once.
    @Test fun aRoutingWithWrongDestinationsOrRepeatsIsRefused() {
        val half = r.run(started(), MatchCommand.GiveTribute(E, "0BJ")).state
        val accepted = r.reduce(half, MatchCommand.GiveTribute(W, "0SA")).accepted()
        val given = accepted.events.takeWhile { it !is MatchEvent.TributeRouted }.fold(half) { s, e -> s.apply(e) }
        val routed = accepted.events.filterIsInstance<MatchEvent.TributeRouted>().single()
        assertEquals(setOf(CardTransfer(E, S, "0BJ"), CardTransfer(W, N, "0SA")), routed.transfers.toSet())
        given.apply(routed) // the genuine event applies
        val swapped = routed.copy(transfers = listOf(CardTransfer(E, N, "0BJ"), CardTransfer(W, S, "0SA")))
        assertFailsWith<IllegalArgumentException> { given.apply(swapped) }
        val repeated = routed.copy(transfers = routed.transfers + routed.transfers.first())
        assertFailsWith<IllegalArgumentException> { given.apply(repeated) }
    }

    // M5.5: the reveal must send each return back to the giver it came from, each once.
    @Test fun aRevealWithAWrongRecipientOrRepeatsIsRefused() {
        val routed = r.run(started(), MatchCommand.GiveTribute(E, "0BJ"), MatchCommand.GiveTribute(W, "0SA"), MatchCommand.ReturnTribute(S, "0S3")).state
        val nCard = r.legalReturnCards(routed, N).first()
        val done = r.reduce(routed, MatchCommand.ReturnTribute(N, nCard)).accepted()
        val beforeReveal = done.events.takeWhile { it !is MatchEvent.ReturnsRevealed }.fold(routed) { s, e -> s.apply(e) }
        val reveal = done.events.filterIsInstance<MatchEvent.ReturnsRevealed>().single()
        beforeReveal.apply(reveal)
        val wrongRecipient = reveal.copy(transfers = listOf(CardTransfer(S, W, "0S3"), CardTransfer(N, E, nCard)))
        assertFailsWith<IllegalArgumentException> { beforeReveal.apply(wrongRecipient) }
        val repeated = reveal.copy(transfers = reveal.transfers + reveal.transfers.first())
        assertFailsWith<IllegalArgumentException> { beforeReveal.apply(repeated) }
    }

    // M7.2, M7.3, M7.5: the lead is resolved only after the step that decides it, by its own rule.
    @Test fun aLeadThatSkipsTheTributeIsRefused() {
        val s = started()
        // No resistance happened: a lead during TRIBUTE would skip the tribute entirely.
        assertFailsWith<IllegalArgumentException> { s.apply(MatchEvent.LeaderResolved(s.revision + 1, S, LeadReason.RESISTANCE_FIRST_PLACE)) }
        assertFailsWith<IllegalArgumentException> { s.apply(MatchEvent.LeaderResolved(s.revision + 1, E, LeadReason.GIVER_TO_FIRST)) }
        // Routed but nothing returned yet: still too early.
        val routed = r.run(s, MatchCommand.GiveTribute(E, "0BJ"), MatchCommand.GiveTribute(W, "0SA")).state
        assertFailsWith<IllegalArgumentException> { routed.apply(MatchEvent.LeaderResolved(routed.revision + 1, E, LeadReason.GIVER_TO_FIRST)) }
        // Once revealed, only the giver whose tribute went to 上游, for that reason.
        val back = r.run(routed, MatchCommand.ReturnTribute(S, "0S3")).state
        val nCard = r.legalReturnCards(back, N).first()
        val done = r.reduce(back, MatchCommand.ReturnTribute(N, nCard)).accepted()
        val revealed = done.events.takeWhile { it !is MatchEvent.LeaderResolved }.fold(back) { st, e -> st.apply(e) }
        assertFailsWith<IllegalArgumentException> { revealed.apply(MatchEvent.LeaderResolved(revealed.revision, W, LeadReason.GIVER_TO_FIRST)) }
        assertFailsWith<IllegalArgumentException> { revealed.apply(MatchEvent.LeaderResolved(revealed.revision, E, LeadReason.TRIBUTE_GIVER)) }
        assertEquals(MatchPhase.READY, revealed.apply(MatchEvent.LeaderResolved(revealed.revision, E, LeadReason.GIVER_TO_FIRST)).phase)
    }
}
