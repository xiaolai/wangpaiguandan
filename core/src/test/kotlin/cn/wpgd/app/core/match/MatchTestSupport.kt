package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.Profiles
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleProfileLoader
import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.DealState
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SeededBot
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.Trick
import java.io.File
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.test.fail

/** M1 is read from the repository's `rules/profiles/`, never from a copy. */
internal fun m1Json(): String {
    val file = File("../rules/profiles/M1-match-v1.json")
    check(file.isFile) { "M1 not found at ${file.absolutePath}" }
    return file.readText()
}

internal fun loadMatch(json: String): RuleProfile = RuleProfileLoader.parse(json, MatchOptions.registry).getOrThrow()

internal object MatchProfiles {
    val m1: RuleProfile by lazy { loadMatch(m1Json()) }

    /** M1 with [options] overridden (raw JSON values keyed by option id). */
    fun with(vararg options: Pair<String, String>): RuleProfile {
        val m1Options = (kotlinx.serialization.json.Json.parseToJsonElement(m1Json()) as kotlinx.serialization.json.JsonObject)
            .getValue("options").let { it as kotlinx.serialization.json.JsonObject }
        val merged = LinkedHashMap<String, String>()
        for ((k, v) in m1Options) merged[k] = v.toString()
        for ((k, v) in options) merged[k] = v
        val body = merged.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        return loadMatch("""{"id":"test-match-variant","version":"1.0.0","displayName":"T","provisional":true,"options":{$body}}""")
    }
}

internal val p1: RuleProfile get() = Profiles.p1

internal fun m1Reducer() = MatchReducer(p1, MatchProfiles.m1)

internal fun reducerWith(vararg options: Pair<String, String>) = MatchReducer(p1, MatchProfiles.with(*options))

internal fun m1Settings(): MatchSettings = MatchSettings.of(MatchProfiles.m1)

internal fun settingsWith(vararg options: Pair<String, String>): MatchSettings = MatchSettings.of(MatchProfiles.with(*options))

internal fun MatchReduceResult.accepted(): MatchReduceResult.Accepted =
    this as? MatchReduceResult.Accepted ?: fail("expected the command to be accepted, got $this")

internal inline fun <reified R : MatchRejection> MatchReduceResult.rejectedWith(): R {
    val rejected = assertIs<MatchReduceResult.Rejected>(this, "expected a rejection, got $this")
    return assertIs<R>(rejected.rejection)
}

internal fun MatchReducer.run(state: MatchState, vararg commands: MatchCommand): MatchReduceResult.Accepted {
    var s = state
    var last: MatchReduceResult.Accepted? = null
    for (c in commands) {
        last = reduce(s, c).accepted()
        s = last.state
    }
    return last ?: fail("no commands")
}

/** Seat order in tests: 上游 first. */
internal val S = Seat.SOUTH
internal val E = Seat.EAST
internal val N = Seat.NORTH
internal val W = Seat.WEST

/**
 * Hands for a scenario: each seat gets the listed ids, then the rest of the deck is dealt in deck
 * order to fill every hand to 27. All 108 cards are always placed.
 */
internal fun handsWith(fixed: Map<Seat, List<String>>): Map<Seat, Set<String>> {
    val used = fixed.values.flatten()
    check(used.size == used.toSet().size) { "a card is listed twice: $used" }
    check(used.all { it in StandardDeck.ids }) { "unknown id in $used" }
    val rest = StandardDeck.cards.map { it.id }.filter { it !in used }.toMutableList()
    return Seat.entries.associateWith { seat ->
        val hand = fixed[seat].orEmpty().toMutableSet()
        check(hand.size <= 27)
        while (hand.size < 27) hand += rest.removeAt(0)
        hand.toSet()
    }
}

/**
 * A match at deal 2 in phase DEALT: the previous deal ended with [previousOrder]; the new deal is
 * dealt with [hands] at [level] (the winning team's level; the other team stays at 2). Built
 * directly (same module) so a scenario gets exact hands.
 */
