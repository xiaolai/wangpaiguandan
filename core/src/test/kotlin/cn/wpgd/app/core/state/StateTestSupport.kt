package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.Profiles
import cn.wpgd.app.core.rules.RuleProfile
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.test.fail

internal val p1: RuleProfile get() = Profiles.p1

internal fun p1Reducer() = DealReducer(p1)

internal fun ReduceResult.accepted(): ReduceResult.Accepted =
    this as? ReduceResult.Accepted ?: fail("expected the command to be accepted, got $this")

internal inline fun <reified R : Rejection> ReduceResult.rejectedWith(): R {
    val rejected = assertIs<ReduceResult.Rejected>(this, "expected a rejection")
    return assertIs<R>(rejected.rejection)
}

/** A fresh deal at [level], dealt from [seed], opened by [leader]. */
internal fun DealReducer.started(seed: Long, level: Int = 2, leader: Seat = Seat.SOUTH): ReduceResult.Accepted =
    reduce(newDeal(level), Command.Start(SplitMix64(seed), leader)).accepted()

/**
 * Seeded test bot. Leading, it plays a uniformly chosen generator action. Following, it passes with
 * probability [passChance] without asking the generator (that keeps 10,000 deals cheap), otherwise
 * it plays a uniformly chosen beating action, or passes if there is none.
 */
internal class SeededBot(seed: Long, private val passChance: Double = 0.5) {
    private val random = Random(seed)

    fun choose(reducer: DealReducer, state: DealState): Command {
        val seat = state.turn ?: fail("no seat to act in phase ${state.phase}")
        val leading = state.trick.top == null
        if (!leading && random.nextDouble() < passChance) return Command.Pass(seat)
        val actions = reducer.legalActions(state, seat)
        if (actions.isEmpty()) {
            if (leading) fail("a leader with cards must have a legal action")
            return Command.Pass(seat)
        }
        val a = actions[random.nextInt(actions.size)]
        // No combination type: under P1 every generator action must have exactly one beating reading (D23).
        return Command.Play(seat, a.cards.map { it.id }, a.declaration)
    }
}

/** One step of a played deal: the state before, the command, and the accepted outcome. */
internal class Step(val before: DealState, val command: Command, val after: DealState, val events: List<DealEvent>)

/**
 * Plays one deal with [SeededBot] until it ends, calling [onStep] after every accepted command
 * (including the Start). Fails on any rejection or if the deal does not end within [maxSteps].
 */
internal fun playDeal(
    reducer: DealReducer,
    seed: Long,
    level: Int = 2,
    leader: Seat = Seat.entries[(seed and 3).toInt()],
    maxSteps: Int = MAX_STEPS,
    onStep: (Step) -> Unit = {},
): DealState {
    val initial = reducer.newDeal(level)
    val start = Command.Start(SplitMix64(seed), leader)
    val opened = reducer.reduce(initial, start).accepted()
    onStep(Step(initial, start, opened.state, opened.events))
    val bot = SeededBot(seed xor BOT_SALT)
    var state = opened.state
    var steps = 0
    while (state.phase != Phase.ENDED) {
        if (++steps > maxSteps) fail("deal $seed did not end within $maxSteps commands")
        val command = bot.choose(reducer, state)
        val result = reducer.reduce(state, command)
        val ok = result as? ReduceResult.Accepted ?: fail("deal $seed step $steps: bot command $command rejected: $result")
        onStep(Step(state, command, ok.state, ok.events))
        state = ok.state
    }
    return state
}

internal const val MAX_STEPS = 2_000
private const val BOT_SALT = 0x5DEECE66DL

/** Every card id in the deal: the four hands plus every card played. */
internal fun DealState.allCardIds(): List<String> = hands.values.flatten() + played

/** A mid-deal state built directly (same module), for scenarios that a random deal rarely reaches. */
internal fun playingState(
    hands: Map<Seat, String>,
    turn: Seat,
    finished: List<Seat> = emptyList(),
    level: Int = 2,
    profile: RuleProfile = p1,
): DealState = DealState(
    phase = Phase.PLAYING,
    level = level,
    profileHash = profile.hash,
    hands = Seat.entries.associateWith { s -> hands[s].orEmpty().split(' ').filter { it.isNotBlank() }.toSet() },
    played = emptySet(),
    trick = Trick.EMPTY,
    leader = turn,
    turn = turn,
    finishOrder = finished,
    revision = 1,
)

internal fun play(seat: Seat, ids: String, vararg declaration: cn.wpgd.app.core.rules.WildcardAssignment) =
    Command.Play(seat, ids.split(' ').filter { it.isNotBlank() }, declaration.toList())

internal fun DealReducer.run(state: DealState, vararg commands: Command): ReduceResult.Accepted {
    var result: ReduceResult.Accepted? = null
    var s = state
    for (c in commands) {
        result = reduce(s, c).accepted()
        s = result.state
    }
    return result ?: fail("no commands")
}
