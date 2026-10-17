package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Suit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * R7.2–R7.11 and decision D6/D7 defaults under P1, plus audit regressions A05, A06, A07 and A32
 * (the upstream docs promised a wildcard could be any card; here it is never a joker, R7.3).
 */
class WildcardDeclarationTest {
    private val e = engine(level = 2)

    private fun allCardReadings(codes: String): Set<Pair<List<WildcardAssignment>?, Combination>> {
        val hand = Notation.cards(codes)
        return e.generator.generate(hand, null).filter { it.cards.size == hand.size }.map { it.declaration to it.combination }.toSet()
    }

    @Test fun twoValidInterpretationsAreBothOffered() {
        val got = allCardReadings("SK DK S6 D6 H2")
        assertEquals(
            setOf(
                Notation.declaration("K") to e.combo("SK DK S6 D6 H2", "K"),
                Notation.declaration("6") to e.combo("SK DK S6 D6 H2", "6"),
            ),
            got,
        )
        assertEquals(13, e.combo("SK DK S6 D6 H2", "K").rank)
        assertEquals(6, e.combo("SK DK S6 D6 H2", "6").rank)
    }

    @Test fun wildcardAtEitherEndOfAStraight() {
        val got = allCardReadings("D4 C5 S6 D7 H2").map { it.second.sequence }.toSet()
        assertEquals(setOf(listOf(3, 4, 5, 6, 7), listOf(4, 5, 6, 7, 8)), got)
    }

    @Test fun forgedDeclarationsAreRejected() {
        fun reason(codes: String, vararg decl: String) = (e.judge(codes, *decl) as? Judgment.Rejected)?.reason
        assertEquals(RejectReason.DECLARED_READING_ILLEGAL, reason("S6 D6 C6 H2", "A"))
        assertEquals(RejectReason.DECLARED_READING_ILLEGAL, reason("D4 C5 S6 D8 H2", "9"))
        assertEquals(RejectReason.DECLARED_READING_ILLEGAL, reason("S6 D6 C6 H2", "6C"), "a suit outside a straight flush")
        assertEquals(RejectReason.DECLARED_READING_ILLEGAL, reason("D7 D8 D9 DJ H2", "10S"), "a suit that breaks the flush")
        assertEquals(RejectReason.DECLARATION_MISMATCH, reason("S6 D6 H2 H2", "6"))
        assertEquals(RejectReason.DECLARATION_MISMATCH, reason("S6 D6", "6"))
        assertEquals(RejectReason.MISSING_DECLARATION, reason("S6 D6 C6 H2"))
        assertEquals(RejectReason.LONE_WILDCARD_REDECLARED, reason("H2", "3"))
        assertEquals(RejectReason.LONE_WILDCARD_REDECLARED, reason("H2 H2", "3", "3"))
    }

    @Test fun threeWildcardsAreRejectedTypedAtEveryEntryPoint() {
        val three = "H2 H2 H2 S7"
        val loose = engine(2, Profiles.with("wildcard.alwaysDeclared" to "false"))
        assertEquals(RejectReason.TOO_MANY_WILDCARDS, (e.judge(three, "7", "7", "7") as Judgment.Rejected).reason)
        assertEquals(RejectReason.TOO_MANY_WILDCARDS, (loose.judge(three) as Judgment.Rejected).reason)
        assertEquals(RejectReason.TOO_MANY_WILDCARDS, assertFailsWith<ImpossibleCardsException> { e.classifier.readings(Notation.cards(three)) }.reason)
        assertEquals(RejectReason.TOO_MANY_WILDCARDS, assertFailsWith<ImpossibleCardsException> { e.generator.generate(Notation.cards(three), null) }.reason)
        // Two wildcards remain fine everywhere.
        assertIs<Judgment.Readable>(e.judge("H2 H2 S7 D7", "7", "7"))
        assertTrue(e.generator.generate(Notation.cards("H2 H2 S7 D7"), null).isNotEmpty())
    }

