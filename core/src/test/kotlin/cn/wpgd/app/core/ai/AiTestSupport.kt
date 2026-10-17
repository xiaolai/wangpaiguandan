package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.CountReport
import cn.wpgd.app.core.match.MatchCommand
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProfiles
import cn.wpgd.app.core.match.MatchProjector
import cn.wpgd.app.core.match.MatchReduceResult
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.rules.Classifier
import cn.wpgd.app.core.rules.Profiles
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleSettings
import cn.wpgd.app.core.rules.WildcardAssignment
import cn.wpgd.app.core.state.DealState
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.RandomSource
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.Trick
import cn.wpgd.app.core.state.TrickPlay
import kotlin.test.assertTrue
import kotlin.test.fail

/** P1 (base) and M1 (match), read from the repository's `rules/profiles/`. */
internal object AiProfiles {
    val base: RuleProfile get() = Profiles.p1
    val match: RuleProfile get() = MatchProfiles.m1
    val reducer: MatchReducer by lazy { MatchReducer(base, match) }
    val projector: MatchProjector by lazy { MatchProjector(base, match) }
    val memberInputs: MemberSeatInputs by lazy { MemberSeatInputs(base, match) }
}

internal val S = Seat.SOUTH
internal val E = Seat.EAST
internal val N = Seat.NORTH
internal val W = Seat.WEST

/** One play already on the table: [seat] put down [ids] declaring [declaration] for its wildcards. */
internal class TablePlay(val seat: Seat, val ids: List<String>, val declaration: List<WildcardAssignment> = emptyList())

internal fun tp(seat: Seat, vararg ids: String, declaration: List<WildcardAssignment> = emptyList()) = TablePlay(seat, ids.toList(), declaration)

/**
 * A match in PLAYING at [level] with exactly these hands (seats not listed hold nothing and must be
 * in [finishOrder] or be given cards), this trick, [turn] to act, and these public count reports.
 * Built directly (same module) so a scenario gets exact cards.
 */
internal fun playing(
    level: Int,
    hands: Map<Seat, List<String>>,
    turn: Seat,
    trick: List<TablePlay> = emptyList(),
    passed: List<Seat> = emptyList(),
    finishOrder: List<Seat> = emptyList(),
    reports: List<CountReport> = emptyList(),
): MatchState {
    val all = hands.values.flatten() + trick.flatMap { it.ids }
    check(all.size == all.toSet().size) { "a card is listed twice" }
    check(all.all { it in StandardDeck.ids }) { "unknown card id in $all" }
    val classifier = Classifier(RuleSettings.of(AiProfiles.base), level)
    val plays = trick.map { p ->
        val reading = classifier.readings(p.ids.map { StandardDeck[it]!! }).firstOrNull { it.declaration == p.declaration.sorted() }
            ?: fail("no reading of ${p.ids} with ${p.declaration}")
        TrickPlay(p.seat, p.ids.sorted(), p.declaration.sorted(), reading.combination)
    }
    val fresh = AiProfiles.reducer.newMatch()
    val deal = DealState(
        phase = Phase.PLAYING,
        level = level,
        profileHash = fresh.baseProfileHash,
        hands = Seat.entries.associateWith { hands[it].orEmpty().toSet() },
        played = trick.flatMap { it.ids }.toSet(),
        trick = Trick(plays, passed),
        leader = trick.firstOrNull()?.seat ?: turn,
        turn = turn,
        finishOrder = finishOrder,
        revision = 10,
    )
    return fresh.copy(
        phase = MatchPhase.PLAYING,
        levels = Team.entries.associateWith { level },
        dealNumber = 1,
        dealLevel = level,
        deal = deal,
        reports = reports,
        revision = 20,
    )
}

internal fun hostInput(state: MatchState, seat: Seat): PolicyInput =
    HostSeatInputs.of(AiProfiles.reducer, AiProfiles.projector, state, seat)

internal fun decide(
    state: MatchState,
    seat: Seat,
    policy: AiPolicy = BaselineHeuristicPolicy,
    style: AiStyle? = null,
    seed: Long = 1,
    limits: DecisionLimits = DecisionLimits.DEFAULT,
): DecisionOutcome = DecisionRunner.decide(policy, hostInput(state, seat), SplitMix64(seed), style, limits)

