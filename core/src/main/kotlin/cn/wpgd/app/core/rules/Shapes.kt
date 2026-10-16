package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit

/**
 * Shape rules for one profile and level: which rank runs are sequences, how each type is keyed,
 * and which readings a fully resolved set of cards has. Shared by [Classifier] and
 * [LegalActionGenerator] so both build identical [Combination] values.
 */
internal class Shapes(val settings: RuleSettings, val level: Int) {
    init {
        require(level in Ranks.NATURAL) { "Level must be a rank 2..A, got $level" }
    }

    /** Sequence windows by length: 5 (straights), 3 (consecutive pairs), 2 (consecutive triples). */
    val windows: Map<Int, List<List<Int>>> = listOf(5, 3, 2).associateWith(::windowsOf)
    private val windowBySet: Map<Int, Map<Set<Int>, List<Int>>> =
        windows.mapValues { (_, ws) -> ws.associateBy { it.toSet() } }

    /**
     * Every run of [length] ranks this profile accepts, in play order.
     * R3.1/R3.2: A is 1 (A-low) or above K. R3.5: level cards sit at their natural rank, so the level
     * changes nothing here. `sequence.wrapAroundA` (D1): off, R3.3 and R3.4 hold; on, a run may pass
     * through A into 2 with A not first (K-A-2-3-4), which deliberately overrides sourced R3.3. R2.11 (`sequence.jokersAllowed`): when allowed, jokers extend the rank order
     * past A as small joker then big joker (R1.5 order without the level card, D13); they wrap from
     * the big joker into 2 only with `sequence.jokersWrap`.
     */
    private fun windowsOf(length: Int): List<List<Int>> = buildList {
        add(listOf(Ranks.ACE) + (2 until 2 + length - 1))
        for (start in 2..Ranks.ACE - length + 1) add((start until start + length).toList())
        if (settings.sequenceWrapAroundA) {
            val cycle = Ranks.NATURAL.toList()
            for (start in (16 - length)..13) {
                val from = cycle.indexOf(start)
                add(List(length) { cycle[(from + it) % cycle.size] })
            }
        }
        if (settings.sequenceJokersAllowed) {
            val extended = Ranks.NATURAL.toList() + listOf(Ranks.SMALL_JOKER, Ranks.BIG_JOKER)
            val lastStart = if (settings.sequenceJokersWrap) extended.size - 1 else extended.size - length
            for (from in 0..lastStart) {
                val w = List(length) { extended[(from + it) % extended.size] }
                if (w.any(Ranks::isJoker)) add(w)
            }
        }
    }

    fun power(rank: Int): Int = Ranks.power(rank, level)

    // ---- factories: the only place a Combination's key is computed

    fun single(rank: Int) = grouped(CombinationType.SINGLE, rank, listOf(rank))
    /** D14: a mixed joker pair (when `joker.bigSmallIsPair`) ranks as its lower card unless the profile says otherwise. */
    fun pair(ranks: List<Int>) =
        grouped(CombinationType.PAIR, if (settings.jokerBigSmallPairRanksAsSmall) ranks.min() else ranks.max(), ranks)
    fun triple(rank: Int) = grouped(CombinationType.TRIPLE, rank, List(3) { rank })
    fun bomb(rank: Int, size: Int) = grouped(CombinationType.BOMB, rank, List(size) { rank })

    /** R2.15: only the triple counts. */
    fun tripleWithPair(triple: Int, pair: List<Int>) =
        grouped(CombinationType.TRIPLE_WITH_PAIR, triple, List(3) { triple } + pair)

    fun fourJokers() = Combination(
        CombinationType.FOUR_JOKERS, 4, null, emptyList(), null,
        listOf(Ranks.SMALL_JOKER, Ranks.SMALL_JOKER, Ranks.BIG_JOKER, Ranks.BIG_JOKER), 0,
    )

    /**
     * R2.16: a sequence is keyed by its last card in play order. D5 (`sequence.aceLowRanksLowest`): an
     * A-low run is 5-high, or A-high when off. D12 (`sequence.wrapRanksByLastCard`): a run through A
     * into 2 is keyed by its last card (K-A-2-3-4 is 4-high), or A-high when off.
     */
    fun sequence(type: CombinationType, window: List<Int>, suit: Suit? = null): Combination {
        val per = when (type) {
            CombinationType.STRAIGHT, CombinationType.STRAIGHT_FLUSH -> 1
            CombinationType.CONSECUTIVE_PAIRS -> 2
            CombinationType.CONSECUTIVE_TRIPLES -> 3
            else -> error("$type is not a sequence type")
        }
        val ace = window.indexOf(Ranks.ACE)
        val aceThenTwo = ace in 0 until window.lastIndex && window[ace + 1] == 2
        val key = when {
            aceThenTwo && ace == 0 && !settings.sequenceAceLowRanksLowest -> Ranks.ACE
            aceThenTwo && ace > 0 && !settings.sequenceWrapRanksByLastCard -> Ranks.ACE
            else -> window.last()
        }
        return Combination(type, window.size * per, null, window, suit, window.flatMap { r -> List(per) { r } }.sorted(), key)
    }

