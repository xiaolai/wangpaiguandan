package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.StandardDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import cn.wpgd.app.core.ThreadCpuClock

/**
 * 500 seeded all-bot matches from level 2 through passing A under M1. After every accepted command:
 * 108 card ids conserved, levels never decrease, the events alone reproduce the state, and a set of
 * illegal probe commands is refused. Every match must end.
 */
class MatchSimulationTest {
    private class Stats {
        var deals = 0L
        var commands = 0L
        var probes = 0L
        var singles = 0
        var doubles = 0
        var ties = 0
        var resisted = 0
        var splitResisted = 0
        var aFailures = 0
        var allHighReturns = 0
    }

    @Test fun fiveHundredSeededMatchesKeepEveryInvariant() {
        val r = m1Reducer()
        val stats = Stats()
        val cpu = ThreadCpuClock()
        var maxDeals = 0
        for (seed in 1L..MATCHES) {
            val initial = r.newMatch()
            val log = ArrayList<MatchEvent>()
            val final = playMatch(r, seed, maxDeals = MAX_DEALS) { step ->
                check(r, seed, step, stats)
                log += step.events
            }
            assertEquals(MatchPhase.OVER, final.phase, "match $seed")
            assertEquals(final, MatchEvents.replay(initial, log), "match $seed: the log alone rebuilds the final state")
            maxDeals = maxOf(maxDeals, final.dealNumber)
            stats.deals += final.dealNumber
        }
        val elapsed = cpu.elapsed()
        println(
            "MATCH_SIMULATION matches=$MATCHES deals=${stats.deals} maxDealsInAMatch=$maxDeals commands=${stats.commands} probes=${stats.probes} " +
                "single=${stats.singles} double=${stats.doubles} ties=${stats.ties} resisted=${stats.resisted} splitResisted=${stats.splitResisted} " +
                "aFailures=${stats.aFailures} allHighReturns=${stats.allHighReturns} cpu_ms=${elapsed.inWholeMilliseconds}",
        )
        assertTrue(stats.singles > 0 && stats.doubles > 0 && stats.ties > 0 && stats.resisted > 0 && stats.splitResisted > 0 && stats.aFailures > 0, "coverage: single, double, tie, resistance, split resistance, A failure")
        assertTrue(elapsed.inWholeSeconds < BUDGET_SECONDS, "simulation used ${elapsed.inWholeSeconds}s of thread CPU time, budget ${BUDGET_SECONDS}s")
    }

    private fun check(r: MatchReducer, seed: Long, step: MatchStep, stats: Stats) {
        val before = step.before
        val after = step.after
        val where = "match $seed deal ${after.dealNumber} r${after.revision}"
        stats.commands++
        assertEquals(before.revision + 1, after.revision, where)
        assertEquals(after, MatchEvents.replay(before, step.events), "$where: events reproduce the state")
        // 108 unique ids, every step.
        after.deal?.let { d ->
            val ids = d.hands.values.flatten() + d.played
            assertEquals(108, ids.size, where)
            assertEquals(StandardDeck.ids, ids.toSet(), where)
        }
        // Levels only increase under M1 (no failure limit), and stay within 2..A.
        for (team in Team.entries) {
            assertTrue(after.levels.getValue(team) >= before.levels.getValue(team), "$where: $team level went down")
            assertTrue(after.levels.getValue(team) <= Ranks.ACE)
        }
        // Every hand holds 27 cards when play opens.
        if (after.phase == MatchPhase.PLAYING && before.phase == MatchPhase.READY) {
            for (s in Seat.entries) assertEquals(27, after.deal!!.hands.getValue(s).size, "$where: $s at the opening lead")
        }
        for (e in step.events) when (e) {
            is MatchEvent.TributeStarted -> if (e.kind == TributeKind.SINGLE) stats.singles++ else stats.doubles++
            is MatchEvent.TributeRouted -> if (e.transfers.size == 2 && e.transfers.all { it.to == it.from.upper } &&
                TributeRules.power(StandardDeck[e.transfers[0].cardId]!!, after.dealLevel) == TributeRules.power(StandardDeck[e.transfers[1].cardId]!!, after.dealLevel)
            ) stats.ties++
            is MatchEvent.Resisted -> { stats.resisted++; if (e.holders.size == 2) stats.splitResisted++ }
            is MatchEvent.DealSettled -> stats.aFailures += e.result.teams.count { it.failedA }
            is MatchEvent.ReturnChosen -> {
                val hand = before.deal!!.hands.getValue(e.seat).map { StandardDeck[it]!! }
                if (hand.none { TributeRules.withinReturnLimit(it, before.dealLevel, r.settings) }) stats.allHighReturns++
            }
            else -> Unit
        }
        probeIllegal(r, after, where, stats)
    }

