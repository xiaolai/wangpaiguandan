package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.CountReport
import cn.wpgd.app.core.rules.CombinationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GDN-056, audit A07: the AI considers bombs when responding and plays one when it wins. */
class BombDecisionTest {
    private val fourSevens = listOf("0S7", "1S7", "0C7", "0D7")

    /** A07: four sevens alone, facing a single ace: the bomb ends the hand, so it is played. */
    @Test fun playsTheHandEndingBomb() {
        val state = playing(2, mapOf(S to fourSevens, E to listOf("0S3", "0S4"), N to listOf("0S5"), W to listOf("0S6")), turn = S, trick = listOf(tp(E, "0SA")))
        for (style in listOf(null) + AiStyle.entries) {
            val out = decide(state, S, style = style)
            assertEquals(DecisionSource.POLICY, out.source)
            val play = assertIs<AiAction.Play>(out.action, "style $style")
            assertEquals(CombinationType.BOMB, play.action.combination.type)
            assertEquals(fourSevens.toSet(), out.action.playedIds())
        }
    }

    /** A hand-ending bomb beats a non-bomb opponent play even when the bomb is the smallest the hand holds. */
    @Test fun playsTheHandEndingBombAgainstAPair() {
        val state = playing(5, mapOf(S to fourSevens, E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S6")), turn = S, trick = listOf(tp(W, "0SK", "1SK")))
        assertEquals(fourSevens.toSet(), decide(state, S).action.playedIds())
    }

    /** Policy rule 6: leading, a bomb is kept unless it ends the hand (four sevens and four eights lead a non-bomb). */
    @Test fun leadingKeepsBombsThatDoNotEndTheHand() {
        val hand = fourSevens + listOf("0S8", "1S8", "0C8", "0D8")
        val state = playing(2, mapOf(S to hand, E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S6")), turn = S)
        for (style in listOf(null) + AiStyle.entries) {
            val play = assertIs<AiAction.Play>(decide(state, S, style = style).action, "style $style")
            assertTrue(!play.action.combination.type.isBombClass, "style $style led ${play.action.combination}")
        }
    }

    /**
     * Rule 6 under any work budget: a lead stopped early never answers with a bomb that keeps cards
     * back. Bombs are not scored or offered as the best answer so far, so they cost no budget either.
     */
    @Test fun leadingStoppedEarlyNeverLeadsAKeptBomb() {
        val hand = fourSevens + listOf("0S8", "1S8", "0C8", "0D8")
        val state = playing(2, mapOf(S to hand, E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S6")), turn = S)
        val full = decide(state, S)
        assertEquals(DecisionSource.POLICY, full.source)
        for (steps in 0..full.steps) {
            val play = assertIs<AiAction.Play>(decide(state, S, limits = DecisionLimits(steps)).action, "budget $steps")
            assertTrue(!play.action.combination.type.isBombClass, "budget $steps led ${play.action.combination}")
        }
    }

    /** With a long hand and no public threat, a bomb is not spent on an opponent's single ace. */
    @Test fun keepsTheBombWhenItDoesNotWin() {
        val hand = fourSevens + listOf("0S3", "0H4", "0C5", "0D8", "0S9", "0HJ", "0CQ", "1D3", "1H9", "1CJ", "0D10", "1S5")
        val state = playing(2, mapOf(S to hand, E to listOf("0S6"), N to listOf("1S6"), W to listOf("0C6")), turn = S, trick = listOf(tp(E, "0SA")))
        assertEquals(AiAction.Pass, decide(state, S).action)
    }

    /** The same hand bombs when that opponent has publicly reported a low count (match-v1 M8.1). */
    @Test fun bombsWhenTheOpponentHasReportedALowCount() {
        val hand = fourSevens + listOf("0S3", "0H4", "0C5", "0D8", "0S9", "0HJ", "0CQ", "1D3", "1H9", "1CJ", "0D10", "1S5")
        val state = playing(
            2, mapOf(S to hand, E to listOf("0S6"), N to listOf("1S6"), W to listOf("0C6")), turn = S,
            trick = listOf(tp(E, "0SA")), reports = listOf(CountReport(E, 2)),
        )
        val out = decide(state, S)
        assertEquals(CombinationType.BOMB, assertIs<AiAction.Play>(out.action).action.combination.type)
        assertEquals(fourSevens.toSet(), out.action.playedIds())
    }

    /** A bomb does not overtake the partner, even against a public threat, unless it ends the hand. */
    @Test fun doesNotBombThePartner() {
        val hand = fourSevens + listOf("0S3", "0H4", "0C5", "0D8")
        val state = playing(
            2, mapOf(S to hand, E to listOf("0S6"), N to listOf("1S6"), W to listOf("0C6")), turn = S,
            trick = listOf(tp(N, "0SA")), passed = listOf(E), reports = listOf(CountReport(E, 1)),
        )
        assertEquals(AiAction.Pass, decide(state, S).action)
    }

    /** Every style, every tie-break seed: the decision is always one of the generator's actions. */
    @Test fun neverLeavesTheChoices() {
        val hand = fourSevens + listOf("0S3", "0H2", "1H2", "0C5", "0D8", "0S9", "0HJ")
        val state = playing(2, mapOf(S to hand, E to listOf("0S6"), N to listOf("1S6"), W to listOf("0C6")), turn = S, trick = listOf(tp(E, "0S10")))
        for (style in AiStyle.entries) for (seed in 1L..20L) {
            val input = hostInput(state, S)
            val out = DecisionRunner.decide(BaselineHeuristicPolicy, input, cn.wpgd.app.core.state.SplitMix64(seed), style)
            assertTrue(input.choices.isLegal(out.action), "$style $seed")
            assertEquals(DecisionSource.POLICY, out.source)
        }
    }
}