internal fun matchAtTribute(
    reducer: MatchReducer,
    previousOrder: List<Seat>,
    hands: Map<Seat, Set<String>>,
    level: Int = 2,
): MatchState {
    val previous = Settlement.settle(1, 2, previousOrder, Team.entries.associateWith { 2 }, Team.entries.associateWith { 0 }, reducer.settings)
    val levels = mapOf(previous.winningTeam to level, previous.winningTeam.other to 2)
    val dealLevel = level
    val fresh = reducer.newMatch()
    check(hands.values.sumOf { it.size } == 108 && hands.values.flatten().toSet() == StandardDeck.ids)
    val deal = DealState(
        phase = Phase.DEALT,
        level = dealLevel,
        profileHash = fresh.baseProfileHash,
        hands = hands,
        played = emptySet(),
        trick = Trick.EMPTY,
        leader = null,
        turn = null,
        finishOrder = emptyList(),
        revision = 1,
    )
    return fresh.copy(
        phase = MatchPhase.DEALT,
        levels = levels,
        dealNumber = 2,
        dealLevel = dealLevel,
        deal = deal,
        previous = previous,
        revision = 5,
    )
}

/**
 * Seeded match bot. It uses only the reducer's public helpers: it resists whenever it may, gives
 * and returns a uniformly chosen legal card, and plays with the WI-03 [SeededBot].
 */
internal class MatchBot(seed: Long) {
    private val random = Random(seed)
    private val dealBot = SeededBot(seed xor 0x2545F4914F6CDD1DL)

    fun choose(reducer: MatchReducer, state: MatchState): MatchCommand = when (state.phase) {
        MatchPhase.AWAITING_DEAL -> MatchCommand.DealNext(SplitMix64(random.nextLong()))
        MatchPhase.DEALT -> MatchCommand.StartTribute
        MatchPhase.TRIBUTE -> {
            val t = checkNotNull(state.tribute)
            if (reducer.mayResist(state)) MatchCommand.Resist(t.obligation.givers.first())
            else {
                val giver = t.obligation.givers.first { it !in t.given }
                val options = reducer.legalTributeCards(state, giver)
                MatchCommand.GiveTribute(giver, options[random.nextInt(options.size)])
            }
        }
        MatchPhase.RETURN -> {
            val t = checkNotNull(state.tribute)
            val returner = t.obligation.receivers.first { it !in t.returns }
            val options = reducer.legalReturnCards(state, returner)
            MatchCommand.ReturnTribute(returner, options[random.nextInt(options.size)])
        }
        MatchPhase.READY -> MatchCommand.OpenPlay
        MatchPhase.PLAYING -> MatchCommand.InDeal(dealBot.choose(reducer.dealReducer, checkNotNull(state.deal)))
        MatchPhase.DEAL_ENDED -> MatchCommand.Settle
        MatchPhase.OVER -> fail("no command after the match is over")
    }
}

internal class MatchStep(val before: MatchState, val command: MatchCommand, val after: MatchState, val events: List<MatchEvent>)

/** Plays a whole match with [MatchBot]; fails on any rejection or if it runs past [maxDeals]. */
internal fun playMatch(
    reducer: MatchReducer,
    seed: Long,
    maxDeals: Int = 1_000,
    stopWhen: (MatchState) -> Boolean = { false },
    from: MatchState = reducer.newMatch(),
    onStep: (MatchStep) -> Unit = {},
): MatchState {
    val bot = MatchBot(seed)
    var state = from
    while (state.phase != MatchPhase.OVER && !stopWhen(state)) {
        if (state.dealNumber > maxDeals) fail("match $seed did not end within $maxDeals deals")
        val command = bot.choose(reducer, state)
        val ok = reducer.reduce(state, command) as? MatchReduceResult.Accepted
            ?: fail("match $seed deal ${state.dealNumber}: bot command $command rejected: ${reducer.reduce(state, command)}")
        onStep(MatchStep(state, command, ok.state, ok.events))
        state = ok.state
    }
    return state
}

/** Drives [state] with the bot until [phase] is reached (in the current deal). */
internal fun MatchReducer.advanceTo(state: MatchState, phase: MatchPhase, seed: Long = 1): MatchState {
    val bot = MatchBot(seed)
    var s = state
    var guard = 0
    while (s.phase != phase) {
        check(++guard < 5_000) { "never reached $phase" }
        s = reduce(s, bot.choose(this, s)).accepted().state
    }
    return s
}

/** Every card id of the current deal: all hands plus every card played. */
internal fun MatchState.allCardIds(): List<String> = checkNotNull(deal).let { d -> d.hands.values.flatten() + d.played }

internal fun play(seat: Seat, vararg ids: String) = MatchCommand.InDeal(Command.Play(seat, ids.toList(), emptyList()))
