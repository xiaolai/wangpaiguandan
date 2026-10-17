package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit

/**
 * Enumerates every legal play from a hand, each with its declared interpretation (R7.8: one action
 * per distinct reading; R7.10: suit variants of a wildcard only in straight flushes).
 *
 * Algorithm: never enumerates card subsets. The hand is reduced to counts per face (rank × suit),
 * joker counts and a wildcard count. Each combination shape is a list of (rank, cards needed)
 * requirements; for each requirement the generator chooses how many wildcards fill it, then
 * which suits supply the rest, using the counts. Each choice is then expanded to concrete cards:
 * the copies of each face with the lowest card ids (the two copies of an exact card are
 * interchangeable), so the actions and their card ids do not depend on the order of the hand.
 * Cost is proportional to the number of distinct actions, not to 2^27.
 */
class LegalActionGenerator(settings: RuleSettings, val level: Int) {
    private val shapes = Shapes(settings, level)
    private val settings = shapes.settings

    /**
     * Every legal action from [hand]; when [trick] is not null, only those that beat it. Passing is not an action.
     * Throws [ImpossibleCardsException] for a hand no deal can produce.
     */
    fun generate(hand: List<Card>, trick: Combination?): List<LegalAction> {
        impossibleCards(hand, level)?.let { throw ImpossibleCardsException(it) }
        val run = Run(Inventory(hand, level))
        run.enumerate()
        var actions: List<LegalAction> = run.actions
        if (!settings.wildcardAlwaysDeclared) actions = omitUnneededDeclarations(actions)
        return if (trick == null) actions else actions.filter { it.combination.beats(trick) }
    }

    /**
     * `wildcard.alwaysDeclared` false: a wildcard play whose cards have exactly one reading needs no declaration (R7.7);
     * with `wildcard.ambiguousNeedsDeclaration` false (D15), neither does one with several readings, as long as
     * its combination type tells them apart. Two readings of the same type keep their declarations: without one,
     * the stated type alone could not say which reading is meant and the reducer would refuse the play.
     */
    private fun omitUnneededDeclarations(actions: List<LegalAction>): List<LegalAction> {
        val keys = actions.map { faceKey(it.cards) }
        val readingsPerFaces = keys.groupingBy { it }.eachCount()
        val readingsPerFacesAndType = actions.indices.groupingBy { keys[it] to actions[it].combination.type }.eachCount()
        return actions.mapIndexed { i, a ->
            val ambiguous = readingsPerFaces.getValue(keys[i]) > 1
            val sameTypeAmbiguous = readingsPerFacesAndType.getValue(keys[i] to a.combination.type) > 1
            val keep = a.declaration.isNullOrEmpty() || sameTypeAmbiguous || (ambiguous && settings.wildcardAmbiguousNeedsDeclaration)
            if (keep) a else a.copy(declaration = null)
        }
    }

    private fun faceKey(cards: List<Card>): List<Int> = cards.map { it.rank * SUITS + it.suit.ordinal }.sorted()

    /**
     * The hand as counts. Wildcards are counted apart from the physical level cards of other suits.
     * Every card list is in card-id order, which is what makes [Run.emit] independent of hand order.
     */
    private class Inventory(unordered: List<Card>, level: Int) {
        private val hand = unordered.sortedBy { it.id }
        val wild: List<Card> = hand.filter { it.isWildcard(level) }
        val jokers: Map<Int, List<Card>> = hand.filter { it.suit == Suit.JOKER }.groupBy { it.rank }
        /** Physical non-joker, non-wildcard cards by face index. */
        val byFace: Array<List<Card>> = Array(FACES) { emptyList() }
        val rankTotal = IntArray(Ranks.ACE + 1)

        init {
            check(wild.size <= WILDCARDS_PER_DEAL) { "generate() admits at most $WILDCARDS_PER_DEAL wildcards (R1.8)" }
            val grouped = hand.filter { it.suit != Suit.JOKER && !it.isWildcard(level) }.groupBy { face(it.rank, it.suit) }
            for ((f, cards) in grouped) byFace[f] = cards
            for ((f, cards) in grouped) rankTotal[f / SUITS] += cards.size
        }

