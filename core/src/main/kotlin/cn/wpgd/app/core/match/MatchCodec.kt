package cn.wpgd.app.core.match

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Combination
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.Sha256
import cn.wpgd.app.core.rules.WildcardAssignment
import cn.wpgd.app.core.state.DealEvent
import cn.wpgd.app.core.state.DealState
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.Trick
import cn.wpgd.app.core.state.TrickPlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The persisted form of the WI-03/WI-04 typed events (GDN-091; WI-06 writes it to disk). The
 * schema owner keeps the codec, because only `:core` can rebuild its immutable types. JSON, keys
 * sorted, no display text. Decoding is strict: an unknown event type, a missing or extra field, or
 * a value of the wrong kind throws [IllegalArgumentException]; nothing is defaulted or repaired.
 * Each event carries its own schema version (`v`); [MatchEvents.apply] refuses versions it does not know.
 */
object MatchEventCodec {
    fun encode(events: List<MatchEvent>): JsonArray = JsonArray(events.map(::encode))

    fun decode(json: JsonElement): List<MatchEvent> = json.arr().map(::decodeMatch)

    fun encode(e: MatchEvent): JsonObject = when (e) {
        is MatchEvent.InDeal -> m(e, "in_deal", "e" to encodeDeal(e.event))
        is MatchEvent.DealStarted -> m(e, "deal_started", "dealNumber" to num(e.dealNumber), "level" to num(e.level))
        is MatchEvent.CutCardDrawn -> m(e, "cut_card_drawn", "cardId" to str(e.cardId), "seat" to seat(e.seat))
        is MatchEvent.TributeStarted -> m(e, "tribute_started", "kind" to str(e.kind.name), "givers" to seats(e.givers), "receivers" to seats(e.receivers))
        is MatchEvent.Resisted -> m(e, "resisted", "claimedBy" to seat(e.claimedBy), "holders" to seats(e.holders), "bigJokerIds" to strs(e.bigJokerIds))
        is MatchEvent.TributeGiven -> m(e, "tribute_given", "seat" to seat(e.seat), "cardId" to str(e.cardId))
        is MatchEvent.TributeRouted -> m(e, "tribute_routed", "transfers" to transfers(e.transfers))
        is MatchEvent.ReturnChosen -> m(e, "return_chosen", "seat" to seat(e.seat), "to" to seat(e.to), "cardId" to str(e.cardId))
        is MatchEvent.ReturnMade -> m(e, "return_made", "seat" to seat(e.seat))
        is MatchEvent.ReturnsRevealed -> m(e, "returns_revealed", "transfers" to transfers(e.transfers))
        is MatchEvent.LeaderResolved -> m(e, "leader_resolved", "leader" to seat(e.leader), "reason" to str(e.reason.name))
        is MatchEvent.CardCountReported -> m(e, "card_count_reported", "seat" to seat(e.seat), "count" to num(e.count))
        is MatchEvent.CardCountAnswered -> m(e, "card_count_answered", "asker" to seat(e.asker), "seat" to seat(e.seat), "count" to num(e.count))
        is MatchEvent.DealSettled -> m(e, "deal_settled", "result" to Json.encodeToJsonElement(DealResult.serializer(), e.result))
        is MatchEvent.MatchEnded -> m(e, "match_ended", "winner" to str(e.winner.name))
        is MatchEvent.MatchRestarted -> m(e, "match_restarted")
    }

