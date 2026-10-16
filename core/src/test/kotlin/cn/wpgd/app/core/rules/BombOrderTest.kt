package cn.wpgd.app.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R4.1–R4.9, R5.3, R6.2: bomb sizes 4–10, same-size level bombs, straight flush placement, four jokers. */
class BombOrderTest {
    private val e = engine(level = 2)

    /** Bombs of 4..10 cards of one rank (8 physical sevens + both heart-2 wildcards at most). */
    private val bombs = (4..10).associateWith { n ->
        val physical = listOf("S7", "S7", "D7", "D7", "C7", "C7", "H7", "H7").take(minOf(n, 8))
        val wild = List(n - physical.size) { "H2" }
        e.combo((physical + wild).joinToString(" "), *Array(wild.size) { "7" })
    }

    @Test fun sizesFourToTenAreBombsAndMoreCardsWin() {
        for ((n, b) in bombs) assertEquals(n, b.bombSize)
        for (a in 4..10) for (b in 4..10) assertEquals(a > b, bombs.getValue(a).beats(bombs.getValue(b)), "$a vs $b")
        // A bigger bomb of a lower rank beats a smaller bomb of a higher rank.
        assertTrue(e.combo("S3 S3 D3 C3 H3").beats(e.combo("SA DA CA HA")))
    }

    @Test fun equalSizeComparesRankAndLevelBombBeatsAceBomb() {
        val e9 = engine(level = 9)
        assertTrue(e9.combo("SK DK CK HK").beats(e9.combo("SQ DQ CQ HQ")))
        assertFalse(e9.combo("SQ DQ CQ HQ").beats(e9.combo("SK DK CK HK")))
        assertFalse(e9.combo("S8 D8 C8 H8").beats(e9.combo("S8 D8 C8 H8")))
        assertTrue(e9.combo("S9 S9 D9 C9").beats(e9.combo("SA DA CA HA")), "R4.8: level bomb beats same-size A bomb")
        assertFalse(e9.combo("SA DA CA HA").beats(e9.combo("S9 S9 D9 C9")))
    }

    @Test fun straightFlushSitsBetweenFiveAndSixCardBombs() {
        val sf = e.combo("S3 S4 S5 S6 S7")
        val five = e.combo("SA SA DA CA HA")
        val six = e.combo("S3 S3 D3 D3 C3 H3")
        assertTrue(sf.beats(five)); assertFalse(five.beats(sf))
        assertTrue(sf.beats(e.combo("SK DK CK HK")))
        assertTrue(six.beats(sf)); assertFalse(sf.beats(six))
        assertTrue(sf.beats(e.combo("S10 DJ CQ SK DA")), "SF beats an ordinary straight")
        assertTrue(e.combo("C4 C5 C6 C7 C8").beats(sf))
        assertFalse(e.combo("C3 C4 C5 C6 C7").beats(sf), "suits never decide")
    }

    @Test fun fourJokersBeatEverything() {
        val fj = e.combo("LJ LJ BJ BJ")
        for (b in bombs.values) { assertTrue(fj.beats(b)); assertFalse(b.beats(fj)) }
        assertTrue(fj.beats(e.combo("S3 S4 S5 S6 S7")))
        assertTrue(fj.beats(e.combo("SA")))
        assertFalse(fj.beats(fj))
    }

    @Test fun anyBombBeatsAnyOrdinaryPlay() {
        val four = e.combo("S3 D3 C3 H3")
        for (codes in listOf("BJ", "BJ BJ", "SA DA CA", "SA DA CA SK DK", "S10 DJ CQ SK DA", "SQ DQ SK DK SA DA", "SK DK CK SA DA CA")) {
            val ordinary = e.combo(codes)
            assertTrue(four.beats(ordinary), codes)
            assertFalse(ordinary.beats(four), codes)
        }
    }
}
