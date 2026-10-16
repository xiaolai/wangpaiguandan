package cn.wpgd.app.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import cn.wpgd.app.core.ThreadCpuClock

/**
 * M0 exit criterion 3: 10,000 seeded all-bot deals, zero invariant violations, zero illegal bot
 * actions. Invariants (plan section 8) are checked after every command.
 */
class SimulationInvariantTest {
    @Test fun tenThousandSeededDealsKeepEveryInvariant() {
        val r = p1Reducer()
        val cpu = ThreadCpuClock()
        var commands = 0L
        var partnerLeads = 0
        var doubleUps = 0
        for (seed in 1L..DEALS) {
            val level = 2 + (seed % 13).toInt()
            val final = playDeal(r, seed, level) { step -> check(r, seed, step); commands++ ; if (step.events.any { it is DealEvent.TrickEnded && it.partnerLead }) partnerLeads++ }
            assertEquals(Phase.ENDED, final.phase, "deal $seed")
            val order = final.finishOrder
            val ended = order.size == 3 || (order.size == 2 && order[0].partner == order[1])
            assertTrue(ended, "deal $seed ended with finish order $order")
            if (order.size == 2) doubleUps++
        }
        val elapsed = cpu.elapsed()
        println("SIMULATION deals=$DEALS commands=$commands partnerLeads=$partnerLeads doubleUps=$doubleUps cpu_ms=${elapsed.inWholeMilliseconds}")
        assertTrue(partnerLeads > 0, "no deal exercised 接风")
        assertTrue(doubleUps > 0, "no deal ended as a double-up")
        assertTrue(elapsed.inWholeSeconds < BUDGET_SECONDS, "simulation used ${elapsed.inWholeSeconds}s of thread CPU time, budget ${BUDGET_SECONDS}s")
    }

    private fun check(r: DealReducer, seed: Long, step: Step) {
        val before = step.before
        val after = step.after
        val where = "deal $seed r${after.revision}"
        // Exactly four seats.
        assertEquals(Seat.entries.toSet(), after.hands.keys, where)
        // 108 unique ids conserved.
        val ids = after.allCardIds()
        assertEquals(108, ids.size, where)
        assertEquals(StandardDeck.ids, ids.toSet(), where)
        // Committed revision strictly increases.
        assertTrue(after.revision > before.revision, where)
        // A seat acts only on its own turn, and a seat that has gone out never has the turn.
        when (val c = step.command) {
            is Command.Start -> Unit
            is Command.Play -> assertEquals(before.turn, c.seat, where)
            is Command.Pass -> assertEquals(before.turn, c.seat, where)
        }
        after.turn?.let { t ->
            assertTrue(after.hands.getValue(t).isNotEmpty(), "$where: $t has the turn with no cards")
            // Every other seat is refused.
            for (s in Seat.entries) if (s != t) {
                val result = r.reduce(after, Command.Pass(s))
                if (result !is ReduceResult.Rejected) fail("$where: $s passed out of turn")
            }
        }
        // Finished seats are exactly the seats with no cards.
        assertEquals(Seat.entries.filter { after.hands.getValue(it).isEmpty() }.toSet(), after.finishOrder.toSet(), where)
        assertEquals(after.phase == Phase.ENDED, after.turn == null, where)
    }

    private companion object {
        const val DEALS = 10_000L
        // A hang guard, not a performance gate: a correctness test must not fail because the machine is busy.
        // So the budget is measured in this thread's CPU time, not wall-clock time.
        // The measured CPU time is printed above. Normal runs take about 30-60 s on the JVM.
        const val BUDGET_SECONDS = 600L
    }
}