    private fun decodeMatch(json: JsonElement): MatchEvent {
        val o = Fields(json.obj())
        val r = o.long("r")
        val v = o.int("v")
        val event = when (val type = o.string("t")) {
            "in_deal" -> MatchEvent.InDeal(r, decodeDeal(o.get("e")), v)
            "deal_started" -> MatchEvent.DealStarted(r, o.int("dealNumber"), o.int("level"), v)
            "cut_card_drawn" -> MatchEvent.CutCardDrawn(r, o.string("cardId"), o.seat("seat"), v)
            "tribute_started" -> MatchEvent.TributeStarted(r, TributeKind.valueOf(o.string("kind")), o.seats("givers"), o.seats("receivers"), v)
            "resisted" -> MatchEvent.Resisted(r, o.seatOrNull("claimedBy"), o.seats("holders"), o.strings("bigJokerIds"), v)
            "tribute_given" -> MatchEvent.TributeGiven(r, o.seat("seat"), o.string("cardId"), v)
            "tribute_routed" -> MatchEvent.TributeRouted(r, o.transfers("transfers"), v)
            "return_chosen" -> MatchEvent.ReturnChosen(r, o.seat("seat"), o.seat("to"), o.string("cardId"), v)
            "return_made" -> MatchEvent.ReturnMade(r, o.seat("seat"), v)
            "returns_revealed" -> MatchEvent.ReturnsRevealed(r, o.transfers("transfers"), v)
            "leader_resolved" -> MatchEvent.LeaderResolved(r, o.seat("leader"), LeadReason.valueOf(o.string("reason")), v)
            "card_count_reported" -> MatchEvent.CardCountReported(r, o.seat("seat"), o.int("count"), v)
            "card_count_answered" -> MatchEvent.CardCountAnswered(r, o.seat("asker"), o.seat("seat"), o.int("count"), v)
            "deal_settled" -> MatchEvent.DealSettled(r, MatchStateCodec.dealResult(o.get("result")), v)
            "match_ended" -> MatchEvent.MatchEnded(r, Team.valueOf(o.string("winner")), v)
            "match_restarted" -> MatchEvent.MatchRestarted(r, v)
            else -> throw IllegalArgumentException("unknown match event type '$type'")
        }
        o.requireAllRead()
        return event
    }

    private fun encodeDeal(e: DealEvent): JsonObject = when (e) {
        is DealEvent.DealOpened -> d(e, "deal_opened", "level" to num(e.level), "profileHash" to str(e.profileHash), "leader" to seat(e.leader))
        is DealEvent.Dealt -> d(e, "dealt", "level" to num(e.level), "profileHash" to str(e.profileHash))
        is DealEvent.CardMoved -> d(e, "card_moved", "from" to seat(e.from), "to" to seat(e.to), "cardId" to str(e.cardId))
        is DealEvent.PlayBegan -> d(e, "play_began", "leader" to seat(e.leader))
        is DealEvent.HandDealt -> d(e, "hand_dealt", "seat" to seat(e.seat), "cardIds" to strs(e.cardIds))
        is DealEvent.Played -> d(
            e, "played", "seat" to seat(e.seat), "cardIds" to strs(e.cardIds),
            "declaration" to (e.declaration?.let(::encodeAssignments) ?: JsonNull), "combination" to MatchStateCodec.combination(e.combination),
        )
        is DealEvent.Passed -> d(e, "passed", "seat" to seat(e.seat))
        is DealEvent.SeatFinished -> d(e, "seat_finished", "seat" to seat(e.seat), "place" to num(e.place))
        is DealEvent.TrickEnded -> d(e, "trick_ended", "winner" to seat(e.winner), "nextLeader" to seat(e.nextLeader), "partnerLead" to JsonPrimitive(e.partnerLead))
        is DealEvent.TurnMoved -> d(e, "turn_moved", "seat" to seat(e.seat))
        is DealEvent.DealEnded -> d(e, "deal_ended", "finishOrder" to seats(e.finishOrder), "unfinished" to seats(e.unfinished))
    }

