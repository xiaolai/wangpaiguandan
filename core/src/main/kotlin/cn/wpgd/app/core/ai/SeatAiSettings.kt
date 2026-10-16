package cn.wpgd.app.core.ai

import cn.wpgd.app.core.state.Seat

/**
 * Per-seat AI settings over a match (GDN-015). Immutable. A change requested while deal `d` is
 * dealt or being played (or after it settled, before the next deal) applies from deal `d + 1`: a
 * seat never changes policy or style inside a deal. The waiting change is visible ([pending]) until
 * it applies.
 *
 * Deal numbers are the match's ([cn.wpgd.app.core.match.MatchState.dealNumber]): 0 before the first
 * deal, then 1, 2, … as each deal is dealt.
 */
class SeatAiSettings private constructor(
    private val initial: AiSettings,
    /** Per seat: (first deal it applies to, settings), in request order. */
    private val changes: Map<Seat, List<Pair<Int, AiSettings>>>,
) {
    /** The settings [seat] plays deal [dealNumber] with. */
    fun effective(seat: Seat, dealNumber: Int): AiSettings =
        changes[seat].orEmpty().lastOrNull { it.first <= dealNumber }?.second ?: initial

    /** A requested change that has not applied yet at [dealNumber], or null. */
    fun pending(seat: Seat, dealNumber: Int): AiSettings? =
        changes[seat].orEmpty().lastOrNull()?.takeIf { it.first > dealNumber }?.second

    /**
     * Requests [settings] for [seat] while the match is at deal [currentDealNumber]. It applies from
     * the next deal boundary. A later request before that boundary replaces it. Unknown policies are
     * refused here, at the request, not at the first decision.
     */
    fun request(seat: Seat, settings: AiSettings, currentDealNumber: Int): SeatAiSettings {
        require(currentDealNumber >= 0) { "deal numbers start at 0" }
        require(AiPolicies.isKnown(settings.policy)) { "unknown AI policy ${settings.policy}" }
        val from = currentDealNumber + 1
        val kept = changes[seat].orEmpty().filter { it.first < from }
        return SeatAiSettings(initial, changes + (seat to kept + (from to settings)))
    }

    companion object {
        /** Every seat starts with [settings]. */
        fun uniform(settings: AiSettings = AiPolicies.DEFAULT_SETTINGS): SeatAiSettings {
            require(AiPolicies.isKnown(settings.policy)) { "unknown AI policy ${settings.policy}" }
            return SeatAiSettings(settings, emptyMap())
        }
    }
}
