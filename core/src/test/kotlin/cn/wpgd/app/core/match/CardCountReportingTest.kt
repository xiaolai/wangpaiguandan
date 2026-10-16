package cn.wpgd.app.core.match

import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.Visibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** match-v1 section 8 (GDN-042): one public report per seat per deal; no other count leaves the host. */
class CardCountReportingTest {
    private val r = m1Reducer()
    private val projector = MatchProjector(p1, MatchProfiles.m1)

    /** Per deal: every report event with the step that emitted it. */
    private fun reportsOf(reducer: MatchReducer, seeds: LongRange, check: (MatchStep, MatchEvent.CardCountReported) -> Unit = { _, _ -> }): Map<Pair<Long, Int>, List<MatchEvent.CardCountReported>> {
        val out = HashMap<Pair<Long, Int>, MutableList<MatchEvent.CardCountReported>>()
        for (seed in seeds) playMatch(reducer, seed, maxDeals = 6, stopWhen = { it.dealNumber >= 6 && it.phase == MatchPhase.AWAITING_DEAL }) { step ->
            for (e in step.events.filterIsInstance<MatchEvent.CardCountReported>()) {
                out.getOrPut(seed to step.before.dealNumber) { ArrayList() } += e
                check(step, e)
            }
        }
        return out
    }

    // match-v1 M8.1, M8.2, M8.6: one exact report per seat, none for the seat that plays out.
    @Test fun thresholdReportIsEmittedExactlyOncePerSeatAndCarriesTheExactCount() {
        var dealsChecked = 0
        for (seed in 1L..8L) {
            val perDeal = HashMap<Int, MutableList<MatchEvent.CardCountReported>>()
            var finishedSeats = HashMap<Int, Set<Seat>>()
            playMatch(r, seed, stopWhen = { it.dealNumber >= 5 && it.phase == MatchPhase.AWAITING_DEAL }) { step ->
                val reports = step.events.filterIsInstance<MatchEvent.CardCountReported>()
                for (e in reports) {
                    // Emitted by the seat's own play, in the same batch, with the count that play left.
                    val play = (step.command as MatchCommand.InDeal).command as Command.Play
                    assertEquals(play.seat, e.seat)
                    assertEquals(step.after.deal!!.hands.getValue(e.seat).size, e.count)
                    assertTrue(e.count in 1..10)
                    assertEquals(Visibility.Public, e.visibility)
                    perDeal.getOrPut(step.before.dealNumber) { ArrayList() } += e
                }
                // No report while 11 or more cards remain (or for a pass).
                if (reports.isEmpty() && step.command is MatchCommand.InDeal) {
                    val c = step.command.command
                    if (c is Command.Play) {
                        val left = step.after.deal!!.hands.getValue(c.seat).size
                        assertTrue(left > 10 || left == 0 || step.before.reports.any { it.seat == c.seat }, "seed $seed: ${c.seat} left with $left did not report")
                    }
                }
                if (step.after.phase == MatchPhase.DEAL_ENDED && step.before.phase == MatchPhase.PLAYING) {
                    finishedSeats[step.after.dealNumber] = step.after.deal!!.finishOrder.toSet()
                    dealsChecked++
                }
            }
            for ((deal, reports) in perDeal) {
                assertEquals(reports.map { it.seat }.toSet().size, reports.size, "seed $seed deal $deal: a seat reported twice")
                // Max play is 10 cards, so no seat goes from 11+ to 0: every seat that played out reported first.
                assertTrue(reports.map { it.seat }.containsAll(finishedSeats.getValue(deal)), "seed $seed deal $deal")
            }
        }
        assertTrue(dealsChecked >= 40)
    }

    /** Re-deals the cards of every seat in [hidden] among those seats at random sizes. */
    private fun scramble(state: MatchState, hidden: Set<Seat>, random: Random): MatchState {
        val deal = state.deal ?: return state
        val holders = hidden.filter { deal.hands.getValue(it).isNotEmpty() }
        if (holders.size < 2) return state
        val pool = holders.flatMap { deal.hands.getValue(it) }.shuffled(random)
        val cuts = (1 until pool.size).shuffled(random).take(holders.size - 1).sorted() + pool.size
        var from = 0
        val hands = deal.hands.toMutableMap()
        holders.forEachIndexed { i, s -> hands[s] = pool.subList(from, cuts[i]).toSet(); from = cuts[i] }
        return state.copy(deal = deal.copy(hands = hands))
    }

    private val samples: List<MatchState> by lazy {
        buildList {
            for (seed in listOf(4L, 17L)) playMatch(r, seed, stopWhen = { it.dealNumber >= 4 && it.phase == MatchPhase.AWAITING_DEAL }) {
                if (it.after.revision % 7 == 0L || it.after.phase != MatchPhase.PLAYING) add(it.after)
            }
        }
    }

