package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Seat
import kotlinx.serialization.Serializable

/** How a seat's place was established (audit A36: inferred places are marked, never presented as played). */
@Serializable
enum class PlaceBasis {
    /** The seat played out in this place. */
    PLAYED,

    /** Three seats played out; this one is 下游 (place 4) by elimination. */
    LAST_BY_ELIMINATION,

    /** The deal ended early at a double-up; this seat is one of the two 双下 seats, places 3–4, unordered. */
    INFERRED_DOUBLE_DOWN,
}

/** A seat's place: exactly [bestPlace] when both bounds agree, otherwise a range (3–4 after an early double-up). */
@Serializable
data class Placement(val seat: Seat, val bestPlace: Int, val worstPlace: Int, val basis: PlaceBasis)

@Serializable
enum class Completion { THREE_FINISHED, DOUBLE_UP_ENDED_EARLY }

/** What one settlement did to one team (match-v1 sections 2 and 3). */
@Serializable
data class TeamOutcome(
    val team: Team,
    val levelBefore: Int,
    val levelAfter: Int,
    val failuresBefore: Int,
    val failuresAfter: Int,
    /** The deal was a qualifying A deal for this team (M3.1). */
    val aAttempt: Boolean,
    /** The team was at A and the deal was at another level (M3.6, an interruption). */
    val interruption: Boolean,
    val passedA: Boolean,
    val failedA: Boolean,
    /** The failure limit was reached and the level returned to the reset level (M3.8). */
    val reset: Boolean,
)

/** The settled result of one deal. Public: every field is public table information. */
@Serializable
data class DealResult(
    val dealNumber: Int,
    val dealLevel: Int,
    val finishOrder: List<Seat>,
    val placements: List<Placement>,
    val completion: Completion,
    /** The profile's completion policy, as a label (`deal.doubleUpCompletion`, D38). */
    val completionPolicy: String,
    val winningTeam: Team,
    /** Place of 上游's partner: 2, 3 or 4. */
    val partnerPlace: Int,
    val gain: Int,
    val teams: List<TeamOutcome>,
    val matchWinner: Team?,
    /** The next deal's level: the winning team's level after settlement (M2.5). */
    val nextLevel: Int,
) {
    fun outcome(team: Team): TeamOutcome = teams.first { it.team == team }

    /** The seat placed 4th by elimination, or null after an early double-up. */
    val lastSeat: Seat? get() = placements.firstOrNull { it.basis == PlaceBasis.LAST_BY_ELIMINATION }?.seat
}

/** Pure settlement of a finished deal (match-v1 sections 1–3). */
object Settlement {
    fun settle(
        dealNumber: Int,
        dealLevel: Int,
        finishOrder: List<Seat>,
        levels: Map<Team, Int>,
        failures: Map<Team, Int>,
        settings: MatchSettings,
    ): DealResult {
        require(finishOrder.toSet().size == finishOrder.size) { "a seat finishes once: $finishOrder" }
        val partnersFirst = finishOrder.size >= 2 && finishOrder[1] == finishOrder[0].partner
        val doubleUp = finishOrder.size == 2 && partnersFirst
        // `endEarly` (D38, the only completion policy): a double-up ends the deal, so no third seat finishes after one.
        require(doubleUp || (finishOrder.size == 3 && !partnersFirst)) { "a deal ends with three finishers or a double-up (M1.4), got $finishOrder" }
        require(dealLevel in Ranks.NATURAL) { "deal level $dealLevel" }

        val first = finishOrder[0]
        val winner = first.team
        val partnerPlace = finishOrder.indexOf(first.partner).let { if (it < 0) 4 else it + 1 }
        val gain = settings.gainFor(partnerPlace)
        val rest = Seat.entries.filter { it !in finishOrder }
        val placements = finishOrder.mapIndexed { i, s -> Placement(s, i + 1, i + 1, PlaceBasis.PLAYED) } +
            if (doubleUp) rest.map { Placement(it, 3, 4, PlaceBasis.INFERRED_DOUBLE_DOWN) }
            else rest.map { Placement(it, 4, 4, PlaceBasis.LAST_BY_ELIMINATION) }

        val outcomes = Team.entries.map { team ->
            teamOutcome(team, levels.getValue(team), failures.getValue(team), dealLevel, team == winner, gain, settings)
        }
        val matchWinner = outcomes.firstOrNull { it.passedA }?.team
        return DealResult(
            dealNumber = dealNumber,
            dealLevel = dealLevel,
            // A copy: the result must not alias the caller's list.
            finishOrder = finishOrder.toList(),
            placements = placements,
            completion = if (doubleUp) Completion.DOUBLE_UP_ENDED_EARLY else Completion.THREE_FINISHED,
            completionPolicy = settings.completionPolicy,
            winningTeam = winner,
            partnerPlace = partnerPlace,
            gain = gain,
            teams = outcomes,
            matchWinner = matchWinner,
            nextLevel = outcomes.first { it.team == winner }.levelAfter,
        )
    }

    /** One team's level and A-attempt accounting after a deal (match-v1 sections 2 and 3). */
    private fun teamOutcome(
        team: Team,
        level: Int,
        failed0: Int,
        dealLevel: Int,
        won: Boolean,
        gain: Int,
        settings: MatchSettings,
    ): TeamOutcome {
        val atA = level == Ranks.ACE
        val attempt = atA && (!settings.requireDealAtA || dealLevel == Ranks.ACE)
        val interruption = atA && !attempt
        val passed = won && attempt && gain >= settings.minGainToPassA
        val failed = attempt && !passed && (won || settings.opponentWinCounts)
        val levelAfter = if (won && !passed) minOf(Ranks.ACE, level + gain) else level
        val failuresAfter = when {
            failed -> failed0 + 1
            interruption && settings.interruptionResets -> 0
            else -> failed0
        }
        val limit = settings.maxAFailures
        val reset = limit != null && failuresAfter >= limit
        return if (reset) {
            TeamOutcome(team, level, settings.resetLevel, failed0, 0, attempt, interruption, passed, failed, reset = true)
        } else {
            TeamOutcome(team, level, levelAfter, failed0, failuresAfter, attempt, interruption, passed, failed, reset = false)
        }
    }
}