    private fun grouped(type: CombinationType, rank: Int, resolved: List<Int>) =
        Combination(type, resolved.size, rank, emptyList(), null, resolved.sorted(), power(rank))

    fun windowOf(ranks: Set<Int>, length: Int): List<Int>? =
        if (ranks.size == length) windowBySet.getValue(length)[ranks] else null

    /**
     * Every reading of [physical] cards (no wildcards among them) plus wildcards declared as
     * [assigned]. A declaration names a suit only in a straight flush (R7.10); a suit anywhere
     * else makes the declaration describe no legal play (D16; [Classifier.judge] may ignore the suit
     * instead under `wildcard.straySuitRejected`).
     */
    fun readings(physical: List<Card>, assigned: List<WildcardAssignment>): List<Combination> {
        val n = physical.size + assigned.size
        if (n == 0 || assigned.any { Ranks.isJoker(it.rank) }) return emptyList()
        if (assigned.any { it.suit != null }) return suitedReadings(physical, assigned)

        val ranks = physical.map { it.rank } + assigned.map { it.rank }
        val counts = ranks.groupingBy { it }.eachCount()
        val out = ArrayList<Combination>(2)
        val oneRank = counts.size == 1
        val onlyRank = ranks.first()
        val jokerFree = counts.keys.none(Ranks::isJoker)

        when (n) {
            1 -> out += single(onlyRank)
            2 -> if (oneRank || (settings.jokerBigSmallIsPair && counts.keys == JOKER_PAIR)) out += pair(ranks)
            3 -> if (oneRank && jokerFree) out += triple(onlyRank)
            4 -> if (counts == FOUR_JOKERS) out += fourJokers()
        }
        if (oneRank && jokerFree && n >= 4 && settings.allowsBombSize(n)) out += bomb(onlyRank, n)
        if (n == 5) {
            tripleWithPairOf(counts)?.let { out += it }
            straightReadings(physical, assigned, counts, out)
        }
        if (n == 6) {
            if (counts.values.all { it == 2 }) windowOf(counts.keys, 3)?.let { out += sequence(CombinationType.CONSECUTIVE_PAIRS, it) }
            if (counts.values.all { it == 3 }) windowOf(counts.keys, 2)?.let { out += sequence(CombinationType.CONSECUTIVE_TRIPLES, it) }
        }
        return out
    }

    private fun tripleWithPairOf(counts: Map<Int, Int>): Combination? {
        if (counts.size == 1) {
            val r = counts.keys.single()
            return if (settings.tripleWithPairSameRankPair && !Ranks.isJoker(r)) tripleWithPair(r, listOf(r, r)) else null
        }
        val triple = counts.entries.singleOrNull { it.value == 3 }?.key ?: return null
        if (Ranks.isJoker(triple)) return null
        val rest = counts - triple
        val pair = rest.flatMap { (r, c) -> List(c) { r } }
        val isPair = when {
            pair.size != 2 -> false
            rest.keys.none(Ranks::isJoker) -> rest.size == 1
            !settings.jokerPairInTripleWithPair -> false
            rest.size == 1 -> true
            else -> settings.jokerBigSmallIsPair && rest.keys == JOKER_PAIR
        }
        return if (isPair) tripleWithPair(triple, pair) else null
    }

    /** R2.8, R5.1, R5.5 (D9), R7.11 (D9). Wildcards here carry no suit. */
    private fun straightReadings(
        physical: List<Card>,
        assigned: List<WildcardAssignment>,
        counts: Map<Int, Int>,
        out: MutableList<Combination>,
    ) {
        val window = windowOf(counts.keys, 5) ?: return
        val suits = physical.map { it.suit }.toSet()
        val flushShape = suits.size == 1 && Suit.JOKER !in suits
        if (flushShape && assigned.isEmpty()) out += sequence(CombinationType.STRAIGHT_FLUSH, window, suits.single())
        val asStraight = when {
            !flushShape -> true
            assigned.isEmpty() -> settings.straightFlushPlayableAsStraight
            else -> settings.wildcardFlushRunAsStraight
        }
        if (asStraight) out += sequence(CombinationType.STRAIGHT, window)
    }

    /** A wildcard declared with a suit: only a straight flush in that one suit is possible. */
    private fun suitedReadings(physical: List<Card>, assigned: List<WildcardAssignment>): List<Combination> {
        if (physical.size + assigned.size != 5 || assigned.any { it.suit == null }) return emptyList()
        val suits = (physical.map { it.suit } + assigned.map { it.suit }).toSet()
        val suit = suits.singleOrNull() ?: return emptyList()
        if (suit == Suit.JOKER) return emptyList()
        val window = windowOf((physical.map { it.rank } + assigned.map { it.rank }).toSet(), 5) ?: return emptyList()
        if (window.any(Ranks::isJoker)) return emptyList()
        return listOf(sequence(CombinationType.STRAIGHT_FLUSH, window, suit))
    }

    private companion object {
        val JOKER_PAIR = setOf(Ranks.SMALL_JOKER, Ranks.BIG_JOKER)
        val FOUR_JOKERS = mapOf(Ranks.SMALL_JOKER to 2, Ranks.BIG_JOKER to 2)
    }
}
