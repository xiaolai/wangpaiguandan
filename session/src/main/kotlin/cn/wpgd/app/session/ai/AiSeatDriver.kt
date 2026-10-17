package cn.wpgd.app.session.ai

import cn.wpgd.app.core.ai.AiAction
import cn.wpgd.app.core.ai.AiPolicies
import cn.wpgd.app.core.ai.AiPolicy
import cn.wpgd.app.core.ai.AiSettings
import cn.wpgd.app.core.ai.CancelSignal
import cn.wpgd.app.core.ai.DecisionLimits
import cn.wpgd.app.core.ai.DecisionOutcome
import cn.wpgd.app.core.ai.DecisionRunner
import cn.wpgd.app.core.ai.DecisionSource
import cn.wpgd.app.core.ai.Fallback
import cn.wpgd.app.core.ai.PolicyId
import cn.wpgd.app.core.ai.PolicyInput
import cn.wpgd.app.core.ai.SeatAiSettings
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.session.host.DeferredSeatPolicy
import cn.wpgd.app.session.host.SeatOptions
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.GiveTributeBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.ResistBody
import cn.wpgd.app.session.wire.ReturnTributeBody
import cn.wpgd.app.session.wire.WireAssignment

/**
 * Runs a decision and reports its outcome once. [INLINE] computes on the calling (host) thread; a
 * platform owner (WI-08) supplies one that computes on a worker and posts [done] back to the host's
 * scheduler thread. The returned handle stops a decision that has not reported yet.
 */
fun interface DecisionExecutor {
    fun execute(work: () -> DecisionOutcome, done: (DecisionOutcome) -> Unit): Cancellable

    companion object {
        val INLINE = DecisionExecutor { work, done ->
            done(work())
            Cancellable { }
        }
    }
}

/**
 * Limits of the AI seats of one room.
 * - [deadlineMs]: scheduler time after which a decision still running is cancelled and the seat
 *   plays the deterministic [Fallback] instead (GDN-061).
 * - [limits]: the deterministic work budget of each decision.
 * - [tieBreakSeed]: the room's tie-break seed. It only breaks exact ties between equally scored
 *   actions; it carries no card information and is never sent to anyone.
 */
data class AiDriverConfig(
    val deadlineMs: Long = 1_000,
    val limits: DecisionLimits = DecisionLimits.DEFAULT,
    val tieBreakSeed: Long = 0,
) {
    init {
        require(deadlineMs > 0) { "deadline must be positive" }
    }
}

/** One AI decision, as recorded for diagnostics (no cards: only who, when and how it was decided). */
data class AiDecisionRecord(
    val seat: Seat,
    val dealNumber: Int,
    val policy: PolicyId,
    val source: DecisionSource,
    val steps: Int,
    /** True when the scheduler deadline, not the policy, ended the decision. */
    val deadline: Boolean,
)

class AiDriverMetrics {
    var decisions = 0L
    var deadlineFallbacks = 0L
    val bySource = HashMap<DecisionSource, Long>()
}

/**
 * The session-side driver of AI seats (WI-07). Give [policy] to `SessionHost.setAi` (or as the
 * recovery substitute); the host calls it inside its (epoch, turn revision, controller) binding
 * (audit A21) and applies the answer only while that binding holds.
 *
 * For each decision the driver builds the core [PolicyInput] from the seat's own observation and
 * legal options (nothing else reaches the policy), picks the seat's [SeatAiSettings] for the deal in
 * progress (a change applies at the next deal boundary, GDN-015), runs [DecisionRunner] under the
 * work budget on the [executor], and arms a deadline on the host's [scheduler]. Whatever happens
 * first wins and is delivered exactly once: the decision, or at the deadline the deterministic
 * [Fallback] (GDN-061). Nothing here touches the network or any inference service (GDN-059).
 */
