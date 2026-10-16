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

    private fun JsonObject.withResult(edit: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val result = getValue("result").jsonObject.toMutableMap().apply(edit)
        return JsonObject(this + ("result" to JsonObject(result)))
    }
}
