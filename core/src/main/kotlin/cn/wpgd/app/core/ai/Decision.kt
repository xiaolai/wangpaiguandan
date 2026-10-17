package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.TributeRules
import cn.wpgd.app.core.state.RandomSource
import cn.wpgd.app.core.state.StandardDeck
import kotlin.concurrent.Volatile

/**
 * A cancellation flag another thread (or a deadline timer) may set while a decision runs. A policy
 * sees it at its next [DecisionContext.spend]. Once set it stays set.
 */
class CancelSignal {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}

/**
 * The deterministic work budget of one decision: at most [maxSteps] units of policy work (one unit
 * is about one candidate evaluated). A step count, never a clock, so the same input gives the same
 * answer on every device and every run (GDN-061).
 */
data class DecisionLimits(val maxSteps: Int) {
    init {
        require(maxSteps >= 0) { "negative budget" }
    }

    companion object {
        /**
         * About 3.5 times the largest generator output measured (17,334 plays: the 27-card,
         * two-wildcard hand of `GeneratorBudgetTest`), so the baseline scores every candidate of any
         * hand seen so far; about 1 µs per step on a desktop JVM.
         */
        val DEFAULT = DecisionLimits(maxSteps = 60_000)
    }
}

/** Why a decision stopped early. */
enum class StopReason { BUDGET, CANCELLED }

/** Thrown inside a policy by [DecisionContext.spend]; caught only by [DecisionRunner]. */
class DecisionStopped internal constructor(val reason: StopReason) : RuntimeException(reason.name)

/**
 * What a running policy may use besides its [PolicyInput]: the work budget, the cancellation flag,
 * a seeded tie-break source, the seat's style, and a place to leave its best answer so far.
 */
class DecisionContext internal constructor(
    private val limits: DecisionLimits,
    private val cancel: CancelSignal,
    /** The only randomness a policy may use. Seeded by the caller, so decisions replay exactly. */
    val tieBreak: RandomSource,
    val style: AiStyle,
) {
    var steps: Int = 0
        private set

    internal var stopped: StopReason? = null
        private set

    internal var best: AiAction? = null
        private set

    /** Spends [units] of budget. Throws [DecisionStopped] when the budget is gone or the decision was cancelled. */
    fun spend(units: Int = 1) {
        require(units >= 0) { "negative units" }
        if (cancel.cancelled) stop(StopReason.CANCELLED)
        // Long arithmetic: an Int sum could wrap negative and slip under the budget.
        val next = steps.toLong() + units
        steps = minOf(next, Int.MAX_VALUE.toLong()).toInt()
        if (next > limits.maxSteps) stop(StopReason.BUDGET)
    }

    /** Records [action] as the best answer so far; it is used if the decision stops early. */
    fun offer(action: AiAction) {
        best = action
    }

    private fun stop(reason: StopReason): Nothing {
        stopped = reason
        throw DecisionStopped(reason)
    }
}

/** A seat's decision procedure. Pure: its result depends only on its arguments. */
interface AiPolicy {
    val id: PolicyId

    /**
     * Chooses one action from [input]'s choices. May call [DecisionContext.spend] (and must, to stay
     * cancellable) and [DecisionContext.offer]. The runner rejects any action outside the choices.
     */
    fun decide(input: PolicyInput, context: DecisionContext): AiAction
}

/** Where the action of a [DecisionOutcome] came from. */
enum class DecisionSource {
    /** The policy finished within its budget and returned a legal action. */
    POLICY,

    /** The budget ran out; the policy's best answer so far was used. */
    BEST_SO_FAR_BUDGET,

    /** The decision was cancelled; the policy's best answer so far was used. */
    BEST_SO_FAR_CANCELLED,

    /** The budget ran out before any answer: [Fallback]. */
    FALLBACK_BUDGET,

    /** Cancelled before any answer, or the caller's deadline passed: [Fallback]. */
    FALLBACK_CANCELLED,

