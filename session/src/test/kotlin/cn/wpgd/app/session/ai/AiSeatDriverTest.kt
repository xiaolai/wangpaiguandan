package cn.wpgd.app.session.ai

import cn.wpgd.app.core.ai.AiPolicies
import cn.wpgd.app.core.ai.AiPolicy
import cn.wpgd.app.core.ai.AiSettings
import cn.wpgd.app.core.ai.DecisionSource
import cn.wpgd.app.core.ai.Fallback
import cn.wpgd.app.core.ai.HostSeatInputs
import cn.wpgd.app.core.ai.PolicyId
import cn.wpgd.app.core.ai.RandomLegalPolicy
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.SeatPolicy
import cn.wpgd.app.session.lobby.ready
import cn.wpgd.app.session.wire.PassBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The WI-07 session driver: AI seats decide inside the host's (epoch, turn revision, controller)
 * binding (audit A21), under the scheduler deadline with the deterministic fallback (GDN-061), with
 * per-seat settings applied at deal boundaries (GDN-015).
 */
class AiSeatDriverTest {
    private val reducer = MatchReducer(TestProfiles.base, TestProfiles.match)

    /** A table with one scripted human in SOUTH and the driver in the other three seats. */
    private fun table(seed: Long, driver: (Table) -> AiSeatDriver): Triple<Table, AiSeatDriver, ScriptedHuman> {
        val t = Table(seed = seed)
        val d = driver(t)
        val south = ScriptedHuman(t, t.player(Seat.SOUTH))
        for (s in listOf(Seat.EAST, Seat.NORTH, Seat.WEST)) assertTrue(t.host.setAi(s, d.policy))
        return Triple(t, d, south)
    }

    @Test fun oneHumanAndThreeAiSeatsCompleteAMatch() {
        val (t, d, south) = table(301) { AiSeatDriver(it.scheduler, AiDriverConfig(tieBreakSeed = 301)) }
        t.driveUntil { it.state.phase == MatchPhase.OVER }
        assertEquals(0L, t.host.metrics.aiRejected, "an AI command was refused")
        assertTrue(south.submitted > 0 && south.rejected == 0, "human ${south.submitted} submitted, ${south.rejected} rejected")
        assertEquals(0L, d.metrics.deadlineFallbacks)
        assertEquals(setOf(DecisionSource.POLICY), d.metrics.bySource.keys, "${d.metrics.bySource}")
        println("SESSION_AI_MATCH deals=${t.host.state.dealNumber} ai_decisions=${d.metrics.decisions} winner=${t.host.state.winner}")
    }

    /** A decision still running past the deadline is cancelled and the seat plays the fallback, exactly once. */
    @Test fun deadlineAppliesTheFallbackOnce() {
        var executor: DelayedExecutor? = null
        val (t, d, _) = table(302) { AiSeatDriver(it.scheduler, AiDriverConfig(deadlineMs = 1_000), DelayedExecutor(it.scheduler, 5_000).also { e -> executor = e }) }
        val records = ArrayList<AiDecisionRecord>()
        d.onDecision = { records += it }
        val ex = checkNotNull(executor)
        t.driveUntil { it.state.phase == MatchPhase.PLAYING && it.state.deal!!.turn != Seat.SOUTH && ex.started > 0 }
        val seat = t.host.state.deal!!.turn!!
        val before = t.host.state
        val expected = Fallback.choose(HostSeatInputs.of(reducer, t.projector, before, seat))
        val commits = t.host.metrics.commits
        t.scheduler.runFor(ex.lastStartedAt + 999 - t.scheduler.now)
        assertEquals(before.revision, t.host.revision, "nothing applied before the deadline")
        t.scheduler.runFor(2)
        assertEquals(commits + 1, t.host.metrics.commits, "exactly one command at the deadline")
        val record = records.single()
        assertTrue(record.deadline && record.seat == seat && record.source == DecisionSource.FALLBACK_CANCELLED, "$record")
        assertEquals(expected.toBody(), lastAction(before, t.host.state, seat), "the deterministic fallback was played")
        // The slow decision completes later; it is not delivered a second time and nothing is refused.
        t.scheduler.runFor(4_100)
        assertTrue(ex.completed >= 1, "the slow decision did complete")
        assertEquals(0L, t.host.metrics.aiDuplicateDeliveries)
        assertEquals(0L, t.host.metrics.aiRejected)
        assertTrue(d.metrics.deadlineFallbacks >= 1)
    }

