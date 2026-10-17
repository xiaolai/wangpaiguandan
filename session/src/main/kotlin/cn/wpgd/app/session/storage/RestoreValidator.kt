package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.StandardDeck

/**
 * Invariants a restored state must hold before a room may continue from it (GDN-052, audit A13).
 * A violation quarantines the table; nothing is repaired, defaulted or dropped.
 */
object RestoreValidator {
    enum class Kind { PROFILE_HASH, REVISION, CARD_COUNT, DUPLICATE_CARD, UNKNOWN_CARD, TURN, PHASE }

    data class Problem(val kind: Kind, val detail: String)

    fun problems(state: MatchState, baseProfileHash: String, matchProfileHash: String, revision: Long): List<Problem> {
        val out = ArrayList<Problem>()
        fun bad(kind: Kind, detail: String) { out += Problem(kind, detail) }

        if (state.baseProfileHash != baseProfileHash || state.matchProfileHash != matchProfileHash) bad(Kind.PROFILE_HASH, "state profiles differ from the room's")
        if (state.revision != revision) bad(Kind.REVISION, "state revision ${state.revision}, log revision $revision")
        if ((state.deal == null) != (state.dealNumber == 0)) bad(Kind.PHASE, "deal ${state.dealNumber} without deal state, or the reverse")

        val deal = state.deal ?: return out.also {
            // No deal yet: only the start of a match, waiting for the first deal.
            if (state.phase != MatchPhase.AWAITING_DEAL) bad(Kind.PHASE, "match phase ${state.phase} without a deal")
        }
        if (deal.profileHash != baseProfileHash) bad(Kind.PROFILE_HASH, "deal profile differs from the room's")
        val all = deal.hands.values.flatten() + deal.played
        val unknown = all.filter { StandardDeck[it] == null }
        if (unknown.isNotEmpty()) bad(Kind.UNKNOWN_CARD, "${unknown.size} unknown card ids")
        val distinct = all.toSet()
        if (all.size != distinct.size) bad(Kind.DUPLICATE_CARD, "${all.size - distinct.size} card ids held twice")
        val dealt = deal.phase != Phase.AWAITING_DEAL
        if (dealt && distinct != StandardDeck.ids) bad(Kind.CARD_COUNT, "${distinct.size} distinct of ${StandardDeck.ids.size} cards")
        if (dealt && all.size != StandardDeck.ids.size) bad(Kind.CARD_COUNT, "${all.size} card ids in play, expected ${StandardDeck.ids.size}")
        if (!dealt && all.isNotEmpty()) bad(Kind.CARD_COUNT, "cards in a deal that was not dealt")

        for (s in deal.finishOrder) if (deal.hands.getValue(s).isNotEmpty()) bad(Kind.TURN, "$s finished but holds cards")
        if (deal.phase == Phase.PLAYING) {
            val turn = deal.turn
            when {
                turn == null -> bad(Kind.TURN, "no turn while playing")
                deal.isFinished(turn) -> bad(Kind.TURN, "turn on finished seat $turn")
                deal.hands.getValue(turn).isEmpty() -> bad(Kind.TURN, "turn on $turn with no cards")
            }
            if (deal.leader == null) bad(Kind.TURN, "no leader while playing")
        }
        val phaseOk = when (state.phase) {
            MatchPhase.PLAYING -> deal.phase == Phase.PLAYING
            MatchPhase.DEAL_ENDED -> deal.phase == Phase.ENDED
            MatchPhase.DEALT, MatchPhase.TRIBUTE, MatchPhase.RETURN, MatchPhase.READY -> deal.phase == Phase.DEALT
            MatchPhase.AWAITING_DEAL, MatchPhase.OVER -> deal.phase == Phase.ENDED
        }
        if (!phaseOk) bad(Kind.PHASE, "match phase ${state.phase} with deal phase ${deal.phase}")
        if ((state.phase == MatchPhase.TRIBUTE || state.phase == MatchPhase.RETURN) && state.tribute == null) bad(Kind.PHASE, "tribute phase without tribute")
        if (state.phase == MatchPhase.READY && state.leader == null) bad(Kind.TURN, "ready without an opening leader")
        return out
    }
}
