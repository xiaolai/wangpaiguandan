package cn.wpgd.app.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every registered option is read from the profile and changes behaviour when flipped. */
class RuleOptionBehaviourTest {
    private fun readings(level: Int, profile: RuleProfile, codes: String) =
        Engine(profile, level).classifier.readings(Notation.cards(codes)).map { it.combination }.toSet()

    private fun actions(level: Int, profile: RuleProfile, codes: String) =
        Engine(profile, level).generator.generate(Notation.cards(codes), null).map { it.key() }.toSet()

    @Test fun everyActiveOptionReachesRuleSettings() {
        val base = RuleSettings.of(Profiles.p1)
        val flipped = mapOf(
            "bomb.minSize" to "5", "bomb.maxSize" to "8", "sequence.wrapAroundA" to "true",
            "sequence.jokersAllowed" to "true", "joker.bigSmallIsPair" to "true",
            "joker.pairInTripleWithPair" to "false", "tripleWithPair.sameRankPair" to "true",
            "straightFlush.playableAsStraight" to "true", "wildcard.flushRunAsStraight" to "false",
            "wildcard.alwaysDeclared" to "false", "wildcard.aloneMayStandIn" to "true",
            "sequence.aceLowRanksLowest" to "false", "sequence.wrapRanksByLastCard" to "false",
            "sequence.jokersWrap" to "true", "joker.bigSmallPairRanksAsSmall" to "false",
            "wildcard.ambiguousNeedsDeclaration" to "false", "wildcard.straySuitRejected" to "false",
        )
        assertEquals(RuleOptions.registry.active.map { it.id }.toSet(), flipped.keys, "a new option needs a flip here")
        for ((id, value) in flipped) assertNotEquals(base, RuleSettings.of(Profiles.with(id to value)), id)
    }

    @Test fun bombSizeLimits() {
        assertTrue(readings(9, Profiles.p1, "S6 D6 C6 H6").isNotEmpty())
        assertTrue(readings(9, Profiles.with("bomb.minSize" to "5"), "S6 D6 C6 H6").isEmpty())
        val ten = "S7 S7 D7 D7 C7 C7 H7 H7 H2 H2"
        assertTrue(readings(2, Profiles.p1, ten).any { it.bombSize == 10 })
        assertTrue(readings(2, Profiles.with("bomb.maxSize" to "8"), ten).none { it.type == CombinationType.BOMB })
        // null = no limit: the card set already caps a bomb at 10, so it reads like the default of 10.
        assertEquals(readings(2, Profiles.p1, ten), readings(2, Profiles.with("bomb.maxSize" to "null"), ten))
        assertNotEquals(actions(2, Profiles.p1, ten), actions(2, Profiles.with("bomb.maxSize" to "8"), ten))
    }

    @Test fun sequenceOptions() {
        assertTrue(readings(9, Profiles.p1, "SK DA C2 S3 D4").isEmpty())
        assertEquals(listOf(13, 14, 2, 3, 4), readings(9, Profiles.with("sequence.wrapAroundA" to "true"), "SK DA C2 S3 D4").single().sequence)
        assertTrue(readings(9, Profiles.p1, "SJ DQ CK SA LJ").isEmpty())
        assertEquals(
            listOf(11, 12, 13, 14, Ranks.SMALL_JOKER),
            readings(9, Profiles.with("sequence.jokersAllowed" to "true"), "SJ DQ CK SA LJ").single().sequence,
        )
        val hand = "SK DA C2 S3 D4 SJ DQ"
        assertNotEquals(actions(9, Profiles.p1, hand), actions(9, Profiles.with("sequence.wrapAroundA" to "true"), hand))
    }

    /** A run that passes through A into 2 with A not first: the shapes R3.3 and R3.4 forbid. */
    private fun wraps(seq: List<Int>): Boolean {
        val ace = seq.indexOf(Ranks.ACE)
        return ace in 1 until seq.lastIndex && seq[ace + 1] == 2
    }

    private val wrapHand = "SJ SQ SK SA S2 S3 S4 DJ DQ DK DA D2 D3 D4"

