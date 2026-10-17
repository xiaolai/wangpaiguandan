package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.fixtureDeck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brute-force oracle: on seeded hands of at most 8 cards, and on a fixed list of seeds with 9- and
 * 10-card hands (review F12: reaches 9- and 10-card bombs), enumerate every subset, read it with the
 * classifier, and require the generator to produce exactly the same actions, compared as
 * face multiset + declaration + combination.
 */
class LegalActionOracleTest {
    private val profiles = listOf(
        "P1" to Profiles.p1,
        "all-flipped" to Profiles.with(
            "bomb.maxSize" to "6",
            "sequence.wrapAroundA" to "true",
            "sequence.jokersAllowed" to "true",
            "joker.bigSmallIsPair" to "true",
            "joker.pairInTripleWithPair" to "false",
            "tripleWithPair.sameRankPair" to "true",
            "straightFlush.playableAsStraight" to "true",
            "wildcard.flushRunAsStraight" to "false",
            "wildcard.alwaysDeclared" to "false",
            "wildcard.aloneMayStandIn" to "true",
            "sequence.aceLowRanksLowest" to "false",
            "sequence.wrapRanksByLastCard" to "false",
            "sequence.jokersWrap" to "true",
            "joker.bigSmallPairRanksAsSmall" to "false",
            "wildcard.ambiguousNeedsDeclaration" to "false",
            "wildcard.straySuitRejected" to "false",
        ),
        "min5-nolimit" to Profiles.with("bomb.minSize" to "5", "bomb.maxSize" to "null", "joker.pairInTripleWithPair" to "true"),
    )

    @Test fun generatorEqualsSubsetOracleOnSeededSmallHands() {
        var hands = 0
        var actions = 0
        val typesSeen = HashSet<CombinationType>()
        var wildActions = 0
        var undeclared = 0
        var follows = 0
        for ((name, profile) in profiles) {
            val random = Random(20261014 + name.hashCode())
            repeat(HANDS_PER_PROFILE) { i ->
                val level = random.nextInt(2, 15)
                val hand = randomHand(random, level, 1..MAX_HAND)
                val e = Engine(profile, level)
                val trick = if (random.nextBoolean()) null else randomTrick(random, e)
                val got = assertGeneratorEqualsOracle(e, hand, trick, "$name #$i")
                hands++
                actions += got.size
                if (trick != null) follows++
                typesSeen += got.map { it.combination.type }
                wildActions += got.count { k -> k.faces.any { it == "H" + Notation.label(level) } }
                undeclared += got.count { it.declaration == null }
            }
        }
        println("ORACLE hands=$hands follows=$follows actions=$actions wildActions=$wildActions undeclared=$undeclared types=$typesSeen")
        // Green means nothing unless the seeded hands reached every shape and the wildcard paths.
        assertEquals(CombinationType.entries.toSet(), typesSeen, "oracle hands did not reach every combination type")
        assertTrue(wildActions > 100 && undeclared > 0 && follows > 50, "oracle coverage too thin")
    }

    /**
     * F12: 9- and 10-card hands on fixed seeds. Every other hand is bomb-heavy (seven or eight physical
     * cards of one rank plus wildcards), so the 9- and 10-card bomb paths are actually reached.
     */
    @Test fun generatorEqualsSubsetOracleOnNineAndTenCardHands() {
        val bombSizes = HashSet<Int>()
        var hands = 0
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        for ((name, profile) in profiles) for (seed in LARGE_SEEDS) {
            val random = Random(seed)
            val level = random.nextInt(2, 15)
            val e = Engine(profile, level)
            val hand = if (seed % 2 == 0) bombHeavyHand(random, level) else randomHand(random, level, 9..LARGE_HAND)
            check(hand.size in 9..LARGE_HAND) { "seed $seed built a ${hand.size}-card hand" }
            val trick = if (random.nextBoolean()) null else randomTrick(random, e)
            val got = assertGeneratorEqualsOracle(e, hand, trick, "$name seed=$seed")
            bombSizes += got.mapNotNull { it.combination.bombSize }
            hands++
        }
        println("ORACLE10 hands=$hands bombSizes=${bombSizes.sorted()} ms=${started.elapsedNow().inWholeMilliseconds}")
        assertTrue(9 in bombSizes && 10 in bombSizes, "large-hand seeds never reached a 9- or 10-card bomb: ${bombSizes.sorted()}")
    }

