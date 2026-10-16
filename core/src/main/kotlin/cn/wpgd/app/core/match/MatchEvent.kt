package cn.wpgd.app.core.match

import cn.wpgd.app.core.state.DealEvent
import cn.wpgd.app.core.state.DealEvents
import cn.wpgd.app.core.state.DealState
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.Visibility

/** Schema version of [MatchEvent]. A change to any event's fields or meaning bumps it. */
const val MATCH_EVENT_SCHEMA_VERSION = 1

/**
 * What happened between and around deals, as typed data only. Every event of one accepted command
 * carries that command's match [revision]. [MatchEvents.apply] is the only code that changes a
 * [MatchState], so the events alone reproduce it.
 */
sealed interface MatchEvent {
    val schemaVersion: Int
    val revision: Long
    val visibility: Visibility

    /** One event of the current deal. Its visibility is the deal event's own. */
    data class InDeal(
        override val revision: Long,
        val event: DealEvent,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = event.visibility
    }

    /** Deal number [dealNumber] is about to be dealt at [level]. */
    data class DealStarted(
        override val revision: Long,
        val dealNumber: Int,
        val level: Int,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** First deal (M7.1): [cardId] was drawn; [seat] holds it. Shown to the whole table, as at a real table. */
    data class CutCardDrawn(
        override val revision: Long,
        val cardId: String,
        val seat: Seat,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    data class TributeStarted(
        override val revision: Long,
        val kind: TributeKind,
        val givers: List<Seat>,
        val receivers: List<Seat>,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** The giving side resisted (M6.x): [holders] show [bigJokerIds]. [claimedBy] is null when automatic. */
    data class Resisted(
        override val revision: Long,
        val claimedBy: Seat?,
        val holders: List<Seat>,
        val bigJokerIds: List<String>,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** [seat] put [cardId] on the table as tribute (M4.8: public). */
    data class TributeGiven(
        override val revision: Long,
        val seat: Seat,
        val cardId: String,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** Every tribute is given; [transfers] moves each to its receiver (M4.6, M4.7). */
    data class TributeRouted(
        override val revision: Long,
        val transfers: List<CardTransfer>,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** [seat] put [cardId] face down for [to] (M5.5). Only the returner knows the card until the reveal. */
    data class ReturnChosen(
        override val revision: Long,
        val seat: Seat,
        val to: Seat,
        val cardId: String,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.SeatPrivate(seat)
    }

    /** [seat] has returned a card; which card is not said (public fact that a face-down card was put down). */
    data class ReturnMade(
        override val revision: Long,
        val seat: Seat,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** Every return is made: the cards are turned up together and move to the givers (M5.5). */
    data class ReturnsRevealed(
        override val revision: Long,
        val transfers: List<CardTransfer>,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** The opening leader of the deal, decided by the profile (audit A02), never by the caller. */
    data class LeaderResolved(
        override val revision: Long,
        val leader: Seat,
        val reason: LeadReason,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** M8.1: [seat] has [count] cards left. Public, once per seat per deal. */
    data class CardCountReported(
        override val revision: Long,
        val seat: Seat,
        val count: Int,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** M8.4 variant: [seat] answers [asker] with [count]. Public. */
    data class CardCountAnswered(
        override val revision: Long,
        val asker: Seat,
        val seat: Seat,
        val count: Int,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    data class DealSettled(
        override val revision: Long,
        val result: DealResult,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** M3.3: [winner] passed A. */
    data class MatchEnded(
        override val revision: Long,
        val winner: Team,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /**
     * GDN-017 (WI-09): a new match starts from the initial state under the same profiles. Added to
     * schema 1 before any release (an additive event; no stored hash changes).
     */
    data class MatchRestarted(
        override val revision: Long,
        override val schemaVersion: Int = MATCH_EVENT_SCHEMA_VERSION,
    ) : MatchEvent {
        override val visibility: Visibility get() = Visibility.Public
    }
}

/**
 * Applies match events. Pure; a malformed log (unknown schema, revision going backwards, an event out
 * of phase) throws, so a corrupt log fails loudly instead of producing a plausible state.
 */
object MatchEvents {
    fun replay(initial: MatchState, events: List<MatchEvent>): MatchState = events.fold(initial, ::apply)

    fun apply(state: MatchState, event: MatchEvent): MatchState {
        require(event.schemaVersion == MATCH_EVENT_SCHEMA_VERSION) { "unsupported match event schema ${event.schemaVersion}" }
        require(event.revision >= state.revision) { "event revision ${event.revision} is behind state revision ${state.revision}" }
        val s = state.copy(revision = event.revision)
        return when (event) {
            is MatchEvent.DealStarted -> {
                requirePhase(s, MatchPhase.AWAITING_DEAL)
                require(event.dealNumber == s.dealNumber + 1 && event.level == s.dealLevel) { "deal ${event.dealNumber} at ${event.level} does not follow" }
                s.copy(
                    phase = MatchPhase.DEALT,
                    dealNumber = event.dealNumber,
                    deal = DealState.awaiting(event.level, s.baseProfileHash),
                    tribute = null,
                    leader = null,
                    leadReason = null,
                    cutCard = null,
                    reports = emptyList(),
                    answers = emptyList(),
                )
            }
            is MatchEvent.InDeal -> {
                val deal = DealEvents.apply(checkNotNull(s.deal) { "deal event with no deal" }, event.event)
                val phase = when {
                    event.event is DealEvent.PlayBegan -> {
                        requirePhase(s, MatchPhase.READY)
                        require(event.event.leader == s.leader) { "play began with ${event.event.leader}, the resolved leader is ${s.leader}" }
                        MatchPhase.PLAYING
                    }
                    deal.phase == Phase.ENDED -> MatchPhase.DEAL_ENDED
                    else -> s.phase
                }
                s.copy(deal = deal, phase = phase)
            }
            is MatchEvent.CutCardDrawn -> {
                requirePhase(s, MatchPhase.DEALT)
                require(event.cardId in checkNotNull(s.deal).hands.getValue(event.seat)) { "${event.seat} does not hold the cut card" }
                s.copy(cutCard = event.cardId)
            }
            is MatchEvent.TributeStarted -> {
                requirePhase(s, MatchPhase.DEALT)
                s.copy(phase = MatchPhase.TRIBUTE, tribute = TributeState(TributeObligation(event.kind, event.givers, event.receivers)))
            }
            is MatchEvent.Resisted -> {
                requirePhase(s, MatchPhase.TRIBUTE)
                val t = checkNotNull(s.tribute)
                require(t.given.isEmpty()) { "resisted after a tribute was given" }
                // M6.x: givers show the two big jokers they hold. Which split the profile allows is the reducer's check.
                val hands = checkNotNull(s.deal).hands
                require(event.claimedBy == null || event.claimedBy in t.obligation.givers) { "${event.claimedBy} is not a giver" }
                require(event.holders.isNotEmpty() && event.holders.toSet().size == event.holders.size && t.obligation.givers.containsAll(event.holders)) {
                    "resistance holders ${event.holders} are not distinct givers"
                }
                val shown = event.holders.map { TributeRules.bigJokers(hands.getValue(it)) }
                require(shown.all { it.isNotEmpty() } && event.bigJokerIds == shown.flatten().sorted() && event.bigJokerIds.size == BIG_JOKERS_PER_DEAL) {
                    "resistance shows ${event.bigJokerIds}, the holders hold $shown"
                }
                s.copy(tribute = t.copy(resistedBy = event.holders, resistedJokers = event.bigJokerIds))
            }
            is MatchEvent.TributeGiven -> {
                requirePhase(s, MatchPhase.TRIBUTE)
                val t = checkNotNull(s.tribute)
                require(event.seat in t.obligation.givers && event.seat !in t.given) { "${event.seat} may not give tribute" }
                s.copy(tribute = t.copy(given = t.given + (event.seat to event.cardId)))
            }
            is MatchEvent.TributeRouted -> {
                requirePhase(s, MatchPhase.TRIBUTE)
                val t = checkNotNull(s.tribute)
                // The whole transfer list, destinations included, must be the routing M4.6/M4.7 gives.
                val expected = if (t.allGiven) TributeRules.route(t.obligation, t.given, s.dealLevel).map { (g, r) -> CardTransfer(g, r, t.given.getValue(g)) }.toSet() else null
                require(expected != null && event.transfers.size == expected.size && event.transfers.toSet() == expected) { "routing does not match the tribute" }
                s.copy(phase = MatchPhase.RETURN, tribute = t.copy(routes = event.transfers.associate { it.from to it.to }))
            }
            is MatchEvent.ReturnChosen -> {
                requirePhase(s, MatchPhase.RETURN)
                val t = checkNotNull(s.tribute)
                require(event.seat in t.routes.values && event.seat !in t.returns && event.to == t.giverTo(event.seat)) { "${event.seat} may not return to ${event.to}" }
                s.copy(tribute = t.copy(returns = t.returns + (event.seat to event.cardId)))
            }
            is MatchEvent.ReturnMade -> {
                requirePhase(s, MatchPhase.RETURN)
                require(event.seat in checkNotNull(s.tribute).returns) { "${event.seat} made no return" }
                s
            }
            is MatchEvent.ReturnsRevealed -> {
                requirePhase(s, MatchPhase.RETURN)
                val t = checkNotNull(s.tribute)
                // Each return goes back to the giver whose tribute its returner received, once.
                val expected = t.returns.map { (r, card) -> CardTransfer(r, t.giverTo(r), card) }.toSet()
                require(t.allReturned && event.transfers.size == expected.size && event.transfers.toSet() == expected) { "reveal does not match the returns" }
                s.copy(tribute = t.copy(revealed = true))
            }
            is MatchEvent.LeaderResolved -> {
                require(s.leader == null) { "leader already resolved" }
                require(leadIsDue(s, event.leader, event.reason)) { "leader ${event.leader} (${event.reason}) not due in phase ${s.phase}" }
                s.copy(phase = MatchPhase.READY, leader = event.leader, leadReason = event.reason)
            }
            is MatchEvent.CardCountReported -> {
                requirePhase(s, MatchPhase.PLAYING, MatchPhase.DEAL_ENDED)
                require(s.reports.none { it.seat == event.seat }) { "${event.seat} reported twice in one deal" }
                s.copy(reports = s.reports + CountReport(event.seat, event.count))
            }
            is MatchEvent.CardCountAnswered -> {
                requirePhase(s, MatchPhase.PLAYING)
                s.copy(answers = s.answers + CountAnswer(event.asker, event.seat, event.count))
            }
            is MatchEvent.DealSettled -> {
                requirePhase(s, MatchPhase.DEAL_ENDED)
                val r = event.result
                require(r.dealNumber == s.dealNumber && r.finishOrder == checkNotNull(s.deal).finishOrder) { "settlement of another deal" }
                s.copy(
                    phase = MatchPhase.AWAITING_DEAL,
                    previous = r,
                    levels = r.teams.associate { it.team to it.levelAfter },
                    aFailures = r.teams.associate { it.team to it.failuresAfter },
                    dealLevel = r.nextLevel,
                )
            }
            is MatchEvent.MatchEnded -> {
                requirePhase(s, MatchPhase.AWAITING_DEAL)
                require(checkNotNull(s.previous).matchWinner == event.winner) { "match ended for a team that did not pass A" }
                s.copy(phase = MatchPhase.OVER, winner = event.winner)
            }
            is MatchEvent.MatchRestarted -> {
                requirePhase(s, MatchPhase.OVER)
                MatchState.initial(s.baseProfileHash, s.matchProfileHash, s.matchNumber + 1).copy(revision = event.revision)
            }
        }
    }

    /**
     * M7: a lead is resolved only once the step before it is complete, by its own rule. First deal
     * (DEALT, no tribute): the cut-card holder or East. Resistance (TRIBUTE): 上游. After the returns
     * are revealed (RETURN): the single giver, or the giver whose tribute went to 上游.
     */
    private fun leadIsDue(s: MatchState, leader: Seat, reason: LeadReason): Boolean {
        val t = s.tribute
        return when (s.phase) {
            MatchPhase.DEALT -> s.dealNumber == 1 && t == null && when (reason) {
                LeadReason.CUT_CARD -> s.cutCard != null && s.cutCard in checkNotNull(s.deal).hands.getValue(leader)
                LeadReason.FIXED_EAST -> leader == Seat.EAST
                else -> false
            }
            MatchPhase.TRIBUTE -> t != null && t.resistedBy != null &&
                reason == LeadReason.RESISTANCE_FIRST_PLACE && leader == t.obligation.receivers.first()
            MatchPhase.RETURN -> t != null && t.allReturned && t.revealed && when (t.obligation.kind) {
                TributeKind.SINGLE -> reason == LeadReason.TRIBUTE_GIVER && leader == t.obligation.givers.single()
                TributeKind.DOUBLE -> reason == LeadReason.GIVER_TO_FIRST && leader == t.giverTo(t.obligation.receivers.first())
            }
            else -> false
        }
    }

    /** A deal holds two big jokers (R1.1); resistance shows both. */
    private const val BIG_JOKERS_PER_DEAL = 2

    private fun requirePhase(s: MatchState, vararg phases: MatchPhase) {
        require(s.phase in phases) { "event in phase ${s.phase}, expected ${phases.toList()}" }
    }
}
