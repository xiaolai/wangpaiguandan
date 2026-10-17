package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.Combination
import cn.wpgd.app.core.rules.Sha256
import cn.wpgd.app.core.rules.WildcardAssignment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Host-internal state hash: SHA-256 (lowercase hex) of a canonical JSON form of the whole
 * [DealState], keys sorted, sets sorted, no whitespace. It covers every hand, so it must never be
 * sent to a member or placed in a projection, receipt or diagnostic (a player near the end of a
 * deal could match it against the few possible hand assignments; plan WI-05). Tests and the host
 * use it to compare states.
 */
object StateHash {
    /** Bump when the canonical form changes, so hashes of the old and new form never collide. */
    private const val CANONICAL_FORMAT = 1

    fun of(state: DealState): String = Sha256.hex(canonicalJson(state))

    internal fun canonicalJson(state: DealState): String =
        Json.encodeToString(JsonElement.serializer(), canonical(state))

    private fun canonical(s: DealState): JsonElement = obj(
        "finishOrder" to JsonArray(s.finishOrder.map { JsonPrimitive(it.name) }),
        "format" to JsonPrimitive(CANONICAL_FORMAT),
        "hands" to obj(*Seat.entries.map { it.name to ids(s.hands.getValue(it)) }.toTypedArray()),
        "leader" to seat(s.leader),
        "level" to JsonPrimitive(s.level),
        "phase" to JsonPrimitive(s.phase.name),
        "played" to ids(s.played),
        "profileHash" to JsonPrimitive(s.profileHash),
        "revision" to JsonPrimitive(s.revision),
        "trick" to obj(
            "passedSinceTop" to JsonArray(s.trick.passedSinceTop.map { JsonPrimitive(it.name) }),
            "plays" to JsonArray(s.trick.plays.map(::play)),
        ),
        "turn" to seat(s.turn),
    )

    private fun play(p: TrickPlay): JsonElement = obj(
        "cardIds" to ids(p.cardIds),
        "combination" to combination(p.combination),
        "declaration" to (p.declaration?.let { d -> JsonArray(d.map(::assignment)) } ?: JsonNull),
        "seat" to JsonPrimitive(p.seat.name),
    )

    private fun combination(c: Combination): JsonElement = obj(
        "key" to JsonPrimitive(c.key),
        "rank" to JsonPrimitive(c.rank),
        "resolvedRanks" to JsonArray(c.resolvedRanks.map(::JsonPrimitive)),
        "sequence" to JsonArray(c.sequence.map(::JsonPrimitive)),
        "size" to JsonPrimitive(c.size),
        "suit" to JsonPrimitive(c.suit?.name),
        "type" to JsonPrimitive(c.type.name),
    )

    private fun assignment(a: WildcardAssignment): JsonElement =
        obj("rank" to JsonPrimitive(a.rank), "suit" to JsonPrimitive(a.suit?.name))

    private fun ids(ids: Collection<String>) = JsonArray(ids.sorted().map(::JsonPrimitive))

    private fun seat(s: Seat?) = JsonPrimitive(s?.name)

    private fun obj(vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(entries.sortedBy { it.first }.toMap(LinkedHashMap()))
}
