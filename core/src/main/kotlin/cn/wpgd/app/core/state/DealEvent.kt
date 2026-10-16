package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.Combination
import cn.wpgd.app.core.rules.WildcardAssignment

/** Schema version of [DealEvent]. A change to any event's fields or meaning bumps it (plan WI-03 rollback). */
const val EVENT_SCHEMA_VERSION = 1

/** Who may receive an event. */
sealed interface Visibility {
    /** Every member and spectator. */
    data object Public : Visibility

    /** Only the member seated at [seat] (and the host). */
    data class SeatPrivate(val seat: Seat) : Visibility

    /** The host alone. No WI-03 event uses it; it is the class for host-internal records. */
    data object HostOnly : Visibility

    fun visibleTo(audience: Audience): Boolean = when (this) {
        Public -> true
        is SeatPrivate -> audience == Audience.Host || audience == Audience.Member(seat)
        HostOnly -> audience == Audience.Host
    }
}

/** A recipient of events. */
sealed interface Audience {
    data class Member(val seat: Seat) : Audience
    data object Spectator : Audience
    data object Host : Audience
}

/**
 * What happened, as typed data only (no display text). Every event of one accepted command carries
 * that command's committed [revision]. [DealEvents.apply] is the only code that changes a
 * [DealState]; the reducer builds new states by applying the events it emits, so replaying the
 * events reproduces the state exactly.
 */
sealed interface DealEvent {
    val schemaVersion: Int
    val revision: Long
    val visibility: Visibility

