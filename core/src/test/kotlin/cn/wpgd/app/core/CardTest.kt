package cn.wpgd.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardTest {
    @Test fun duplicateFacesRetainPhysicalIdentity() {
        val deck = fixtureDeck()
        assertEquals(108, deck.size)
        assertEquals(108, deck.map { it.id }.toSet().size)
        assertEquals(54, deck.groupBy { it.suit to it.rank }.size)
        assertTrue(deck.groupBy { it.suit to it.rank }.values.all { it.size == 2 })
    }
    @Test fun onlyHeartOfCurrentLevelIsWild() {
        assertEquals(2, fixtureDeck().count { it.isWildcard(7) })
        assertTrue(Card("heart", Suit.HEART, 7).isWildcard(7))
        assertFalse(Card("spade", Suit.SPADE, 7).isWildcard(7))
        assertFalse(Card("joker", Suit.JOKER, 17).isWildcard(7))
        assertFailsWith<IllegalArgumentException> { Card("c", Suit.HEART, 7).isWildcard(17) }
    }
    @Test fun invalidCardDataCannotEnterModel() {
        assertFailsWith<IllegalArgumentException> { Card("", Suit.SPADE, 2) }
        assertFailsWith<IllegalArgumentException> { Card("c", Suit.JOKER, 14) }
        assertFailsWith<IllegalArgumentException> { Card("c", Suit.HEART, 17) }
        assertFailsWith<IllegalArgumentException> { Card("c", Suit.SPADE, 1) }
    }
}