    @Test fun wrapAroundOffForbidsJQKA2AndKKAA22AtEveryLevel() {
        for (level in Ranks.NATURAL) {
            val e = Engine(Profiles.p1, level)
            for (codes in listOf("SJ DQ CK SA D2", "SK DK SA DA S2 D2", "SQ DK CA S2 D3", "SK DA C2 S3 D4")) {
                assertTrue(e.classifier.readings(Notation.cards(codes)).isEmpty(), "level $level: $codes")
                assertEquals(RejectReason.NO_COMBINATION, (e.judge(codes) as Judgment.Rejected).reason, "level $level: $codes")
            }
            val sequences = e.generator.generate(Notation.cards(wrapHand), null).map { it.combination.sequence }
            assertTrue(sequences.none(::wraps), "level $level")
        }
    }

    @Test fun wrapAroundOnAddsExactlyTheWrapShapes() {
        val on = Profiles.with("sequence.wrapAroundA" to "true")
        val wrapWindows = listOf(listOf(11, 12, 13, 14, 2), listOf(12, 13, 14, 2, 3), listOf(13, 14, 2, 3, 4))
        val expected = wrapWindows.flatMap { w -> listOf(CombinationType.STRAIGHT to w, CombinationType.STRAIGHT_FLUSH to w) } +
            (CombinationType.CONSECUTIVE_PAIRS to listOf(13, 14, 2))
        for (level in Ranks.NATURAL) {
            val off = actions(level, Profiles.p1, wrapHand)
            val added = actions(level, on, wrapHand)
            assertTrue(added.containsAll(off), "level $level: wrap on removed an action")
            assertEquals(expected.toSet(), (added - off).map { it.combination.type to it.combination.sequence }.toSet(), "level $level")
            assertTrue((added - off).all { wraps(it.combination.sequence) }, "level $level")
        }
    }

    @Test fun sequenceKeyOptions() {
        val p1 = Engine(Profiles.p1, 9)
        val wrap = Engine(Profiles.with("sequence.wrapAroundA" to "true"), 9)
        // D12 default: a wrap run ranks by its last card, so K-A-2-3-4 sits below A-2-3-4-5.
        assertEquals(listOf(2, 3, 4), listOf("SJ DQ CK SA D2", "SQ DK CA S2 D3", "SK DA C2 S3 D4").map { wrap.combo(it).key })
        assertTrue(wrap.combo("SA D2 C3 S4 D5").beats(wrap.combo("SK DA C2 S3 D4")))
        assertTrue(!wrap.combo("SK DA C2 S3 D4").beats(wrap.combo("SA D2 C3 S4 D5")))
        assertEquals(2, wrap.combo("SK DK SA DA S2 D2").key)
        val wrapHigh = Engine(Profiles.with("sequence.wrapAroundA" to "true", "sequence.wrapRanksByLastCard" to "false"), 9)
        assertEquals(Ranks.ACE, wrapHigh.combo("SK DA C2 S3 D4").key)
        assertEquals(5, wrapHigh.combo("SA D2 C3 S4 D5").key, "D12 must not change an A-low run")
        // D5 default: A-2-3-4-5 is 5-high, below 2-3-4-5-6; off, it ranks as A-high.
        assertEquals(5, p1.combo("SA D2 C3 S4 D5").key)
        assertEquals(3, p1.combo("SA DA S2 D2 S3 D3").key)
        val aceHigh = Engine(Profiles.with("sequence.aceLowRanksLowest" to "false"), 9)
        assertEquals(Ranks.ACE, aceHigh.combo("SA D2 C3 S4 D5").key)
        assertEquals(Ranks.ACE, aceHigh.combo("SA DA S2 D2 S3 D3").key)
        assertTrue(aceHigh.combo("SA D2 C3 S4 D5").beats(aceHigh.combo("S9 D10 CJ SQ DK")))
        assertEquals(Ranks.ACE, aceHigh.combo("S10 DJ CQ SK DA").key)
    }

    @Test fun jokerSequenceWrapOption() {
        val jokers = Profiles.with("sequence.jokersAllowed" to "true")
        val wrapping = Profiles.with("sequence.jokersAllowed" to "true", "sequence.jokersWrap" to "true")
        // D13 default: jokers follow A, small then big, and never wrap into 2.
        assertTrue(readings(9, jokers, "LJ BJ S2 D3 C4").isEmpty())
        assertTrue(readings(9, jokers, "BJ LJ SA SK DQ").isNotEmpty())
        assertEquals(listOf(Ranks.SMALL_JOKER, Ranks.BIG_JOKER, 2, 3, 4), readings(9, wrapping, "LJ BJ S2 D3 C4").single().sequence)
        assertEquals(listOf(Ranks.BIG_JOKER, 2, 3, 4, 5), readings(9, wrapping, "BJ S2 D3 C4 S5").single().sequence)
        assertTrue(readings(9, Profiles.with("sequence.jokersWrap" to "true"), "LJ BJ S2 D3 C4").isEmpty(), "needs jokersAllowed")
    }

