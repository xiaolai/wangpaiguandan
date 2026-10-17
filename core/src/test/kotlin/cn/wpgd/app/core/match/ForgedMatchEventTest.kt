package cn.wpgd.app.core.match

import cn.wpgd.app.core.state.DealEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Forged match events and malformed settlement records are refused, never applied or repaired. */
class ForgedMatchEventTest {
    private val r = m1Reducer()

    private fun singleTribute(wJokers: List<String>): MatchState {
        val w = low(27 - wJokers.size) + wJokers
        return r.reduce(matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to w))), MatchCommand.StartTribute).accepted().state
    }

    @Test fun playBeganMustNameTheResolvedLeader() {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        val given = r.run(start, MatchCommand.StartTribute, MatchCommand.GiveTribute(W, "0SA")).state
        val ready = r.run(given, MatchCommand.ReturnTribute(S, r.legalReturnCards(given, S).first())).state
        assertEquals(W, ready.leader)
        val genuine = r.reduce(ready, MatchCommand.OpenPlay).accepted().events.single() as MatchEvent.InDeal
        val began = genuine.event as DealEvent.PlayBegan
        assertEquals(W, began.leader)
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(ready, genuine.copy(event = began.copy(leader = S))) }
        assertEquals(W, MatchEvents.apply(ready, genuine).deal!!.turn)
    }

    @Test fun resistanceNeedsGiversHoldingBothBigJokers() {
        val rev = 100L
        // No holder and no jokers: would let the side skip tribute without resisting cards.
        val tribute = singleTribute(listOf("0BJ"))
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(tribute, MatchEvent.Resisted(rev, null, emptyList(), emptyList())) }
        // West holds one big joker only; the other is not West's to show.
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(tribute, MatchEvent.Resisted(rev, W, listOf(W), listOf("0BJ", "1BJ"))) }
        // A receiver is not a holder on the giving side; the same joker twice is not two jokers.
        val both = singleTribute(listOf("0BJ", "1BJ"))
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(both, MatchEvent.Resisted(rev, W, listOf(S), listOf("0BJ", "1BJ"))) }
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(both, MatchEvent.Resisted(rev, W, listOf(W), listOf("0BJ", "0BJ"))) }
        // A claimant who is not a giver.
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(both, MatchEvent.Resisted(rev, S, listOf(W), listOf("0BJ", "1BJ"))) }
        // The genuine resistance replays.
        val out = r.reduce(both, MatchCommand.Resist(W)).accepted()
        assertEquals(out.state, MatchEvents.replay(both, out.events))
    }

    @Test fun settlementDecodingIsAsStrictAsTheRestOfTheCodec() {
        val result = Settlement.settle(1, 2, listOf(S, E, N), Team.entries.associateWith { 2 }, Team.entries.associateWith { 0 }, r.settings)
        val event = MatchEventCodec.encode(MatchEvent.DealSettled(7, result))
        assertEquals(MatchEvent.DealSettled(7, result), MatchEventCodec.decode(Json.parseToJsonElement("[$event]")).single())
        val quoted = event.withResult { put("dealNumber", JsonPrimitive("1")) }
        assertFailsWith<IllegalArgumentException> { MatchEventCodec.decode(Json.parseToJsonElement("[$quoted]")) }

        // The checkpoint's previous result goes through the same strict reader.
        val state = matchAtTribute(r, listOf(S, E, N), handsWith(emptyMap()))
        val encoded = MatchStateCodec.encode(state)
        assertEquals(state, MatchStateCodec.decode(encoded))
        val previous = encoded.getValue("previous").jsonObject
        val forged = JsonObject(encoded + ("previous" to JsonObject(previous + ("dealLevel" to JsonPrimitive("2")))))
        assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(forged) }
    }

    @Test fun settlementDoesNotAliasTheCallersFinishOrder() {
        val order = mutableListOf(S, E, N)
        val result = Settlement.settle(1, 2, order, Team.entries.associateWith { 2 }, Team.entries.associateWith { 0 }, r.settings)
        order.clear()
        assertEquals(listOf(S, E, N), result.finishOrder)
        TributeRules.obligation(result)
    }

    /** A single tribute (West to South), its return made: the state before OpenPlay, and the steps. */
    private fun readyAfterReturn(): Triple<MatchState, MatchState, MatchState> {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        val given = r.run(start, MatchCommand.StartTribute, MatchCommand.GiveTribute(W, "0SA")).state
        val ready = r.run(given, MatchCommand.ReturnTribute(S, r.legalReturnCards(given, S).first())).state
        return Triple(start, given, ready)
    }

    /** Card moves inside a match are only the routed tributes and revealed returns, in the RETURN phase. */
    @Test fun cardMovesAreOnlyTheTributeAndReturnTransfers() {
        val (start, given, ready) = readyAfterReturn()
        assertEquals(MatchPhase.READY, ready.phase)
        val rev = ready.revision + 1
        // READY: any move would unbalance the hands (26/28) with no tribute behind it.
        val westCard = ready.deal!!.hands.getValue(W).first()
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(ready, MatchEvent.InDeal(rev, DealEvent.CardMoved(rev, W, S, westCard))) }
        // DEALT, before any tribute.
        val dealtCard = start.deal!!.hands.getValue(W).first()
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(start, MatchEvent.InDeal(rev, DealEvent.CardMoved(rev, W, S, dealtCard))) }
        // RETURN: South's return card is not due before the reveal; another West card is not the tribute.
        assertEquals(MatchPhase.RETURN, given.phase)
        val southCard = given.deal!!.hands.getValue(S).first()
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(given, MatchEvent.InDeal(rev, DealEvent.CardMoved(rev, S, W, southCard))) }
        val otherWest = given.deal.hands.getValue(W).first()
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(given, MatchEvent.InDeal(rev, DealEvent.CardMoved(rev, W, S, otherWest))) }
        // A deal may not open itself inside a match.
        assertFailsWith<IllegalArgumentException> {
            MatchEvents.apply(ready, MatchEvent.InDeal(rev, DealEvent.DealOpened(rev, ready.dealLevel, ready.baseProfileHash, W)))
        }
    }

    /** The lead after a return is resolved only once every tribute and return has moved (hands back to 27). */
    @Test fun leadAfterTheReturnNeedsEveryTransferMoved() {
        val (_, given, _) = readyAfterReturn()
        val out = r.reduce(given, MatchCommand.ReturnTribute(S, r.legalReturnCards(given, S).first())).accepted()
        val withoutMove = out.events.filterNot { it is MatchEvent.InDeal || it is MatchEvent.LeaderResolved }
        val before = MatchEvents.replay(given, withoutMove)
        val lead = out.events.filterIsInstance<MatchEvent.LeaderResolved>().single()
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(before, lead) }
        assertEquals(out.state, MatchEvents.replay(given, out.events))
    }

    /** Committed tribute and resistance state does not alias the event's lists. */
    @Test fun tributeStateDoesNotAliasTheEventsLists() {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        val genuine = r.reduce(start, MatchCommand.StartTribute).accepted().events.filterIsInstance<MatchEvent.TributeStarted>().single()
        val givers = genuine.givers.toMutableList()
        val receivers = genuine.receivers.toMutableList()
        val after = MatchEvents.apply(start, genuine.copy(givers = givers, receivers = receivers))
        givers.clear()
        receivers.clear()
        assertEquals(genuine.givers, after.tribute!!.obligation.givers)
        assertEquals(genuine.receivers, after.tribute.obligation.receivers)

        val both = singleTribute(listOf("0BJ", "1BJ"))
        val resisted = r.reduce(both, MatchCommand.Resist(W)).accepted().events.filterIsInstance<MatchEvent.Resisted>().single()
        val holders = resisted.holders.toMutableList()
        val jokers = resisted.bigJokerIds.toMutableList()
        val afterResist = MatchEvents.apply(both, resisted.copy(holders = holders, bigJokerIds = jokers))
        holders.clear()
        jokers.clear()
        assertEquals(resisted.holders, afterResist.tribute!!.resistedBy)
        assertEquals(resisted.bigJokerIds, afterResist.tribute.resistedJokers)
    }

    /** A tribute or return names a card its seat holds. */
    @Test fun tributeAndReturnCardsMustBeHeld() {
        val tribute = singleTribute(emptyList())
        val rev = tribute.revision + 1
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(tribute, MatchEvent.TributeGiven(rev, W, "not-a-card")) }
        val southCard = tribute.deal!!.hands.getValue(S).first()
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(tribute, MatchEvent.TributeGiven(rev, W, southCard)) }

        val (_, given, _) = readyAfterReturn()
        val westCard = given.deal!!.hands.getValue(W).first()
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(given, MatchEvent.ReturnChosen(given.revision + 1, S, W, westCard)) }
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(given, MatchEvent.ReturnChosen(given.revision + 1, S, W, "not-a-card")) }
    }

    /** A public count is the seat's true count, above zero. */
    @Test fun countEventsCarryTheTrueCount() {
        val (_, _, ready) = readyAfterReturn()
        val playing = r.reduce(ready, MatchCommand.OpenPlay).accepted().state
        val rev = playing.revision + 1
        val held = playing.deal!!.hands.getValue(E).size
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(playing, MatchEvent.CardCountReported(rev, E, -1)) }
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(playing, MatchEvent.CardCountReported(rev, E, held - 1)) }
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(playing, MatchEvent.CardCountAnswered(rev, S, E, 3)) }
        assertEquals(listOf(CountReport(E, held)), MatchEvents.apply(playing, MatchEvent.CardCountReported(rev, E, held)).reports)
    }

    /** A settlement must agree with the deal it settles: level, starting levels, winner and next level. */
    @Test fun settlementMustAgreeWithTheState() {
        val ended = r.advanceTo(r.newMatch(), MatchPhase.DEAL_ENDED)
        val out = r.reduce(ended, MatchCommand.Settle).accepted()
        val settled = out.events.filterIsInstance<MatchEvent.DealSettled>().single()
        val result = settled.result
        val forgeries = listOf(
            result.copy(dealLevel = 99),
            result.copy(nextLevel = 99),
            result.copy(nextLevel = result.outcome(result.winningTeam).levelAfter + 1),
            result.copy(winningTeam = result.winningTeam.other),
            result.copy(teams = result.teams.map { it.copy(levelBefore = it.levelBefore + 1) }),
            result.copy(teams = result.teams.take(1)),
            result.copy(matchWinner = result.winningTeam),
        )
        for (f in forgeries) assertFailsWith<IllegalArgumentException>("$f") { MatchEvents.apply(ended, settled.copy(result = f)) }
        assertEquals(out.state, MatchEvents.replay(ended, out.events))
        // The committed result does not alias the event's lists.
        val teams = result.teams.toMutableList()
        val after = MatchEvents.apply(ended, settled.copy(result = result.copy(teams = teams)))
        teams.clear()
        assertEquals(result.teams, after.previous!!.teams)
    }

    /**
     * #41, round 2: a settlement's outcomes must be coherent in themselves. A team that passes A
     * without an A attempt (here at level 2) ended the match.
     */
    @Test fun incoherentOutcomesCannotSettleOrEndTheMatch() {
        val ended = r.advanceTo(r.newMatch(), MatchPhase.DEAL_ENDED)
        val settled = r.reduce(ended, MatchCommand.Settle).accepted().events.filterIsInstance<MatchEvent.DealSettled>().single()
        val result = settled.result
        val winner = result.winningTeam
        fun edit(team: Team, change: (TeamOutcome) -> TeamOutcome) = result.copy(teams = result.teams.map { if (it.team == team) change(it) else it })
        val forgeries = mapOf(
            "passed A without an attempt" to edit(winner) { it.copy(passedA = true) }.copy(matchWinner = winner),
            "an A attempt below A" to edit(winner) { it.copy(aAttempt = true) },
            "a failed A without an attempt" to edit(winner.other) { it.copy(failedA = true, failuresAfter = it.failuresBefore + 1) },
            "an interruption below A" to edit(winner.other) { it.copy(interruption = true) },
            "the loser's level moved" to edit(winner.other) { it.copy(levelAfter = it.levelBefore + 1) },
            "the winner gained other than the gain" to edit(winner) { it.copy(levelAfter = it.levelAfter + 1) }.let { it.copy(nextLevel = it.outcome(winner).levelAfter) },
            "failures changed without a failure" to edit(winner.other) { it.copy(failuresAfter = it.failuresBefore + 1) },
            "a reset that kept failures" to edit(winner.other) { it.copy(reset = true, failuresAfter = 1) },
            "a partner place the finish order does not give" to result.copy(partnerPlace = if (result.partnerPlace == 2) 3 else 2),
        )
        for ((what, f) in forgeries) {
            assertFailsWith<IllegalArgumentException>(what) { MatchEvents.apply(ended, settled.copy(result = f)) }
        }
    }

    /**
     * #41, round 2: what only the profile decides (the gain for a partner place, the A rules) is
     * checked where the profile is known: replay through the reducer recomputes each settlement.
     */
    @Test fun replayThroughTheReducerRefusesASettlementTheProfileDoesNotGive() {
        val ended = r.advanceTo(r.newMatch(), MatchPhase.DEAL_ENDED)
        val out = r.reduce(ended, MatchCommand.Settle).accepted()
        val settled = out.events.filterIsInstance<MatchEvent.DealSettled>().single()
        val result = settled.result
        val winner = result.winningTeam
        // Coherent in itself (the profile-free checks pass), but one level more than this profile's gain.
        val forged = result.copy(
            gain = result.gain + 1,
            teams = result.teams.map { if (it.team == winner) it.copy(levelAfter = it.levelAfter + 1) else it },
            nextLevel = result.nextLevel + 1,
        )
        MatchEvents.apply(ended, settled.copy(result = forged))
        assertFailsWith<IllegalArgumentException> { r.replay(ended, listOf(settled.copy(result = forged))) }
        assertEquals(out.state, r.replay(ended, out.events))
    }

    private fun JsonObject.withResult(edit: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val result = getValue("result").jsonObject.toMutableMap().apply(edit)
        return JsonObject(this + ("result" to JsonObject(result)))
    }
}
