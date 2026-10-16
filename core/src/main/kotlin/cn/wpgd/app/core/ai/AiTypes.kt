package cn.wpgd.app.core.ai

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.match.MatchCommand
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.rules.LegalAction
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
import kotlinx.serialization.Serializable

/** Identity of a policy. Recorded with every decision so a result can be traced to its code (GDN-064). */
@Serializable
data class PolicyId(val name: String, val version: String) {
    init {
        require(name.isNotBlank() && version.isNotBlank()) { "a policy needs a name and a version" }
    }

    override fun toString() = "$name@$version"
}

/**
 * An optional play style for a seat (GDN-015). It moves the thresholds of the baseline policy only;
 * it never widens what is legal or what the policy may observe.
 */
@Serializable
enum class AiStyle { CAUTIOUS, BALANCED, BOLD }

/** Per-seat AI settings: which policy version plays, and an optional style (null: the policy default). */
@Serializable
data class AiSettings(val policy: PolicyId, val style: AiStyle? = null)

/** One thing a seat may do now. Every value names only the seat's own cards. */
sealed interface AiAction {
    data class Play(val action: LegalAction) : AiAction
    data object Pass : AiAction
    data class GiveTribute(val cardId: String) : AiAction
    data class ReturnTribute(val cardId: String) : AiAction

    /** Claim resistance (match-v1 M6.3), offered only when the seat's own hand holds both big jokers. */
    data object Resist : AiAction

    /** A canonical one-line form, stable across runs (used to compare decisions byte for byte). */
    fun canonical(): String = when (this) {
        is Play -> "play:" + action.cards.map { it.id }.sorted().joinToString(",") +
            "|" + (action.declaration?.joinToString(",") { "${it.rank}${it.suit?.name?.first() ?: '*'}" } ?: "-") +
            "|" + action.combination.type.name
        Pass -> "pass"
        is GiveTribute -> "tribute:$cardId"
        is ReturnTribute -> "return:$cardId"
        Resist -> "resist"
    }

    /** The match command that carries this action for [seat]. */
    fun toCommand(seat: Seat): MatchCommand = when (this) {
        is Play -> MatchCommand.InDeal(Command.Play(seat, action.cards.map { it.id }, action.declaration, action.combination.type))
        Pass -> MatchCommand.InDeal(Command.Pass(seat))
        is GiveTribute -> MatchCommand.GiveTribute(seat, cardId)
        is ReturnTribute -> MatchCommand.ReturnTribute(seat, cardId)
        Resist -> MatchCommand.Resist(seat)
    }
}

/**
 * What a seat may choose from right now: the legal-action generator's plays for its own hand and the
 * match rules' tribute and return cards for its own hand. Built only by [PolicyInput.of], which
 * checks that every card named here is in the observing seat's own hand.
 */
class SeatChoices internal constructor(
    val plays: List<LegalAction>,
    val mayPass: Boolean,
    val tributeCards: List<String>,
    val returnCards: List<String>,
    val mayResist: Boolean,
) {
    /** Every legal action, in a fixed order: resist, tribute, return, plays in generator order, pass. */
    val all: List<AiAction> = buildList {
        if (mayResist) add(AiAction.Resist)
        tributeCards.forEach { add(AiAction.GiveTribute(it)) }
        returnCards.forEach { add(AiAction.ReturnTribute(it)) }
        plays.forEach { add(AiAction.Play(it)) }
        if (mayPass) add(AiAction.Pass)
    }

    private val playSet = plays.toHashSet()

    fun isLegal(action: AiAction): Boolean = when (action) {
        is AiAction.Play -> action.action in playSet
        AiAction.Pass -> mayPass
        is AiAction.GiveTribute -> action.cardId in tributeCards
        is AiAction.ReturnTribute -> action.cardId in returnCards
        AiAction.Resist -> mayResist
    }

    /** A canonical form of the whole choice set (for byte comparisons in tests and diagnostics). */
    fun canonical(): String = all.joinToString("\n") { it.canonical() }
}

/**
 * The complete input of a policy (GDN-058, section 8 P2): the seat's own projection and its legal
 * options. There is deliberately no field for authoritative state, so a policy cannot read another
 * seat's hand: [observation] is the same [MatchMemberView] a human in the seat receives, and
 * [choices] names only cards of that seat's own hand (checked here).
 */
class PolicyInput private constructor(val observation: MatchMemberView, val choices: SeatChoices) {
    val seat: Seat get() = observation.seat

    /** The seat's own cards, from its own projection. Empty when no deal is in progress. */
    val hand: List<Card> by lazy { observation.deal?.hand.orEmpty().map { checkNotNull(StandardDeck[it.id]) { "unknown card ${it.id}" } } }

    /** The level of the current deal. */
    val level: Int get() = observation.match.dealLevel

    val hasChoice: Boolean get() = choices.all.isNotEmpty()

    companion object {
        /**
         * Builds the input of [observation]'s seat. [resistanceOpen] says the match lets the giving side
         * claim resistance now; the seat is offered the claim only when its own hand holds both big
         * jokers, so the offer never reveals a partner's card. Fails if any option names a card the
         * seat does not hold.
         */
        fun of(
            observation: MatchMemberView,
            plays: List<LegalAction>,
            mayPass: Boolean,
            tributeCards: List<String>,
            returnCards: List<String>,
            resistanceOpen: Boolean,
        ): PolicyInput {
            val own = observation.deal?.hand.orEmpty().map { it.id }.toSet()
            val named = plays.flatMap { a -> a.cards.map { it.id } } + tributeCards + returnCards
            val foreign = named.filter { it !in own }.distinct().sorted()
            require(foreign.isEmpty()) { "options name cards outside the seat's own hand: ${foreign.size} card(s)" }
            require(!mayPass || observation.deal?.mayPass == true) { "pass offered while the seat's view says it may not pass" }
            val bigJokers = own.count { StandardDeck[it]?.rank == Ranks.BIG_JOKER }
            val choices = SeatChoices(
                plays = plays.toList(),
                mayPass = mayPass,
                tributeCards = tributeCards.sorted(),
                returnCards = returnCards.sorted(),
                mayResist = resistanceOpen && bigJokers >= 2,
            )
            return PolicyInput(observation, choices)
        }
    }
}