    private fun decodeDeal(json: JsonElement): DealEvent {
        val o = Fields(json.obj())
        val r = o.long("r")
        val v = o.int("v")
        val event = when (val type = o.string("t")) {
            "deal_opened" -> DealEvent.DealOpened(r, o.int("level"), o.string("profileHash"), o.seat("leader"), v)
            "dealt" -> DealEvent.Dealt(r, o.int("level"), o.string("profileHash"), v)
            "card_moved" -> DealEvent.CardMoved(r, o.seat("from"), o.seat("to"), o.string("cardId"), v)
            "play_began" -> DealEvent.PlayBegan(r, o.seat("leader"), v)
            "hand_dealt" -> DealEvent.HandDealt(r, o.seat("seat"), o.strings("cardIds"), v)
            "played" -> DealEvent.Played(
                r, o.seat("seat"), o.strings("cardIds"),
                o.nullable("declaration")?.let(MatchStateCodec::assignments), MatchStateCodec.combination(o.get("combination")), v,
            )
            "passed" -> DealEvent.Passed(r, o.seat("seat"), v)
            "seat_finished" -> DealEvent.SeatFinished(r, o.seat("seat"), o.int("place"), v)
            "trick_ended" -> DealEvent.TrickEnded(r, o.seat("winner"), o.seat("nextLeader"), o.boolean("partnerLead"), v)
            "turn_moved" -> DealEvent.TurnMoved(r, o.seat("seat"), v)
            "deal_ended" -> DealEvent.DealEnded(r, o.seats("finishOrder"), o.seats("unfinished"), v)
            else -> throw IllegalArgumentException("unknown deal event type '$type'")
        }
        o.requireAllRead()
        return event
    }

    private fun m(e: MatchEvent, type: String, vararg fields: Pair<String, JsonElement>) =
        obj("t" to str(type), "v" to num(e.schemaVersion), "r" to JsonPrimitive(e.revision), *fields)

    private fun d(e: DealEvent, type: String, vararg fields: Pair<String, JsonElement>) =
        obj("t" to str(type), "v" to num(e.schemaVersion), "r" to JsonPrimitive(e.revision), *fields)

    private fun transfers(list: List<CardTransfer>) = JsonArray(list.map { obj("from" to seat(it.from), "to" to seat(it.to), "cardId" to str(it.cardId)) })

    private fun Fields.transfers(key: String): List<CardTransfer> = get(key).arr().map {
        val f = Fields(it.obj())
        CardTransfer(f.seat("from"), f.seat("to"), f.string("cardId")).also { f.requireAllRead() }
    }
}

/**
 * The checkpoint form of a [MatchState] and its host-internal hash (WI-06). The hash covers every
 * hand, so like [cn.wpgd.app.core.state.StateHash] it must never leave the host: it is written only
 * to the host's own event log and checkpoints, never to a member, receipt or diagnostic.
 *
 * [decode] rebuilds the state through the normal constructors, so their structural checks run; it
 * does not judge card conservation or turn order (the restore validator in `:session` does, and
 * quarantines instead of repairing).
 */
object MatchStateCodec {
    /**
     * Bump when the encoded form changes, so old and new hashes never collide. 2 (2026-10-16): adds
     * `matchNumber`. Format 1 is refused, not migrated: it was never released (pre-release reset,
     * storage schema 3 in `:session`).
     */
    const val FORMAT = 2

    fun hash(state: MatchState): String = Sha256.hex(canonicalJson(state))

    fun canonicalJson(state: MatchState): String = Json.encodeToString(JsonElement.serializer(), encode(state))

    fun encode(s: MatchState): JsonObject = obj(
        "format" to num(FORMAT),
        "phase" to str(s.phase.name),
        "matchNumber" to num(s.matchNumber),
        "baseProfileHash" to str(s.baseProfileHash),
        "matchProfileHash" to str(s.matchProfileHash),
        "levels" to teamMap(s.levels),
        "aFailures" to teamMap(s.aFailures),
        "dealNumber" to num(s.dealNumber),
        "dealLevel" to num(s.dealLevel),
        "deal" to (s.deal?.let(::deal) ?: JsonNull),
        "previous" to (s.previous?.let { Json.encodeToJsonElement(DealResult.serializer(), it) } ?: JsonNull),
        "tribute" to (s.tribute?.let(::tribute) ?: JsonNull),
        "leader" to seat(s.leader),
        "leadReason" to (s.leadReason?.let { str(it.name) } ?: JsonNull),
        "cutCard" to (s.cutCard?.let(::str) ?: JsonNull),
        "reports" to JsonArray(s.reports.map { obj("seat" to seat(it.seat), "count" to num(it.count)) }),
        "answers" to JsonArray(s.answers.map { obj("asker" to seat(it.asker), "seat" to seat(it.seat), "count" to num(it.count)) }),
        "winner" to (s.winner?.let { str(it.name) } ?: JsonNull),
        "revision" to JsonPrimitive(s.revision),
    )