class AiSeatDriver(
    private val scheduler: Scheduler,
    private val config: AiDriverConfig = AiDriverConfig(),
    private val executor: DecisionExecutor = DecisionExecutor.INLINE,
    initial: SeatAiSettings = SeatAiSettings.uniform(),
    /** Resolves a policy id; tests substitute policies, production uses the core registry. */
    private val policies: (PolicyId) -> AiPolicy = { AiPolicies[it] },
) {
    var settings: SeatAiSettings = initial
        private set

    val metrics = AiDriverMetrics()

    /** Called after every delivered decision, on the host's thread. */
    var onDecision: ((AiDecisionRecord) -> Unit)? = null

    /**
     * Requests new settings for [seat] while the room is at match [currentMatchNumber], deal
     * [currentDealNumber] (`SessionHost.state.matchNumber` and `.dealNumber`). Visible at once through
     * [SeatAiSettings.pending]; applied from the next deal. An unknown policy is refused here.
     */
    fun request(seat: Seat, settings: AiSettings, currentDealNumber: Int, currentMatchNumber: Int) {
        observeDeal(currentMatchNumber)
        this.settings = this.settings.request(seat, settings, currentDealNumber)
    }

    /** The match the deal-indexed settings history belongs to; null before the driver saw any. */
    private var currentMatch: Int? = null

    /**
     * A rematch (GDN-017) reuses this driver but numbers deals from 1 again. When the match number
     * changes, the deal-indexed history of the earlier match is rebased so each seat keeps its latest
     * requested settings from the new match's first deal, instead of applying them by the old deal
     * numbers. The match number, not a falling deal number, says so: the driver may not have seen the
     * later deals of the old match, nor the early deals of the new one.
     */
    private fun observeDeal(matchNumber: Int) {
        val previous = currentMatch
        currentMatch = matchNumber
        if (previous != null && previous != matchNumber) {
            val initial = settings.effective(Seat.entries.first(), 0)
            settings = Seat.entries.fold(SeatAiSettings.uniform(initial)) { acc, seat ->
                val latest = settings.effective(seat, Int.MAX_VALUE)
                if (latest == initial) acc else acc.request(seat, latest, 0)
            }
        }
    }

    /** The seat policy to install in the host. One instance serves every AI seat of the room. */
    val policy: DeferredSeatPolicy = object : DeferredSeatPolicy {
        override fun start(observation: MatchMemberView, options: SeatOptions, deliver: (CommandBody) -> Unit): Cancellable =
            this@AiSeatDriver.start(observation, options, deliver)

        /** Synchronous use (no deadline): the same decision, inline. */
        override fun choose(observation: MatchMemberView, options: SeatOptions): CommandBody {
            val (input, settings) = prepare(observation, options)
            val outcome = run(input, settings, CancelSignal())
            record(input, observation, outcome, deadline = false)
            return body(outcome.action)
        }
    }

    private fun prepare(observation: MatchMemberView, options: SeatOptions): Pair<PolicyInput, AiSettings> {
        val input = PolicyInput.of(observation, options.plays, options.mayPass, options.tributeCards, options.returnCards, options.resistanceOpen)
        check(input.hasChoice) { "the host asked ${observation.seat} to act with nothing to choose" }
        observeDeal(observation.match.matchNumber)
        return input to settings.effective(observation.seat, observation.match.dealNumber)
    }

    private fun run(input: PolicyInput, settings: AiSettings, cancel: CancelSignal): DecisionOutcome {
        val view = input.observation
        // Tie-break seed from the room seed and public facts only (deal, deal revision, phase, seat).
        val seed = config.tieBreakSeed xor (view.match.dealNumber.toLong() shl 48) xor
            ((view.deal?.table?.revision ?: 0L) shl 16) xor (view.match.phase.ordinal.toLong() shl 8) xor view.seat.ordinal.toLong()
        return DecisionRunner.decide(policies(settings.policy), input, SplitMix64(seed), settings.style, config.limits, cancel)
    }

    private fun start(observation: MatchMemberView, options: SeatOptions, deliver: (CommandBody) -> Unit): Cancellable {
        val (input, settings) = prepare(observation, options)
        val cancel = CancelSignal()
        var finished = false
        var deadline: Cancellable? = null
        fun finish(outcome: DecisionOutcome, byDeadline: Boolean) {
            if (finished) return
            finished = true
            deadline?.cancel()
            record(input, observation, outcome, byDeadline)
            deliver(body(outcome.action))
        }
        deadline = scheduler.schedule(config.deadlineMs) {
            if (!finished) {
                cancel.cancel()
                metrics.deadlineFallbacks++
                finish(DecisionOutcome(Fallback.choose(input), DecisionSource.FALLBACK_CANCELLED, 0, policies(settings.policy).id), byDeadline = true)
            }
        }
        val job = executor.execute({ run(input, settings, cancel) }) { finish(it, byDeadline = false) }
        return Cancellable {
            finished = true
            cancel.cancel()
            deadline.cancel()
            job.cancel()
        }
    }

    private fun record(input: PolicyInput, observation: MatchMemberView, outcome: DecisionOutcome, deadline: Boolean) {
        check(input.choices.isLegal(outcome.action)) { "a decision outside the choices reached the driver" }
        metrics.decisions++
        metrics.bySource[outcome.source] = (metrics.bySource[outcome.source] ?: 0) + 1
        onDecision?.invoke(AiDecisionRecord(observation.seat, observation.match.dealNumber, outcome.policy, outcome.source, outcome.steps, deadline))
    }

    private fun body(action: AiAction): CommandBody = when (action) {
        is AiAction.Play -> PlayBody(
            action.action.cards.map { it.id },
            action.action.declaration?.map { WireAssignment(it.rank, it.suit) },
            action.action.combination.type,
        )
        AiAction.Pass -> PassBody
        is AiAction.GiveTribute -> GiveTributeBody(action.cardId)
        is AiAction.ReturnTribute -> ReturnTributeBody(action.cardId)
        AiAction.Resist -> ResistBody
    }
}
