package cn.wpgd.app.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** All eight physical sevens plus both heart-2 wildcards at level 2 (D2 default: sizes up to 10). */
class SevenBomb27CardTest {
    @Test fun everyPermittedSevenBombIsOffered() {
        val e = engine(level = 2)
        val hand = padTo27(Notation.cards("S7 S7 D7 D7 C7 C7 H7 H7 H2 H2")) { it.rank == 7 || it.isWildcard(2) }
        val sevens = e.generator.generate(hand, null).filter { it.combination.type == CombinationType.BOMB && it.combination.rank == 7 }
        fun split(a: LegalAction) = Triple(a.cards.size - a.cards.count { it.isWildcard(2) }, a.cards.count { it.isWildcard(2) }, a.combination.size)
        val splits = sevens.map(::split).toSet()
        assertEquals((4..10).toList(), sevens.map { it.combination.size }.toSortedSet().toList())
        assertTrue(Triple(8, 1, 9) in splits)
        assertTrue(Triple(7, 2, 9) in splits)
        assertTrue(Triple(8, 2, 10) in splits)
        for (a in sevens) assertEquals(List(split(a).second) { WildcardAssignment(7) }, a.declaration)
    }

    @Test fun bombMaxSizeCapsTheSevenBombs() {
        val e = engine(level = 2, profile = Profiles.with("bomb.maxSize" to "8"))
        val hand = padTo27(Notation.cards("S7 S7 D7 D7 C7 C7 H7 H7 H2 H2")) { it.rank == 7 || it.isWildcard(2) }
        val sizes = e.generator.generate(hand, null).filter { it.combination.type == CombinationType.BOMB }.map { it.combination.size }.toSet()
        assertEquals(8, sizes.max())
    }
}
