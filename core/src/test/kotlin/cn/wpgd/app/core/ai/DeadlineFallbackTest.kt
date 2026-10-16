package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.rules.LegalAction
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.StandardDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * GDN-061: a decision is bounded by a step budget (not a clock) and cancellable; when the policy
 * runs out of budget, is cancelled, throws or answers outside the choices, exactly one legal
 * fallback action is returned, chosen deterministically.
 */
class DeadlineFallbackTest {
    private val follow = playing(
        2, mapOf(S to listOf("0SJ", "0HQ", "0CK", "0D3", "1D4"), E to listOf("0C3"), N to listOf("0C4"), W to listOf("0C5")),
        turn = S, trick = listOf(tp(E, "0S10")),
    )
    private val lead = playing(2, mapOf(S to listOf("0SJ", "0HQ", "0CK", "0D3", "1D4"), E to listOf("0C3"), N to listOf("0C4"), W to listOf("0C5")), turn = S)

    /**
     * Spends until the runner stops it. If the budget or cancellation were not enforced this would
     * never return; the guard turns that hang into a loud failure (the outcome is then FALLBACK_FAILED).
     */
    private companion object {
        const val SPIN_GUARD = 5_000_000

        fun spinUntilStopped(context: DecisionContext): Nothing {
            repeat(SPIN_GUARD) { context.spend() }
            error("the runner never stopped this decision: budget or cancellation ignored")
        }
    }

