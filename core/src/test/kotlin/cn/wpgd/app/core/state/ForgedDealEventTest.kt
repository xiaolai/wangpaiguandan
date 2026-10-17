package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.CombinationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A replayed deal log is checked event by event: a forged or out-of-phase event throws instead of
 * producing a plausible state, and committed state never aliases an event's caller-owned lists.
 */
class ForgedDealEventTest {
    private val r = p1Reducer()

    private fun opened(): DealState = r.started(1).state

    /** South's lead of a natural pair, as the reducer accepts it. */
    private fun pairLead(state: DealState): ReduceResult.Accepted {
        val pair = r.legalActions(state, Seat.SOUTH).first { it.combination.type == CombinationType.PAIR && it.declaration.isNullOrEmpty() }
        return r.reduce(state, Command.Play(Seat.SOUTH, pair.cards.map { it.id }, null)).accepted()
    }

    @Test fun replayRejectsAPlayOfRepeatedOrNoCards() {
        val state = opened()
        val played = pairLead(state).events.filterIsInstance<DealEvent.Played>().single()
        val id = played.cardIds.first()
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(state, played.copy(cardIds = listOf(id, id))) }
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(state, played.copy(cardIds = emptyList())) }
        // The genuine event still applies.
        DealEvents.apply(state, played)
    }

    @Test fun replayRejectsACombinationThatDoesNotMatchTheCardsOrBeatTheTable() {
        val state = opened()
        val played = pairLead(state).events.filterIsInstance<DealEvent.Played>().single()
        // The pair's combination stated for a single card.
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(state, played.copy(cardIds = played.cardIds.take(1))) }
        // East follows with the same combination: an equal play never beats the table.
        val afterLead = pairLead(state).state
        val eastPair = afterLead.hands.getValue(Seat.EAST).take(2)
        val forged = DealEvent.Played(afterLead.revision + 1, Seat.EAST, eastPair.sorted(), null, played.combination)
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(afterLead, forged) }
    }

    @Test fun committedPlayDoesNotAliasTheEventsLists() {
        val state = opened()
        val played = pairLead(state).events.filterIsInstance<DealEvent.Played>().single()
        val ids = played.cardIds.toMutableList()
        val after = DealEvents.apply(state, played.copy(cardIds = ids))
        ids.clear()
        assertEquals(played.cardIds, after.trick.top?.cardIds)
    }

    @Test fun seatFinishedNeedsPlayAndTheSeatsOwnFinalPlay() {
        val awaiting = r.newDeal(2)
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(awaiting, DealEvent.SeatFinished(1, Seat.SOUTH, 1)) }
        // Mid-play, a seat that did not just play cannot be recorded as finished, even with an empty hand.
        val afterLead = pairLead(opened()).state
        val emptied = afterLead.copy(hands = afterLead.hands + (Seat.EAST to emptySet()))
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(emptied, DealEvent.SeatFinished(afterLead.revision, Seat.EAST, 1)) }
    }

    /** R2.12: the opening leader of an empty trick may not pass, in replay as in the reducer. */
    @Test fun replayRejectsAPassWithNoPlayOnTheTable() {
        val state = opened()
        r.reduce(state, Command.Pass(Seat.SOUTH)).rejectedWith<Rejection.PassAsLeader>()
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(state, DealEvent.Passed(state.revision + 1, Seat.SOUTH)) }
    }

    /** D22: the turn moves to the next seat still holding cards; replay may not skip seats or land on another. */
    @Test fun replayRejectsATurnMoveToAnyButTheNextActiveSeat() {
        val lead = pairLead(opened())
        val moved = lead.events.filterIsInstance<DealEvent.TurnMoved>().single()
        assertEquals(Seat.EAST, moved.seat)
        val beforeMove = DealEvents.replay(opened(), lead.events.filter { it !is DealEvent.TurnMoved })
        for (seat in Seat.entries - Seat.EAST) {
            assertFailsWith<IllegalArgumentException>(seat.name) { DealEvents.apply(beforeMove, moved.copy(seat = seat)) }
        }
        assertEquals(lead.state, DealEvents.apply(beforeMove, moved))
    }

    @Test fun trickEndsOnlyAfterEveryPassAndWithTheDerivedLeader() {
        val lead = pairLead(opened())
        // Straight after the lead, before anyone passed.
        assertFailsWith<IllegalArgumentException> {
            DealEvents.apply(lead.state, DealEvent.TrickEnded(lead.state.revision + 1, Seat.SOUTH, Seat.SOUTH, partnerLead = false))
        }
        // Three passes: the reducer emits TrickEnded(SOUTH -> SOUTH). Forge the leader or the partner-lead flag.
        var s = lead.state
        var last: ReduceResult.Accepted? = null
        for (seat in listOf(Seat.EAST, Seat.NORTH, Seat.WEST)) {
            last = r.reduce(s, Command.Pass(seat)).accepted()
            s = last.state
        }
        val events = checkNotNull(last).events
        val ended = events.filterIsInstance<DealEvent.TrickEnded>().single()
        assertEquals(DealEvent.TrickEnded(ended.revision, Seat.SOUTH, Seat.SOUTH, partnerLead = false), ended)
        val beforeEnd = DealEvents.replay(r.reduce(lead.state, Command.Pass(Seat.EAST)).accepted().state.let { st ->
            r.reduce(st, Command.Pass(Seat.NORTH)).accepted().state
        }, events.filter { it !is DealEvent.TrickEnded })
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(beforeEnd, ended.copy(nextLeader = Seat.EAST)) }
        assertFailsWith<IllegalArgumentException> { DealEvents.apply(beforeEnd, ended.copy(nextLeader = Seat.NORTH, partnerLead = true)) }
        assertEquals(s, DealEvents.apply(beforeEnd, ended))
    }
}