    /**
     * The host's own guard (A21), independent of the driver: a deferred policy that answers late
     * (after a restart) or twice is ignored, so a broken executor cannot act out of turn.
     */
    @Test fun hostIgnoresLateAndDuplicateDeliveries() {
        val held = ArrayList<Pair<(cn.wpgd.app.session.wire.CommandBody) -> Unit, cn.wpgd.app.session.wire.CommandBody>>()
        val d = AiSeatDriver(cn.wpgd.app.session.transport.VirtualScheduler())
        val holding = object : cn.wpgd.app.session.host.DeferredSeatPolicy {
            override fun start(observation: cn.wpgd.app.core.match.MatchMemberView, options: cn.wpgd.app.session.host.SeatOptions, deliver: (cn.wpgd.app.session.wire.CommandBody) -> Unit): cn.wpgd.app.session.transport.Cancellable {
                held += deliver to d.policy.choose(observation, options)
                return cn.wpgd.app.session.transport.Cancellable { } // ignores cancellation on purpose
            }
            override fun choose(observation: cn.wpgd.app.core.match.MatchMemberView, options: cn.wpgd.app.session.host.SeatOptions) = d.policy.choose(observation, options)
        }
        val t = Table(seed = 308)
        ScriptedHuman(t, t.player(Seat.SOUTH))
        for (s in listOf(Seat.EAST, Seat.NORTH, Seat.WEST)) t.host.setAi(s, holding)
        t.driveUntil { held.isNotEmpty() }
        val (deliver, body) = held.removeAt(0)
        val rev = t.host.revision
        deliver(body)
        assertEquals(rev + 1, t.host.revision, "the first delivery applies")
        deliver(body)
        assertEquals(1L, t.host.metrics.aiDuplicateDeliveries, "a second delivery for one task is ignored")
        t.driveUntil { held.isNotEmpty() }
        val (late, lateBody) = held.removeAt(0)
        t.host.restartRoom()
        val stale = t.host.metrics.staleAiTasks
        late(lateBody)
        assertEquals(0L, t.host.revision, "a delivery from the old epoch does not act in the new one")
        assertEquals(stale + 1, t.host.metrics.staleAiTasks)
    }

    /** A cancelled decision that answers late must not make the host forget the seat's newer decision. */
    @Test fun aLateAnswerOfACancelledDecisionKeepsTheNewerOneCancellable() {
        val held = ArrayList<Pair<(cn.wpgd.app.session.wire.CommandBody) -> Unit, cn.wpgd.app.session.wire.CommandBody>>()
        val cancelled = HashSet<Int>()
        val d = AiSeatDriver(cn.wpgd.app.session.transport.VirtualScheduler())
        val holding = object : cn.wpgd.app.session.host.DeferredSeatPolicy {
            override fun start(observation: cn.wpgd.app.core.match.MatchMemberView, options: cn.wpgd.app.session.host.SeatOptions, deliver: (cn.wpgd.app.session.wire.CommandBody) -> Unit): cn.wpgd.app.session.transport.Cancellable {
                val index = held.size
                held += deliver to d.policy.choose(observation, options)
                return cn.wpgd.app.session.transport.Cancellable { cancelled += index }
            }
            override fun choose(observation: cn.wpgd.app.core.match.MatchMemberView, options: cn.wpgd.app.session.host.SeatOptions) = d.policy.choose(observation, options)
        }
        val t = Table(seed = 309)
        ScriptedHuman(t, t.player(Seat.SOUTH))
        for (s in listOf(Seat.EAST, Seat.NORTH, Seat.WEST)) t.host.setAi(s, holding)
        t.driveUntil { held.isNotEmpty() }
        val seat = checkNotNull(t.host.state.deal?.turn)
        assertTrue(t.host.setAi(seat, holding), "a new controller for the seat")
        assertTrue(0 in cancelled, "the first decision is cancelled with its controller")
        t.run(1_000)
        assertEquals(2, held.size, "the new controller started its own decision")
        val (late, lateBody) = held[0]
        late(lateBody) // the cancelled decision answers anyway: stale, ignored
        assertTrue(t.host.setAi(seat, holding))
        assertTrue(1 in cancelled, "the newer decision was forgotten and could not be cancelled")
    }

