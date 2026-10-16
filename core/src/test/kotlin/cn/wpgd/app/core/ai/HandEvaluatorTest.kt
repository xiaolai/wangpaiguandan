package cn.wpgd.app.core.ai

import cn.wpgd.app.core.state.StandardDeck
import kotlin.test.Test
import kotlin.test.assertEquals

/** The play-count estimate: a spare wildcard joins only a play that can legally absorb it. */
class HandEvaluatorTest {
    private fun plays(vararg ids: String, level: Int = 2) = HandEvaluator.shape(ids.map { StandardDeck[it]!! }, level).plays

    /** Two big jokers plus a wildcard: the wildcard cannot stand for a joker, so it is a play of its own. */
    @Test fun wildcardBesideJokerPairNeedsItsOwnPlay() {
        assertEquals(2, plays("0BJ", "1BJ", "0H2"))
    }

    /** A spare wildcard still joins a natural single (making a pair). */
    @Test fun wildcardJoinsANaturalSingle() {
        assertEquals(1, plays("0H2", "0S7"))
        assertEquals(2, plays("0BJ", "1BJ", "0H2", "0S7"))
    }

    /** A wildcard alone is one play. */
    @Test fun loneWildcardIsOnePlay() {
        assertEquals(1, plays("0H2"))
    }
}