    /** The deal is dealt: [leader] leads the first trick at [level] under the profile with [profileHash]. */
    data class DealOpened(
        override val revision: Long,
        val level: Int,
        val profileHash: String,
        val leader: Seat,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** The deal is dealt for the match layer ([SetupCommand.Deal]); play opens later with [PlayBegan]. */
    data class Dealt(
        override val revision: Long,
        val level: Int,
        val profileHash: String,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /**
     * [cardId] moves from [from]'s hand to [to]'s (a tribute or a return). Host-only: the match layer
     * announces each move with its own event and its own visibility (match-v1 M4.8, M5.5, M5.6).
     */
    data class CardMoved(
        override val revision: Long,
        val from: Seat,
        val to: Seat,
        val cardId: String,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.HostOnly
    }

    /** Play opens after the match layer's tribute phase: [leader] leads the first trick. */
    data class PlayBegan(
        override val revision: Long,
        val leader: Seat,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** [seat]'s 27 cards (sorted ids). Private to that seat. */
    data class HandDealt(
        override val revision: Long,
        val seat: Seat,
        val cardIds: List<String>,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.SeatPrivate(seat)
    }

    data class Played(
        override val revision: Long,
        val seat: Seat,
        val cardIds: List<String>,
        val declaration: List<WildcardAssignment>?,
        val combination: Combination,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    data class Passed(
        override val revision: Long,
        val seat: Seat,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** [seat] played its last card and finished in [place] (1 = 上游). */
    data class SeatFinished(
        override val revision: Long,
        val seat: Seat,
        val place: Int,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /**
     * Every seat still holding cards passed after [winner]'s play. [nextLeader] leads the next trick:
     * the winner, or its partner when the winner has played out ([partnerLead], 接风, D20).
     */
    data class TrickEnded(
        override val revision: Long,
        val winner: Seat,
        val nextLeader: Seat,
        val partnerLead: Boolean,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /** The turn moves to [seat] within the current trick. */
    data class TurnMoved(
        override val revision: Long,
        val seat: Seat,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }

    /**
     * The D21 end condition holds. [finishOrder] lists the seats that played out; [unfinished] the
     * rest in seat order (one seat, the 下游, after three finish; two after a double-up).
     */
    data class DealEnded(
        override val revision: Long,
        val finishOrder: List<Seat>,
        val unfinished: List<Seat>,
        override val schemaVersion: Int = EVENT_SCHEMA_VERSION,
    ) : DealEvent {
        override val visibility: Visibility get() = Visibility.Public
    }
}

/**
 * Applies events to a state. Pure and total over well-formed logs; a malformed log (unknown schema,
 * revision going backwards, a card not in the hand, an event out of phase) throws, so a corrupt log
 * fails loudly instead of producing a plausible state.
 */
object DealEvents {
    fun replay(initial: DealState, events: List<DealEvent>): DealState = events.fold(initial, ::apply)

    fun apply(state: DealState, event: DealEvent): DealState {
        require(event.schemaVersion == EVENT_SCHEMA_VERSION) { "unsupported event schema ${event.schemaVersion}" }
        require(event.revision >= state.revision) { "event revision ${event.revision} is behind state revision ${state.revision}" }
        val s = state.copy(revision = event.revision)
        return when (event) {
            is DealEvent.DealOpened -> {
                require(s.phase == Phase.AWAITING_DEAL) { "deal opened in phase ${s.phase}" }
                require(event.profileHash == s.profileHash) { "deal opened under another profile" }
                require(event.level == s.level) { "deal opened at level ${event.level}, state is at ${s.level}" }
                s.copy(phase = Phase.PLAYING, leader = event.leader, turn = event.leader)
            }
            is DealEvent.Dealt -> {
                require(s.phase == Phase.AWAITING_DEAL) { "deal dealt in phase ${s.phase}" }
                require(event.profileHash == s.profileHash) { "deal dealt under another profile" }
                require(event.level == s.level) { "deal dealt at level ${event.level}, state is at ${s.level}" }
                s.copy(phase = Phase.DEALT)
            }
            is DealEvent.HandDealt -> {
                require((s.phase == Phase.PLAYING || s.phase == Phase.DEALT) && s.hands.getValue(event.seat).isEmpty() && s.played.isEmpty()) { "hand dealt twice or mid-deal" }
                val ids = event.cardIds.toSet()
                require(event.cardIds.size == HAND_SIZE && ids.size == HAND_SIZE) { "a hand is $HAND_SIZE distinct cards, got ${event.cardIds.size} (${ids.size} distinct)" }
                require(StandardDeck.ids.containsAll(ids)) { "unknown card ids ${ids - StandardDeck.ids}" }
                val held = s.hands.values.flatten().toSet()
                require(ids.none { it in held }) { "cards already held by another seat: ${ids.filter { it in held }}" }
                s.copy(hands = s.hands + (event.seat to ids))
            }
            is DealEvent.CardMoved -> {
                require(s.phase == Phase.DEALT) { "card moved in phase ${s.phase}" }
                require(event.from != event.to) { "card moved from ${event.from} to itself" }
                require(event.cardId in s.hands.getValue(event.from)) { "${event.from} does not hold ${event.cardId}" }
                s.copy(
                    hands = s.hands +
                        (event.from to (s.hands.getValue(event.from) - event.cardId)) +
                        (event.to to (s.hands.getValue(event.to) + event.cardId)),
                )
            }
            is DealEvent.PlayBegan -> {
                require(s.phase == Phase.DEALT) { "play began in phase ${s.phase}" }
                s.copy(phase = Phase.PLAYING, leader = event.leader, turn = event.leader)
            }
            is DealEvent.Played -> {
                requirePlaying(s, event.seat)
                // Copies: committed state must not alias the event's (possibly caller-owned) lists.
                val ids = event.cardIds.toList()
                val hand = s.hands.getValue(event.seat)
                require(ids.isNotEmpty() && ids.toSet().size == ids.size) { "a play is one or more distinct cards, got $ids" }
                require(hand.containsAll(ids)) { "${event.seat} does not hold ${ids - hand}" }
                // The profile-free checks: the reading covers exactly these cards and beats the table.
                // Which readings the cards admit is the reducer's (profile-bound) judgment.
                require(event.combination.size == ids.size) { "a ${event.combination.size}-card reading stated for ${ids.size} cards" }
                val top = s.trick.top
                require(top == null || event.combination.beats(top.combination)) { "${event.combination} does not beat ${top?.combination}" }
                val play = TrickPlay(event.seat, ids, event.declaration?.toList(), event.combination)
                s.copy(
                    hands = s.hands + (event.seat to (hand - ids.toSet())),
                    played = s.played + ids,
                    trick = Trick(s.trick.plays + play, emptyList()),
                )
            }
            is DealEvent.Passed -> {
                requirePlaying(s, event.seat)
                s.copy(trick = s.trick.copy(passedSinceTop = s.trick.passedSinceTop + event.seat))
            }
            is DealEvent.SeatFinished -> {
                require(s.phase == Phase.PLAYING && s.trick.top?.seat == event.seat) { "${event.seat} finished outside its own play" }
                require(s.hands.getValue(event.seat).isEmpty() && !s.isFinished(event.seat)) { "${event.seat} cannot finish" }
                require(event.place == s.finishOrder.size + 1) { "place ${event.place} out of order" }
                s.copy(finishOrder = s.finishOrder + event.seat)
            }
            is DealEvent.TrickEnded -> {
                require(s.phase == Phase.PLAYING && s.trick.top?.seat == event.winner) { "trick ended for a seat that does not hold it" }
                // D19: every seat still holding cards, other than the winner, passed since the winning play.
                val mustPass = Seat.entries.filter { it != event.winner && !s.isFinished(it) }
                require(s.trick.passedSinceTop.containsAll(mustPass)) { "trick ended before ${mustPass - s.trick.passedSinceTop.toSet()} passed" }
                // D20 (接风): a winner who has played out hands the lead to the partner.
                val winnerOut = s.isFinished(event.winner)
                val next = if (winnerOut) event.winner.partner else event.winner
                require(event.nextLeader == next && event.partnerLead == winnerOut) { "next leader ${event.nextLeader} (partner lead ${event.partnerLead}), expected $next ($winnerOut)" }
                s.copy(trick = Trick.EMPTY, leader = event.nextLeader, turn = event.nextLeader)
            }
            is DealEvent.TurnMoved -> {
                require(s.phase == Phase.PLAYING) { "turn moved in phase ${s.phase}" }
                s.copy(turn = event.seat)
            }
            is DealEvent.DealEnded -> {
                require(s.phase == Phase.PLAYING && event.finishOrder == s.finishOrder) { "deal ended out of phase or with another finish order" }
                require(dealOver(s.finishOrder)) { "deal ended before D21 holds: finish order ${s.finishOrder}" }
                require(event.unfinished == Seat.entries.filter { !s.isFinished(it) }) { "unfinished ${event.unfinished} are not the remaining seats" }
                s.copy(phase = Phase.ENDED, turn = null)
            }
        }
    }

    /** R1.1: the 108 cards dealt round-robin to four seats. */
    private val HAND_SIZE = StandardDeck.cards.size / Seat.entries.size

    private fun requirePlaying(s: DealState, seat: Seat) {
        require(s.phase == Phase.PLAYING && s.turn == seat) { "$seat acted out of turn (turn ${s.turn}, phase ${s.phase})" }
    }
}
