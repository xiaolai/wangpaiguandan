package cn.wpgd.app.core.ai

import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.WildcardAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Audit A06: the AI finds the wildcard play that the old rank-based bot missed. */
class WildcardPairTest {
    /** Level 2, hand heart-2 (wildcard) plus a seven, facing a pair of sixes: play the pair of sevens. */
    @Test fun playsThePairOfSevens() {
        val state = playing(2, mapOf(S to listOf("0H2", "0S7"), E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S5")), turn = S, trick = listOf(tp(E, "0S6", "0C6")))
        val input = hostInput(state, S)
        assertTrue(input.choices.plays.isNotEmpty(), "the generator offers the wildcard pair (WI-02 A06 case)")
        val out = DecisionRunner.decide(BaselineHeuristicPolicy, input, cn.wpgd.app.core.state.SplitMix64(7))
        val play = assertIs<AiAction.Play>(out.action)
        assertEquals(CombinationType.PAIR, play.action.combination.type)
        assertEquals(setOf("0H2", "0S7"), out.action.playedIds())
        assertEquals(listOf(WildcardAssignment(7)), play.action.declaration)
        assertEquals(DecisionSource.POLICY, out.source)
    }

    /** The same holds for every style and with the other heart-2 copy. */
    @Test fun eitherWildcardCopyAndEveryStyle() {
        for (wild in listOf("0H2", "1H2")) for (style in AiStyle.entries) {
            val state = playing(2, mapOf(S to listOf(wild, "1D7"), E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S5")), turn = S, trick = listOf(tp(W, "0S6", "0C6")))
            assertEquals(setOf(wild, "1D7"), decide(state, S, style = style).action.playedIds(), "$wild $style")
        }
    }

    /** Leading with the same two cards: the pair ends the hand, so it is preferred to a single. */
    @Test fun leadsThePairWhenItEndsTheHand() {
        val state = playing(2, mapOf(S to listOf("0H2", "0S7"), E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S5")), turn = S)
        val play = assertIs<AiAction.Play>(decide(state, S).action)
        assertEquals(2, play.action.cards.size)
    }
}