    /** A policy that throws: the seat plays the fallback and the table goes on (no stall). */
    @Test fun aThrowingPolicyFallsBackAndTheDealGoesOn() {
        val broken = object : AiPolicy {
            override val id = PolicyId("broken", "1")
            override fun decide(input: cn.wpgd.app.core.ai.PolicyInput, context: cn.wpgd.app.core.ai.DecisionContext) = error("bug")
        }
        val (t, d, _) = table(303) { AiSeatDriver(it.scheduler, policies = { broken }) }
        t.driveUntil { it.state.phase == MatchPhase.DEAL_ENDED || it.state.dealNumber >= 2 }
        assertEquals(0L, t.host.metrics.aiRejected)
        assertTrue((d.metrics.bySource[DecisionSource.FALLBACK_FAILED] ?: 0) > 10, "${d.metrics.bySource}")
        assertEquals(setOf(DecisionSource.FALLBACK_FAILED), d.metrics.bySource.keys)
    }

    /** A21: a decision in flight when the room restarts is cancelled, and its late result never acts in the new epoch. */
    @Test fun inFlightDecisionDoesNotActAfterRestart() {
        val (t, d, _) = table(304) { AiSeatDriver(it.scheduler, AiDriverConfig(deadlineMs = 3_000), DelayedExecutor(it.scheduler, 500)) }
        t.driveUntil { it.state.phase == MatchPhase.PLAYING && it.state.deal!!.turn != Seat.SOUTH }
        t.run(300) // the AI seat's decision is running now
        val decisions = d.metrics.decisions
        t.host.restartRoom()
        val cancelled = t.host.metrics.aiCancelled
        t.run(5_000)
        assertTrue(cancelled >= 1, "restart cancels the running decision")
        assertEquals(0L, t.host.revision, "nothing of the old epoch acted in the new one")
        assertEquals(decisions, d.metrics.decisions, "the cancelled decision delivered nothing")
    }

    /** A21: a running decision whose turn was taken by another command (a newer revision) is dropped, not applied. */
    @Test fun lateResultForAnOldRevisionIsDropped() {
        // Two AI givers in a double tribute decide at the same revision; the first commit makes the other stale.
        val t = Table(seed = 305)
        val d = AiSeatDriver(t.scheduler, AiDriverConfig(deadlineMs = 3_000), DelayedExecutor(t.scheduler, 100))
        for (s in Seat.entries) assertTrue(t.host.setAi(s, d.policy))
        t.driveUntil { it.metrics.aiCancelled > 0 || it.state.phase == MatchPhase.OVER }
        assertTrue(t.host.metrics.aiCancelled > 0, "superseded decisions were cancelled (needs a double tribute)")
        t.run(1_000)
        assertEquals(0L, t.host.metrics.aiRejected, "no stale answer was applied")
    }

    /** Pausing while an AI task waits must not strand the seat: after resume the AI acts. */
    @Test fun aiActsAfterPauseAndResume() {
        for (deferred in listOf(false, true)) {
            val t = Table(seed = 306)
            val d = AiSeatDriver(t.scheduler)
            val sync = SeatPolicy { o, opts -> d.policy.choose(o, opts) }
            ScriptedHuman(t, t.player(Seat.SOUTH))
            for (s in listOf(Seat.EAST, Seat.NORTH, Seat.WEST)) t.host.setAi(s, if (deferred) d.policy else sync)
            t.driveUntil { it.state.phase == MatchPhase.PLAYING && it.state.deal!!.turn != Seat.SOUTH }
            t.host.pause()
            val rev = t.host.revision
            t.run(1_000) // the AI task fires while paused and does nothing
            assertEquals(rev, t.host.revision)
            assertTrue(t.host.resume())
            t.run(1_000)
            assertTrue(t.host.revision > rev, "deferred=$deferred: the AI seat never acted after resume")
        }
    }