    private fun assertGeneratorEqualsOracle(e: Engine, hand: List<Card>, trick: Combination?, label: String): List<ActionKey> {
        val expected = oracle(e, hand, trick)
        val got = e.generator.generate(hand, trick).map { it.key() }
        assertEquals(got.size, got.toSet().size, "$label duplicate actions for ${hand.map(Notation::code)}")
        if (expected != got.toSet()) {
            val missing = expected - got.toSet()
            val extra = got.toSet() - expected
            throw AssertionError("$label level=${e.level} hand=${hand.map(Notation::code)} trick=$trick\nmissing=$missing\nextra=$extra")
        }
        return got
    }

    /** Seven or eight physical cards of one non-level rank, one or two wildcards, padded to 9 or 10 cards. */
    private fun bombHeavyHand(random: Random, level: Int): List<Card> {
        val deck = fixtureDeck()
        val rank = (2..14).filter { it != level }.random(random)
        val core = deck.filter { it.rank == rank }.shuffled(random).take(random.nextInt(7, 9)) +
            deck.filter { it.isWildcard(level) }.take(random.nextInt(1, 3))
        val size = random.nextInt(9, LARGE_HAND + 1)
        return core + deck.filter { it.rank != rank && !it.isWildcard(level) }.shuffled(random).take(maxOf(0, size - core.size))
    }

    /** Every distinct face multiset among the subsets, read independently of the generator. */
    private fun oracle(e: Engine, hand: List<Card>, trick: Combination?): Set<ActionKey> {
        val out = HashSet<ActionKey>()
        val seen = HashSet<List<String>>()
        for (mask in 1 until (1 shl hand.size)) {
            val subset = hand.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val faces = subset.map(Notation::code).sorted()
            if (!seen.add(faces)) continue
            val readings = e.classifier.readings(subset)
            val hasWild = subset.any { it.isWildcard(e.level) }
            val perType = readings.groupingBy { it.combination.type }.eachCount()
            for (r in readings) {
                if (trick != null && !r.combination.beats(trick)) continue
                // R7.7 / D15: no declaration when the cards, or (D15 off) the cards and the stated type, name one reading.
                val omit = hasWild && !e.settings.wildcardAlwaysDeclared &&
                    (readings.size == 1 || (!e.settings.wildcardAmbiguousNeedsDeclaration && perType.getValue(r.combination.type) == 1))
                out += ActionKey(faces, if (omit) null else r.declaration, r.combination)
            }
        }
        return out
    }

    /** Mix of uniform hands and dense hands (narrow rank band, wildcards, jokers) so sequences occur. */
    private fun randomHand(random: Random, level: Int, sizes: IntRange): List<Card> {
        val deck = fixtureDeck()
        val size = random.nextInt(sizes.first, sizes.last + 1)
        val pool = if (random.nextBoolean()) deck else {
            val low = random.nextInt(2, 11)
            deck.filter { it.rank in low..low + 4 || it.isWildcard(level) || (it.suit == Suit.JOKER && random.nextInt(4) == 0) }
        }
        val wild = deck.filter { it.isWildcard(level) }
        val jokers = deck.filter { it.suit == Suit.JOKER }
        val forced = (if (random.nextInt(6) == 0) jokers.shuffled(random).take(random.nextInt(2, 5)) else emptyList()) +
            (if (random.nextInt(3) == 0) wild.take(random.nextInt(1, 3)) else emptyList())
        val rest = pool.filter { it !in forced }.shuffled(random).take(maxOf(0, size - forced.size))
        return (forced + rest).take(size)
    }

    private fun randomTrick(random: Random, e: Engine): Combination? {
        val source = randomHand(random, e.level, 1..MAX_HAND)
        val readings = (1 until (1 shl source.size)).flatMap { mask ->
            e.classifier.readings(source.filterIndexed { i, _ -> mask and (1 shl i) != 0 }).map { it.combination }
        }
        return readings.randomOrNull(random)
    }

    private companion object {
        const val MAX_HAND = 8
        const val HANDS_PER_PROFILE = 150
        const val LARGE_HAND = 10
        /** Fixed seeds for the 9- and 10-card run; 2^10 subsets per hand. */
        val LARGE_SEEDS = (1..64).map { 1014 + it }
    }
}
