package cn.wpgd.app.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GDN-055 ("targeted 27-card fixtures cover all types"): on full 27-card hands at level 2, the
 * generator offers every combination type of base-v1 (R2–R6), each offered action is one the
 * classifier itself accepts under the declared reading, and nothing is offered twice. The subset
 * oracle (`LegalActionOracleTest`) cannot run at 27 cards; the bomb oracle (`LargeBombCountOracleTest`)
 * covers only bombs. This closes the gap for the other types.
 */
class Generator27CardFixtureTest {
    private val e = engine(level = 2)

    /** No wildcard. Every type is present naturally: S3–S7 straight flush, 9-9-9-10-10-10 plate, J-Q-K tube, 8888 bomb, four jokers. */
    private val natural = "S3 S4 S5 S6 S7 D9 C9 H9 D10 C10 H10 DJ CJ DQ CQ DK CK S8 H8 D8 C8 LJ LJ BJ BJ DA CA"

    /** The same hand with the two aces exchanged for both heart-two wildcards. */
    private val wild = "S3 S4 S5 S6 S7 D9 C9 H9 D10 C10 H10 DJ CJ DQ CQ DK CK S8 H8 D8 C8 LJ LJ BJ BJ H2 H2"

    @Test fun aNaturalHandLeadsEveryType() {
        val hand = Notation.cards(natural)
        assertEquals(27, hand.size)
        assertEquals(0, hand.count { it.isWildcard(2) })
        val actions = e.generator.generate(hand, null)
        assertEquals(CombinationType.entries.toSet(), actions.map { it.combination.type }.toSet())
        assertConsistent(actions, null)
    }

    @Test fun bothWildcardsTakePartInEveryTypeButFourJokers() {
        val hand = Notation.cards(wild)
        assertEquals(2, hand.count { it.isWildcard(2) })
        val actions = e.generator.generate(hand, null)
        assertEquals(CombinationType.entries.toSet(), actions.map { it.combination.type }.toSet())
        val withWildcard = actions.filter { a -> a.cards.any { it.isWildcard(2) } }.map { it.combination.type }.toSet()
        // R6.3, R7.3: a wildcard never stands in for a joker, so four jokers is the one type it cannot join.
        assertEquals(CombinationType.entries.toSet() - CombinationType.FOUR_JOKERS, withWildcard)
        assertTrue(actions.filter { it.combination.type == CombinationType.FOUR_JOKERS }.none { a -> a.cards.any { it.isWildcard(2) } })
        assertConsistent(actions, null)
    }

    @Test fun followingTheLowestLeadOfEachTypeOffersOnlyBeatingPlays() {
        val leads = e.generator.generate(Notation.cards(natural), null)
        val follower = Notation.cards(wild)
        for (type in CombinationType.entries) {
            val table = leads.filter { it.combination.type == type }.map { it.combination }.minWith(compareBy({ it.size }, { it.key }))
            val follows = e.generator.generate(follower, table)
            assertConsistent(follows, table)
            assertTrue(follows.all { it.combination.beats(table) }, "a follow of $table does not beat it")
            // The follower holds four jokers, the top bomb (R6.1), so it can always answer.
            assertTrue(follows.any { it.combination.type == CombinationType.FOUR_JOKERS } || type == CombinationType.FOUR_JOKERS, "no answer to $table")
            if (!type.isBombClass) assertTrue(follows.any { it.combination.type == type }, "no same-type answer to $table")
        }
    }

    /** Each action is accepted by the classifier as exactly the combination offered, and is offered once. */
    private fun assertConsistent(actions: List<LegalAction>, table: Combination?) {
        val keys = actions.map { it.key() }
        assertEquals(keys.size, keys.toSet().size, "an action is offered twice")
        for (a in actions) {
            val j = e.classifier.judge(a.cards, a.declaration, table)
            assertTrue(j is Judgment.Readable && a.combination in j.beating, "classifier refuses ${a.key()}: $j")
        }
    }
}
