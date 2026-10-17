package cn.wpgd.app.core.match

import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.RandomSource
import cn.wpgd.app.core.state.Rejection
import cn.wpgd.app.core.state.Seat

/** An input to [MatchReducer.reduce]. Seats and card ids are untrusted until judged. */
sealed interface MatchCommand {
    /** Shuffle and deal the next deal at the match's current deal level. [random] is used only if accepted. */
    class DealNext(val random: RandomSource) : MatchCommand {
        override fun toString() = "DealNext"
    }

    /** Begin the tribute owed from the previous deal (match-v1 section 4). Not used on the first deal. */
    data object StartTribute : MatchCommand

    data class GiveTribute(val seat: Seat, val cardId: String) : MatchCommand

    /** A giver claims resistance (match-v1 M6.3; `resistance.mode` = claimed). */
    data class Resist(val seat: Seat) : MatchCommand

    data class ReturnTribute(val seat: Seat, val cardId: String) : MatchCommand

    /** Open play. Takes no seat: the leader is the one the profile resolved (audit A02). */
    data object OpenPlay : MatchCommand

    /** A play or pass in the current deal ([Command.Start] is refused: dealing is [DealNext]). */
    data class InDeal(val command: Command) : MatchCommand

    /** [asker] asks [target]'s card count (match-v1 M8.4; only when `reporting.answerWhenAsked` is on). */
    data class AskCardCount(val asker: Seat, val target: Seat) : MatchCommand

    /** Settle the ended deal from its finish order (match-v1 sections 1–3). */
    data object Settle : MatchCommand

    /**
     * Start a new match under the same profiles once this one is [MatchPhase.OVER] (GDN-017, WI-09).
     * The room keeps its seats and members; the revision keeps increasing.
     */
    data object Rematch : MatchCommand
}

/** Why a match command was refused. A rejection never changes state. */
sealed interface MatchRejection {
    data class WrongPhase(val phase: MatchPhase) : MatchRejection
    data object MatchOver : MatchRejection

    /** Dealing goes through [MatchCommand.DealNext], never a deal-level [Command.Start]. */
    data object StartNotAllowed : MatchRejection

    /** The deal reducer refused a play or pass. */
    data class Deal(val rejection: Rejection) : MatchRejection

    data class NotAGiver(val seat: Seat) : MatchRejection
    data class AlreadyGave(val seat: Seat) : MatchRejection
    data class UnownedCard(val cardId: String) : MatchRejection

    /** M4.3: a heart level card (wildcard) is never tribute. */
    data class WildcardNotTributable(val cardId: String) : MatchRejection

    /** M4.3: the card is not of the giver's highest rank. */
    data class NotHighestCard(val cardId: String) : MatchRejection

    /** The giving side does not hold the two big jokers as M6.1/M6.2 require. */
    data object ResistanceNotAvailable : MatchRejection

    /** M6.3: a tribute card was already given, which waives the claim. */
    data object ResistanceWaived : MatchRejection

    /** `resistance.mode` is automatic: there is no claim to make. */
    data object ResistanceIsAutomatic : MatchRejection

    /** The seat received no tribute this deal, so it owes no return. */
    data class NotAReturner(val seat: Seat) : MatchRejection
    data class AlreadyReturned(val seat: Seat) : MatchRejection

    /** M5.2–M5.4: the card is above the return limit while a card within it exists, or not among the lowest. */
    data class ReturnNotAllowed(val cardId: String) : MatchRejection

    /** M8.4 default: nobody may ask a card count. */
    data object AskingNotAllowed : MatchRejection

    /** Only an opponent may ask (CUP:90). */
    data object AskOwnTeam : MatchRejection

    data object AskTargetFinished : MatchRejection

    /** The target holds more than `reporting.askThreshold` cards and need not answer. */
    data object AskAboveThreshold : MatchRejection
}

sealed interface MatchReduceResult {
    data class Accepted(val state: MatchState, val events: List<MatchEvent>) : MatchReduceResult
    data class Rejected(val rejection: MatchRejection) : MatchReduceResult
}
