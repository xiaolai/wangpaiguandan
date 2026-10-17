package cn.wpgd.app.core

import java.lang.management.ManagementFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * CPU time spent by the calling thread since this clock started. A single-threaded simulation that
 * hangs spins on its own thread, so this still catches it, while time spent waiting for a busy
 * machine's other processes is not counted (wall-clock time is, and fails a correct run on a loaded host).
 */
internal class ThreadCpuClock {
    private val bean = ManagementFactory.getThreadMXBean().also {
        check(it.isCurrentThreadCpuTimeSupported) { "this JVM cannot measure thread CPU time" }
        if (!it.isThreadCpuTimeEnabled) it.isThreadCpuTimeEnabled = true
    }
    private val start = bean.currentThreadCpuTime

    fun elapsed(): Duration = (bean.currentThreadCpuTime - start).nanoseconds
}
