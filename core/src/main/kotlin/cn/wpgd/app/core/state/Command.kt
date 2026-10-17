package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.RejectReason
import cn.wpgd.app.core.rules.WildcardAssignment

/** An input to [DealReducer.reduce]. Card ids and declarations are untrusted until judged. */
sealed interface Command {
    /**
     * Shuffle and deal. [random] is consumed only if the command is accepted. [openingLeader] is
     * chosen by the caller: the opening-lead rule belongs to the match profile (WI-04, GDN-038).
     */
    class Start(val random: RandomSource, val openingLeader: Seat) : Command {
        override fun toString() = "Start(openingLeader=$openingLeader)"
    }

    /**
     * Put [cardIds] on the table with [declaration] for any wildcards among them. [combinationType]
     * names the intended reading; it is needed only when the cards and declaration admit more than
     * one beating reading (base-v1 D23), and is otherwise checked if given.
     */
    data class Play(
        val seat: Seat,
        val cardIds: List<String>,
        val declaration: List<WildcardAssignment>?,
        val combinationType: CombinationType? = null,
    ) : Command

    data class Pass(val seat: Seat) : Command
}

/**
 * Commands that only the match layer issues between dealing and the first lead (WI-04). Kept out of
 * [Command] so a member's play command can never move a card between hands.
 */
sealed interface SetupCommand {
    /** Shuffle and deal like [Command.Start], but leave the deal in [Phase.DEALT] with no leader. */
    class Deal(val random: RandomSource) : SetupCommand {
        override fun toString() = "Deal"
    }

    /** Move one card from [from]'s hand to [to]'s hand (a tribute or a return). */
    data class MoveCard(val from: Seat, val to: Seat, val cardId: String) : SetupCommand

    /** Open play: [leader] leads the first trick. The match profile chooses the seat (match-v1 section 7). */
    data class BeginPlay(val leader: Seat) : SetupCommand
}

/** Why a command was refused. A rejection never changes state; the caller keeps its state object. */
sealed interface Rejection {
    /** The command does not fit the deal's phase (play before the deal, or anything after it ends). */
    data class WrongPhase(val phase: Phase) : Rejection

    /** It is [expected]'s turn, not [actual]'s. */
    data class WrongSeat(val expected: Seat, val actual: Seat) : Rejection

    /** [seat] has played out (base-v1 D22: such a seat is skipped and cannot act). */
    data class SeatAlreadyFinished(val seat: Seat) : Rejection

    data object EmptySelection : Rejection

    /** The same id appears more than once in the selection; [cardIds] are the repeated ids, sorted. */
    data class DuplicateCard(val cardIds: List<String>) : Rejection

    /** The seat does not hold these ids (or no such card exists); sorted. */
    data class UnownedCard(val cardIds: List<String>) : Rejection

    /** The classifier refused the cards and declaration as a play. */
    data class IllegalCombination(val reason: RejectReason) : Rejection

    /** A legal play that does not beat the play on the table. */
    data object DoesNotBeatTrick : Rejection

    /** The leader must play (R2.12); passing is only for followers. */
    data object PassAsLeader : Rejection

    /** More than one reading beats the table and the command did not pick one (D23). */
    data class AmbiguousReading(val types: List<CombinationType>) : Rejection

    /** The named [type] is not among the readings that beat the table. */
    data class ReadingNotAvailable(val type: CombinationType) : Rejection

    /** A [SetupCommand.MoveCard] from a seat to itself. */
    data class SelfMove(val seat: Seat) : Rejection

    /** A [SetupCommand.BeginPlay] while [seat] holds no card: play cannot open (setup-only, like [SelfMove]). */
    data class EmptyHand(val seat: Seat) : Rejection
}

sealed interface ReduceResult {
    data class Accepted(val state: DealState, val events: List<DealEvent>) : ReduceResult
    data class Rejected(val rejection: Rejection) : ReduceResult
}
