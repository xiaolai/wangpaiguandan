package cn.wpgd.app.session.transport

import java.util.PriorityQueue

/**
 * Deterministic single-threaded virtual time. Tasks run in (time, insertion order); nothing runs
 * until [runFor] or [runUntil] is called. Used by the in-memory transport and every session test.
 */
class VirtualScheduler(start: Long = 0) : Scheduler {
    private class Task(val at: Long, val seq: Long, val body: () -> Unit) {
        var cancelled = false
    }

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var seq = 0L

    override var now: Long = start
        private set

    /** Tasks executed so far (a measure of simulated work). */
    var executed: Long = 0
        private set

    val pending: Int get() = queue.count { !it.cancelled }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        require(delayMs >= 0) { "negative delay $delayMs" }
        val t = Task(now + delayMs, seq++, task)
        queue += t
        return Cancellable { t.cancelled = true }
    }

    /** Runs every task due within the next [ms] milliseconds, then sets the clock to the end of the window. */
    fun runFor(ms: Long, maxTasks: Long = DEFAULT_TASK_GUARD) {
        val end = now + ms
        runWhile(maxTasks) { it.at <= end }
        now = maxOf(now, end)
    }

    /**
     * Runs tasks in order until [done] holds (checked after each task) or virtual time passes
     * [maxMs] from now. Returns whether [done] held. The task guard turns a livelock into a failure
     * instead of a hang: at most [maxTasks] tasks run, and a further due task fails before it runs.
     */
    fun runUntil(maxMs: Long, maxTasks: Long = DEFAULT_TASK_GUARD, done: () -> Boolean): Boolean {
        val end = now + maxMs
        if (done()) return true
        var ran = 0L
        while (true) {
            val next = peekDue(end) ?: break
            // Guard before dequeuing: a task the budget refuses stays queued.
            check(ran++ < maxTasks) { "scheduler ran $maxTasks tasks without finishing (livelock?)" }
            queue.poll()
            execute(next)
            if (done()) return true
        }
        now = maxOf(now, end)
        return done()
    }

    private fun runWhile(maxTasks: Long, due: (Task) -> Boolean) {
        var ran = 0L
        while (true) {
            val head = queue.peek() ?: return
            if (head.cancelled) { queue.poll(); continue }
            if (!due(head)) return
            check(ran++ < maxTasks) { "scheduler ran $maxTasks tasks without finishing (livelock?)" }
            queue.poll()
            execute(head)
        }
    }

    private fun peekDue(end: Long): Task? {
        while (true) {
            val head = queue.peek() ?: return null
            if (head.cancelled) { queue.poll(); continue }
            return if (head.at <= end) head else null
        }
    }

    private fun execute(t: Task) {
        now = maxOf(now, t.at)
        executed++
        t.body()
    }

    private companion object {
        const val DEFAULT_TASK_GUARD = 50_000_000L
    }
}
