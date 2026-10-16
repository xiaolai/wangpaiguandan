package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.Combination
import cn.wpgd.app.core.rules.WildcardAssignment

enum class Phase {
    /** Made by [DealReducer.newDeal]; only [Command.Start] or [SetupCommand.Deal] is accepted. */
    AWAITING_DEAL,
    /**
     * Dealt by [SetupCommand.Deal] but no lead yet: the match layer moves tribute and return cards
     * here ([SetupCommand.MoveCard], match-v1 sections 4–5), then opens play with [SetupCommand.BeginPlay].
     */
    DEALT,
    PLAYING,
    /** The base-v1 D21 end condition holds; every command is refused. */
    ENDED,
}

/** One play in the current trick, as the player stated it. [cardIds] are sorted. */
data class TrickPlay(
    val seat: Seat,
    val cardIds: List<String>,
    /** The submitted wildcard declaration; null when the player gave none (allowed only by some profiles). */
    val declaration: List<WildcardAssignment>?,
    /** The reading that was judged to beat the table. */
    val combination: Combination,
)

/**
 * The trick in progress. [plays] in order; [passedSinceTop] lists the seats that passed after the
 * latest play, in order. Empty plays means the next action is a lead.
 */
data class Trick(val plays: List<TrickPlay>, val passedSinceTop: List<Seat>) {
    val top: TrickPlay? get() = plays.lastOrNull()

    companion object {
        val EMPTY = Trick(emptyList(), emptyList())
    }
}

/**
 * Authoritative state of one deal. Host-internal: it holds every hand. Members see only a
 * projection ([SeatProjector]). Immutable; the constructor is internal so that outside `:core`
 * a state comes only from [DealReducer.newDeal] and [DealReducer.reduce] or from replaying events.
 *
 * - [hands]: card ids held by each seat; always all four seats.
 * - [played]: every card id played in this deal, including the current trick.
 * - [leader]: the seat that led (or is to lead) the current trick.
 * - [turn]: the seat to act; null unless [phase] is PLAYING.
 * - [finishOrder]: seats in the order they played out (上游, 二游, …).
 * - [revision]: committed revision; +1 per accepted command.
 */
@ConsistentCopyVisibility
data class DealState internal constructor(
    val phase: Phase,
    val level: Int,
    val profileHash: String,
    val hands: Map<Seat, Set<String>>,
    val played: Set<String>,
    val trick: Trick,
    val leader: Seat?,
    val turn: Seat?,
    val finishOrder: List<Seat>,
    val revision: Long,
) {
    init {
        require(hands.keys == Seat.entries.toSet()) { "a deal has exactly four seats, got ${hands.keys}" }
        require((turn != null) == (phase == Phase.PLAYING)) { "turn must be set exactly while playing" }
        require(finishOrder.toSet().size == finishOrder.size) { "a seat finishes once" }
    }

    fun isFinished(seat: Seat): Boolean = seat in finishOrder

    internal companion object {
        fun awaiting(level: Int, profileHash: String) = DealState(
            phase = Phase.AWAITING_DEAL,
            level = level,
            profileHash = profileHash,
            hands = Seat.entries.associateWith { emptySet() },
            played = emptySet(),
            trick = Trick.EMPTY,
            leader = null,
            turn = null,
            finishOrder = emptyList(),
            revision = 0,
        )
    }
}
