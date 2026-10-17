package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.fixtureDeck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A second, independent oracle over rank/suit counts (not subsets), so it runs on full 27-card
 * hands under P1. Branch A: same-rank bombs per rank, every permitted size from natural count +
 * wildcards. Branch B: four jokers and straight flushes from suit/rank counts. The union must
 * equal the generator's bomb-class actions.
 *
 * Independence (review F11): the sequence windows, bomb sizes and action shapes below are written
 * from the text of rules/base-v1.md, not taken from `Shapes`, `RuleSettings.allowsBombSize` or any
 * `Combination` factory, so a defect there is caught here rather than mirrored. Both sides are
 * compared as a test-owned [OracleAction] (faces, declaration, type, rank, run, suit, size). The
 * comparison key (play order) is not part of it; `BombOrderTest` and the golden set cover order.
 */
class LargeBombCountOracleTest {
    @Test fun bombClassActionsMatchCountOracle() {
        val random = Random(27)
        val bombSizes = HashSet<Int>()
        repeat(40) { i ->
            val level = random.nextInt(2, 15)
            val e = engine(level)
            val deck = fixtureDeck()
            val wild = deck.filter { it.isWildcard(level) }
            // Every fourth hand holds all eight cards of one non-level rank, so 9- and 10-card bombs occur.
            val heavyRank = (2..14).filter { it != level }[(i / 4) % 12]
            val eight = if (i % 4 == 3) deck.filter { it.rank == heavyRank } else emptyList()
            val hand = (wild.take(i % 3) + eight + deck.filter { !it.isWildcard(level) && it !in eight }.shuffled(random)).take(27)
            val got = e.generator.generate(hand, null).filter { it.combination.type in BOMB_CLASS }.map { it.project() }
            assertEquals(got.size, got.toSet().size)
            assertEquals(countOracle(level, hand), got.toSet(), "hand #$i level=$level ${hand.map(Notation::code)}")
            bombSizes += got.filter { it.type == "BOMB" }.map { it.size }
        }
        assertEquals(BOMB_SIZES.toSet(), bombSizes, "the seeded hands must reach every permitted bomb size")
    }

    /** The oracle below assumes P1's values for the options it encodes as literals; fail loud if P1 moves. */
    @Test fun p1MatchesTheOracleAssumptions() {
        val s = engine(2).settings
        assertTrue(!s.sequenceWrapAroundA && !s.sequenceJokersAllowed, "oracle windows assume no wrap and no jokers in runs")
        assertEquals(BOMB_SIZES.first, s.bombMinSize)
        assertEquals(BOMB_SIZES.last, s.bombMaxSize)
    }

    private fun countOracle(level: Int, hand: List<Card>): Set<OracleAction> {
        val wildCode = "H" + Notation.label(level)
        val w = hand.count { Notation.code(it) == wildCode } // R1.8: the heart level cards
        val count = hand.map(Notation::code).filter { it != wildCode }.groupingBy { it }.eachCount()
        val out = HashSet<OracleAction>()

        // Branch A (R4.1, R4.2, R4.5, R4.6): n cards of rank r, k of them wildcards declared as r.
        for (r in 2..14) {
            val label = Notation.label(r)
            val avail = SUIT_LETTERS.map { count[it + label] ?: 0 }
            for (n in BOMB_SIZES) {
                for (k in 0..minOf(w, n)) {
                    val p = n - k
                    for (a in 0..avail[0]) for (b in 0..avail[1]) for (c in 0..avail[2]) {
                        val d = p - a - b - c
                        if (d < 0 || d > avail[3]) continue
                        val faces = listOf(a, b, c, d).flatMapIndexed { i, m -> List(m) { SUIT_LETTERS[i] + label } } + List(k) { wildCode }
                        out += OracleAction(faces.sorted(), List(k) { label }, "BOMB", r, emptyList(), null, n)
                    }
                }
            }
        }
        // Branch B (R6.1, R6.3): all four physical jokers.
        if (count["LJ"] == 2 && count["BJ"] == 2) out += OracleAction(listOf("BJ", "BJ", "LJ", "LJ"), emptyList(), "FOUR_JOKERS", null, emptyList(), null, 4)
        // Branch B (R5.1, R7.2, R7.4): each position is the physical card of that suit, or a wildcard declared as it.
        for (window in WINDOWS) for (suit in SUIT_LETTERS) {
            fun go(i: Int, wl: Int, faces: List<String>, decl: List<String>) {
                if (i == window.size) {
                    out += OracleAction(faces.sorted(), decl.sorted(), "STRAIGHT_FLUSH", null, window, suit, 5)
                    return
                }
                val code = suit + Notation.label(window[i])
                if ((count[code] ?: 0) > 0) go(i + 1, wl, faces + code, decl)
                if (wl > 0) go(i + 1, wl - 1, faces + wildCode, decl + (Notation.label(window[i]) + suit))
            }
            go(0, w, emptyList(), emptyList())
        }
        return out
    }

    /** Test-owned identity of one bomb-class action. Declarations are "rank" or "rank+suit" labels. */
    private data class OracleAction(
        val faces: List<String>,
        val declaration: List<String>,
        val type: String,
        val rank: Int?,
        val run: List<Int>,
        val suit: String?,
        val size: Int,
    )

    private fun LegalAction.project() = OracleAction(
        faces = cards.map(Notation::code).sorted(),
        declaration = declaration.orEmpty().map { Notation.label(it.rank) + (it.suit?.let(Notation::letter) ?: "") }.sorted(),
        type = combination.type.name,
        rank = combination.rank,
        run = combination.sequence,
        suit = combination.suit?.let(Notation::letter),
        size = combination.size,
    )

    private companion object {
        val BOMB_CLASS = setOf(CombinationType.BOMB, CombinationType.STRAIGHT_FLUSH, CombinationType.FOUR_JOKERS)
        val SUIT_LETTERS = listOf("S", "H", "C", "D")

        /** R4.1: at least four cards. R4.5, R4.6 (D2 default): up to 8 physical + 2 wildcards = 10. */
        val BOMB_SIZES = 4..10

        /**
         * Five-card runs, low to high in play order. R3.1, R3.2: A is 1 (A-2-3-4-5, written with A
         * first) or above K (10-J-Q-K-A). R3.3, R3.4 (D1 default): no run passes through A into 2.
         * R2.11 (D4 default): no jokers. R3.5: a level card sits at its natural rank, so the level changes nothing.
         */
        val WINDOWS: List<List<Int>> = listOf(listOf(14, 2, 3, 4, 5)) + (2..10).map { low -> (low..low + 4).toList() }
    }
}