    /** GDN-015 through the session: a change requested in the middle of deal 1 applies from deal 2. */
    @Test fun settingsChangeMidDealAppliesAtTheNextDeal() {
        val (t, d, _) = table(307) { AiSeatDriver(it.scheduler) }
        val records = ArrayList<AiDecisionRecord>()
        d.onDecision = { records += it }
        val random = AiSettings(RandomLegalPolicy.id)
        t.driveUntil { records.count { it.seat == Seat.EAST && it.dealNumber == 1 } >= 3 }
        d.request(Seat.EAST, random, t.host.state.dealNumber)
        assertEquals(random, d.settings.pending(Seat.EAST, 1), "the change is visible while it waits")
        t.driveUntil { it.state.dealNumber >= 2 && it.state.phase == MatchPhase.DEAL_ENDED || it.state.phase == MatchPhase.OVER }
        val east = records.filter { it.seat == Seat.EAST }
        assertTrue(east.filter { it.dealNumber == 1 }.all { it.policy == AiPolicies.BASELINE }, "deal 1 kept the old policy")
        assertTrue(east.filter { it.dealNumber >= 2 }.let { it.isNotEmpty() && it.all { r -> r.policy == RandomLegalPolicy.id } }, "deal 2 uses the new policy")
        assertTrue(records.filter { it.seat != Seat.EAST }.all { it.policy == AiPolicies.BASELINE }, "other seats unchanged")
    }

    /** A rematch restarts deal numbering: a change made in the last match stays in force, it does not revert and reapply. */
    @Test fun settingsChangesSurviveARematch() {
        val t = Table(seed = 310)
        val d = AiSeatDriver(t.scheduler, AiDriverConfig(tieBreakSeed = 310))
        val south = t.player(Seat.SOUTH)
        for (s in Seat.entries.drop(1)) assertTrue(t.host.setAi(s, d.policy))
        val records = ArrayList<AiDecisionRecord>()
        d.onDecision = { records += it }
        val random = AiSettings(RandomLegalPolicy.id)
        kotlin.test.assertIs<cn.wpgd.app.session.wire.Receipt>(t.ready(south))
        kotlin.test.assertIs<cn.wpgd.app.session.host.SessionHost.StartResult.Started>(t.host.start())
        ScriptedHuman(t, south)
        t.driveUntil { it.state.dealNumber >= 1 && records.any { r -> r.seat == Seat.EAST } }
        d.request(Seat.EAST, random, t.host.state.dealNumber)
        t.driveUntil { it.state.phase == MatchPhase.OVER }
        val lastDeal = t.host.state.dealNumber
        assertTrue(lastDeal >= 2, "the match needs a second deal to apply the change")
        assertTrue(t.host.rematch())
        t.run(500)
        records.clear()
        kotlin.test.assertIs<cn.wpgd.app.session.wire.Receipt>(t.ready(south))
        kotlin.test.assertIs<cn.wpgd.app.session.host.SessionHost.StartResult.Started>(t.host.start())
        t.driveUntil { it.state.dealNumber >= 2 || it.state.phase == MatchPhase.OVER }
        val east = records.filter { it.seat == Seat.EAST && it.dealNumber == 1 }
        assertTrue(east.isNotEmpty() && east.all { it.policy == RandomLegalPolicy.id }, "deal 1 of the rematch reverted to ${east.map { it.policy }.distinct()}")
        assertEquals(random, d.settings.effective(Seat.EAST, lastDeal + 1), "the change stays in force for the whole new match")
    }

    /** The play or pass [seat] made between two host states, as a wire body (from the deal's public trick). */
    private fun lastAction(before: cn.wpgd.app.core.match.MatchState, after: cn.wpgd.app.core.match.MatchState, seat: Seat): cn.wpgd.app.session.wire.CommandBody {
        val b = before.deal!!
        val a = after.deal!!
        val played = b.hands.getValue(seat) - a.hands.getValue(seat)
        if (played.isEmpty()) return PassBody
        val top = a.trick.plays.lastOrNull { it.seat == seat } ?: error("no play by $seat")
        return cn.wpgd.app.session.wire.PlayBody(top.cardIds, top.declaration?.map { cn.wpgd.app.session.wire.WireAssignment(it.rank, it.suit) }, top.combination.type)
            .also { check(top.cardIds.toSet() == played) }
    }
}
