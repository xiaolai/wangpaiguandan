package cn.wpgd.app.core.state

import cn.wpgd.app.core.refusesJavaClear
import cn.wpgd.app.core.rules.CombinationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 3 #44, #45: committed deal state never aliases an event's nested lists, and a projection
 * never hands out the state's own lists (a Java caller could otherwise change state without a revision).
 */
class StateSnapshotImmutabilityTest {
    private val r = p1Reducer()

    private fun pairLead(state: DealState): ReduceResult.Accepted {
        val pair = r.legalActions(state, Seat.SOUTH).first { it.combination.type == CombinationType.PAIR && it.declaration.isNullOrEmpty() }
        return r.reduce(state, Command.Play(Seat.SOUTH, pair.cards.map { it.id }, null)).accepted()
    }

    @Test fun committedPlayDoesNotAliasTheEventsCombinationLists() {
        val state = r.started(1).state
        val played = pairLead(state).events.filterIsInstance<DealEvent.Played>().single()
        val ranks = played.combination.resolvedRanks.toMutableList()
        val after = DealEvents.apply(state, played.copy(combination = played.combination.copy(resolvedRanks = ranks)))
        val hash = StateHash.of(after)
        ranks[0] = ranks[0] + 1
        assertEquals(played.combination.resolvedRanks, after.trick.top!!.combination.resolvedRanks)
        assertEquals(hash, StateHash.of(after))
        val top = after.trick.top!!
        assertTrue(refusesJavaClear(top.cardIds), "cardIds")
        assertTrue(refusesJavaClear(top.combination.resolvedRanks), "resolvedRanks")
    }

    @Test fun projectionsDoNotExposeTheStatesLists() {
        val led = pairLead(r.started(1).state).state
        val passed = r.reduce(led, Command.Pass(Seat.EAST)).accepted().state
        assertEquals(listOf(Seat.EAST), passed.trick.passedSinceTop)
        val projector = SeatProjector(p1)
        for (table in listOf(projector.spectatorView(passed).table, projector.memberView(passed, Seat.NORTH).table)) {
            assertTrue(refusesJavaClear(table.passedSinceLastPlay), "passedSinceLastPlay")
            assertTrue(refusesJavaClear(table.finishOrder), "finishOrder")
        }
        assertEquals(listOf(Seat.EAST), passed.trick.passedSinceTop)
    }
}