    /** Spends forever and never offers an answer: a runaway search. */
    private object Runaway : AiPolicy {
        override val id = PolicyId("test-runaway", "1")
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
            spinUntilStopped(context)
        }
    }

    private object Throws : AiPolicy {
        override val id = PolicyId("test-throws", "1")
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction = error("policy bug")
    }

    /** Offers its first legal action, then keeps searching forever. */
    private object OffersThenRuns : AiPolicy {
        override val id = PolicyId("test-offers", "1")
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
            context.spend()
            context.offer(input.choices.all.last())
            spinUntilStopped(context)
        }
    }

    /** Swallows the stop signal and returns something that is not a choice. */
    private object Swallows : AiPolicy {
        override val id = PolicyId("test-swallows", "1")
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
            try {
                spinUntilStopped(context)
            } catch (_: DecisionStopped) {
                return AiAction.GiveTribute("0BJ")
            }
        }
    }

    /** Answers with a card it does not hold (what dropping the legality filter would produce). */
    private object Illegal : AiPolicy {
        override val id = PolicyId("test-illegal", "1")
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
            val foreign = StandardDeck.cards.first { c -> input.hand.none { it.id == c.id } && c.rank == 14 }
            val template = input.choices.plays.first()
            return AiAction.Play(LegalAction(listOf(foreign), emptyList(), template.combination))
        }
    }

    /** Cancels its own decision mid-way (stands for a deadline timer firing on another thread). */
    private class CancelsMidway(private val signal: CancelSignal, private val offerFirst: Boolean) : AiPolicy {
        override val id = PolicyId("test-cancelled", "1")
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
            context.spend()
            if (offerFirst) context.offer(input.choices.all.first())
            signal.cancel()
            spinUntilStopped(context)
        }
    }

    private fun assertOneLegalFallback(state: MatchState, out: DecisionOutcome, source: DecisionSource) {
        val input = hostInput(state, S)
        assertEquals(source, out.source)
        assertTrue(input.choices.isLegal(out.action), "fallback must be legal: ${out.action.canonical()}")
        assertEquals(Fallback.choose(input), out.action, "the fallback is the documented deterministic action")
    }

    @Test fun timeoutGivesTheLegalFallback() {
        for (state in listOf(follow, lead)) {
            val out = DecisionRunner.decide(Runaway, hostInput(state, S), SplitMix64(1), limits = DecisionLimits(500))
            assertOneLegalFallback(state, out, DecisionSource.FALLBACK_BUDGET)
            assertEquals(501, out.steps, "stopped on the first step past the budget")
        }
        // A zero budget stops the baseline before it can offer anything.
        val zero = DecisionRunner.decide(BaselineHeuristicPolicy, hostInput(lead, S), SplitMix64(1), limits = DecisionLimits(0))
        assertOneLegalFallback(lead, zero, DecisionSource.FALLBACK_BUDGET)
    }

    @Test fun exceptionGivesTheLegalFallback() {
        for (state in listOf(follow, lead)) assertOneLegalFallback(state, DecisionRunner.decide(Throws, hostInput(state, S), SplitMix64(1)), DecisionSource.FALLBACK_FAILED)
    }

    @Test fun cancellationGivesTheLegalFallback() {
        for (state in listOf(follow, lead)) {
            val before = CancelSignal().also { it.cancel() }
            assertOneLegalFallback(state, DecisionRunner.decide(BaselineHeuristicPolicy, hostInput(state, S), SplitMix64(1), cancel = before), DecisionSource.FALLBACK_CANCELLED)
            val during = CancelSignal()
            assertOneLegalFallback(state, DecisionRunner.decide(CancelsMidway(during, offerFirst = false), hostInput(state, S), SplitMix64(1), cancel = during), DecisionSource.FALLBACK_CANCELLED)
        }
    }

    @Test fun illegalAnswerGivesTheLegalFallback() {
        for (state in listOf(follow, lead)) {
            assertOneLegalFallback(state, DecisionRunner.decide(Illegal, hostInput(state, S), SplitMix64(1)), DecisionSource.FALLBACK_ILLEGAL)
        }
    }

    @Test fun stoppedEarlyUsesTheBestAnswerSoFar() {
        val input = hostInput(lead, S)
        val budget = DecisionRunner.decide(OffersThenRuns, input, SplitMix64(1), limits = DecisionLimits(50))
        assertEquals(DecisionSource.BEST_SO_FAR_BUDGET, budget.source)
        assertEquals(input.choices.all.last(), budget.action)
        val signal = CancelSignal()
        val cancelled = DecisionRunner.decide(CancelsMidway(signal, offerFirst = true), input, SplitMix64(1), cancel = signal)
        assertEquals(DecisionSource.BEST_SO_FAR_CANCELLED, cancelled.source)
        assertEquals(input.choices.all.first(), cancelled.action)
        // Following, the baseline offers pass before any search, so a tiny budget still answers sensibly.
        val tiny = DecisionRunner.decide(BaselineHeuristicPolicy, hostInput(follow, S), SplitMix64(1), limits = DecisionLimits(1))
        assertEquals(DecisionSource.BEST_SO_FAR_BUDGET, tiny.source)
        assertEquals(AiAction.Pass, tiny.action)
    }

    /** Spends 1, then Int.MAX_VALUE: the sum must not wrap negative and slip under the budget. */
    private object HugeSpend : AiPolicy {
        override val id = PolicyId("test-huge-spend", "1")
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
            context.spend(1)
            context.spend(Int.MAX_VALUE)
            return input.choices.all.first()
        }
    }

    @Test fun aHugeSpendCannotOverflowPastTheBudget() {
        val out = DecisionRunner.decide(HugeSpend, hostInput(lead, S), SplitMix64(1), limits = DecisionLimits(Int.MAX_VALUE))
        assertOneLegalFallback(lead, out, DecisionSource.FALLBACK_BUDGET)
        assertEquals(Int.MAX_VALUE, out.steps, "the step count saturates instead of wrapping")
    }

    @Test fun aPolicyThatSwallowsTheStopIsStillStopped() {
        val out = DecisionRunner.decide(Swallows, hostInput(lead, S), SplitMix64(1), limits = DecisionLimits(10))
        assertOneLegalFallback(lead, out, DecisionSource.FALLBACK_BUDGET)
    }

    /** The fallback is legal in every phase a seat acts in: lead, follow, tribute, return. */
    @Test fun fallbackIsLegalInEveryPhase() {
        val reducer = AiProfiles.reducer
        val phases = HashSet<MatchPhase>()
        for (seed in 1L..40L) {
            var state = reducer.newMatch()
            val stats = RunStats()
            val choosers = cn.wpgd.app.core.state.Seat.entries.associateWith { aiChooser(seed) }
            var guard = 0
            while (state.phase != MatchPhase.OVER && state.dealNumber <= 3 && guard++ < 3_000) {
                for (seat in actingSeats(state)) {
                    val input = hostInput(state, seat)
                    assertTrue(input.hasChoice, "acting seat $seat has a choice in ${state.phase}")
                    val fb = Fallback.choose(input)
                    assertTrue(input.choices.isLegal(fb), "fallback ${fb.canonical()} illegal in ${state.phase}")
                    assertNotNull(reducer.reduce(state, fb.toCommand(seat)) as? cn.wpgd.app.core.match.MatchReduceResult.Accepted, "reducer refused the fallback in ${state.phase}")
                    phases += state.phase
                }
                state = step(reducer, state, { SplitMix64(seed * 31 + it) }, choosers, stats)
            }
            assertEquals(0, stats.illegal)
        }
        assertTrue(phases.containsAll(listOf(MatchPhase.PLAYING, MatchPhase.TRIBUTE, MatchPhase.RETURN)), "covered $phases")
    }

    /**
     * The default budget is enough for the baseline on the largest generator output known: the
     * second hand of `GeneratorBudgetTest` (all four suits of 3–8, a 9, both wildcards; 17,334 leads).
     */
    @Test fun defaultBudgetCoversTheLargestKnownHand() {
        val hand = (3..8).flatMap { r -> listOf("S", "H", "C", "D").map { "0$it$r" } } + listOf("0S9", "0H2", "1H2")
        val state = playing(2, mapOf(S to hand, E to listOf("1SA"), N to listOf("1HA"), W to listOf("1CA")), turn = S)
        val input = hostInput(state, S)
        val mark = TimeSource.Monotonic.markNow()
        val out = DecisionRunner.decide(BaselineHeuristicPolicy, input, SplitMix64(1))
        println("BASELINE_FULL_HAND plays=${input.choices.plays.size} steps=${out.steps} elapsed_ms=${mark.elapsedNow().inWholeMilliseconds}")
        assertEquals(17_334, input.choices.plays.size, "the GeneratorBudgetTest hand")
        assertEquals(DecisionSource.POLICY, out.source, "steps ${out.steps} for ${input.choices.plays.size} plays")
        assertTrue(out.steps <= DecisionLimits.DEFAULT.maxSteps / 3, "budget margin below 3x: ${out.steps} steps")
    }
}