        fun avail(rank: Int, suit: Suit): Int = byFace[face(rank, suit)].size
        fun jokers(rank: Int): Int = jokers[rank]?.size ?: 0
    }

    /** One enumeration over one hand. Holds the current partial choice as mutable counts. */
    private inner class Run(private val inv: Inventory) {
        val actions = ArrayList<LegalAction>()
        private val used = IntArray(FACES)
        private val usedJokers = IntArray(2)
        private val wildStack = ArrayList<WildcardAssignment>(2)

        fun enumerate() {
            val w = inv.wild.size
            for (r in Ranks.NATURAL) {
                emitAll(listOf(r to 1)) { shapes.single(r) }
                emitAll(listOf(r to 2)) { shapes.pair(listOf(r, r)) }
                emitAll(listOf(r to 3)) { shapes.triple(r) }
                var n = 4
                while (n <= inv.rankTotal[r] + w) {
                    val size = n
                    if (settings.allowsBombSize(size)) emitAll(listOf(r to size)) { shapes.bomb(r, size) }
                    n++
                }
                tripleWithPairs(r)
            }
            for (j in listOf(Ranks.SMALL_JOKER, Ranks.BIG_JOKER)) {
                emitAll(listOf(j to 1)) { shapes.single(j) }
                emitAll(listOf(j to 2)) { shapes.pair(listOf(j, j)) }
            }
            if (settings.jokerBigSmallIsPair) emitAll(MIXED_JOKERS) { shapes.pair(listOf(Ranks.SMALL_JOKER, Ranks.BIG_JOKER)) }
            emitAll(listOf(Ranks.SMALL_JOKER to 2, Ranks.BIG_JOKER to 2)) { shapes.fourJokers() }

            for (window in shapes.windows.getValue(5)) {
                emitAll(window.map { it to 1 }, ::straightAllowed) { shapes.sequence(CombinationType.STRAIGHT, window) }
                if (window.none(Ranks::isJoker)) for (suit in CARD_SUITS) straightFlushes(window, suit)
            }
            for (window in shapes.windows.getValue(3)) emitAll(window.map { it to 2 }) { shapes.sequence(CombinationType.CONSECUTIVE_PAIRS, window) }
            for (window in shapes.windows.getValue(2)) emitAll(window.map { it to 3 }) { shapes.sequence(CombinationType.CONSECUTIVE_TRIPLES, window) }
        }

        private fun tripleWithPairs(t: Int) {
            for (p in Ranks.NATURAL) if (p != t) emitAll(listOf(t to 3, p to 2)) { shapes.tripleWithPair(t, listOf(p, p)) }
            if (settings.tripleWithPairSameRankPair) emitAll(listOf(t to 5)) { shapes.tripleWithPair(t, listOf(t, t)) }
            if (settings.jokerPairInTripleWithPair) {
                for (j in listOf(Ranks.SMALL_JOKER, Ranks.BIG_JOKER)) emitAll(listOf(t to 3, j to 2)) { shapes.tripleWithPair(t, listOf(j, j)) }
                if (settings.jokerBigSmallIsPair) {
                    emitAll(listOf(t to 3) + MIXED_JOKERS) { shapes.tripleWithPair(t, listOf(Ranks.SMALL_JOKER, Ranks.BIG_JOKER)) }
                }
            }
        }

        /** R5.5 and R7.11 (D9): a straight whose physical cards share one suit. */
        private fun straightAllowed(): Boolean {
            if (usedJokers.any { it > 0 }) return true
            var suit = -1
            for (f in 0 until FACES) if (used[f] > 0) {
                val s = f % SUITS
                if (suit == -1) suit = s else if (suit != s) return true
            }
            return if (wildStack.isEmpty()) settings.straightFlushPlayableAsStraight else settings.wildcardFlushRunAsStraight
        }

        /**
         * Fills every requirement (rank, count), then emits. For a natural rank, k wildcards (declared
         * as that rank) plus count−k physical cards spread over suits; jokers are physical only (R6.3).
         */
        private fun emitAll(reqs: List<Pair<Int, Int>>, accept: () -> Boolean = { true }, combination: () -> Combination) {
            var combo: Combination? = null
            fill(reqs, 0) {
                if (accept() && loneWildcardsAllowed()) {
                    val c = combo ?: combination().also { combo = it }
                    emit(c)
                }
            }
        }

        private fun fill(reqs: List<Pair<Int, Int>>, i: Int, done: () -> Unit) {
            if (i == reqs.size) return done()
            val (rank, need) = reqs[i]
            if (Ranks.isJoker(rank)) {
                val j = rank - Ranks.SMALL_JOKER
                if (inv.jokers(rank) - usedJokers[j] < need) return
                usedJokers[j] += need
                fill(reqs, i + 1, done)
                usedJokers[j] -= need
                return
            }
            val wildLeft = inv.wild.size - wildStack.size
            val physical = CARD_SUITS.sumOf { inv.avail(rank, it) - used[face(rank, it)] }
            for (k in maxOf(0, need - physical)..minOf(need, wildLeft)) {
                repeat(k) { wildStack += WildcardAssignment(rank) }
                spreadOverSuits(rank, need - k, 0) { fill(reqs, i + 1, done) }
                repeat(k) { wildStack.removeAt(wildStack.lastIndex) }
            }
        }

        /** Every way to take [m] physical cards of [rank] from suits [s] onward. */
        private fun spreadOverSuits(rank: Int, m: Int, s: Int, done: () -> Unit) {
            if (m == 0) return done()
            if (s == SUITS) return
            val f = face(rank, CARD_SUITS[s])
            val free = inv.byFace[f].size - used[f]
            for (c in minOf(m, free) downTo 0) {
                used[f] += c
                spreadOverSuits(rank, m - c, s + 1, done)
                used[f] -= c
            }
        }

        /** Straight flushes: each position is the physical card of that suit, or a wildcard declared in that suit. */
        private fun straightFlushes(window: List<Int>, suit: Suit) {
            val combo = shapes.sequence(CombinationType.STRAIGHT_FLUSH, window, suit)
            fun go(i: Int) {
                if (i == window.size) return emit(combo)
                val f = face(window[i], suit)
                if (inv.byFace[f].size > used[f]) {
                    used[f]++; go(i + 1); used[f]--
                }
                if (wildStack.size < inv.wild.size) {
                    wildStack += WildcardAssignment(window[i], suit); go(i + 1); wildStack.removeAt(wildStack.lastIndex)
                }
            }
            go(0)
        }

        /** R7.9 / D7: wildcards played with nothing else count as level cards unless the profile allows otherwise. */
        private fun loneWildcardsAllowed(): Boolean {
            if (wildStack.isEmpty() || settings.wildcardAloneMayStandIn) return true
            if (usedJokers.any { it > 0 } || used.any { it > 0 }) return true
            return wildStack.all { it.rank == level }
        }

        private fun emit(combination: Combination) {
            val cards = ArrayList<Card>(combination.size)
            for (f in 0 until FACES) if (used[f] > 0) cards += inv.byFace[f].subList(0, used[f])
            for (j in 0..1) if (usedJokers[j] > 0) cards += inv.jokers.getValue(Ranks.SMALL_JOKER + j).subList(0, usedJokers[j])
            cards += inv.wild.subList(0, wildStack.size)
            actions += LegalAction(cards, wildStack.toList().canonical(), combination)
        }
    }

    private companion object {
        const val SUITS = 4
        val CARD_SUITS = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
        const val FACES = (Ranks.ACE + 1) * SUITS
        val MIXED_JOKERS = listOf(Ranks.SMALL_JOKER to 1, Ranks.BIG_JOKER to 1)

        fun face(rank: Int, suit: Suit): Int = rank * SUITS + CARD_SUITS.indexOf(suit)
    }
}
