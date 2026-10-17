package cn.wpgd.app.core.match

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * WI-06 persistence form of the typed events and of a checkpoint state: every event and every
 * state of seeded whole matches survives encode → text → decode unchanged, the decoded events alone
 * replay to the same state, and malformed input throws instead of being repaired.
 */
class MatchCodecTest {
    private val reducer = m1Reducer()

    @Test fun everyEventAndStateOfSeededMatchesRoundTrips() {
        var events = 0
        val types = HashSet<String>()
        for (seed in 1L..6L) {
            val log = ArrayList<MatchEvent>()
            val final = playMatch(reducer, seed) { step ->
                val text = Json.encodeToString(JsonElement.serializer(), MatchEventCodec.encode(step.events))
                val decoded = MatchEventCodec.decode(Json.parseToJsonElement(text))
                assertEquals(step.events, decoded, "seed $seed revision ${step.after.revision}")
                log += decoded
                events += decoded.size
                decoded.forEach { types += MatchEventCodec.encode(it)["t"].toString() }

                val stateText = MatchStateCodec.canonicalJson(step.after)
                val state = MatchStateCodec.decode(Json.parseToJsonElement(stateText))
                assertEquals(step.after, state)
                assertEquals(MatchStateCodec.hash(step.after), MatchStateCodec.hash(state))
            }
            assertEquals(final, MatchEvents.replay(reducer.newMatch(), log), "seed $seed: decoded events replay to the state")
        }
        assertTrue(events > 1_000, "covered $events events")
        assertTrue(types.size >= 10, "covered event types $types")
    }

    @Test fun hashIsStableAndSensitive() {
        var s = reducer.newMatch()
        val a = MatchStateCodec.hash(s)
        assertEquals(a, MatchStateCodec.hash(reducer.newMatch()))
        s = reducer.advanceTo(s, MatchPhase.PLAYING)
        assertNotEquals(a, MatchStateCodec.hash(s))
        assertEquals(64, MatchStateCodec.hash(s).length)
    }

    @Test fun malformedInputThrows() {
        val s = reducer.advanceTo(reducer.newMatch(), MatchPhase.PLAYING)
        val good = MatchStateCodec.encode(s)
        assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(JsonObject(good + ("extra" to JsonPrimitive(1)))) }
        assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(JsonObject(good - "deal")) }
        assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(JsonObject(good + ("format" to JsonPrimitive(99)))) }
        assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(JsonObject(good + ("revision" to JsonPrimitive("5")))) }

        // A repeated card id in a hand or in the played cards is malformed, not silently merged.
        val deal = good.getValue("deal") as JsonObject
        val hands = deal.getValue("hands") as JsonObject
        val south = hands.getValue("SOUTH") as JsonArray
        val doubledHand = JsonObject(deal + ("hands" to JsonObject(hands + ("SOUTH" to JsonArray(south + south.first())))))
        assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(JsonObject(good + ("deal" to doubledHand))) }
        val doubledPlayed = JsonObject(deal + ("played" to JsonArray(listOf(south.first(), south.first()))))
        assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(JsonObject(good + ("deal" to doubledPlayed))) }

        val event = MatchEventCodec.encode(MatchEvent.ReturnMade(3, cn.wpgd.app.core.state.Seat.EAST))
        assertFailsWith<IllegalArgumentException> { MatchEventCodec.decode(JsonArray(listOf(JsonObject(event + ("t" to JsonPrimitive("no_such")))))) }
        assertFailsWith<IllegalArgumentException> { MatchEventCodec.decode(JsonArray(listOf(JsonObject(event + ("x" to JsonPrimitive(1)))))) }
        assertFailsWith<IllegalArgumentException> { MatchEventCodec.decode(JsonArray(listOf(JsonObject(event - "seat")))) }
        assertFailsWith<IllegalArgumentException> { MatchEventCodec.decode(JsonObject(event)) }
    }

    @Test fun aFutureEventSchemaDecodesButIsRefusedOnApply() {
        val e = MatchEventCodec.encode(MatchEvent.DealStarted(1, 1, 2))
        val future = MatchEventCodec.decode(JsonArray(listOf(JsonObject(e + ("v" to JsonPrimitive(MATCH_EVENT_SCHEMA_VERSION + 1)))))).single()
        assertEquals(MATCH_EVENT_SCHEMA_VERSION + 1, future.schemaVersion)
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(reducer.newMatch(), future) }
        assertEquals("deal_started", e.jsonObject["t"].let { (it as JsonPrimitive).content })
    }
}