    @Test fun jokerOptions() {
        assertTrue(readings(9, Profiles.p1, "LJ BJ").isEmpty())
        assertEquals(CombinationType.PAIR, readings(9, Profiles.with("joker.bigSmallIsPair" to "true"), "LJ BJ").single().type)
        assertEquals(CombinationType.TRIPLE_WITH_PAIR, readings(9, Profiles.p1, "S3 D3 C3 BJ BJ").single().type)
        assertTrue(readings(9, Profiles.with("joker.pairInTripleWithPair" to "false"), "S3 D3 C3 BJ BJ").isEmpty())
        val hand = "S3 D3 C3 LJ BJ BJ"
        assertNotEquals(actions(9, Profiles.p1, hand), actions(9, Profiles.with("joker.bigSmallIsPair" to "true"), hand))
        assertNotEquals(actions(9, Profiles.p1, hand), actions(9, Profiles.with("joker.pairInTripleWithPair" to "false"), hand))
    }

    @Test fun mixedJokerPairRanking() {
        // D14 default: big+small ranks as a small-joker pair, so it ties LJ LJ and loses to BJ BJ.
        val mixed = Engine(Profiles.with("joker.bigSmallIsPair" to "true"), 9)
        assertEquals(mixed.combo("LJ LJ").key, mixed.combo("LJ BJ").key)
        assertEquals(Ranks.SMALL_JOKER, mixed.combo("LJ BJ").rank)
        assertTrue(!mixed.combo("LJ BJ").beats(mixed.combo("LJ LJ")))
        assertTrue(mixed.combo("BJ BJ").beats(mixed.combo("LJ BJ")))
        assertTrue(mixed.combo("LJ BJ").beats(mixed.combo("S9 D9")))
        val high = Engine(Profiles.with("joker.bigSmallIsPair" to "true", "joker.bigSmallPairRanksAsSmall" to "false"), 9)
        assertEquals(Ranks.BIG_JOKER, high.combo("LJ BJ").rank)
        assertTrue(high.combo("LJ BJ").beats(high.combo("LJ LJ")))
        assertEquals(Ranks.SMALL_JOKER, high.combo("LJ LJ").rank, "D14 must not change a same-kind pair")
    }

    @Test fun tripleWithPairSameRank() {
        assertEquals(setOf(CombinationType.BOMB), readings(9, Profiles.p1, "S7 S7 D7 C7 H7").map { it.type }.toSet())
        assertEquals(
            setOf(CombinationType.BOMB, CombinationType.TRIPLE_WITH_PAIR),
            readings(9, Profiles.with("tripleWithPair.sameRankPair" to "true"), "S7 S7 D7 C7 H7").map { it.type }.toSet(),
        )
    }

    @Test fun straightFlushOptions() {
        val flush = "S3 S4 S5 S6 S7"
        assertEquals(setOf(CombinationType.STRAIGHT_FLUSH), readings(9, Profiles.p1, flush).map { it.type }.toSet())
        assertEquals(
            setOf(CombinationType.STRAIGHT_FLUSH, CombinationType.STRAIGHT),
            readings(9, Profiles.with("straightFlush.playableAsStraight" to "true"), flush).map { it.type }.toSet(),
        )
        val run = "D7 D8 D9 DJ H2"
        assertIs<Judgment.Readable>(Engine(Profiles.p1, 2).judge(run, "10"))
        val strict = Engine(Profiles.with("wildcard.flushRunAsStraight" to "false"), 2)
        assertEquals(RejectReason.DECLARED_READING_ILLEGAL, (strict.judge(run, "10") as Judgment.Rejected).reason)
        assertNotEquals(actions(2, Profiles.p1, run), actions(2, Profiles.with("wildcard.flushRunAsStraight" to "false"), run))
    }

