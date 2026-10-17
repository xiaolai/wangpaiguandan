package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Budget: a 27-card hand holding both wildcards, leading (the largest action set). Audit A27 (52² substitutions). */
class GeneratorBudgetTest {
    @Test fun twentySevenCardHandWithTwoWildcardsWithinBudget() {
        val e = engine(level = 2)
        val hands = listOf(
            // two cards of every rank 3..K in mixed suits, A, both jokers kinds
            "H2 H2 S3 D3 S4 C4 S5 D5 H6 C6 S7 D7 S8 H8 C9 D9 S10 H10 SJ DJ SQ CQ HK DK SA LJ BJ",
            // four suits of six adjacent ranks: many straights, straight flushes, triples and bombs
            "H2 H2 S3 H3 C3 D3 S4 H4 C4 D4 S5 H5 C5 D5 S6 H6 C6 D6 S7 H7 C7 D7 S8 H8 C8 D8 S9",
        ).map(Notation::cards)
        for (hand in hands) measure(e, hand)
    }

    private fun measure(e: Engine, hand: List<Card>) {
        assertEquals(27, hand.size)
        assertEquals(2, hand.count { it.isWildcard(e.level) })
        repeat(WARMUP) { e.generator.generate(hand, null) }
        val times = ArrayList<Long>()
        var count = 0
        repeat(RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            count = e.generator.generate(hand, null).size
            times += mark.elapsedNow().inWholeMicroseconds
        }
        val median = times.sorted()[RUNS / 2]
        val cold = TimeSource.Monotonic.markNow().let { e.generator.generate(hand, null); it.elapsedNow().inWholeMicroseconds }
        println("BUDGET actions=$count median_us=$median max_us=${times.max()} last_us=$cold")
        assertTrue(count > 1000, "suspiciously few actions: $count")
        assertTrue(median < BUDGET_MICROS, "median ${median}us exceeds budget ${BUDGET_MICROS}us")
    }

    private companion object {
        const val WARMUP = 5
        const val RUNS = 21
        /** JVM budget for one full lead generation; the device budget is checked in WI-08. */
        const val BUDGET_MICROS = 50_000L
    }
}