    fun decode(json: JsonElement): MatchState {
        val o = Fields(json.obj())
        val format = o.int("format")
        require(format == FORMAT) { "unsupported state format $format" }
        val state = MatchState(
            phase = MatchPhase.valueOf(o.string("phase")),
            matchNumber = o.int("matchNumber"),
            baseProfileHash = o.string("baseProfileHash"),
            matchProfileHash = o.string("matchProfileHash"),
            levels = teamMap(o.get("levels")),
            aFailures = teamMap(o.get("aFailures")),
            dealNumber = o.int("dealNumber"),
            dealLevel = o.int("dealLevel"),
            deal = o.nullable("deal")?.let(::deal),
            previous = o.nullable("previous")?.let(::dealResult),
            tribute = o.nullable("tribute")?.let(::tribute),
            leader = o.seatOrNull("leader"),
            leadReason = o.nullable("leadReason")?.let { LeadReason.valueOf(it.string()) },
            cutCard = o.nullable("cutCard")?.string(),
            reports = o.get("reports").arr().map { e -> Fields(e.obj()).let { CountReport(it.seat("seat"), it.int("count")).also { _ -> it.requireAllRead() } } },
            answers = o.get("answers").arr().map { e -> Fields(e.obj()).let { CountAnswer(it.seat("asker"), it.seat("seat"), it.int("count")).also { _ -> it.requireAllRead() } } },
            winner = o.nullable("winner")?.let { Team.valueOf(it.string()) },
            revision = o.long("revision"),
        )
        o.requireAllRead()
        return state
    }

    private fun deal(d: DealState): JsonObject = obj(
        "phase" to str(d.phase.name),
        "level" to num(d.level),
        "profileHash" to str(d.profileHash),
        "hands" to obj(*Seat.entries.map { it.name to strs(d.hands.getValue(it).sorted()) }.toTypedArray()),
        "played" to strs(d.played.sorted()),
        "trick" to obj(
            "plays" to JsonArray(d.trick.plays.map(::play)),
            "passedSinceTop" to seats(d.trick.passedSinceTop),
        ),
        "leader" to seat(d.leader),
        "turn" to seat(d.turn),
        "finishOrder" to seats(d.finishOrder),
        "revision" to JsonPrimitive(d.revision),
    )

    private fun deal(json: JsonElement): DealState {
        val o = Fields(json.obj())
        val hands = Fields(o.get("hands").obj())
        val trick = Fields(o.get("trick").obj())
        val state = DealState(
            phase = Phase.valueOf(o.string("phase")),
            level = o.int("level"),
            profileHash = o.string("profileHash"),
            hands = Seat.entries.associateWith { distinctIds(hands.strings(it.name), "hand ${it.name}") }.also { hands.requireAllRead() },
            played = distinctIds(o.strings("played"), "played"),
            trick = Trick(trick.get("plays").arr().map(::play), trick.seats("passedSinceTop")).also { trick.requireAllRead() },
            leader = o.seatOrNull("leader"),
            turn = o.seatOrNull("turn"),
            finishOrder = o.seats("finishOrder"),
            revision = o.long("revision"),
        )
        o.requireAllRead()
        return state
    }

    /** A set of card ids decoded from an array: a repeated id is malformed, never silently merged. */
    private fun distinctIds(ids: List<String>, what: String): Set<String> =
        ids.toSet().also { require(it.size == ids.size) { "duplicate card id in $what" } }

    private fun play(p: TrickPlay): JsonObject = obj(
        "seat" to seat(p.seat),
        "cardIds" to strs(p.cardIds),
        "declaration" to (p.declaration?.let(::encodeAssignments) ?: JsonNull),
        "combination" to combination(p.combination),
    )