    @Test fun wildcardDeclarationOptions() {
        val p1 = Engine(Profiles.p1, 2)
        val loose = Engine(Profiles.with("wildcard.alwaysDeclared" to "false"), 2)
        assertEquals(RejectReason.MISSING_DECLARATION, (p1.judge("S6 D6 C6 H2") as Judgment.Rejected).reason)
        assertTrue((loose.judge("S6 D6 C6 H2") as Judgment.Readable).legal)
        // Still required when the reading is ambiguous (R7.7).
        assertEquals(RejectReason.MISSING_DECLARATION, (loose.judge("SK DK S6 D6 H2") as Judgment.Rejected).reason)
        val bomb = loose.generator.generate(Notation.cards("S6 D6 C6 H2"), null).single { it.cards.size == 4 }
        assertNull(bomb.declaration)
        assertTrue(loose.generator.generate(Notation.cards("SK DK S6 D6 H2"), null).filter { it.cards.size == 5 }.all { it.declaration != null })

        assertEquals(RejectReason.LONE_WILDCARD_REDECLARED, (p1.judge("H2", "3") as Judgment.Rejected).reason)
        val stand = Engine(Profiles.with("wildcard.aloneMayStandIn" to "true"), 2)
        assertEquals(3, (stand.judge("H2", "3") as Judgment.Readable).readings.single().rank)
        assertEquals(1, actions(2, Profiles.p1, "H2").size)
        assertEquals(13, actions(2, Profiles.with("wildcard.aloneMayStandIn" to "true"), "H2").size)
    }

    @Test fun ambiguousPlayDeclarationOption() {
        // D15 off: an ambiguous undeclared play is read every way. The generator drops a declaration only
        // when the stated type still names one reading: these two full houses share a type, so both keep theirs.
        val open = Engine(Profiles.with("wildcard.alwaysDeclared" to "false", "wildcard.ambiguousNeedsDeclaration" to "false"), 2)
        val j = open.judge("SK DK S6 D6 H2") as Judgment.Readable
        assertEquals(setOf(13, 6), j.readings.map { it.rank }.toSet())
        val all = open.generator.generate(Notation.cards("SK DK S6 D6 H2"), null)
        val full = all.filter { it.cards.size == 5 }
        assertEquals(2, full.size)
        assertTrue(full.all { it.declaration != null })
        assertEquals(2, full.map { it.declaration }.toSet().size, "the two actions are distinguishable commands")
        // Generator to reducer: every action, submitted as the reducer receives it (cards, declaration,
        // stated type), names exactly one reading, which is the action's own combination.
        for (a in all) {
            val judged = open.classifier.judge(a.cards, a.declaration, null) as Judgment.Readable
            assertEquals(listOf(a.combination), judged.beating.distinct().filter { it.type == a.combination.type }, "$a")
        }
        // A declaration is still dropped where the type alone decides (a bomb of sixes).
        assertNull(open.generator.generate(Notation.cards("S6 D6 C6 H2"), null).single { it.cards.size == 4 }.declaration)
        // alwaysDeclared still wins over D15.
        val strict = Engine(Profiles.with("wildcard.ambiguousNeedsDeclaration" to "false"), 2)
        assertEquals(RejectReason.MISSING_DECLARATION, (strict.judge("SK DK S6 D6 H2") as Judgment.Rejected).reason)
    }

    @Test fun straySuitOption() {
        // D16 default: a suit outside a straight flush rejects the play; off, the suit is ignored.
        val lax = Engine(Profiles.with("wildcard.straySuitRejected" to "false"), 2)
        assertEquals(CombinationType.BOMB, (lax.judge("S6 D6 C6 H2", "6C") as Judgment.Readable).readings.single().type)
        assertEquals(CombinationType.STRAIGHT, (lax.judge("D7 D8 D9 DJ H2", "10S") as Judgment.Readable).readings.single().type)
        assertEquals(CombinationType.STRAIGHT_FLUSH, (lax.judge("D7 D8 D9 DJ H2", "10D") as Judgment.Readable).readings.single().type)
        assertEquals(RejectReason.DECLARED_READING_ILLEGAL, (lax.judge("S6 D6 C6 H2", "AC") as Judgment.Rejected).reason)
        assertEquals(RejectReason.DECLARED_READING_ILLEGAL, (Engine(Profiles.p1, 2).judge("S6 D6 C6 H2", "6C") as Judgment.Rejected).reason)
    }
}
