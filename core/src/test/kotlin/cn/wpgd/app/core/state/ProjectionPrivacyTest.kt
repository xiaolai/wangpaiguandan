package cn.wpgd.app.core.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan section 8. P1: a member's serialized projection (and the AI observation for that seat) is
 * byte-identical when only cards that member may not see change. P3: the spectator view holds no
 * hand, no hint and no unused wildcard declaration. Also: no projection carries the state hash or
 * an exact opponent hand count.
 */
class ProjectionPrivacyTest {
    private val r = p1Reducer()
    private val projector = SeatProjector(p1)

    /** States sampled along several seeded deals, including openings, mid-tricks, partner leads and the end. */
    private val samples: List<DealState> by lazy {
        buildList {
            for (seed in listOf(2L, 13L, 31L, 64L)) playDeal(r, seed, level = if (seed % 2 == 0L) 2 else 9) { if (it.after.revision % 5 == 1L || it.after.phase == Phase.ENDED) add(it.after) }
        }
    }

    /** Re-deals the cards of every seat in [hidden] among those seats at random sizes; seats that have gone out stay empty. */
    private fun scramble(state: DealState, hidden: Set<Seat>, random: Random): DealState {
        val holders = hidden.filter { state.hands.getValue(it).isNotEmpty() }
        val pool = holders.flatMap { state.hands.getValue(it) }.shuffled(random)
        if (holders.isEmpty()) return state
        // Distinct cut points: every holder keeps at least one card; the sizes otherwise change freely.
        val cuts = (1 until pool.size).shuffled(random).take(holders.size - 1).sorted() + pool.size
        var from = 0
        val newHands = state.hands.toMutableMap()
        holders.forEachIndexed { i, seat ->
            newHands[seat] = pool.subList(from, cuts[i]).toSet()
            from = cuts[i]
        }
        check(from == pool.size)
        return state.copy(hands = newHands)
    }

    @Test fun memberProjectionIgnoresEveryOtherSeatsCards() {
        val random = Random(49)
        var compared = 0
        var countsChanged = 0
        for (state in samples) for (viewer in Seat.entries) {
            val reference = ProjectionCodec.encode(projector.memberView(state, viewer))
            val referenceAi = ProjectionCodec.encode(projector.aiObservation(state, viewer))
            repeat(4) {
                val other = scramble(state, Seat.entries.toSet() - viewer, random)
                if (Seat.entries.any { other.hands.getValue(it).size != state.hands.getValue(it).size }) countsChanged++
                assertContentEquals(reference, ProjectionCodec.encode(projector.memberView(other, viewer)), "member $viewer at r${state.revision}")
                assertContentEquals(referenceAi, ProjectionCodec.encode(projector.aiObservation(other, viewer)), "AI $viewer at r${state.revision}")
                compared++
            }
        }
        assertTrue(compared > 200, "only $compared comparisons")
        assertTrue(countsChanged > compared / 2, "scramble rarely changed hand sizes ($countsChanged of $compared), so counts were not exercised")
    }

    @Test fun memberProjectionChangesWhenTheMembersOwnCardsChange() {
        // Guards the test above against a projection that ignores hands altogether.
        val state = samples.first()
        val viewer = state.turn!!
        val mine = state.hands.getValue(viewer).sorted()
        val donor = Seat.entries.first { it != viewer && state.hands.getValue(it).isNotEmpty() }
        val theirs = state.hands.getValue(donor).sorted()
        val swapped = state.copy(
            hands = state.hands + mapOf(viewer to (mine.drop(1) + theirs.first()).toSet(), donor to (theirs.drop(1) + mine.first()).toSet()),
        )
        assertFalse(ProjectionCodec.encode(projector.memberView(state, viewer)).contentEquals(ProjectionCodec.encode(projector.memberView(swapped, viewer))))
    }

    @Test fun memberProjectionHoldsOnlyOwnAndTableCards() {
        for (state in samples) for (viewer in Seat.entries) {
            val view = projector.memberView(state, viewer)
            assertEquals(state.hands.getValue(viewer).sorted(), view.hand.map { it.id })
            val ids = cardIds(parse(ProjectionCodec.encode(view)))
            val allowed = state.hands.getValue(viewer) + state.trick.plays.flatMap { it.cardIds }
            assertTrue(allowed.containsAll(ids), "member $viewer sees ${ids - allowed}")
        }
    }

