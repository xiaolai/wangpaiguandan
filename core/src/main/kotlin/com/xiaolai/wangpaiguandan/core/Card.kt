package com.xiaolai.wangpaiguandan.core

enum class Suit(val glyph: String, val isRed: Boolean) {
    SPADE("♠", false), HEART("♥", true), CLUB("♣", false), DIAMOND("♦", true), JOKER("", false)
}

/** Immutable display/domain identity. Rules and session identifiers are separate future modules. */
data class Card(val id: String, val suit: Suit, val rank: Int) {
    init {
        require(id.isNotBlank()) { "A physical card needs a non-empty identity" }
        require(if (suit == Suit.JOKER) rank in 16..17 else rank in 2..14) { "Invalid rank/suit" }
    }
    val label: String get() = when (rank) {
        11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; 16 -> "小王"; 17 -> "大王"; else -> rank.toString()
    }
    fun isWildcard(level: Int): Boolean {
        require(level in 2..14)
        return suit == Suit.HEART && rank == level
    }
}

/** Deterministic unshuffled fixture deck; production dealing/randomness is not implemented here. */
fun fixtureDeck(): List<Card> = buildList {
    repeat(2) { deck ->
        for (suit in Suit.entries.filter { it != Suit.JOKER }) {
            for (rank in 2..14) add(Card("fixture-$deck-$suit-$rank", suit, rank))
        }
        for (rank in 16..17) add(Card("fixture-$deck-JOKER-$rank", Suit.JOKER, rank))
    }
}
