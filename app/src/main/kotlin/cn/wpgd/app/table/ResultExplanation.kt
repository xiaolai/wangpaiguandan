package cn.wpgd.app.table

import cn.wpgd.app.core.match.DealResult
import cn.wpgd.app.core.match.PlaceBasis
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.match.TributeRules
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat

/** Who reads a result: a seated player (relative wording) or a spectator (neutral wording, GDN-082). */
sealed interface Viewer {
    data class Player(val seat: Seat) : Viewer
    data object Spectator : Viewer
}

/** A team as named to the viewer: relative for a player, by its seats for a spectator. */
sealed interface TeamLabel {
    data object Own : TeamLabel
    data object Opponents : TeamLabel

    /** Neutral: the team by name, never "you" or "us". */
    data class Named(val team: Team) : TeamLabel
}

/** A seat as named to the viewer. */
sealed interface SeatLabel {
    data class Relative(val relation: SeatRelation) : SeatLabel
    data class Named(val seat: Seat) : SeatLabel
}

enum class ResultHeadline(val messageKey: String) {
    OWN_TEAM_WON_DEAL("result.own_team_won_deal"),
    OWN_TEAM_LOST_DEAL("result.own_team_lost_deal"),
    OWN_TEAM_WON_MATCH("result.own_team_won_match"),
    OWN_TEAM_LOST_MATCH("result.own_team_lost_match"),
    TEAM_WON_DEAL("result.team_won_deal"),
    TEAM_WON_MATCH("result.team_won_match"),
}

/** Your part in the next tribute; null for a spectator. */
enum class TributeRole { YOU_GIVE, YOU_RECEIVE, PARTNER_GIVES, PARTNER_RECEIVES }

/** One line of the explanation. Every field is public table information. */
sealed interface ResultLine {
    val messageKey: String

    data class Placing(val seat: SeatLabel, val bestPlace: Int, val worstPlace: Int, val basis: PlaceBasis) : ResultLine {
        override val messageKey get() = "result.placing"
    }

    /** Places 3–4 after an early double-up were not played out (audit A36). */
    data object PlacesInferred : ResultLine {
        override val messageKey get() = "result.places_inferred"
    }

    data class LevelChange(val team: TeamLabel, val from: Int, val to: Int, val gain: Int) : ResultLine {
        override val messageKey get() = "result.level_change"
    }

    data class APassed(val team: TeamLabel) : ResultLine {
        override val messageKey get() = "result.a_passed"
    }

    /** The team failed its A attempt: [failures] so far, of [limit] before the level resets (null: no limit). */
    data class AFailed(val team: TeamLabel, val failures: Int, val limit: Int?) : ResultLine {
        override val messageKey get() = "result.a_failed"
    }

    data class AReset(val team: TeamLabel, val toLevel: Int) : ResultLine {
        override val messageKey get() = "result.a_reset"
    }

    /** The team is at A, the deal was at another level: an interruption. */
    data class AInterrupted(val team: TeamLabel) : ResultLine {
        override val messageKey get() = "result.a_interrupted"
    }

    /** Next deal: who gives tribute to whom (resistance, if the givers hold both big jokers, is shown when dealt). */
    data class TributeNext(val kind: TributeKind, val givers: List<SeatLabel>, val receivers: List<SeatLabel>, val yourRole: TributeRole?) : ResultLine {
        override val messageKey get() = "result.tribute_next"
    }
}

/** A settled deal explained (GDN-082): A failures, the next tribute, and wording fit for the viewer. */
data class ResultExplanation(val headline: ResultHeadline, val winner: TeamLabel, val lines: List<ResultLine>) {
    companion object {
        /** [maxAFailures] is the match profile's failure limit (`MatchSettings.maxAFailures`). */
        fun of(result: DealResult, viewer: Viewer, maxAFailures: Int?): ResultExplanation {
            val me = (viewer as? Viewer.Player)?.seat
            fun team(t: Team): TeamLabel = when {
                me == null -> TeamLabel.Named(t)
                t == me.team -> TeamLabel.Own
                else -> TeamLabel.Opponents
            }
            fun seat(s: Seat): SeatLabel = if (me == null) SeatLabel.Named(s) else SeatLabel.Relative(SeatRelation.of(me, s))

            val over = result.matchWinner != null
            val headline = when {
                me == null -> if (over) ResultHeadline.TEAM_WON_MATCH else ResultHeadline.TEAM_WON_DEAL
                over -> if (result.matchWinner == me.team) ResultHeadline.OWN_TEAM_WON_MATCH else ResultHeadline.OWN_TEAM_LOST_MATCH
                result.winningTeam == me.team -> ResultHeadline.OWN_TEAM_WON_DEAL
                else -> ResultHeadline.OWN_TEAM_LOST_DEAL
            }
            val lines = ArrayList<ResultLine>()
            result.placements.sortedWith(compareBy({ it.bestPlace }, { it.seat.ordinal })).forEach {
                lines += ResultLine.Placing(seat(it.seat), it.bestPlace, it.worstPlace, it.basis)
            }
            if (result.placements.any { it.basis == PlaceBasis.INFERRED_DOUBLE_DOWN }) lines += ResultLine.PlacesInferred
            for (o in result.teams) {
                val label = team(o.team)
                if (o.levelAfter != o.levelBefore && !o.reset) lines += ResultLine.LevelChange(label, o.levelBefore, o.levelAfter, result.gain)
                if (o.passedA) lines += ResultLine.APassed(label)
                if (o.failedA) lines += ResultLine.AFailed(label, if (o.reset) o.failuresBefore + 1 else o.failuresAfter, maxAFailures)
                if (o.reset) lines += ResultLine.AReset(label, o.levelAfter)
                if (o.interruption) lines += ResultLine.AInterrupted(label)
            }
            if (!over) {
                val o = TributeRules.obligation(result)
                // Null when neither the viewer nor the partner takes part (first and last were partners).
                val role = me?.let {
                    when {
                        it in o.givers -> TributeRole.YOU_GIVE
                        it in o.receivers -> TributeRole.YOU_RECEIVE
                        it.partner in o.givers -> TributeRole.PARTNER_GIVES
                        it.partner in o.receivers -> TributeRole.PARTNER_RECEIVES
                        else -> null
                    }
                }
                lines += ResultLine.TributeNext(o.kind, o.givers.map(::seat), o.receivers.map(::seat), role)
            }
            return ResultExplanation(headline, team(result.matchWinner ?: result.winningTeam), lines)
        }
    }
}