    private fun play(json: JsonElement): TrickPlay {
        val o = Fields(json.obj())
        return TrickPlay(o.seat("seat"), o.strings("cardIds"), o.nullable("declaration")?.let(::assignments), combination(o.get("combination")))
            .also { o.requireAllRead() }
    }

    private fun tribute(t: TributeState): JsonObject = obj(
        "kind" to str(t.obligation.kind.name),
        "givers" to seats(t.obligation.givers),
        "receivers" to seats(t.obligation.receivers),
        "given" to seatMap(t.given.mapValues { str(it.value) }),
        "routes" to seatMap(t.routes.mapValues { seat(it.value) }),
        "returns" to seatMap(t.returns.mapValues { str(it.value) }),
        "revealed" to JsonPrimitive(t.revealed),
        "resistedBy" to (t.resistedBy?.let(::seats) ?: JsonNull),
        "resistedJokers" to strs(t.resistedJokers),
    )

    private fun tribute(json: JsonElement): TributeState {
        val o = Fields(json.obj())
        return TributeState(
            obligation = TributeObligation(TributeKind.valueOf(o.string("kind")), o.seats("givers"), o.seats("receivers")),
            given = seatMap(o.get("given")) { it.string() },
            routes = seatMap(o.get("routes")) { Seat.valueOf(it.string()) },
            returns = seatMap(o.get("returns")) { it.string() },
            revealed = o.boolean("revealed"),
            resistedBy = o.nullable("resistedBy")?.arr()?.map { Seat.valueOf(it.string()) },
            resistedJokers = o.strings("resistedJokers"),
        ).also { o.requireAllRead() }
    }

    internal fun combination(c: Combination): JsonObject = obj(
        "type" to str(c.type.name),
        "size" to num(c.size),
        "rank" to (c.rank?.let(::num) ?: JsonNull),
        "sequence" to JsonArray(c.sequence.map(::num)),
        "suit" to (c.suit?.let { str(it.name) } ?: JsonNull),
        "resolvedRanks" to JsonArray(c.resolvedRanks.map(::num)),
        "key" to num(c.key),
    )

    internal fun combination(json: JsonElement): Combination {
        val o = Fields(json.obj())
        return Combination(
            type = CombinationType.valueOf(o.string("type")),
            size = o.int("size"),
            rank = o.nullable("rank")?.int(),
            sequence = o.get("sequence").arr().map { it.int() },
            suit = o.nullable("suit")?.let { Suit.valueOf(it.string()) },
            resolvedRanks = o.get("resolvedRanks").arr().map { it.int() },
            key = o.int("key"),
        ).also { o.requireAllRead() }
    }

    /**
     * A [DealResult] read field by field with the strict readers, so a wrong kind (a quoted number,
     * a string flag) throws as it does everywhere else in this codec. Encoding stays the serializer's.
     */
    internal fun dealResult(json: JsonElement): DealResult {
        val o = Fields(json.obj())
        return DealResult(
            dealNumber = o.int("dealNumber"),
            dealLevel = o.int("dealLevel"),
            finishOrder = o.seats("finishOrder"),
            placements = o.get("placements").arr().map { e ->
                val p = Fields(e.obj())
                Placement(p.seat("seat"), p.int("bestPlace"), p.int("worstPlace"), PlaceBasis.valueOf(p.string("basis"))).also { p.requireAllRead() }
            },
            completion = Completion.valueOf(o.string("completion")),
            completionPolicy = o.string("completionPolicy"),
            winningTeam = Team.valueOf(o.string("winningTeam")),
            partnerPlace = o.int("partnerPlace"),
            gain = o.int("gain"),
            teams = o.get("teams").arr().map { e ->
                val t = Fields(e.obj())
                TeamOutcome(
                    team = Team.valueOf(t.string("team")),
                    levelBefore = t.int("levelBefore"),
                    levelAfter = t.int("levelAfter"),
                    failuresBefore = t.int("failuresBefore"),
                    failuresAfter = t.int("failuresAfter"),
                    aAttempt = t.boolean("aAttempt"),
                    interruption = t.boolean("interruption"),
                    passedA = t.boolean("passedA"),
                    failedA = t.boolean("failedA"),
                    reset = t.boolean("reset"),
                ).also { t.requireAllRead() }
            },
            matchWinner = o.nullable("matchWinner")?.let { Team.valueOf(it.string()) },
            nextLevel = o.int("nextLevel"),
        ).also { o.requireAllRead() }
    }

