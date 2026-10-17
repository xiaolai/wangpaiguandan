package cn.wpgd.app.owner

import cn.wpgd.app.session.wire.RejectionCode

/**
 * Why a command was not applied, as a message key the UI translates (GDN-050, audit A20). The host
 * sends a [RejectionCode] and, for rule refusals, the rule's type name only (`Deal.DoesNotBeatTrick`,
 * `NotHighestCard`), never card or seat data; [RejectionKeys.of] maps both to one of these keys.
 */
enum class RejectionKey(val messageKey: String) {
    /** The table moved on before the command arrived (another seat acted, or a new epoch). */
    TABLE_MOVED_ON("table.reject.table_moved_on"),
    NOT_YOUR_TURN("table.reject.not_your_turn"),
    ALREADY_FINISHED("table.reject.already_finished"),
    WRONG_PHASE("table.reject.wrong_phase"),
    NOTHING_SELECTED("table.reject.nothing_selected"),
    CARDS_NOT_IN_HAND("table.reject.cards_not_in_hand"),
    NOT_A_COMBINATION("table.reject.not_a_combination"),
    DOES_NOT_BEAT("table.reject.does_not_beat"),
    LEADER_MUST_PLAY("table.reject.leader_must_play"),
    CHOOSE_A_READING("table.reject.choose_a_reading"),
    TRIBUTE_CARD_NOT_ALLOWED("table.reject.tribute_card_not_allowed"),
    RETURN_CARD_NOT_ALLOWED("table.reject.return_card_not_allowed"),
    RESISTANCE_NOT_AVAILABLE("table.reject.resistance_not_available"),
    ASKING_NOT_ALLOWED("table.reject.asking_not_allowed"),
    ROOM_PAUSED("table.reject.room_paused"),
    ROOM_QUARANTINED("table.reject.room_quarantined"),

    /** The command never reached the host (not admitted, stale or closed link). */
    NOT_CONNECTED("table.reject.not_connected"),

    /** The link dropped while the command was pending: it may or may not have been applied; the table shows the truth. */
    OUTCOME_UNKNOWN("table.reject.outcome_unknown"),
    REFUSED_BY_HOST("table.reject.refused_by_host"),

    /** WI-09: the host changed the room settings; the player must look at them again. */
    SETTINGS_CHANGED("table.reject.settings_changed"),
}

object RejectionKeys {
    /** Maps the host's typed rejection to a key. [reason] is the rule type name the host sends with [RejectionCode.RULE]. */
    fun of(code: RejectionCode, reason: String): RejectionKey = when (code) {
        RejectionCode.STALE_EPOCH, RejectionCode.STALE_REVISION -> RejectionKey.TABLE_MOVED_ON
        RejectionCode.NOT_SEATED, RejectionCode.NOT_A_PLAYER -> RejectionKey.NOT_YOUR_TURN
        RejectionCode.ROOM_PAUSED -> RejectionKey.ROOM_PAUSED
        RejectionCode.ROOM_QUARANTINED -> RejectionKey.ROOM_QUARANTINED
        RejectionCode.NOT_IN_LOBBY -> RejectionKey.WRONG_PHASE
        RejectionCode.SETTINGS_CHANGED -> RejectionKey.SETTINGS_CHANGED
        RejectionCode.RULE -> RULES[reason] ?: RejectionKey.REFUSED_BY_HOST
        RejectionCode.BAD_CAPABILITY, RejectionCode.MALFORMED, RejectionCode.FOLLOW_DISABLED, RejectionCode.ONE_VIEW_PER_DEAL,
        RejectionCode.ALREADY_FOLLOWING, RejectionCode.NO_CONSENTING_PLAYER, RejectionCode.GRANT_UNKNOWN,
        -> RejectionKey.REFUSED_BY_HOST
    }

    /** Rule type names, as `SessionHost.ruleReason` writes them (core `MatchRejection` and deal `Rejection`). */
    private val RULES: Map<String, RejectionKey> = mapOf(
        "Deal.WrongPhase" to RejectionKey.WRONG_PHASE,
        "Deal.WrongSeat" to RejectionKey.NOT_YOUR_TURN,
        "Deal.SeatAlreadyFinished" to RejectionKey.ALREADY_FINISHED,
        "Deal.EmptySelection" to RejectionKey.NOTHING_SELECTED,
        "Deal.DuplicateCard" to RejectionKey.CARDS_NOT_IN_HAND,
        "Deal.UnownedCard" to RejectionKey.CARDS_NOT_IN_HAND,
        "Deal.IllegalCombination" to RejectionKey.NOT_A_COMBINATION,
        "Deal.DoesNotBeatTrick" to RejectionKey.DOES_NOT_BEAT,
        "Deal.PassAsLeader" to RejectionKey.LEADER_MUST_PLAY,
        "Deal.AmbiguousReading" to RejectionKey.CHOOSE_A_READING,
        "Deal.ReadingNotAvailable" to RejectionKey.CHOOSE_A_READING,
        "WrongPhase" to RejectionKey.WRONG_PHASE,
        "MatchOver" to RejectionKey.WRONG_PHASE,
        "StartNotAllowed" to RejectionKey.WRONG_PHASE,
        "NotAGiver" to RejectionKey.TRIBUTE_CARD_NOT_ALLOWED,
        "AlreadyGave" to RejectionKey.TRIBUTE_CARD_NOT_ALLOWED,
        "UnownedCard" to RejectionKey.CARDS_NOT_IN_HAND,
        "WildcardNotTributable" to RejectionKey.TRIBUTE_CARD_NOT_ALLOWED,
        "NotHighestCard" to RejectionKey.TRIBUTE_CARD_NOT_ALLOWED,
        "ResistanceNotAvailable" to RejectionKey.RESISTANCE_NOT_AVAILABLE,
        "ResistanceWaived" to RejectionKey.RESISTANCE_NOT_AVAILABLE,
        "ResistanceIsAutomatic" to RejectionKey.RESISTANCE_NOT_AVAILABLE,
        "NotAReturner" to RejectionKey.RETURN_CARD_NOT_ALLOWED,
        "AlreadyReturned" to RejectionKey.RETURN_CARD_NOT_ALLOWED,
        "ReturnNotAllowed" to RejectionKey.RETURN_CARD_NOT_ALLOWED,
        "AskingNotAllowed" to RejectionKey.ASKING_NOT_ALLOWED,
        "AskOwnTeam" to RejectionKey.ASKING_NOT_ALLOWED,
        "AskTargetFinished" to RejectionKey.ASKING_NOT_ALLOWED,
        "AskAboveThreshold" to RejectionKey.ASKING_NOT_ALLOWED,
    )

    /** Every rule name with its own key (tests check this list against the core rejection types). */
    val ruleNames: Set<String> get() = RULES.keys
}