internal fun AiAction.playedIds(): Set<String> = (this as? AiAction.Play)?.action?.cards?.map { it.id }?.toSet() ?: emptySet()

/** Mixes public facts into one tie-break seed. */
internal fun seedOf(vararg parts: Long): Long = parts.fold(0x9E3779B97F4A7C15uL.toLong()) { acc, p -> SplitMix64(acc xor p).nextLong() }

/** Chooses for one seat from its own [PolicyInput] (an AI, or a scripted human working from its own view). */
internal fun interface SeatChooser {
    fun choose(state: MatchState, seat: Seat, input: PolicyInput): AiAction
}

/** An AI seat: [policy] through the [DecisionRunner], seeded from public facts. */
internal fun aiChooser(seed: Long, policy: (MatchState, Seat) -> AiPolicy = { _, _ -> BaselineHeuristicPolicy }, onOutcome: (DecisionOutcome) -> Unit = {}) =
    SeatChooser { state, seat, input ->
        DecisionRunner.decide(policy(state, seat), input, SplitMix64(seedOf(seed, state.revision, seat.ordinal.toLong())))
            .also(onOutcome).action
    }

/**
 * A scripted human: works from its own view only ([MemberSeatInputs], what a client UI shows) and
 * takes the deterministic simplest legal action ([Fallback]).
 */
internal val scriptedHuman = SeatChooser { _, _, input -> Fallback.choose(input) }

/** Statistics of one driven match or deal. */
internal class RunStats {
    var decisions = 0L
    var illegal = 0L
}

/** The seats that must act now, in a fixed order. */
internal fun actingSeats(state: MatchState): List<Seat> = when (state.phase) {
    MatchPhase.PLAYING -> listOfNotNull(state.deal?.turn)
    MatchPhase.TRIBUTE -> state.tribute!!.let { t -> t.obligation.givers.filter { it !in t.given } }
    MatchPhase.RETURN -> state.tribute!!.let { t -> t.routes.values.filter { it !in t.returns } }
    else -> emptyList()
}

/**
 * Applies one step: the host's own step (deal, tribute start, open play, settle) or the first acting
 * seat's choice. Every seat choice must be legal and accepted; an illegal one is counted and fails.
 */
internal fun step(
    reducer: MatchReducer,
    state: MatchState,
    dealRandom: (Int) -> RandomSource,
    choosers: Map<Seat, SeatChooser>,
    stats: RunStats,
    humanInputsFromView: Set<Seat> = emptySet(),
): MatchState {
    val command: MatchCommand = when (state.phase) {
        MatchPhase.AWAITING_DEAL -> MatchCommand.DealNext(dealRandom(state.dealNumber + 1))
        MatchPhase.DEALT -> MatchCommand.StartTribute
        MatchPhase.READY -> MatchCommand.OpenPlay
        MatchPhase.DEAL_ENDED -> MatchCommand.Settle
        MatchPhase.OVER -> fail("match is over")
        else -> {
            val seat = actingSeats(state).first()
            val input = if (seat in humanInputsFromView) {
                AiProfiles.memberInputs.of(AiProfiles.projector.memberView(state, seat)) ?: fail("a human at $seat has nothing to choose in ${state.phase}")
            } else hostInput(state, seat)
            val action = choosers.getValue(seat).choose(state, seat, input)
            stats.decisions++
            if (!input.choices.isLegal(action)) {
                stats.illegal++
                fail("illegal action ${action.canonical()} at $seat in ${state.phase}")
            }
            action.toCommand(seat)
        }
    }
    val result = reducer.reduce(state, command)
    if (result !is MatchReduceResult.Accepted) {
        stats.illegal++
        fail("command $command refused: $result")
    }
    return result.state
}

/** Plays one deal from [state] (AWAITING_DEAL) until it ends (DEAL_ENDED). */
internal fun playDeal(reducer: MatchReducer, state: MatchState, seed: Long, choosers: Map<Seat, SeatChooser>, stats: RunStats): MatchState {
    var s = state
    var guard = 0
    do {
        s = step(reducer, s, { SplitMix64(seed) }, choosers, stats)
        assertTrue(++guard < 5_000, "deal $seed did not end")
    } while (s.phase != MatchPhase.DEAL_ENDED)
    return s
}
