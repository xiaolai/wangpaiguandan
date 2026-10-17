package cn.wpgd.app.core.state

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.readOnlyCopy
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.LegalActionGenerator
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleSettings
import cn.wpgd.app.core.rules.WildcardAssignment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version of the projection wire shape. Bump on any field change. */
const val PROJECTION_SCHEMA_VERSION = 1

@Serializable
data class ViewCard(val id: String, val suit: Suit, val rank: Int)

@Serializable
data class ViewAssignment(val rank: Int, val suit: Suit?)

/** One play of the current trick, as every player at the table saw it put down and declared. */
@Serializable
data class PublicPlay(
    val seat: Seat,
    val cards: List<ViewCard>,
    /** The stated declaration of the wildcards in [cards]: a used declaration, public by R7.7. */
    val declaration: List<ViewAssignment>?,
    val type: CombinationType,
)

/**
 * What base-v1 makes public at the table (D24): the deal's level, profile hash and committed
 * revision, who leads and who acts, the current trick, and who has played out in which order.
 * Not public: any hand, any hand size, the cards of tricks already closed (turned face down,
 * CG22a line 270), any hint, and the host state hash.
 */
@Serializable
data class PublicTable(
    val phase: Phase,
    val level: Int,
    val profileHash: String,
    val revision: Long,
    val leader: Seat?,
    val turn: Seat?,
    val trick: List<PublicPlay>,
    val passedSinceLastPlay: List<Seat>,
    val finishOrder: List<Seat>,
)

/**
 * What the member seated at [seat] may know: the public table plus its own hand, whether it is its
 * turn, and whether it may pass and has any legal play. Nothing about another seat's cards.
 */
@Serializable
data class MemberView(
    val schemaVersion: Int,
    val seat: Seat,
    val table: PublicTable,
    val hand: List<ViewCard>,
    val yourTurn: Boolean,
    val mayPass: Boolean,
    val hasLegalPlay: Boolean,
)

/** The public table only (P3): no hand, no hint, no unused wildcard declaration. */
@Serializable
data class SpectatorPublicView(val schemaVersion: Int, val table: PublicTable)

/**
 * What a bot at a seat may know. Deliberately the same type as [MemberView]: a bot sees exactly
 * what a human in that seat sees, and nothing hidden (GDN-058, P2).
 */
typealias AiObservation = MemberView

/** Builds every projection from one code path, under one profile (for the legal-play flag). */
class SeatProjector(private val profile: RuleProfile) {
    private val settings = RuleSettings.of(profile)
    private val generators = Ranks.NATURAL.associateWith { lazy { LegalActionGenerator(settings, it) } }

    fun memberView(state: DealState, seat: Seat): MemberView {
        require(state.profileHash == profile.hash) { "state belongs to another profile" }
        val own = state.hands.getValue(seat)
        val yourTurn = state.phase == Phase.PLAYING && state.turn == seat
        val top = state.trick.top
        val hasLegalPlay = yourTurn &&
            generators.getValue(state.level).value.generate(own.map(::card), top?.combination).isNotEmpty()
        return MemberView(
            schemaVersion = PROJECTION_SCHEMA_VERSION,
            seat = seat,
            table = publicTable(state),
            hand = own.sorted().map(::viewCard),
            yourTurn = yourTurn,
            mayPass = yourTurn && top != null,
            hasLegalPlay = hasLegalPlay,
        )
    }

    fun aiObservation(state: DealState, seat: Seat): AiObservation = memberView(state, seat)

    fun spectatorView(state: DealState): SpectatorPublicView {
        require(state.profileHash == profile.hash) { "state belongs to another profile" }
        return SpectatorPublicView(PROJECTION_SCHEMA_VERSION, publicTable(state))
    }

    private fun publicTable(s: DealState) = PublicTable(
        phase = s.phase,
        level = s.level,
        profileHash = s.profileHash,
        revision = s.revision,
        leader = s.leader,
        turn = s.turn,
        trick = s.trick.plays.map { p ->
            PublicPlay(p.seat, p.cardIds.map(::viewCard), p.declaration?.map(::viewAssignment), p.combination.type)
        },
        // Snapshots (round 3 #45): handing out the state's own lists would let a Java caller change it.
        passedSinceLastPlay = s.trick.passedSinceTop.readOnlyCopy(),
        finishOrder = s.finishOrder.readOnlyCopy(),
    )

    private fun card(id: String) = checkNotNull(StandardDeck[id]) { "unknown card id $id" }

    private fun viewCard(id: String) = card(id).let { ViewCard(it.id, it.suit, it.rank) }

    private fun viewAssignment(a: WildcardAssignment) = ViewAssignment(a.rank, a.suit)
}

/**
 * Deterministic bytes for projections: UTF-8 JSON, keys in declaration order (fixed by the classes
 * above), every field written including defaults and nulls, no whitespace.
 */
object ProjectionCodec {
    /** The one JSON configuration of every projection encoding, deal and match (`MatchProjectionCodec`). */
    internal val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun encode(view: MemberView): ByteArray = json.encodeToString(MemberView.serializer(), view).encodeToByteArray()

    fun encode(view: SpectatorPublicView): ByteArray = json.encodeToString(SpectatorPublicView.serializer(), view).encodeToByteArray()
}
