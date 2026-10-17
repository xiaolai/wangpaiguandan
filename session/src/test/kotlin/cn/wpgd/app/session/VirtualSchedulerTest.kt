package cn.wpgd.app.session

import cn.wpgd.app.session.transport.VirtualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The task guard runs at most maxTasks tasks: the next due one fails before it runs. */
class VirtualSchedulerTest {
    private fun scheduler(tasks: Int): Pair<VirtualScheduler, IntArray> {
        val s = VirtualScheduler()
        val ran = IntArray(1)
        repeat(tasks) { s.schedule(1) { ran[0]++ } }
        return s to ran
    }

    @Test fun runForRunsAtMostMaxTasks() {
        val (s, ran) = scheduler(3)
        assertFailsWith<IllegalStateException> { s.runFor(10, maxTasks = 2) }
        assertEquals(2, ran[0])
        val (exact, ranExact) = scheduler(3)
        exact.runFor(10, maxTasks = 3)
        assertEquals(3, ranExact[0])
    }

    @Test fun runUntilCannotFinishOnATaskBeyondTheBudget() {
        val (s, ran) = scheduler(3)
        assertFailsWith<IllegalStateException> { s.runUntil(10, maxTasks = 2) { ran[0] == 3 } }
        assertEquals(2, ran[0])
        val (exact, ranExact) = scheduler(3)
        assertTrue(exact.runUntil(10, maxTasks = 3) { ranExact[0] == 3 })
    }

    @Test fun runUntilGuardKeepsTheUnexecutedTaskQueued() {
        val (s, ran) = scheduler(3)
        assertFailsWith<IllegalStateException> { s.runUntil(10, maxTasks = 2) { ran[0] == 3 } }
        assertEquals(1, s.pending)
        s.runFor(10)
        assertEquals(3, ran[0])
    }

    /** A deadline past Long.MAX_VALUE is refused instead of wrapping into the past and running early. */
    @Test fun anOverflowingDeadlineIsRefused() {
        val s = VirtualScheduler(start = Long.MAX_VALUE - 1)
        val ran = IntArray(1)
        assertFailsWith<IllegalArgumentException> { s.schedule(2) { ran[0]++ } }
        s.runFor(0)
        assertEquals(0, ran[0])
        assertEquals(0, s.pending)
        assertFailsWith<IllegalArgumentException> { s.runFor(2) }
        assertFailsWith<IllegalArgumentException> { s.runUntil(2) { false } }
        s.schedule(1) { ran[0]++ }
        s.runFor(1)
        assertEquals(1, ran[0], "the last representable millisecond still works")
        assertEquals(Long.MAX_VALUE, s.now)
    }

    /** Round 2 of #70: a negative window is refused, and the overflow guard holds at a negative clock. */
    @Test fun negativeWindowsAndNegativeClocksCannotWrap() {
        val low = VirtualScheduler(start = Long.MIN_VALUE + 1)
        val ran = IntArray(1)
        low.schedule(0) { ran[0]++ }
        // now - 5 would wrap to a far-future end and run every task, then jump the clock forward.
        assertFailsWith<IllegalArgumentException> { low.runFor(-5) }
        assertFailsWith<IllegalArgumentException> { low.runUntil(-5) { false } }
        assertEquals(0, ran[0])
        assertEquals(Long.MIN_VALUE + 1, low.now)

        // At a negative clock, Long.MAX_VALUE - now itself overflows: the old guard refused deadlines
        // that fit (and so cannot be trusted to refuse the ones that do not).
        val negative = VirtualScheduler(start = -10)
        negative.schedule(Long.MAX_VALUE) { ran[0]++ }
        negative.runFor(Long.MAX_VALUE)
        assertEquals(1, ran[0], "the largest deadline from a negative clock fits and runs")
        assertEquals(Long.MAX_VALUE - 10, negative.now)
        assertFailsWith<IllegalArgumentException> { negative.schedule(11) { ran[0]++ } }
        assertFailsWith<IllegalArgumentException> { negative.runFor(11) }
    }
}
