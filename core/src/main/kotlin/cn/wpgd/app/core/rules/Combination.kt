package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit

/** Rank values shared by [Card.rank] and declarations. 2..14 are natural ranks (14 = A). */
object Ranks {
    const val ACE = 14
    const val SMALL_JOKER = 16
    const val BIG_JOKER = 17

    /** Comparison value of a level card outside sequences (R1.5): above A, below the small joker. */
    const val LEVEL_POWER = 15

    val NATURAL: IntRange = 2..ACE

    fun isJoker(rank: Int): Boolean = rank == SMALL_JOKER || rank == BIG_JOKER

    /** R1.5: the level's rank leaves its natural place and sits just below the small joker. */
    fun power(rank: Int, level: Int): Int = if (rank == level) LEVEL_POWER else rank
}

/** The ten play types of base-v1 sections 2, 4, 5 and 6. */
enum class CombinationType(val isBombClass: Boolean) {
    SINGLE(false),
    PAIR(false),
    TRIPLE(false),
    TRIPLE_WITH_PAIR(false),
    STRAIGHT(false),
    CONSECUTIVE_PAIRS(false),
    CONSECUTIVE_TRIPLES(false),
    STRAIGHT_FLUSH(true),
    BOMB(true),
    FOUR_JOKERS(true),
}

/**
 * One reading of a play: what type it is and how it ranks.
 *
 * - [rank]: natural rank of the defining group (the single, pair, triple, the triple of a
 *   triple-with-pair, the bomb); 16/17 for jokers. Null for sequences and four jokers.
 * - [sequence]: for sequence types, the natural ranks in play order, low to high. An A-low
 *   sequence starts with 14 (`[14, 2, 3, 4, 5]`). Empty otherwise.
 * - [suit]: the suit of a straight flush; null for every other type.
 * - [resolvedRanks]: every card's rank after wildcard declaration, sorted. It tells apart readings
 *   that share a type and key, such as 777+88 and 777+99.
 * - [key]: the value compared between two plays of the same type (R2.15, R2.16, R4.8, R5.4).
 */
@ConsistentCopyVisibility
data class Combination internal constructor(
    val type: CombinationType,
    val size: Int,
    val rank: Int?,
    val sequence: List<Int>,
    val suit: Suit?,
    val resolvedRanks: List<Int>,
    val key: Int,
) {
    init {
        require(size == resolvedRanks.size) { "size $size does not match ${resolvedRanks.size} resolved ranks" }
        require((suit != null) == (type == CombinationType.STRAIGHT_FLUSH)) { "only a straight flush carries a suit" }
        require(sequence.isEmpty() == (rank != null || type == CombinationType.FOUR_JOKERS)) { "rank xor sequence" }
    }

    val bombSize: Int? get() = if (type == CombinationType.BOMB) size else null

    /**
     * True if this play may be put on [table] (R2.13–R2.15, R4.7–R4.9, R5.3, R6.2).
     * Ordinary plays need the same type and card count and a higher key. Bomb-class plays are
     * ordered by tier: four-card bomb < five-card bomb < straight flush < six-card bomb < … <
     * four jokers; within one tier the higher key wins.
     */
    fun beats(table: Combination): Boolean {
        val mine = bombTier()
        val theirs = table.bombTier()
        return when {
            mine == null && theirs == null -> type == table.type && size == table.size && key > table.key
            mine == null -> false
            theirs == null -> true
            mine != theirs -> mine > theirs
            else -> key > table.key
        }
    }

    private fun bombTier(): Int? = when (type) {
        CombinationType.FOUR_JOKERS -> Int.MAX_VALUE
        CombinationType.STRAIGHT_FLUSH -> 2 * 5 + 1
        CombinationType.BOMB -> 2 * size
        else -> null
    }
}

/**
 * What one wildcard stands for (R7.8, R7.10). [suit] is named only in a straight flush.
 * A declaration is untrusted input, so a joker rank is representable here and rejected by
 * [Classifier.judge] (R7.3), not by this constructor.
 */
data class WildcardAssignment(val rank: Int, val suit: Suit? = null) : Comparable<WildcardAssignment> {
    init {
        require(rank in Ranks.NATURAL || Ranks.isJoker(rank)) { "No such rank: $rank" }
        require(suit != Suit.JOKER) { "A declaration suit must be a card suit" }
    }

    override fun compareTo(other: WildcardAssignment): Int =
        compareValuesBy(this, other, { it.rank }, { it.suit?.ordinal ?: -1 })
}

/** One legal interpretation of a selection: the combination plus the declaration that yields it. */
data class Reading(val combination: Combination, val declaration: List<WildcardAssignment>)

/**
 * One legal play from a hand. [cards] are the hand's own [Card] objects.
 * [declaration] is what the player states for the wildcards in [cards], in canonical order; it
 * is empty when [cards] hold no wildcard, and null only when the profile does not require a
 * declaration (`wildcard.alwaysDeclared` false) and these cards have exactly one reading, or
 * `wildcard.ambiguousNeedsDeclaration` is also false (D15).
 */
data class LegalAction(val cards: List<Card>, val declaration: List<WildcardAssignment>?, val combination: Combination)

/** Canonical order for a declaration, so equal declarations compare equal. */
internal fun List<WildcardAssignment>.canonical(): List<WildcardAssignment> = sorted()
