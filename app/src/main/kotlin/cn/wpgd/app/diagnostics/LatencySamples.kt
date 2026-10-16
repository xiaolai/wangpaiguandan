package cn.wpgd.app.diagnostics

/**
 * The most recent [capacity] durations in milliseconds, for percentiles in diagnostics and the device
 * spike (WI-10: packet and record times on a Bluetooth link; command-to-receipt time at the table).
 * Bounded memory; thread-safe. Holds numbers only, never payload.
 */
class LatencySamples(private val capacity: Int = 4_096) {
    init {
        require(capacity >= 1)
    }

    private val ring = LongArray(capacity)
    private var next = 0

    /** Samples ever added (the ring keeps the last [capacity]). */
    var count: Long = 0
        @Synchronized get
        private set

    var max: Long = 0
        @Synchronized get
        private set

    @Synchronized
    fun add(ms: Long) {
        require(ms >= 0) { "negative duration $ms" }
        ring[next] = ms
        next = (next + 1) % capacity
        count++
        if (ms > max) max = ms
    }

    /** Nearest-rank percentile ([p] in 0..100] of the kept samples; -1 when there are none. */
    @Synchronized
    fun percentile(p: Double): Long {
        require(p > 0.0 && p <= 100.0) { "percentile $p" }
        val n = minOf(count, capacity.toLong()).toInt()
        if (n == 0) return -1
        val sorted = ring.copyOf(n).also { it.sort() }
        val rank = kotlin.math.ceil(p / 100.0 * n).toInt().coerceIn(1, n)
        return sorted[rank - 1]
    }

    /** `name_n=… name_p50=… name_p95=… name_max=…` for a one-line report, all from one snapshot. */
    @Synchronized
    fun summary(name: String): String = "${name}_n=$count ${name}_p50=${percentile(50.0)} ${name}_p95=${percentile(95.0)} ${name}_max=$max"
}
