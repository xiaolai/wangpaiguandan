package cn.wpgd.app.owner

import cn.wpgd.app.session.transport.VirtualScheduler
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Audit #18: the worker can shut down between the executor's shutdown check and its submit. That
 * race is a shutdown like any other (the decision never runs), not an error that fails the owner.
 */
class WorkerDecisionExecutorTest {
    /** An executor that refuses every task; [shutDownAfterChecks] isShutdown calls answer false first. */
    private class Refusing(private var shutDownAfterChecks: Int) : AbstractExecutorService() {
        var shut = false
        override fun execute(command: Runnable) {
            shut = shut || shutDownAfterChecks == 0
            throw RejectedExecutionException("refused")
        }
        override fun isShutdown(): Boolean = if (shutDownAfterChecks > 0) { shutDownAfterChecks--; false } else shut
        override fun shutdown() { shut = true }
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shut = true }
        override fun isTerminated(): Boolean = shut
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shut
    }

    private val errors = ArrayList<Throwable>()

    @Test fun aShutdownBetweenTheCheckAndTheSubmitIsNoError() {
        // The first isShutdown (the check) says running; the submit then finds the worker shut down.
        val worker = AiWorker(executor = Refusing(shutDownAfterChecks = 1))
        val executor = WorkerDecisionExecutor(worker, VirtualScheduler(), errors::add)
        val handle = executor.execute({ error("never runs") }) { error("never reports") }
        handle.cancel()
        assertTrue(errors.isEmpty())
    }

    @Test fun aRefusalWhileRunningIsStillLoud() {
        val refusing = object : AbstractExecutorService() {
            override fun execute(command: Runnable) = throw RejectedExecutionException("saturated")
            override fun isShutdown() = false
            override fun shutdown() = Unit
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
        }
        val executor = WorkerDecisionExecutor(AiWorker(executor = refusing), VirtualScheduler(), errors::add)
        assertThrows(RejectedExecutionException::class.java) { executor.execute({ error("never runs") }) { error("never reports") } }
    }
}