    /** The policy threw: [Fallback]. */
    FALLBACK_FAILED,

    /** The policy returned an action outside the legal choices: [Fallback]. */
    FALLBACK_ILLEGAL,
}

/** One decision: exactly one legal [action], where it came from, and the work it took. */
data class DecisionOutcome(val action: AiAction, val source: DecisionSource, val steps: Int, val policy: PolicyId)

/**
 * The one legal action used when a policy cannot answer (GDN-061): deterministic, and always in the
 * choices. Resist if offered (never worse than giving tribute); otherwise the lowest-id tribute card;
 * otherwise the return card of lowest R1.5 power (then id); otherwise pass when passing is allowed;
 * otherwise the play with the fewest cards, then the lowest comparison key, then the lowest card ids.
 */
object Fallback {
    fun choose(input: PolicyInput): AiAction {
        val c = input.choices
        require(c.all.isNotEmpty()) { "the seat has nothing to choose" }
        return when {
            c.mayResist -> AiAction.Resist
            c.tributeCards.isNotEmpty() -> AiAction.GiveTribute(c.tributeCards.min())
            c.returnCards.isNotEmpty() -> AiAction.ReturnTribute(
                c.returnCards.minWith(compareBy({ TributeRules.power(checkNotNull(StandardDeck[it]), input.level) }, { it })),
            )
            c.mayPass -> AiAction.Pass
            else -> AiAction.Play(
                c.plays.minWith(
                    compareBy({ it.cards.size }, { it.combination.key }, { a -> a.cards.map { it.id }.sorted().joinToString(",") }),
                ),
            )
        }
    }
}

/**
 * Runs a policy under a work budget and a cancellation flag and always returns exactly one legal
 * action: the policy's answer, its best answer so far when stopped early, or [Fallback] when it
 * threw, broke the rules, or had nothing to offer.
 */
object DecisionRunner {
    fun decide(
        policy: AiPolicy,
        input: PolicyInput,
        tieBreak: RandomSource,
        style: AiStyle? = null,
        limits: DecisionLimits = DecisionLimits.DEFAULT,
        cancel: CancelSignal = CancelSignal(),
    ): DecisionOutcome {
        require(input.hasChoice) { "the seat has nothing to choose" }
        val context = DecisionContext(limits, cancel, tieBreak, style ?: AiStyle.BALANCED)
        fun fallback(source: DecisionSource) = DecisionOutcome(Fallback.choose(input), source, context.steps, policy.id)
        fun early(reason: StopReason): DecisionOutcome {
            val best = context.best?.takeIf { input.choices.isLegal(it) }
            return when {
                best == null && reason == StopReason.BUDGET -> fallback(DecisionSource.FALLBACK_BUDGET)
                best == null -> fallback(DecisionSource.FALLBACK_CANCELLED)
                reason == StopReason.BUDGET -> DecisionOutcome(best, DecisionSource.BEST_SO_FAR_BUDGET, context.steps, policy.id)
                else -> DecisionOutcome(best, DecisionSource.BEST_SO_FAR_CANCELLED, context.steps, policy.id)
            }
        }
        if (cancel.cancelled) return fallback(DecisionSource.FALLBACK_CANCELLED)
        val chosen = try {
            policy.decide(input, context)
        } catch (e: DecisionStopped) {
            return early(e.reason)
        } catch (e: Exception) {
            // A policy that swallowed the stop signal and then failed still stopped for that reason.
            return context.stopped?.let(::early) ?: fallback(DecisionSource.FALLBACK_FAILED)
        }
        // A policy that caught DecisionStopped itself and returned anyway is treated as stopped.
        context.stopped?.let { return early(it) }
        if (!input.choices.isLegal(chosen)) return fallback(DecisionSource.FALLBACK_ILLEGAL)
        return DecisionOutcome(chosen, DecisionSource.POLICY, context.steps, policy.id)
    }
}