    @Test fun duplicateCardIsRejectedTypedAtEveryEntryPoint() {
        val s7 = Notation.card("S7")
        val twice = listOf(s7, s7)
        assertEquals(Judgment.Rejected(RejectReason.DUPLICATE_CARD), e.classifier.judge(twice, null, null))
        assertEquals(RejectReason.DUPLICATE_CARD, assertFailsWith<ImpossibleCardsException> { e.classifier.readings(twice) }.reason)
        assertEquals(RejectReason.DUPLICATE_CARD, assertFailsWith<ImpossibleCardsException> { e.generator.generate(twice, null) }.reason)
        // Two copies of the same face are two physical cards, not a duplicate.
        assertIs<Judgment.Readable>(e.judge("S7 S7"))
    }

    /** R1.1: a deal holds two copies of each face, so three distinct ids of one face are impossible. */
    @Test fun aThirdCopyOfAFaceIsRejectedTypedAtEveryEntryPoint() {
        for (three in listOf("S6 S6 S6", "BJ BJ BJ")) {
            val cards = Notation.cards(three)
            assertEquals(Judgment.Rejected(RejectReason.TOO_MANY_COPIES), e.classifier.judge(cards, null, null), three)
            assertEquals(RejectReason.TOO_MANY_COPIES, assertFailsWith<ImpossibleCardsException> { e.classifier.readings(cards) }.reason)
            assertEquals(RejectReason.TOO_MANY_COPIES, assertFailsWith<ImpossibleCardsException> { e.generator.generate(cards, null) }.reason)
        }
        assertIs<Judgment.Readable>(e.judge("S6 S6 D6"))
    }

    @Test fun jokersCannotBeSubstituted() {
        assertEquals(RejectReason.WILDCARD_AS_JOKER, (e.judge("BJ H2", "BJ") as Judgment.Rejected).reason)
        assertEquals(RejectReason.WILDCARD_AS_JOKER, (e.judge("LJ LJ BJ H2", "BJ") as Judgment.Rejected).reason)
        val actions = e.generator.generate(Notation.cards("LJ LJ BJ H2 H2 S5"), null)
        assertTrue(actions.none { it.combination.type == CombinationType.FOUR_JOKERS })
        assertTrue(actions.flatMap { it.declaration.orEmpty() }.none { Ranks.isJoker(it.rank) })
        assertTrue(actions.none { a -> a.combination.type == CombinationType.PAIR && a.cards.any { it.suit == Suit.JOKER } && a.cards.any { it.isWildcard(2) } })
    }

    @Test fun straightFlushDeclarationCarriesSuitAndPlainStraightDoesNot() {
        val got = allCardReadings("D7 D8 D9 DJ H2")
        val sf = got.single { it.second.type == CombinationType.STRAIGHT_FLUSH }
        assertEquals(listOf(WildcardAssignment(10, Suit.DIAMOND)), sf.first)
        val st = got.single { it.second.type == CombinationType.STRAIGHT }
        assertEquals(listOf(WildcardAssignment(10)), st.first)
    }

    @Test fun a05ConcreteCardsRanksAndSuitsAreRetained() {
        val hand = Notation.cards("S7 H2 D9")
        for (a in e.generator.generate(hand, null)) {
            assertTrue(a.cards.all { c -> hand.any { it === c } }, "action cards must be the hand's own Card objects")
        }
        val pair = e.generator.generate(hand, null).single { it.combination.type == CombinationType.PAIR && it.combination.rank == 7 }
        assertEquals(listOf("H2", "S7"), pair.cards.map(Notation::code).sorted())
        assertEquals(Notation.declaration("7"), pair.declaration)
    }

    @Test fun a06HeartTwoPlusSevenBeatsPairOfSixes() {
        val trick = e.combo("S6 D6")
        val hand = padTo27(Notation.cards("S7 H2")) { it.rank in setOf(2, 6, 7) || it.suit == Suit.JOKER }
        val actions = e.generator.generate(hand, trick)
        assertTrue(actions.any { a -> a.combination.type == CombinationType.PAIR && a.combination.rank == 7 && a.declaration == Notation.declaration("7") })
        val j = e.judge("S7 H2", "7", table = trick)
        assertIs<Judgment.Readable>(j)
        assertTrue(j.legal)
    }

    @Test fun a07FourSevensBombASingleAce() {
        val trick = e.combo("SA")
        val hand = padTo27(Notation.cards("S7 D7 C7 H7")) { it.rank == 7 || it.rank == 2 }
        val actions = e.generator.generate(hand, trick)
        assertTrue(actions.any { it.combination.type == CombinationType.BOMB && it.combination.rank == 7 && it.combination.size == 4 })
    }
}
