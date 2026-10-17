package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProjector
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.match.MatchSettings
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.ResistanceMode
import cn.wpgd.app.core.match.TributeRules
import cn.wpgd.app.core.rules.Classifier
import cn.wpgd.app.core.rules.LegalActionGenerator
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleSettings
import cn.wpgd.app.core.state.topReading
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck

/**
 * Host side: the [PolicyInput] of an AI seat, from the WI-03/WI-04 projector (its observation) and
 * the reducer's legal options for that seat. The authoritative [MatchState] stays here; the policy
 * receives only the projection and the options.
 */
object HostSeatInputs {
    fun of(reducer: MatchReducer, projector: MatchProjector, state: MatchState, seat: Seat): PolicyInput {
        val observation = projector.aiObservation(state, seat)
        val giver = state.tribute?.let { t -> seat in t.obligation.givers && seat !in t.given } ?: false
        return PolicyInput.of(
            observation = observation,
            plays = reducer.legalActions(state, seat),
            mayPass = state.phase == MatchPhase.PLAYING && observation.deal?.mayPass == true,
            tributeCards = reducer.legalTributeCards(state, seat),
            returnCards = reducer.legalReturnCards(state, seat),
            resistanceOpen = giver && reducer.mayResist(state),
        )
    }
}

/**
 * Member side: the same [PolicyInput], derived from a member's own [MatchMemberView] and the two
 * profiles alone, with the same generator and the same tribute rules (GDN-036). Used for hints, so
 * a hint needs nothing the member does not already hold.
 */
class MemberSeatInputs(base: RuleProfile, match: RuleProfile) {
    private val settings = RuleSettings.of(base)
    private val matchSettings = MatchSettings.of(match)
    private val generators = Ranks.NATURAL.associateWith { lazy { LegalActionGenerator(settings, it) } }
    private val classifiers = Ranks.NATURAL.associateWith { lazy { Classifier(settings, it) } }

    /** The member's choices now, or null when it has nothing to decide (or its view cannot be read unambiguously). */
    fun of(view: MatchMemberView): PolicyInput? {
        val deal = view.deal ?: return null
        val level = view.match.dealLevel
        val handIds = deal.hand.map { it.id }
        val hand = handIds.map { checkNotNull(StandardDeck[it]) { "unknown card $it" } }
        val seat = view.seat
        val tribute = view.match.tribute
        return when (view.match.phase) {
            MatchPhase.PLAYING -> {
                if (!deal.yourTurn) return null
                val trick = deal.table.trick
                val top = if (trick.isEmpty()) null else trick.topReading(classifiers.getValue(level).value) ?: return null
                PolicyInput.of(view, generators.getValue(level).value.generate(hand, top), deal.mayPass, emptyList(), emptyList(), false)
            }
            MatchPhase.TRIBUTE -> {
                val t = tribute ?: return null
                if (seat !in t.givers || t.given.any { it.from == seat }) return null
                val open = matchSettings.resistanceMode == ResistanceMode.CLAIMED && t.given.isEmpty() && t.resistedBy == null
                PolicyInput.of(view, emptyList(), false, TributeRules.legalTributeCards(handIds, level), emptyList(), open)
            }
            MatchPhase.RETURN -> {
                val t = tribute ?: return null
                if (t.given.none { it.to == seat } || seat in t.returnsMade) return null
                PolicyInput.of(view, emptyList(), false, emptyList(), TributeRules.legalReturnCards(handIds, level, matchSettings), false)
            }
            else -> null
        }
    }
}
