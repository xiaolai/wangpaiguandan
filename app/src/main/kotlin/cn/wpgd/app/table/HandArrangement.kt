package cn.wpgd.app.table

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.ViewCard

/** How the free (not manually grouped) cards are sorted into columns (GDN-075). */
enum class HandSort {
    /** One column per rank, low to high by power at the deal level; jokers together. */
    RANK,

    /** Per suit (♠ ♥ ♣ ♦) in columns of four, then wildcards, then jokers. */
    SUIT,

    /** Bombs, then triples, then pairs, then singles in columns of four, then wildcards, then jokers. */
    COMBINATION,
}

enum class GroupKind { MANUAL, RANK, SUIT, BOMB, TRIPLE, PAIR, SINGLES, WILDCARDS, JOKERS }

/** One column of the hand. [key] is stable for the same cards and sort, so the UI can keep identity. */
data class CardGroup(val key: String, val kind: GroupKind, val cards: List<ViewCard>)

/**
 * Pure arrangement of a hand into columns. Card ids are never changed or dropped: the arranged
 * cards are exactly the hand. Manual groups come first, in the order they were made, and lose only
 * the cards that left the hand (so they survive unrelated plays). Within a column, a wildcard sits
 * at the bottom of its rank (design README: 万能牌排在同点数列最下方).
 */
object HandArrangement {
    fun arrange(hand: List<ViewCard>, level: Int, sort: HandSort, manualGroups: List<List<String>> = emptyList()): List<CardGroup> {
        val byId = hand.associateBy { it.id }
        val manual = pruneGroups(manualGroups, byId.keys)
        val used = manual.flatten().toSet()
        val out = ArrayList<CardGroup>()
        manual.forEach { ids -> out += CardGroup("m:" + ids.joinToString(","), GroupKind.MANUAL, ids.map(byId::getValue)) }
        val rest = hand.filter { it.id !in used }.sortedWith(order(level))
        val wild = rest.filter { isWildcard(it, level) }
        val jokers = rest.filter { Ranks.isJoker(it.rank) }
        val natural = rest.filter { !isWildcard(it, level) && !Ranks.isJoker(it.rank) }
        when (sort) {
            HandSort.RANK -> {
                // The wildcards stay in their own rank's column, at its bottom (sorted order puts them last).
                rest.filter { !Ranks.isJoker(it.rank) }.groupBy { it.rank }.forEach { (rank, cards) -> out += CardGroup("r:$rank", GroupKind.RANK, cards) }
            }
            HandSort.SUIT -> {
                for (suit in SUITS) {
                    natural.filter { it.suit == suit }.chunked(4).forEachIndexed { i, c -> out += CardGroup("s:${suit.name}:$i", GroupKind.SUIT, c) }
                }
            }
            HandSort.COMBINATION -> {
                val byRank = natural.groupBy { it.rank }
                byRank.filter { it.value.size >= 4 }.forEach { (r, c) -> out += CardGroup("b:$r", GroupKind.BOMB, c) }
                byRank.filter { it.value.size == 3 }.forEach { (r, c) -> out += CardGroup("t:$r", GroupKind.TRIPLE, c) }
                byRank.filter { it.value.size == 2 }.forEach { (r, c) -> out += CardGroup("p:$r", GroupKind.PAIR, c) }
                byRank.filter { it.value.size == 1 }.values.flatten().chunked(4).forEachIndexed { i, c -> out += CardGroup("1:$i", GroupKind.SINGLES, c) }
            }
        }
        // Rank sort keeps each wildcard in its rank's column; the other sorts give them their own group.
        if (sort != HandSort.RANK && wild.isNotEmpty()) out += CardGroup("w", GroupKind.WILDCARDS, wild)
        if (jokers.isNotEmpty()) out += CardGroup("j", GroupKind.JOKERS, jokers)
        check(out.sumOf { it.cards.size } == hand.size) { "arrangement lost or duplicated cards" }
        return out
    }

    /** Manual groups restricted to [handIds]; groups left empty disappear. Order is kept. */
    fun pruneGroups(groups: List<List<String>>, handIds: Set<String>): List<List<String>> {
        val seen = HashSet<String>()
        return groups.map { g -> g.filter { it in handIds && seen.add(it) } }.filter { it.isNotEmpty() }
    }

    /** Makes [selected] (in hand order) one manual group, taking those cards out of any other group. */
    fun group(groups: List<List<String>>, selected: Collection<String>, hand: List<ViewCard>): List<List<String>> {
        val ids = hand.map { it.id }.filter { it in selected }
        if (ids.isEmpty()) return groups
        val chosen = ids.toSet()
        return groups.map { g -> g.filter { it !in chosen } }.filter { it.isNotEmpty() } + listOf(ids)
    }

    fun isWildcard(card: ViewCard, level: Int): Boolean = card.suit == Suit.HEART && card.rank == level

    /** Low to high by power at [level], wildcards last within their rank, then suit, then id. */
    fun order(level: Int): Comparator<ViewCard> = compareBy<ViewCard>(
        { Ranks.power(it.rank, level) },
        { if (isWildcard(it, level)) 1 else 0 },
        { it.suit.ordinal },
        { it.id },
    )

    private val SUITS = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
}

/**
 * Hand layout A (two rows by rank, the design default): the columns split into rows of
 * ceil(n / 2) columns, so a full 27-card hand needs no sideways scrolling. [maxColumns] gives the
 * explicit alternate layout for narrow screens and large text (GDN-073): more rows of at most that
 * many columns. Pure; the UI measures, this decides.
 */
object HandLayoutA {
    fun <T> rows(groups: List<T>, maxColumns: Int? = null): List<List<T>> {
        require(maxColumns == null || maxColumns >= 1) { "maxColumns must be positive" }
        if (groups.isEmpty()) return emptyList()
        val perRow = ((groups.size + 1) / 2).let { if (maxColumns != null) minOf(it, maxColumns) else it }
        return groups.chunked(perRow)
    }
}
