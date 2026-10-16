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
}
