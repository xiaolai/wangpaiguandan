package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * GDN-014: one to four scripted humans plus AI complete a whole match under one profile (P1 + M1).
 * A scripted human works only from its own member view (the member-side inputs a client UI would
 * show) and takes the simplest legal action; AI seats run the baseline through the decision runner.
 */
class HumanCountMatrixTest {
    @Test fun oneToFourHumansCompleteAMatch() {
        val mark = TimeSource.Monotonic.markNow()
        val reducer = AiProfiles.reducer
        for (humans in 1..4) {
            for (seed in 1L..3L) {
                val humanSeats = Seat.entries.take(humans).toSet()
                val choosers = Seat.entries.associateWith { if (it in humanSeats) scriptedHuman else aiChooser(seed) }
                val stats = RunStats()
                var state = reducer.newMatch()
                var guard = 0L
                while (state.phase != MatchPhase.OVER) {
                    state = step(reducer, state, { SplitMix64(seed * 1_000 + it) }, choosers, stats, humanInputsFromView = humanSeats)
                    assertTrue(++guard < 2_000_000 && state.dealNumber <= MAX_DEALS, "humans=$humans seed=$seed: match did not end")
                }
                assertEquals(0L, stats.illegal)
                assertEquals(reducer.newMatch().baseProfileHash, state.baseProfileHash, "one profile for the whole match")
                println("HUMAN_COUNT_MATRIX humans=$humans seed=$seed deals=${state.dealNumber} decisions=${stats.decisions} winner=${state.winner}")
            }
        }
        println("HUMAN_COUNT_MATRIX elapsed_ms=${mark.elapsedNow().inWholeMilliseconds}")
    }

    private companion object {
        const val MAX_DEALS = 600
    }
}
