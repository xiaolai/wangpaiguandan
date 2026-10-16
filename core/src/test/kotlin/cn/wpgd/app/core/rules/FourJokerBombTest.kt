package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Suit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 27-card hand with all four jokers and no wildcard (R6.1, R6.3). */
class FourJokerBombTest {
    @Test fun generatorEmitsFourJokerBomb() {
        val e = engine(level = 2)
        val hand = padTo27(Notation.cards("LJ LJ BJ BJ")) { it.suit == Suit.JOKER || it.isWildcard(2) }
        assertEquals(0, hand.count { it.isWildcard(2) })
        val fj = e.generator.generate(hand, null).filter { it.combination.type == CombinationType.FOUR_JOKERS }
        assertEquals(1, fj.size)
        assertTrue(fj.single().cards.all { it.suit == Suit.JOKER })
        // Following a 10-card bomb, four jokers is still offered.
        val ten = e.combo("S7 S7 D7 D7 C7 C7 H7 H7 H2 H2", "7", "7")
        assertTrue(e.generator.generate(hand, ten).any { it.combination.type == CombinationType.FOUR_JOKERS })
    }

    @Test fun threeJokersAndAWildcardAreNotFourJokers() {
        val e = engine(level = 2)
        val hand = padTo27(Notation.cards("LJ LJ BJ H2 H2")) { it.suit == Suit.JOKER || it.isWildcard(2) }
        assertTrue(e.generator.generate(hand, null).none { it.combination.type == CombinationType.FOUR_JOKERS })
    }

    @Test fun threeJokersAreNeverATriple() {
        // R6.4. A deal holds two of each joker, but the classifier also reads untrusted input.
        val e = engine(level = 2)
        for (codes in listOf("LJ LJ BJ", "LJ BJ BJ")) {
            assertTrue(e.classifier.readings(Notation.cards(codes)).isEmpty(), codes)
        }
        // A third copy of one joker cannot come from a deal (R1.1): refused as impossible, not read.
        for (codes in listOf("LJ LJ LJ", "BJ BJ BJ")) {
            val thrown = kotlin.test.assertFailsWith<ImpossibleCardsException>(codes) { e.classifier.readings(Notation.cards(codes)) }
            kotlin.test.assertEquals(RejectReason.TOO_MANY_COPIES, thrown.reason, codes)
        }
    }
}
