package cn.wpgd.app.core.match

import cn.wpgd.app.core.readOnlyCopy
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.DealEvent
import cn.wpgd.app.core.state.DealEvents
import cn.wpgd.app.core.state.DealState
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
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
                requireInDealDue(s, event.event)
                val deal = DealEvents.apply(checkNotNull(s.deal) { "deal event with no deal" }, event.event)
                val phase = when {
                    event.event is DealEvent.PlayBegan -> MatchPhase.PLAYING
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
                // M4.1, M4.2: the obligation is the one the previous result gives, never the event's choice (round 3 #38).
                val previous = requireNotNull(s.previous) { "tribute started with no previous deal" }
                val owed = TributeRules.obligation(previous)
                val obligation = TributeObligation(event.kind, event.givers.readOnlyCopy(), event.receivers.readOnlyCopy())
                require(obligation == owed) { "tribute ${event.kind} ${event.givers} -> ${event.receivers}, the previous result owes ${owed.kind} ${owed.givers} -> ${owed.receivers}" }
                // Copies: committed state must not alias the event's (possibly caller-owned) lists.
                s.copy(phase = MatchPhase.TRIBUTE, tribute = TributeState(obligation))
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
                s.copy(tribute = t.copy(resistedBy = event.holders.toList(), resistedJokers = event.bigJokerIds.toList()))
            }
            is MatchEvent.TributeGiven -> {
                requirePhase(s, MatchPhase.TRIBUTE)
                val t = checkNotNull(s.tribute)
                require(event.seat in t.obligation.givers && event.seat !in t.given) { "${event.seat} may not give tribute" }
                require(event.cardId in checkNotNull(s.deal).hands.getValue(event.seat)) { "${event.seat} does not hold ${event.cardId}" }
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
                require(event.cardId in checkNotNull(s.deal).hands.getValue(event.seat)) { "${event.seat} does not hold ${event.cardId}" }
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
                requireCount(s, event.seat, event.count)
                s.copy(reports = s.reports + CountReport(event.seat, event.count))
            }
            is MatchEvent.CardCountAnswered -> {
                requirePhase(s, MatchPhase.PLAYING)
                requireCount(s, event.seat, event.count)
                s.copy(answers = s.answers + CountAnswer(event.asker, event.seat, event.count))
            }
            is MatchEvent.DealSettled -> {
                requirePhase(s, MatchPhase.DEAL_ENDED)
                val r = settledCopy(event.result)
                require(r.dealNumber == s.dealNumber && r.finishOrder == checkNotNull(s.deal).finishOrder) { "settlement of another deal" }
                requireConsistentSettlement(s, r)
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
            // Every tribute and return has moved: each hand is back to its dealt size.
            MatchPhase.RETURN -> t != null && t.allReturned && t.revealed &&
                checkNotNull(s.deal).hands.values.all { it.size == StandardDeck.cards.size / Seat.entries.size } && when (t.obligation.kind) {
                TributeKind.SINGLE -> reason == LeadReason.TRIBUTE_GIVER && leader == t.obligation.givers.single()
                TributeKind.DOUBLE -> reason == LeadReason.GIVER_TO_FIRST && leader == t.giverTo(t.obligation.receivers.first())
            }
            else -> false
        }
    }

    /**
     * Which deal events the match admits in its current phase (the match reducer emits no others):
     * dealing while DEALT; card moves only while RETURN, each one a pending tribute or (after the
     * reveal) return transfer; the opening of play from READY by the resolved leader; plays after.
     * [DealEvent.DealOpened] (a deal that opens itself, with no tribute or resolved lead) is never a match event.
     */
    private fun requireInDealDue(s: MatchState, e: DealEvent) {
        when (e) {
            is DealEvent.DealOpened -> throw IllegalArgumentException("a match deal opens with PlayBegan, not DealOpened")
            is DealEvent.Dealt, is DealEvent.HandDealt -> requirePhase(s, MatchPhase.DEALT)
            is DealEvent.CardMoved -> {
                requirePhase(s, MatchPhase.RETURN)
                val t = checkNotNull(s.tribute)
                val transfer = CardTransfer(e.from, e.to, e.cardId)
                // Before the reveal only the routed tributes move; after it only the returns.
                val due = if (!t.revealed) {
                    t.routes.map { (giver, receiver) -> CardTransfer(giver, receiver, t.given.getValue(giver)) }
                } else {
                    t.returns.map { (returner, card) -> CardTransfer(returner, t.giverTo(returner), card) }
                }
                require(transfer in due) { "card move $transfer is not a pending tribute or return" }
            }
            is DealEvent.PlayBegan -> {
                requirePhase(s, MatchPhase.READY)
                require(e.leader == s.leader) { "play began with ${e.leader}, the resolved leader is ${s.leader}" }
            }
            else -> requirePhase(s, MatchPhase.PLAYING)
        }
    }

    /** A public count (M8.1, M8.4) is the seat's true count; only a seat still holding cards has one. */
    private fun requireCount(s: MatchState, seat: Seat, count: Int) {
        val held = checkNotNull(s.deal).hands.getValue(seat).size
        require(count > 0 && count == held) { "$seat holds $held cards, the count says $count" }
    }

    /** The result with its lists copied, so committed state never aliases the event's (possibly caller-owned) lists. */
    private fun settledCopy(r: DealResult): DealResult =
        r.copy(finishOrder = r.finishOrder.toList(), placements = r.placements.toList(), teams = r.teams.toList())

    /**
     * The profile-free facts of a settlement must agree with the state it settles: the deal's level,
     * one outcome per team starting from that team's level and failures, 上游's team as the winner,
     * the partner's place as the finish order gives it, each outcome coherent in itself
     * ([requireCoherentOutcome]), the next level as the winner's level after, and the match winner
     * as the team that passed A. What the match profile decides (the gain for a partner place, when
     * a deal at A counts, the failure limit) is checked by [MatchReducer.replay], which knows it.
     */
    private fun requireConsistentSettlement(s: MatchState, r: DealResult) {
        require(r.dealLevel == s.dealLevel) { "settlement at level ${r.dealLevel}, the deal was at ${s.dealLevel}" }
        require(r.teams.map { it.team }.sorted() == Team.entries) { "settlement outcomes ${r.teams.map { it.team }} are not one per team" }
        require(r.winningTeam == r.finishOrder.first().team) { "winning team ${r.winningTeam} is not 上游's" }
        val partnerPlace = r.finishOrder.indexOf(r.finishOrder.first().partner).let { if (it < 0) 4 else it + 1 }
        require(r.partnerPlace == partnerPlace) { "partner place ${r.partnerPlace}, the finish order gives $partnerPlace" }
        require(r.gain >= 0) { "negative gain ${r.gain}" }
        for (o in r.teams) {
            require(o.levelBefore == s.levels.getValue(o.team) && o.failuresBefore == s.aFailures.getValue(o.team)) {
                "settlement of ${o.team} starts from another level or failure count"
            }
            require(o.levelAfter in Ranks.NATURAL && o.failuresAfter >= 0) { "settlement of ${o.team} ends at level ${o.levelAfter}, failures ${o.failuresAfter}" }
            requireCoherentOutcome(o, won = o.team == r.winningTeam, gain = r.gain)
        }
        require(r.nextLevel == r.outcome(r.winningTeam).levelAfter) { "next level ${r.nextLevel} is not the winner's level after" }
        require(r.matchWinner == r.teams.firstOrNull { it.passedA }?.team) { "match winner ${r.matchWinner} disagrees with the outcomes" }
    }

    /**
     * One team's outcome agrees with itself (match-v1 sections 2 and 3, as [Settlement] computes it):
     * an A attempt only at A, and an interruption exactly when at A without one; passing A only by
     * winning an attempt, failing it only in an attempt not passed; a level that moves only for a
     * winner that did not pass, by the deal's gain (capped at A); failures that grow by one exactly
     * on a failure, otherwise stay or are cleared by an interruption. A reset clears the failures.
     */
    private fun requireCoherentOutcome(o: TeamOutcome, won: Boolean, gain: Int) {
        val atA = o.levelBefore == Ranks.ACE
        require(!o.aAttempt || atA) { "${o.team} made an A attempt at level ${o.levelBefore}" }
        require(o.interruption == (atA && !o.aAttempt)) { "${o.team} interruption ${o.interruption} disagrees with level and attempt" }
        require(!o.passedA || (won && o.aAttempt)) { "${o.team} passed A without winning an A attempt" }
        require(!o.failedA || (o.aAttempt && !o.passedA)) { "${o.team} failed A without an unpassed attempt" }
        if (o.reset) {
            require(o.failedA && o.failuresAfter == 0) { "${o.team} reset without a failure, or kept its failures" }
            return
        }
        val levelAfter = if (won && !o.passedA) minOf(Ranks.ACE, o.levelBefore + gain) else o.levelBefore
        require(o.levelAfter == levelAfter) { "${o.team} ends at level ${o.levelAfter}, the outcome gives $levelAfter" }
        val failuresOk = when {
            o.failedA -> o.failuresAfter == o.failuresBefore + 1
            o.interruption -> o.failuresAfter == o.failuresBefore || o.failuresAfter == 0
            else -> o.failuresAfter == o.failuresBefore
        }
        require(failuresOk) { "${o.team} failures ${o.failuresBefore} -> ${o.failuresAfter} disagree with the outcome" }
    }

    /** A deal holds two big jokers (R1.1); resistance shows both. */
    private const val BIG_JOKERS_PER_DEAL = 2

    private fun requirePhase(s: MatchState, vararg phases: MatchPhase) {
        require(s.phase in phases) { "event in phase ${s.phase}, expected ${phases.toList()}" }
    }
}
