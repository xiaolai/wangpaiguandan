package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.state.MemberView
import cn.wpgd.app.core.state.SeatProjector
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SpectatorPublicView
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.ViewCard
import kotlinx.serialization.Serializable
import cn.wpgd.app.core.state.ProjectionCodec

/** Version of the match projection wire shape. Bump on any field change. 2 (2026-10-16): [MatchPublicView.matchNumber]. */
const val MATCH_PROJECTION_SCHEMA_VERSION = 2

@Serializable
data class TeamView(val team: Team, val level: Int, val aFailures: Int)

/**
 * One card handed over in the tribute step. [to] is null only for a tribute card given before the
 * routing is known (double tribute, M4.4): the recipient is never invented.
 */
@Serializable
data class TransferView(val from: Seat, val to: Seat?, val card: ViewCard)

/**
 * The public part of the tribute: who owes, the tribute cards (face up, M4.8), their routing, who has
 * put a return down, the returns once revealed (M5.5), and a resistance with its jokers (M6.4).
 */
@Serializable
data class TributeView(
    val kind: TributeKind,
    val givers: List<Seat>,
    val receivers: List<Seat>,
    val given: List<TransferView>,
    val returnsMade: List<Seat>,
    val returnsRevealed: List<TransferView>,
    val resistedBy: List<Seat>?,
    val resistedJokers: List<ViewCard>,
)

/**
 * Everything about the match that the whole table may know. Card counts appear only as
 * [reports] and [answers] (match-v1 section 8): never derived from a hand.
 */
@Serializable
data class MatchPublicView(
    val phase: MatchPhase,
    /** Which match of the room this is (from 1); with [dealNumber] it names one deal of the room. */
    val matchNumber: Int,
    val baseProfileHash: String,
    val matchProfileHash: String,
    val teams: List<TeamView>,
    val dealNumber: Int,
    val dealLevel: Int,
    val leader: Seat?,
    val leadReason: LeadReason?,
    val cutCard: ViewCard?,
    val tribute: TributeView?,
    val reports: List<CountReport>,
    val answers: List<CountAnswer>,
    val lastResult: DealResult?,
    val winner: Team?,
)

/** What the member at [seat] may know: the public match, its deal view, and its own face-down return. */
@Serializable
data class MatchMemberView(
    val schemaVersion: Int,
    val seat: Seat,
    val match: MatchPublicView,
    val deal: MemberView?,
    val ownReturn: ViewCard?,
)

@Serializable
data class MatchSpectatorView(val schemaVersion: Int, val match: MatchPublicView, val deal: SpectatorPublicView?)

/** Builds every match projection from one code path; deal views come from the WI-03 [SeatProjector]. */
class MatchProjector(base: RuleProfile, private val match: RuleProfile) {
    private val seats = SeatProjector(base)

    fun memberView(state: MatchState, seat: Seat): MatchMemberView {
        require(state.matchProfileHash == match.hash) { "state belongs to another match profile" }
        val own = state.tribute?.takeIf { !it.revealed }?.returns?.get(seat)
        return MatchMemberView(
            schemaVersion = MATCH_PROJECTION_SCHEMA_VERSION,
            seat = seat,
            match = publicView(state),
            deal = state.deal?.let { seats.memberView(it, seat) },
            ownReturn = own?.let(::viewCard),
        )
    }

    /** A bot sees exactly what a human in that seat sees (GDN-058, P2). */
    fun aiObservation(state: MatchState, seat: Seat): MatchMemberView = memberView(state, seat)

    fun spectatorView(state: MatchState): MatchSpectatorView {
        require(state.matchProfileHash == match.hash) { "state belongs to another match profile" }
        return MatchSpectatorView(MATCH_PROJECTION_SCHEMA_VERSION, publicView(state), state.deal?.let(seats::spectatorView))
    }

    private fun publicView(s: MatchState) = MatchPublicView(
        phase = s.phase,
        matchNumber = s.matchNumber,
        baseProfileHash = s.baseProfileHash,
        matchProfileHash = s.matchProfileHash,
        teams = Team.entries.map { TeamView(it, s.levels.getValue(it), s.aFailures.getValue(it)) },
        dealNumber = s.dealNumber,
        dealLevel = s.dealLevel,
        leader = s.leader,
        leadReason = s.leadReason,
        cutCard = s.cutCard?.let(::viewCard),
        tribute = s.tribute?.let(::tributeView),
        reports = s.reports,
        answers = s.answers,
        lastResult = s.previous,
        winner = s.winner,
    )

    private fun tributeView(t: TributeState) = TributeView(
        kind = t.obligation.kind,
        givers = t.obligation.givers,
        receivers = t.obligation.receivers,
        given = t.obligation.givers.mapNotNull { g -> t.given[g]?.let { TransferView(g, t.routes[g], viewCard(it)) } },
        returnsMade = t.obligation.receivers.filter { it in t.returns },
        returnsRevealed = if (t.revealed) t.obligation.receivers.map { r -> TransferView(r, t.giverTo(r), viewCard(t.returns.getValue(r))) } else emptyList(),
        resistedBy = t.resistedBy,
        resistedJokers = t.resistedJokers.map(::viewCard),
    )

    private fun viewCard(id: String) = checkNotNull(StandardDeck[id]) { "unknown card id $id" }.let { ViewCard(it.id, it.suit, it.rank) }
}

/** Deterministic bytes for match projections, with the deal [ProjectionCodec]'s own JSON configuration. */
object MatchProjectionCodec {
    private val json = ProjectionCodec.json

    fun encode(view: MatchMemberView): ByteArray = json.encodeToString(MatchMemberView.serializer(), view).encodeToByteArray()

    fun encode(view: MatchSpectatorView): ByteArray = json.encodeToString(MatchSpectatorView.serializer(), view).encodeToByteArray()
}