    /** Commands that must be refused in [state]; a refusal returns no new state, so nothing can mutate. */
    private fun probeIllegal(r: MatchReducer, state: MatchState, where: String, stats: Stats) {
        val probes = buildList {
            if (state.phase != MatchPhase.DEAL_ENDED) add(MatchCommand.Settle)
            if (state.phase != MatchPhase.READY) add(MatchCommand.OpenPlay)
            if (state.phase != MatchPhase.DEALT) add(MatchCommand.StartTribute)
            if (state.phase != MatchPhase.AWAITING_DEAL) add(MatchCommand.DealNext(SplitMix64(1)))
            add(MatchCommand.InDeal(Command.Start(SplitMix64(1), Seat.SOUTH)))
            add(MatchCommand.AskCardCount(Seat.SOUTH, Seat.EAST))
            val deal = state.deal
            when (state.phase) {
                MatchPhase.PLAYING -> {
                    val turn = deal!!.turn!!
                    for (s in Seat.entries) if (s != turn) add(MatchCommand.InDeal(Command.Pass(s)))
                    // A card the actor does not hold.
                    val foreign = Seat.entries.first { it != turn && deal.hands.getValue(it).isNotEmpty() }
                    add(MatchCommand.InDeal(Command.Play(turn, listOf(deal.hands.getValue(foreign).first()), emptyList())))
                }
                MatchPhase.TRIBUTE -> {
                    val t = state.tribute!!
                    for (g in t.obligation.givers.filter { it !in t.given }) {
                        val legal = r.legalTributeCards(state, g).toSet()
                        deal!!.hands.getValue(g).firstOrNull { it !in legal }?.let { add(MatchCommand.GiveTribute(g, it)) }
                    }
                    for (s in t.obligation.receivers) add(MatchCommand.GiveTribute(s, deal!!.hands.getValue(s).first()))
                    if (!r.mayResist(state)) add(MatchCommand.Resist(t.obligation.givers.first()))
                }
                MatchPhase.RETURN -> {
                    val t = state.tribute!!
                    for (s in t.obligation.receivers.filter { it !in t.returns }) {
                        val legal = r.legalReturnCards(state, s).toSet()
                        deal!!.hands.getValue(s).firstOrNull { it !in legal }?.let { add(MatchCommand.ReturnTribute(s, it)) }
                    }
                    for (g in t.obligation.givers) add(MatchCommand.ReturnTribute(g, deal!!.hands.getValue(g).first()))
                }
                else -> Unit
            }
        }
        for (p in probes) {
            stats.probes++
            if (r.reduce(state, p) !is MatchReduceResult.Rejected) fail("$where: illegal $p accepted in phase ${state.phase}")
        }
    }

    private companion object {
        const val MATCHES = 500L
        const val MAX_DEALS = 600
        // A hang guard, not a performance gate: a correctness test must not fail because the machine is busy.
        // So the budget is measured in this thread's CPU time, not wall-clock time.
        // The measured CPU time is printed above. Normal runs take about 40-110 s on the JVM.
        const val BUDGET_SECONDS = 900L
    }
}