    @Test fun spectatorViewHasNoHandHintOrUnusedDeclaration() {
        val random = Random(3)
        for (state in samples) {
            val bytes = ProjectionCodec.encode(projector.spectatorView(state))
            val tree = parse(bytes)
            val keys = keys(tree)
            for (k in FORBIDDEN_PUBLIC_KEYS) assertFalse(k in keys, "spectator view has '$k'")
            // Cards and declarations appear only inside the plays of the current trick.
            assertEquals(state.trick.plays.flatMap { it.cardIds }.sorted(), cardIds(tree).sorted())
            assertEquals(state.trick.plays.size, countKey(tree, "declaration"))
            // Any change to any hand leaves the public bytes unchanged.
            val other = scramble(state, Seat.entries.toSet(), random)
            assertContentEquals(bytes, ProjectionCodec.encode(projector.spectatorView(other)))
        }
    }

    @Test fun noProjectionCarriesTheStateHashOrHandCounts() {
        for (state in samples) {
            val hash = StateHash.of(state)
            val views = Seat.entries.map { ProjectionCodec.encode(projector.memberView(state, it)) } + ProjectionCodec.encode(projector.spectatorView(state))
            for (bytes in views) {
                val text = bytes.decodeToString()
                assertFalse(hash in text, "state hash leaked")
                for (k in keys(parse(bytes))) assertFalse(k.contains("count", ignoreCase = true) || k.contains("size", ignoreCase = true), "key '$k' looks like a hand count")
            }
        }
    }

    @Test fun serializationIsDeterministic() {
        val state = samples[samples.size / 2]
        val a = ProjectionCodec.encode(projector.memberView(state, Seat.WEST))
        val b = ProjectionCodec.encode(SeatProjector(p1).memberView(state.copy(), Seat.WEST))
        assertContentEquals(a, b)
        // Fixed key order: the member view starts with its schema version and seat, then the public table.
        assertTrue(a.decodeToString().startsWith("{\"schemaVersion\":1,\"seat\":\"WEST\",\"table\":{"), a.decodeToString().take(80))
    }

    @Test fun eventVisibilityRoutesPrivateHandsOnlyToTheirSeat() {
        val opened = r.started(6)
        for (e in opened.events) when (e) {
            is DealEvent.HandDealt -> {
                assertEquals(Visibility.SeatPrivate(e.seat), e.visibility)
                assertTrue(e.visibility.visibleTo(Audience.Member(e.seat)))
                for (s in Seat.entries - e.seat) assertFalse(e.visibility.visibleTo(Audience.Member(s)))
                assertFalse(e.visibility.visibleTo(Audience.Spectator))
                assertTrue(e.visibility.visibleTo(Audience.Host))
            }
            else -> assertEquals(Visibility.Public, e.visibility, "$e")
        }
        assertFalse(Visibility.HostOnly.visibleTo(Audience.Spectator))
        assertFalse(Seat.entries.any { Visibility.HostOnly.visibleTo(Audience.Member(it)) })
        assertTrue(Visibility.HostOnly.visibleTo(Audience.Host))
    }

    private fun parse(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

    private fun keys(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap(::keys)
        is JsonArray -> e.flatMap(::keys).toSet()
        else -> emptySet()
    }

    private fun countKey(e: JsonElement, key: String): Int = when (e) {
        is JsonObject -> (if (key in e) 1 else 0) + e.values.sumOf { countKey(it, key) }
        is JsonArray -> e.sumOf { countKey(it, key) }
        else -> 0
    }

    /** Every card id in a projection: the "id" fields of card objects. */
    private fun cardIds(e: JsonElement): List<String> = when (e) {
        is JsonObject -> listOfNotNull((e["id"] as? JsonPrimitive)?.content) + e.values.flatMap(::cardIds)
        is JsonArray -> e.flatMap(::cardIds)
        else -> emptyList()
    }

    private companion object {
        val FORBIDDEN_PUBLIC_KEYS = setOf("hand", "hands", "hint", "hints", "legalActions", "hasLegalPlay", "mayPass", "yourTurn")
    }
}
