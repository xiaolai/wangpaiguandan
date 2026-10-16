package cn.wpgd.app.owner

import cn.wpgd.app.core.ai.DecisionOutcome
import cn.wpgd.app.diagnostics.LatencySamples
import cn.wpgd.app.session.ai.DecisionExecutor
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The background thread AI decisions run on (GDN-102: no input-blocking AI work). Never the main
 * thread and never the session loop: the loop must keep answering heartbeats while a seat thinks.
 */
class AiWorker(threadName: String = "wpgd-ai") {
    val executor: ExecutorService = Executors.newSingleThreadExecutor(ThreadFactory { r -> Thread(r, threadName).also { it.isDaemon = true } })

    /** Stops the worker and waits briefly for it. An interrupted caller stops waiting, keeps its interrupt, and never throws. */
    fun shutdown() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    val terminated: Boolean get() = executor.isTerminated
}

/**
 * Runs each AI decision on [worker] and posts the outcome back to the session [loop], where the
 * session's `AiSeatDriver` applies it (the driver's deadline still runs on the loop). A cancelled
 * decision never reports. A decision that throws is reported to [onError] and does not report an
 * outcome; the driver's deadline then delivers its deterministic fallback.
 */
class WorkerDecisionExecutor(
    private val worker: AiWorker,
    private val loop: Scheduler,
    private val onError: (Throwable) -> Unit,
) : DecisionExecutor {
    override fun execute(work: () -> DecisionOutcome, done: (DecisionOutcome) -> Unit): Cancellable {
        val cancelled = AtomicBoolean(false)
        if (worker.executor.isShutdown) return Cancellable { cancelled.set(true) }
        val future = worker.executor.submit {
            if (cancelled.get()) return@submit
            val outcome = try {
                work()
            } catch (t: Throwable) {
                onError(t)
                return@submit
            }
            loop.schedule(0) { if (!cancelled.get()) done(outcome) }
        }
        return Cancellable {
            cancelled.set(true)
            future.cancel(false)
        }
    }
}

/**
 * Records each AI decision's compute time in [samples] (WI-07 acceptance: p95 ≤ 1 s on the named
 * minimum device, read from diagnostics during HP-1), around whichever executor runs it. The time is
 * taken where the work runs, so a decision cancelled at the deadline or one that throws still counts.
 */
class TimedDecisionExecutor(
    private val inner: DecisionExecutor,
    private val samples: LatencySamples,
    private val nanoTime: () -> Long = System::nanoTime,
) : DecisionExecutor {
    override fun execute(work: () -> DecisionOutcome, done: (DecisionOutcome) -> Unit): Cancellable =
        inner.execute({
            val start = nanoTime()
            try {
                work()
            } finally {
                samples.add(maxOf(0L, (nanoTime() - start) / 1_000_000))
            }
        }, done)
}
