package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.fixtureDeck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review F14: the generator's actions, including the ids of the cards they use, do not depend on
 * the order of the hand. For each face it takes the copies with the lowest card ids.
 */
class PermutedHandTest {
    @Test fun permutedHandGivesIdenticalActionsAndCardIds() {
        val random = Random(14)
        for (level in listOf(2, 7, 14)) {
            val e = engine(level)
            val hand = denseHand(level)
            val reference = e.generator.generate(hand, null)
            assertTrue(reference.size > 1000, "hand too thin to test: ${reference.size} actions")
            for (permuted in listOf(hand.reversed(), hand.shuffled(random), hand.shuffled(random))) {
                assertEquals(reference, e.generator.generate(permuted, null), "level $level")
            }
            assertLowestIdsChosen(hand, reference)
        }
    }

    /** Every action takes, for each face it uses m times, the m lowest-id copies of that face in the hand. */
    private fun assertLowestIdsChosen(hand: List<Card>, actions: List<LegalAction>) {
        val idsByFace = hand.groupBy(::face).mapValues { (_, cards) -> cards.map { it.id }.sorted() }
        var duplicatesSeen = 0
        for (a in actions) for ((f, cards) in a.cards.groupBy(::face)) {
            val held = idsByFace.getValue(f)
            if (held.size > cards.size) duplicatesSeen++
            assertEquals(held.take(cards.size), cards.map { it.id }.sorted(), "face $f in ${a.cards.map { it.id }}")
        }
        assertTrue(duplicatesSeen > 0, "no action left a second copy unused, so the id choice was never exercised")
    }

    /** Both wildcards, both big jokers, then both copies of each card from 3 upward: 27 cards, mostly duplicate faces. */
    private fun denseHand(level: Int): List<Card> {
        val deck = fixtureDeck()
        val wild = deck.filter { it.isWildcard(level) }
        val jokers = deck.filter { it.suit == Suit.JOKER && it.rank == Ranks.BIG_JOKER }
        // Sorted by face so the two copies of each card sit together and both make it into the hand.
        val rest = deck.filter { it.rank in 3..8 && !it.isWildcard(level) }.sortedWith(compareBy({ it.rank }, { it.suit.ordinal }))
        return (wild + jokers + rest).take(27).also { check(it.size == 27) }
    }

    private fun face(c: Card) = Notation.code(c)
}