    internal fun assignments(json: JsonElement): List<WildcardAssignment> = json.arr().map { e ->
        val o = Fields(e.obj())
        WildcardAssignment(o.int("rank"), o.nullable("suit")?.let { Suit.valueOf(it.string()) }).also { o.requireAllRead() }
    }

    private fun teamMap(m: Map<Team, Int>) = obj(*Team.entries.map { it.name to num(m.getValue(it)) }.toTypedArray())

    private fun teamMap(json: JsonElement): Map<Team, Int> {
        val o = Fields(json.obj())
        return Team.entries.associateWith { o.int(it.name) }.also { o.requireAllRead() }
    }

    private fun seatMap(m: Map<Seat, JsonElement>) = obj(*m.entries.map { it.key.name to it.value }.toTypedArray())

    private fun <V> seatMap(json: JsonElement, value: (JsonElement) -> V): Map<Seat, V> =
        json.obj().entries.associate { (k, v) -> Seat.valueOf(k) to value(v) }
}

private fun encodeAssignments(list: List<WildcardAssignment>) =
    JsonArray(list.map { obj("rank" to num(it.rank), "suit" to (it.suit?.let { s -> str(s.name) } ?: JsonNull)) })

// ---- Small strict JSON helpers (shared by both codecs) ---------------------------------------------

private fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(entries.sortedBy { it.first }.toMap(LinkedHashMap()))

private fun str(s: String) = JsonPrimitive(s)

private fun num(n: Int) = JsonPrimitive(n)

private fun seat(s: Seat?): JsonElement = s?.let { JsonPrimitive(it.name) } ?: JsonNull

private fun seats(list: List<Seat>) = JsonArray(list.map { JsonPrimitive(it.name) })

private fun strs(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("expected an object")

private fun JsonElement.arr(): JsonArray = this as? JsonArray ?: throw IllegalArgumentException("expected an array")

private fun JsonElement.prim(): JsonPrimitive = (this as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: throw IllegalArgumentException("expected a value")

private fun JsonElement.string(): String = prim().takeIf { it.isString }?.content ?: throw IllegalArgumentException("expected a string")

private fun JsonElement.int(): Int = prim().takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("expected an int")

private fun JsonElement.long(): Long = prim().takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("expected a long")

/** Reads the fields of one object and fails on any field that was never read (no silent extras). */
internal class Fields(private val o: JsonObject) {
    private val read = HashSet<String>()

    fun get(key: String): JsonElement {
        read += key
        return o[key] ?: throw IllegalArgumentException("missing field '$key'")
    }

    fun nullable(key: String): JsonElement? = get(key).takeIf { it !is JsonNull }

    fun string(key: String) = get(key).string()
    fun int(key: String) = get(key).int()
    fun long(key: String) = get(key).long()
    fun boolean(key: String): Boolean = get(key).prim().takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("'$key' is not a boolean")
    fun seat(key: String): Seat = Seat.valueOf(string(key))
    fun seatOrNull(key: String): Seat? = nullable(key)?.let { Seat.valueOf(it.string()) }
    fun seats(key: String): List<Seat> = get(key).arr().map { Seat.valueOf(it.string()) }
    fun strings(key: String): List<String> = get(key).arr().map { it.string() }

    fun requireAllRead() {
        val extra = o.keys - read
        require(extra.isEmpty()) { "unexpected fields $extra" }
    }
}
