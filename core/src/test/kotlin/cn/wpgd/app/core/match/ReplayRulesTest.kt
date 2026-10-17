package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.DealEvent
import cn.wpgd.app.core.state.DealEvents
import cn.wpgd.app.core.state.StandardDeck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Round 3 #37, #38, #42, #43: replaying a logged batch through [MatchReducer.replay] applies the same
 * rules as command handling. Each batch must be exactly what the reducer emits for the command it
 * records, so a forged tribute card, obligation, reading or skipped turn is refused.
 */
class ReplayRulesTest {
    private val r = m1Reducer()

    @Test fun aTributeCardTheCommandRefusesDoesNotReplay() {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        val tribute = r.reduce(start, MatchCommand.StartTribute).accepted().state
        val lowCard = low(1).single()
        r.reduce(tribute, MatchCommand.GiveTribute(W, lowCard)).rejectedWith<MatchRejection.NotHighestCard>()
        val rev = tribute.revision + 1
        val forged = listOf(
            MatchEvent.TributeGiven(rev, W, lowCard),
            MatchEvent.TributeRouted(rev, listOf(CardTransfer(W, S, lowCard))),
            MatchEvent.InDeal(rev, DealEvent.CardMoved(rev, W, S, lowCard)),
        )
        MatchEvents.replay(tribute, forged) // the profile-free checks alone pass it
        assertFailsWith<IllegalArgumentException> { r.replay(tribute, forged) }
        val genuine = r.reduce(tribute, MatchCommand.GiveTribute(W, "0SA")).accepted()
        assertEquals(genuine.state, r.replay(tribute, genuine.events))
    }

    @Test fun aTributeObligationOtherThanThePreviousResultsIsRefused() {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(emptyMap()))
        val genuine = r.reduce(start, MatchCommand.StartTribute).accepted()
        val started = genuine.events.filterIsInstance<MatchEvent.TributeStarted>().single()
        assertEquals(listOf(W), started.givers)
        val forged = started.copy(givers = listOf(N), receivers = listOf(E))
        assertFailsWith<IllegalArgumentException> { MatchEvents.apply(start, forged) }
        assertFailsWith<IllegalArgumentException> { r.replay(start, listOf(forged)) }
        assertEquals(genuine.state, r.replay(start, genuine.events))
    }

    private fun playing(): MatchState {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        val given = r.run(start, MatchCommand.StartTribute, MatchCommand.GiveTribute(W, "0SA")).state
        val ready = r.run(given, MatchCommand.ReturnTribute(S, r.legalReturnCards(given, S).first())).state
        return r.reduce(ready, MatchCommand.OpenPlay).accepted().state
    }

    @Test fun aReadingTheCardsDoNotFormDoesNotReplay() {
        val playing = playing()
        assertEquals(W, playing.deal!!.turn)
        val lead = r.legalActions(playing, W).first { it.combination.type == CombinationType.PAIR && it.declaration.isNullOrEmpty() }
        val genuine = r.reduce(playing, MatchCommand.InDeal(Command.Play(W, lead.cards.map { it.id }, null))).accepted()
        // The same pair reading stated for two West cards of different ranks.
        val first = low(26).first { it in playing.deal.hands.getValue(W) }
        val other = low(26).first { it in playing.deal.hands.getValue(W) && StandardDeck[it]!!.rank != StandardDeck[first]!!.rank }
        val mismatched = listOf(first, other).sorted()
        val forged = genuine.events.map { e ->
            if (e is MatchEvent.InDeal && e.event is DealEvent.Played) e.copy(event = (e.event as DealEvent.Played).copy(cardIds = mismatched)) else e
        }
        assertNotEquals(genuine.events, forged)
        MatchEvents.replay(playing, forged) // the profile-free checks alone pass it
        assertFailsWith<IllegalArgumentException> { r.replay(playing, forged) }
        assertEquals(genuine.state, r.replay(playing, genuine.events))
    }

    @Test fun aTurnMoveWithNoPlayOrPassDoesNotReplay() {
        val playing = playing()
        val rev = playing.revision + 1
        val skip = listOf(MatchEvent.InDeal(rev, DealEvent.TurnMoved(rev, DealEvents.nextActive(playing.deal!!, W))))
        MatchEvents.replay(playing, skip) // the per-event checks alone pass it
        assertFailsWith<IllegalArgumentException> { r.replay(playing, skip) }
    }

    /** Genuine logs replay under each lead and resistance mode, batch by batch and whole, also after the codec. */
    @Test fun genuineMatchesReplayThroughTheReducer() {
        val reducers = listOf(
            m1Reducer(),
            reducerWith("lead.firstDeal" to "\"east\"", "resistance.mode" to "\"automatic\"", "reporting.answerWhenAsked" to "true"),
        )
        for (reducer in reducers) for (seed in 1L..4L) {
            val log = ArrayList<MatchEvent>()
            val final = playMatch(reducer, seed) { step ->
                val text = Json.encodeToString(JsonElement.serializer(), MatchEventCodec.encode(step.events))
                val decoded = MatchEventCodec.decode(Json.parseToJsonElement(text))
                assertEquals(step.after, reducer.replay(step.before, decoded), "seed $seed revision ${step.after.revision}")
                log += decoded
            }
            assertEquals(final, reducer.replay(reducer.newMatch(), log), "seed $seed: the whole log")
        }
    }

    @Test fun anAnsweredCardCountReplaysOnlyWhereTheProfileAllowsIt() {
        val asking = reducerWith("reporting.answerWhenAsked" to "true", "reporting.askThreshold" to "26")
        // A seat holds 27 cards until it plays: the target plays once, then an opponent asks.
        val opened = asking.advanceTo(asking.newMatch(), MatchPhase.PLAYING)
        val target = opened.deal!!.turn!!
        val lead = asking.legalActions(opened, target).first()
        val play = Command.Play(target, lead.cards.map { it.id }, lead.declaration, lead.combination.type)
        val playing = asking.reduce(opened, MatchCommand.InDeal(play)).accepted().state
        val genuine = asking.reduce(playing, MatchCommand.AskCardCount(target.next, target)).accepted()
        assertEquals(genuine.state, asking.replay(playing, genuine.events))
        val m1 = m1Reducer()
        val m1Playing = m1.advanceTo(m1.newMatch(), MatchPhase.PLAYING)
        val rev = m1Playing.revision + 1
        val t = m1Playing.deal!!.turn!!
        val answer = MatchEvent.CardCountAnswered(rev, t, t.next, m1Playing.deal.hands.getValue(t.next).size)
        MatchEvents.apply(m1Playing, answer) // profile-free checks pass it
        assertFailsWith<IllegalArgumentException> { m1.replay(m1Playing, listOf(answer)) }
    }
}
