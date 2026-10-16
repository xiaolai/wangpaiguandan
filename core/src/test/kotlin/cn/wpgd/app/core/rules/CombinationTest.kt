package cn.wpgd.app.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Each type of base-v1 section 2 (and bomb-class types), A-low straights, jokers excluded from sequences. */
class CombinationTest {
    private val e = engine(level = 9)

    private fun only(codes: String): Combination {
        val readings = e.classifier.readings(Notation.cards(codes)).map { it.combination }.distinct()
        assertEquals(1, readings.size, "$codes readings: $readings")
        return readings.single()
    }

    private fun none(codes: String) = assertEquals(emptyList(), e.classifier.readings(Notation.cards(codes)), codes)

    @Test fun eachType() {
        assertEquals(CombinationType.SINGLE, only("S5").type)
        assertEquals(CombinationType.PAIR, only("S5 D5").type)
        assertEquals(CombinationType.TRIPLE, only("S5 D5 C5").type)
        only("S5 D5 C5 SK DK").let { assertEquals(CombinationType.TRIPLE_WITH_PAIR, it.type); assertEquals(5, it.rank) }
        only("S3 D4 C5 S6 D7").let { assertEquals(CombinationType.STRAIGHT, it.type); assertEquals(listOf(3, 4, 5, 6, 7), it.sequence) }
        assertEquals(CombinationType.CONSECUTIVE_PAIRS, only("S3 D3 S4 D4 S5 D5").type)
        assertEquals(CombinationType.CONSECUTIVE_TRIPLES, only("S3 D3 C3 S4 D4 C4").type)
        only("S3 S4 S5 S6 S7").let { assertEquals(CombinationType.STRAIGHT_FLUSH, it.type); assertEquals(cn.wpgd.app.core.Suit.SPADE, it.suit) }
        only("S6 D6 C6 H6").let { assertEquals(CombinationType.BOMB, it.type); assertEquals(4, it.bombSize) }
        assertEquals(CombinationType.FOUR_JOKERS, only("LJ LJ BJ BJ").type)
    }

    @Test fun illegalShapes() {
        none("S5 D5 C5 SK")          // triple + single
        none("S5 D5 C5 SK SQ")       // triple + two odd cards
        none("S3 D4 C5 S6")          // four in a row
        none("S3 D4 C5 S6 D7 C8")    // six in a row
        none("S3 D3 S4 D4")          // two consecutive pairs
        none("SJ DJ CJ SQ DQ CQ SK DK CK") // three consecutive triples
        none("LJ BJ")                // D3: big + small is not a pair
        none("LJ LJ BJ")
    }

    @Test fun aLowAndAHighSequences() {
        val low = only("SA D2 C3 S4 D5")
        assertEquals(listOf(14, 2, 3, 4, 5), low.sequence)
        assertEquals(5, low.key, "D5: A-2-3-4-5 ranks as 5-high")
        val high = only("S10 DJ CQ SK DA")
        assertEquals(14, high.key)
        assertTrue(only("S2 D3 C4 S5 D6").beats(low))
        assertEquals(listOf(14, 2, 3), only("SA DA S2 D2 S3 D3").sequence)
        assertEquals(listOf(13, 14), only("SK DK CK SA DA CA").sequence)
        none("SK DA C2 S3 D4")       // D1: no wrap-around
    }

    @Test fun levelCardSitsAtItsNaturalRankInSequences() {
        val e7 = engine(level = 7)
        assertEquals(listOf(5, 6, 7, 8, 9), e7.classifier.readings(Notation.cards("S5 D6 C7 S8 D9")).single().combination.sequence)
        assertEquals(emptyList(), e7.classifier.readings(Notation.cards("SJ DQ CK SA D7")))
        // Outside sequences the level card ranks above A (R1.5).
        assertTrue(e7.combo("S7").beats(e7.combo("SA")))
    }

    @Test fun jokersExcludedFromSequences() {
        none("S10 DJ CQ SK BJ")
        none("SQ SK SA LJ BJ")
        none("SA DA LJ LJ BJ BJ")
    }

    @Test fun differentTypesNeverBeatEachOther() {
        assertTrue(!only("S6 D6 C6").beats(only("S5 D5")))
        assertTrue(!only("S5 D5 S6 D6 S7 D7").beats(only("S3 D3 C3 S4 D4 C4")))
        val j = e.judge("S4 D5 C6 S7 D8", table = only("S3 D3 C3 SK DK"))
        assertIs<Judgment.Readable>(j)
        assertTrue(!j.legal)
    }
}
