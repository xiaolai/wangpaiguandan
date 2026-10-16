package cn.wpgd.app.owner

import cn.wpgd.app.session.transport.Cancellable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The session loop's lifecycle edges: shutdown from its own thread, and racing shutdown or cancellation. */
class SessionLoopTest {
    @Test fun shutdownFromTheLoopsOwnThreadStopsItWithoutAnError() {
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val loop = ExecutorSessionLoop(onError = { errors += it })
        val returned = CountDownLatch(1)
        loop.post {
            loop.shutdown()
            returned.countDown()
        }
        assertTrue("shutdown on the loop returns at once", returned.await(1, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5_000
        while (!loop.terminated) {
            assertTrue("the loop stops after the task returns", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        assertEquals("no interruption or self-wait reaches the error handler", emptyList<Throwable>(), errors.toList())
    }

    @Test fun schedulingWhileTheLoopShutsDownNeverThrows() {
        repeat(200) {
            val loop = ExecutorSessionLoop(onError = { throw AssertionError(it) })
            val failures = Collections.synchronizedList(ArrayList<Throwable>())
            val go = CountDownLatch(1)
            val scheduler = thread {
                go.await()
                try {
                    while (!loop.terminated) loop.schedule(1_000) { }
                } catch (t: Throwable) {
                    failures += t
                }
            }
            go.countDown()
            loop.shutdown()
            scheduler.join(5_000)
            assertEquals("schedule after shutdown is a no-op, never an exception", emptyList<Throwable>(), failures.toList())
        }
    }

    @Test fun registrationRacingCancelAllLeavesNothingTracked() {
        /** A loop that never runs anything: only the tracked set is observed. */
        val inert = object : SessionLoop {
            override val now: Long = 0
            override val isOnLoop: Boolean = false
            override fun schedule(delayMs: Long, task: () -> Unit): Cancellable = Cancellable { }
            override fun shutdown() = Unit
            override val terminated: Boolean = false
            override fun whenTerminated(action: () -> Unit) = Unit
        }
        repeat(2_000) {
            val tracked = TrackedScheduler(inert)
            val go = CountDownLatch(1)
            val scheduler = thread {
                go.await()
                while (!tracked.isClosed) tracked.schedule(10) { }
                tracked.schedule(10) { }
            }
            go.countDown()
            tracked.cancelAll()
            scheduler.join(5_000)
            assertEquals("nothing stays registered after cancelAll", 0, tracked.liveTasks)
        }
    }

    @Test fun cancellingBeforeTheLoopReturnsItsHandleStillCancelsTheLoopTask() {
        var tracked: TrackedScheduler? = null
        var innerCancelled = false
        /** cancelAll lands while the loop is still scheduling: the handle is registered, its loop task not yet known. */
        val racing = object : SessionLoop {
            override val now: Long = 0
            override val isOnLoop: Boolean = false
            override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
                checkNotNull(tracked).cancelAll()
                return Cancellable { innerCancelled = true }
            }
            override fun shutdown() = Unit
            override val terminated: Boolean = false
            override fun whenTerminated(action: () -> Unit) = Unit
        }
        tracked = TrackedScheduler(racing)
        tracked.schedule(60_000) { }
        assertTrue("the loop task of a handle cancelled mid-schedule is cancelled too", innerCancelled)
        assertEquals(0, tracked.liveTasks)
    }
}
