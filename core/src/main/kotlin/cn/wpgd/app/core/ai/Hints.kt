package cn.wpgd.app.core.ai

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64

/**
 * Whether the room offers live hints. PROVISIONAL (gate G4 unsigned): the G4 proposed default is
 * that friendly rooms offer hints and competitive rooms do not. The session maps its room mode here.
 */
enum class HintAvailability { OFFERED, DISABLED_BY_ROOM }

/** Who asks for a hint. Only a seated player asks for its own seat; anyone else is a spectator. */
sealed interface HintRequester {
    data class Player(val seat: Seat) : HintRequester
    data object Spectator : HintRequester
}

sealed interface HintResult {
    /** The suggestion: the action the AI would take in this seat, with the policy that chose it. */
    data class Offered(val action: AiAction, val policy: PolicyId, val source: DecisionSource) : HintResult

    /** The room does not offer hints (competitive). */
    data object DisabledByRoom : HintResult

    /** A spectator, including one privately following a player, never gets a hint. */
    data object NotAPlayer : HintResult

    /** The player has nothing to decide now. */
    data object NothingToDecide : HintResult
}

/**
 * Hints (GDN-062, audit A09): the same generator ([PolicyInput] from the same code) and the same
 * policy as the AI, under the same budget. Pure and local: the result goes only to the caller, which
 * is the requesting member's own device, so it never enters a projection, the wire, or any other
 * member's view. A spectator never gets one, and a room that disables hints gets none.
 */
class HintService(
    private val policy: AiPolicy = BaselineHeuristicPolicy,
    private val limits: DecisionLimits = DecisionLimits.DEFAULT,
) {
    fun hint(availability: HintAvailability, requester: HintRequester, input: PolicyInput?): HintResult {
        if (requester !is HintRequester.Player) return HintResult.NotAPlayer
        if (availability != HintAvailability.OFFERED) return HintResult.DisabledByRoom
        if (input == null || !input.hasChoice) return HintResult.NothingToDecide
        require(input.seat == requester.seat) { "a player asks hints for its own seat only" }
        // Tie-break seeded from public facts only, so the same view always gets the same hint.
        val seed = (input.observation.match.dealNumber.toLong() shl 40) xor
            ((input.observation.deal?.table?.revision ?: 0L) shl 8) xor input.seat.ordinal.toLong()
        val outcome = DecisionRunner.decide(policy, input, SplitMix64(seed), limits = limits)
        return HintResult.Offered(outcome.action, outcome.policy, outcome.source)
    }
}
