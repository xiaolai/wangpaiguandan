package cn.wpgd.app.session.ai

import cn.wpgd.app.core.ai.DecisionSource
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * GDN-014 over the session: 1 to 4 scripted human clients plus driver-controlled AI seats complete
 * a whole match through the host, over the in-memory transport, under one profile pair.
 */
class SessionHumanCountMatrixTest {
    @Test fun oneToFourHumansCompleteAMatchThroughTheHost() {
        val mark = TimeSource.Monotonic.markNow()
        for (humans in 1..4) {
            val t = Table(seed = 500L + humans)
            val driver = AiSeatDriver(t.scheduler, AiDriverConfig(tieBreakSeed = humans.toLong()))
            val scripted = Seat.entries.take(humans).map { ScriptedHuman(t, t.player(it)) }
            for (s in Seat.entries.drop(humans)) assertTrue(t.host.setAi(s, driver.policy))
            val profile = t.host.state.baseProfileHash to t.host.state.matchProfileHash
            t.driveUntil { it.state.phase == MatchPhase.OVER }
            assertEquals(0L, t.host.metrics.aiRejected, "humans=$humans")
            assertTrue(scripted.all { it.rejected == 0 && it.submitted > 0 }, "humans=$humans: every human acted, none refused: ${scripted.flatMap { it.rejections }}")
            assertTrue(driver.metrics.bySource.keys.all { it == DecisionSource.POLICY }, "humans=$humans ${driver.metrics.bySource}")
            assertEquals(profile, t.host.state.baseProfileHash to t.host.state.matchProfileHash, "one profile for the whole match")
            println("SESSION_HUMAN_COUNT humans=$humans deals=${t.host.state.dealNumber} ai_decisions=${driver.metrics.decisions} human_commands=${scripted.sumOf { it.submitted }} winner=${t.host.state.winner}")
        }
        println("SESSION_HUMAN_COUNT elapsed_ms=${mark.elapsedNow().inWholeMilliseconds}")
    }
}
