package cn.wpgd.app.core.state

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Ranks

/**
 * The 108 physical cards of a deal (R1.1) with stable ids. An id is the copy (0 or 1), then the
 * suit letter S, H, C or D and the rank 2–10, J, Q, K, A (`0S10`, `1HA`), or `LJ` / `BJ` for the
 * small and big joker (`0LJ`, `1BJ`). An id names a face and a copy, nothing else, so a member who
 * sees an id learns only that card.
 *
 * [cards] is the canonical pre-shuffle order, part of the cross-platform deal contract: copy 0 then
 * copy 1; within a copy, suits S, H, C, D, each rank 2 to A, then the small and the big joker.
 */
object StandardDeck {
    val cards: List<Card> = buildList {
        for (copy in 0..1) {
            for (suit in listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)) {
                for (rank in Ranks.NATURAL) add(Card("$copy${letter(suit)}${label(rank)}", suit, rank))
            }
            add(Card("${copy}LJ", Suit.JOKER, Ranks.SMALL_JOKER))
            add(Card("${copy}BJ", Suit.JOKER, Ranks.BIG_JOKER))
        }
    }

    private val byId: Map<String, Card> = cards.associateBy { it.id }

    val ids: Set<String> = byId.keys

    /** The card with [id], or null if no such card exists. */
    operator fun get(id: String): Card? = byId[id]

    init {
        check(cards.size == 108 && byId.size == 108) { "the deck must hold 108 distinct cards (R1.1)" }
    }

    private fun letter(suit: Suit) = when (suit) {
        Suit.SPADE -> "S"; Suit.HEART -> "H"; Suit.CLUB -> "C"; Suit.DIAMOND -> "D"; Suit.JOKER -> error("jokers have no suit letter")
    }

    private fun label(rank: Int) = when (rank) {
        11 -> "J"; 12 -> "Q"; 13 -> "K"; Ranks.ACE -> "A"; else -> rank.toString()
    }
}
