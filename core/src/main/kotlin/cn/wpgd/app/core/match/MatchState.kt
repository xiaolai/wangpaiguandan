package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.DealState
import cn.wpgd.app.core.state.Seat
import kotlinx.serialization.Serializable

@Serializable
enum class MatchPhase {
    /** Before the first deal, or after a settlement: only [MatchCommand.DealNext] is accepted. */
    AWAITING_DEAL,

    /** Dealt; the tribute has not started ([MatchCommand.StartTribute]). */
    DEALT,

    /** Tribute owed: [MatchCommand.GiveTribute], or [MatchCommand.Resist] while the claim is open. */
    TRIBUTE,

    /** Every tribute is given and routed: [MatchCommand.ReturnTribute]. */
    RETURN,

    /** The opening leader is resolved: [MatchCommand.OpenPlay]. */
    READY,

    PLAYING,

    /** The deal has ended: [MatchCommand.Settle]. */
    DEAL_ENDED,

    /** A team has passed A. Every command is refused. */
    OVER,
}

/** Why a seat leads the first trick of a deal (match-v1 section 7). */
@Serializable
enum class LeadReason {
    /** First deal, holder of the drawn card (M7.1). */
    CUT_CARD,

    /** First deal, fixed East seat (M7.1 variant). */
    FIXED_EAST,

    /** After a single tribute, the giver (M7.2). */
    TRIBUTE_GIVER,

    /** After a double tribute, the giver whose card went to 上游 (M7.3). */
    GIVER_TO_FIRST,

    /** After resistance, 上游 (M7.5). */
    RESISTANCE_FIRST_PLACE,
}

/** A threshold report (M8.1): public, once per seat per deal. */
@Serializable
data class CountReport(val seat: Seat, val count: Int)

/** An answer to an opponent's question (M8.4 variant): public. */
@Serializable
data class CountAnswer(val asker: Seat, val seat: Seat, val count: Int)

/** One card handed from [from] to [to]. */
@Serializable
data class CardTransfer(val from: Seat, val to: Seat, val cardId: String)

/**
 * The tribute of the current deal. [given] is public (M4.8). [returns] is host state: each entry is
 * known only to its returner until [revealed] (M5.5, M5.6).
 */
data class TributeState(
    val obligation: TributeObligation,
    val given: Map<Seat, String> = emptyMap(),
    /** giver → receiver; filled once every tribute is given. */
    val routes: Map<Seat, Seat> = emptyMap(),
    /** returner → card id. */
    val returns: Map<Seat, String> = emptyMap(),
    val revealed: Boolean = false,
    /** The givers holding the big jokers, when the side resisted (M6.x). */
    val resistedBy: List<Seat>? = null,
    val resistedJokers: List<String> = emptyList(),
) {
    val allGiven: Boolean get() = given.keys == obligation.givers.toSet()
    val allReturned: Boolean get() = routes.isNotEmpty() && returns.keys == routes.values.toSet()

    /** The giver whose tribute [receiver] holds. */
    fun giverTo(receiver: Seat): Seat = routes.entries.first { it.value == receiver }.key
}

/**
 * Authoritative match state. Host-internal: it holds the current [deal] with every hand. Members see
 * only [MatchProjector] views. Immutable; changed only by [MatchEvents.apply].
 *
 * - [matchNumber]: which match of the room this is, from 1; a rematch is the next number. Deal
 *   numbers restart at 1 in every match, so (matchNumber, dealNumber) names one deal of a room.
 * - [levels]: each team's level (2..A). [aFailures]: failed A attempts per team (match-v1 section 3).
 * - [dealNumber]: deals dealt so far. [dealLevel]: level of the current deal, or of the next one.
 * - [previous]: the last settled deal; it decides the next tribute and lead.
 * - [leader]: the resolved opening leader of the current deal, from READY on.
 * - [reports]/[answers]: this deal's public card counts (match-v1 section 8). No other count is kept.
 */
@ConsistentCopyVisibility
data class MatchState internal constructor(
    val phase: MatchPhase,
    val matchNumber: Int,
    val baseProfileHash: String,
    val matchProfileHash: String,
    val levels: Map<Team, Int>,
    val aFailures: Map<Team, Int>,
    val dealNumber: Int,
    val dealLevel: Int,
    val deal: DealState?,
    val previous: DealResult?,
    val tribute: TributeState?,
    val leader: Seat?,
    val leadReason: LeadReason?,
    /** First deal only: the drawn card whose holder leads (M7.1). */
    val cutCard: String?,
    val reports: List<CountReport>,
    val answers: List<CountAnswer>,
    val winner: Team?,
    val revision: Long,
) {
    init {
        require(matchNumber >= 1) { "match number $matchNumber" }
        require(levels.keys == Team.entries.toSet() && levels.values.all { it in Ranks.NATURAL }) { "levels $levels" }
        require(aFailures.keys == Team.entries.toSet() && aFailures.values.all { it >= 0 }) { "failures $aFailures" }
        require((winner != null) == (phase == MatchPhase.OVER)) { "winner exactly when over" }
    }

    internal companion object {
        fun initial(baseProfileHash: String, matchProfileHash: String, matchNumber: Int = 1) = MatchState(
            phase = MatchPhase.AWAITING_DEAL,
            matchNumber = matchNumber,
            baseProfileHash = baseProfileHash,
            matchProfileHash = matchProfileHash,
            levels = Team.entries.associateWith { FIRST_LEVEL },
            aFailures = Team.entries.associateWith { 0 },
            dealNumber = 0,
            dealLevel = FIRST_LEVEL,
            deal = null,
            previous = null,
            tribute = null,
            leader = null,
            leadReason = null,
            cutCard = null,
            reports = emptyList(),
            answers = emptyList(),
            winner = null,
            revision = 0,
        )

        /** M1.2: the first deal is at level 2 (JS17:36). */
        const val FIRST_LEVEL = 2
    }
}