    // match-v1 M8.5 (D39): no hand size is public apart from reports and answers.
    @Test fun hiddenCountsAreAbsentFromProjectionBytes() {
        val random = Random(42)
        var sizesChanged = 0
        assertTrue(samples.any { it.reports.isNotEmpty() }, "samples include states after a report")
        for (state in samples) {
            for (viewer in Seat.entries) {
                val reference = MatchProjectionCodec.encode(projector.memberView(state, viewer))
                repeat(3) {
                    val other = scramble(state, Seat.entries.toSet() - viewer, random)
                    if (other.deal?.hands?.any { (s, h) -> h.size != state.deal!!.hands.getValue(s).size } == true) sizesChanged++
                    assertContentEquals(reference, MatchProjectionCodec.encode(projector.memberView(other, viewer)), "$viewer at r${state.revision}")
                }
            }
            val spectator = MatchProjectionCodec.encode(projector.spectatorView(state))
            assertContentEquals(spectator, MatchProjectionCodec.encode(projector.spectatorView(scramble(state, Seat.entries.toSet(), random))))
        }
        assertTrue(sizesChanged > 50, "scrambles changed hand sizes only $sizesChanged times")
    }

    // match-v1 M8.3, M8.5: reports and answers are the only counts a projection holds.
    @Test fun reportsAndAnswersAreTheOnlyCountsInAProjection() {
        for (state in samples) for (viewer in Seat.entries) {
            val json = Json.parseToJsonElement(MatchProjectionCodec.encode(projector.memberView(state, viewer)).decodeToString())
            val keys = keyPaths(json, "")
            val countKeys = keys.filter { it.substringAfterLast('.').let { k -> k.contains("count", ignoreCase = true) || k.contains("size", ignoreCase = true) } }
            assertTrue(countKeys.all { it.startsWith(".match.reports") || it.startsWith(".match.answers") }, "$countKeys")
        }
    }

    private fun keyPaths(e: JsonElement, prefix: String): List<String> = when (e) {
        is JsonObject -> e.flatMap { (k, v) -> listOf("$prefix.$k") + keyPaths(v, "$prefix.$k") }
        is JsonArray -> e.flatMap { keyPaths(it, prefix) }
        else -> emptyList()
    }

    // match-v1 M8.1 (D39): the threshold is profile data; null turns reporting off.
    @Test fun thresholdComesFromTheProfile() {
        val seu = reducerWith("reporting.threshold" to "6")
        val reports = reportsOf(seu, 1L..4L).values.flatten()
        assertTrue(reports.isNotEmpty() && reports.all { it.count in 1..6 })
        assertTrue(reportsOf(reducerWith("reporting.threshold" to "null"), 1L..3L).isEmpty(), "null turns reporting off")
    }

    // match-v1 M8.4 (D40): nobody may ask by default.
    @Test fun askingIsRefusedByDefault() {
        val playing = playMatch(r, 3, stopWhen = { it.phase == MatchPhase.PLAYING })
        r.reduce(playing, MatchCommand.AskCardCount(S, E)).rejectedWith<MatchRejection.AskingNotAllowed>()
    }

    // match-v1 M8.4 (D40): the CUP answer-when-asked variant.
    @Test fun cupVariantAnswersAnOpponentWithTheExactCount() {
        val cup = reducerWith("reporting.answerWhenAsked" to "true")
        var answered = 0
        var refusedAbove = 0
        for (seed in 1L..6L) playMatch(cup, seed, stopWhen = { it.dealNumber >= 3 }) { step ->
            val s = step.after
            if (s.phase != MatchPhase.PLAYING) return@playMatch
            val deal = s.deal!!
            for (asker in Seat.entries) for (target in Seat.entries) {
                val result = cup.reduce(s, MatchCommand.AskCardCount(asker, target))
                when {
                    asker.team == target.team -> result.rejectedWith<MatchRejection.AskOwnTeam>()
                    deal.isFinished(target) -> result.rejectedWith<MatchRejection.AskTargetFinished>()
                    deal.hands.getValue(target).size > 10 -> { result.rejectedWith<MatchRejection.AskAboveThreshold>(); refusedAbove++ }
                    else -> {
                        val e = result.accepted().events.single() as MatchEvent.CardCountAnswered
                        assertEquals(MatchEvent.CardCountAnswered(s.revision + 1, asker, target, deal.hands.getValue(target).size), e)
                        assertEquals(Visibility.Public, e.visibility)
                        answered++
                    }
                }
            }
        }
        assertTrue(answered > 0 && refusedAbove > 0)
    }
}
