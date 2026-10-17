package cn.wpgd.app.owner

import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.VirtualScheduler
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one thread a session lives on. `SessionHost` and `SessionClient` are single-threaded: every
 * transport callback, timer and host or client call must run on this loop, never on the main thread.
 *
 * Plain JVM, so the same code runs on Android and in unit tests.
 */
interface SessionLoop : Scheduler {
    /** True when the caller is on the loop's thread. */
    val isOnLoop: Boolean

    /** Runs [task] on the loop as soon as possible. */
    fun post(task: () -> Unit) {
        schedule(0, task)
    }

    /** Stops the loop. Pending tasks never run. Idempotent. */
    fun shutdown()

    /** True once [shutdown] has been called and the loop's thread (if any) has stopped. */
    val terminated: Boolean

    /**
     * Runs [action] once, when the loop has stopped for good (its last task returned): at once, on
     * the caller's thread, if it already has; otherwise on the loop's thread as it ends.
     */
    fun whenTerminated(action: () -> Unit)
}

/**
 * Production loop: one daemon thread, monotonic real time. A task that throws is reported to
 * [onError] (fail loud) instead of being swallowed by the executor's future.
 */
class ExecutorSessionLoop(
    threadName: String = "wpgd-session",
    private val onError: (Throwable) -> Unit,
) : SessionLoop {
    @Volatile
    private var thread: Thread? = null

    /** Actions for [whenTerminated]; null once they have run. Guarded by [lock]. */
    private var onTerminated: MutableList<() -> Unit>? = ArrayList()
    private val lock = Any()

    private val executor = object : ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { r -> Thread(r, threadName).also { it.isDaemon = true; thread = it } },
    ) {
        override fun terminated() {
            super.terminated()
            val actions = synchronized(lock) { onTerminated.also { onTerminated = null } }.orEmpty()
            for (action in actions) {
                try {
                    action()
                } catch (t: Throwable) {
                    onError(t)
                }
            }
        }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    private val origin = System.nanoTime()

    override val now: Long get() = (System.nanoTime() - origin) / 1_000_000

    override val isOnLoop: Boolean get() = Thread.currentThread() === thread

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        require(delayMs >= 0) { "negative delay $delayMs" }
        if (executor.isShutdown) return Cancellable { }
        val future = try {
            executor.schedule({
                try {
                    task()
                } catch (t: Throwable) {
                    onError(t)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Shutdown started after the check above: the task never runs, as documented.
            if (executor.isShutdown) return Cancellable { }
            throw e
        }
        return Cancellable { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
        // On the loop's own thread the loop stops when the running task returns: waiting here would
        // wait for this very task (and shutdownNow has just interrupted it).
        if (isOnLoop) return
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override val terminated: Boolean get() = executor.isTerminated

    override fun whenTerminated(action: () -> Unit) {
        val queued = synchronized(lock) { onTerminated?.add(action) == true }
        if (!queued) action()
    }
}

/**
 * Test loop over a [VirtualScheduler]: deterministic virtual time, driven by the test thread with
 * `runFor` / `runUntil`. Everything runs on the caller's thread, so [isOnLoop] is always true.
 */
class VirtualSessionLoop(val scheduler: VirtualScheduler = VirtualScheduler()) : SessionLoop {
    private var stopped = false

    override val now: Long get() = scheduler.now
    override val isOnLoop: Boolean get() = true

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable =
        if (stopped) Cancellable { } else scheduler.schedule(delayMs) { if (!stopped) task() }

    override fun shutdown() {
        if (stopped) return
        stopped = true
        onTerminated.toList().also { onTerminated.clear() }.forEach { it() }
    }

    override val terminated: Boolean get() = stopped

    private val onTerminated = ArrayList<() -> Unit>()

    override fun whenTerminated(action: () -> Unit) {
        if (stopped) action() else onTerminated += action
    }
}

/**
 * The owner's view of its loop: remembers every task it scheduled that has not run yet, so
 * [cancelAll] stops all of them at once (AI deadlines, heartbeats, retries, link deliveries).
 * After [cancelAll] nothing new is scheduled. Used only on the loop thread, except [liveTasks]
 * and [cancelAll], which are synchronized.
 */
class TrackedScheduler(private val loop: SessionLoop) : Scheduler {
    private val live = LinkedHashSet<Cancellable>()
    private val closed = AtomicBoolean(false)

    override val now: Long get() = loop.now

    /** Scheduled tasks that have neither run nor been cancelled. */
    val liveTasks: Int get() = synchronized(live) { live.size }

    val isClosed: Boolean get() = closed.get()

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        lateinit var handle: Cancellable
        // Both guarded by [live]: a cancel that lands before the loop returns its task (another
        // thread's cancelAll) is remembered, and the task is cancelled as soon as it is known.
        var inner: Cancellable? = null
        var cancelled = false
        handle = Cancellable {
            val known = synchronized(live) {
                live.remove(handle)
                cancelled = true
                inner
            }
            known?.cancel()
        }
        // The closed check and the registration are one step under the lock [cancelAll] takes, so a
        // handle is either registered before it (and cancelled by it) or refused.
        synchronized(live) {
            if (closed.get()) return Cancellable { }
            live += handle
        }
        val scheduled = loop.schedule(delayMs) {
            val due = synchronized(live) { live.remove(handle) }
            if (due && !closed.get()) task()
        }
        val cancelNow = synchronized(live) {
            inner = scheduled
            cancelled
        }
        if (cancelNow) scheduled.cancel()
        return handle
    }

    /** Cancels every task still pending and refuses new ones. Idempotent. */
    fun cancelAll() {
        val all = synchronized(live) {
            if (!closed.compareAndSet(false, true)) return
            live.toList().also { live.clear() }
        }
        all.forEach { it.cancel() }
    }
}
