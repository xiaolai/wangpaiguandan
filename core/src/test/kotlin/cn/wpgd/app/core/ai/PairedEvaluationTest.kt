package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * GDN-064 paired evaluation: the baseline policy against a random-legal bot on the same seeded
 * deals, once with the baseline in NORTH–SOUTH and once with the seats swapped. Each deal is the
 * first deal of a fresh M1 match (cut-card lead, no tribute), played to its end. A deal is won by the
 * team of the first seat to play out (上游). Reports the win rate with a Wilson 95% interval. Every
 * action of both sides goes through the reducer: zero illegal actions.
 */
class PairedEvaluationTest {
    private class Tally {
        var deals = 0
        var wins = 0
        var illegal = 0L
        var decisions = 0L
        var fallbacks = 0L
        var steps = 0L
        var baselineDecisions = 0L
        var doubleUps = 0
    }

    @Test fun baselineBeatsRandomLegalOnSwappedSeats() {
        val mark = TimeSource.Monotonic.markNow()
        val byDirection = Team.entries.associateWith { Tally() }
        for (baselineTeam in Team.entries) {
            val tally = byDirection.getValue(baselineTeam)
            for (seed in 1L..DEALS_PER_DIRECTION) {
                val choosers = Seat.entries.associateWith { seat ->
                    if (seat.team == baselineTeam) {
                        aiChooser(seed, { _, _ -> BaselineHeuristicPolicy }) { out ->
                            tally.steps += out.steps
                            tally.baselineDecisions++
                            if (out.source != DecisionSource.POLICY) tally.fallbacks++
                        }
                    } else aiChooser(seed xor 0x5DEECE66DL, { _, _ -> RandomLegalPolicy })
                }
                val stats = RunStats()
                val end = playDeal(AiProfiles.reducer, AiProfiles.reducer.newMatch(), seed, choosers, stats)
                assertEquals(MatchPhase.DEAL_ENDED, end.phase)
                val order = end.deal!!.finishOrder
                tally.deals++
                if (order.first().team == baselineTeam) tally.wins++
                if (order.size >= 2 && order[0].team == baselineTeam && order[1].team == baselineTeam) tally.doubleUps++
                tally.illegal += stats.illegal
                tally.decisions += stats.decisions
            }
        }
        val elapsed = mark.elapsedNow()
        val total = Tally().apply {
            for (t in byDirection.values) { deals += t.deals; wins += t.wins; illegal += t.illegal; decisions += t.decisions; fallbacks += t.fallbacks; steps += t.steps; baselineDecisions += t.baselineDecisions; doubleUps += t.doubleUps }
        }
        for ((team, t) in byDirection) {
            val (lo, hi) = wilson(t.wins, t.deals)
            println("PAIRED_EVALUATION direction=baseline_${team.name} deals=${t.deals} wins=${t.wins} win_rate=${fmt(t.wins.toDouble() / t.deals)} wilson95=[${fmt(lo)}, ${fmt(hi)}] double_ups=${t.doubleUps} illegal=${t.illegal} baseline_fallbacks=${t.fallbacks}")
        }
        val (lo, hi) = wilson(total.wins, total.deals)
        println(
            "PAIRED_EVALUATION policy=${BaselineHeuristicPolicy.id} opponent=${RandomLegalPolicy.id} deals=${total.deals} wins=${total.wins} " +
                "win_rate=${fmt(total.wins.toDouble() / total.deals)} wilson95=[${fmt(lo)}, ${fmt(hi)}] decisions=${total.decisions} " +
                "illegal=${total.illegal} baseline_fallbacks=${total.fallbacks} mean_baseline_steps=${total.steps / maxOf(1, total.baselineDecisions)} elapsed_ms=${elapsed.inWholeMilliseconds}",
        )
        assertEquals(0L, total.illegal, "illegal actions in the corpus")
        assertEquals(0L, total.fallbacks, "the baseline fell back under the default budget")
        assertTrue(byDirection.values.all { it.deals >= 300 }, "at least 300 deals per direction")
        assertTrue(lo > 0.5, "baseline must beat random-legal: Wilson lower bound ${fmt(lo)}")
        // A hang guard, not a performance gate; the measured time is printed above.
        assertTrue(elapsed.inWholeSeconds < HANG_GUARD_SECONDS, "evaluation took ${elapsed.inWholeSeconds}s")
    }

    @Test fun wilsonIntervalMatchesAKnownValue() {
        // 81 of 100 at z = 1.96: [0.7222, 0.8749] (Wilson 1927; e.g. Newcombe 1998, method 3).
        val (lo, hi) = wilson(81, 100)
        assertEquals(0.7222, lo, 1e-4)
        assertEquals(0.8749, hi, 1e-4)
    }

    private fun wilson(wins: Int, n: Int, z: Double = 1.959964): Pair<Double, Double> {
        val p = wins.toDouble() / n
        val z2 = z * z
        val centre = (p + z2 / (2 * n)) / (1 + z2 / n)
        val half = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n)
        return (centre - half) to (centre + half)
    }

    private fun fmt(x: Double) = ((x * 10_000).toLong() / 10_000.0).toString()

    private companion object {
        const val DEALS_PER_DIRECTION = 320L
        const val HANG_GUARD_SECONDS = 900L
    }
}
