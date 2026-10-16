package cn.wpgd.app.session.transport

import kotlin.random.Random

/**
 * Faults applied to one direction of an in-memory link. Rates are per chunk. Every decision comes
 * from the network's seeded RNG, so a failing seed replays exactly.
 *
 * - [latencyMs] + uniform [jitterMs]: delivery delay.
 * - [reorderRate]: the chunk gets up to [reorderDelayMs] extra delay, so later chunks overtake it.
 * - [duplicateRate]: a second copy arrives later.
 * - [truncateRate]: the chunk arrives cut to a random shorter length.
 * - [dropRate]: the chunk never arrives.
 * - [window]: chunks in flight before [Transport.trySend] refuses (backpressure). A slow link has
 *   high latency and a small window.
 */
data class LinkFaults(
    val latencyMs: Long = 2,
    val jitterMs: Long = 0,
    val dropRate: Double = 0.0,
    val duplicateRate: Double = 0.0,
    val reorderRate: Double = 0.0,
    val reorderDelayMs: Long = 40,
    val truncateRate: Double = 0.0,
    val window: Int = 256,
) {
    init {
        require(latencyMs >= 0 && jitterMs >= 0 && reorderDelayMs >= 0 && window >= 1)
        require(listOf(dropRate, duplicateRate, reorderRate, truncateRate).all { it in 0.0..1.0 })
    }

    companion object {
        val RELIABLE = LinkFaults()
    }
}

/** A chunk as it was handed to a link, before any fault: what a radio eavesdropper would capture. */
class CapturedChunk(val linkId: Int, val toHost: Boolean, val bytes: ByteArray)

/**
 * An in-memory network of point-to-point links driven by a [Scheduler] and one seeded RNG.
 * [captured] records every chunk sent on every link (for confidentiality tests).
 */
class InMemoryNetwork(private val scheduler: Scheduler, seed: Long) {
    internal val random = Random(seed)
    private var nextLinkId = 1
    private val capturedChunks = ArrayList<CapturedChunk>()

    var captureEnabled: Boolean = true

    val captured: List<CapturedChunk> get() = capturedChunks

    fun link(toHost: LinkFaults = LinkFaults.RELIABLE, toClient: LinkFaults = toHost): InMemoryLink =
        InMemoryLink(this, scheduler, nextLinkId++, toHost, toClient)

    internal fun capture(c: CapturedChunk) {
        if (captureEnabled) capturedChunks += c
    }
}

/** Two endpoints: [hostEnd] (give it to the host) and [clientEnd] (give it to the client). */
class InMemoryLink internal constructor(
    private val network: InMemoryNetwork,
    private val scheduler: Scheduler,
    val id: Int,
    toHost: LinkFaults,
    toClient: LinkFaults,
) {
    val hostEnd: InMemoryEndpoint = InMemoryEndpoint(this, isHost = true)
    val clientEnd: InMemoryEndpoint = InMemoryEndpoint(this, isHost = false)

    private var faultsToHost = toHost
    private var faultsToClient = toClient

    /** While true, every chunk in both directions vanishes and no close is signalled (a dead radio). */
    var blackholed: Boolean = false

    /** Simulates racy platforms: chunks still reach a listener after its own end was closed (audit A16). */
    var deliverAfterClose: Boolean = false

    var disconnected: Boolean = false
        private set

    fun setFaults(toHost: LinkFaults, toClient: LinkFaults = toHost) {
        faultsToHost = toHost
        faultsToClient = toClient
    }

    /** Both ends lose the link and are told so. */
    fun disconnect() {
        if (disconnected) return
        disconnected = true
        for (end in listOf(hostEnd, clientEnd)) scheduler.schedule(0) { end.listener?.onClosed() }
    }

    internal fun send(from: InMemoryEndpoint, chunk: ByteArray): Boolean {
        if (disconnected || from.closed) return false
        val to = if (from.isHost) clientEnd else hostEnd
        val faults = if (from.isHost) faultsToClient else faultsToHost
        if (from.inFlight >= faults.window) {
            from.wantsWritable = true
            return false
        }
        from.inFlight++
        val copy = chunk.copyOf()
        network.capture(CapturedChunk(id, toHost = !from.isHost, bytes = copy))
        val r = network.random
        // The sender's window frees once the chunk has left, whatever happens to it on the way.
        scheduler.schedule(faults.latencyMs) {
            from.inFlight--
            if (from.wantsWritable && !from.closed && !disconnected) {
                from.wantsWritable = false
                from.listener?.onWritable()
            }
        }
        if (blackholed) return true
        if (r.nextDouble() < faults.dropRate) return true
        val delay = faults.latencyMs + (if (faults.jitterMs > 0) r.nextLong(faults.jitterMs + 1) else 0) +
            (if (r.nextDouble() < faults.reorderRate) r.nextLong(faults.reorderDelayMs + 1) else 0)
        val payload = if (r.nextDouble() < faults.truncateRate && copy.size > 1) copy.copyOf(r.nextInt(copy.size)) else copy
        deliver(to, payload, delay)
        if (r.nextDouble() < faults.duplicateRate) deliver(to, payload.copyOf(), delay + 1 + r.nextLong(faults.reorderDelayMs + 1))
        return true
    }

    private fun deliver(to: InMemoryEndpoint, bytes: ByteArray, delay: Long) {
        scheduler.schedule(delay) {
            if (blackholed || disconnected) return@schedule
            if (to.closed && !deliverAfterClose) return@schedule
            to.listener?.onReceive(bytes)
        }
    }

    internal fun closeFrom(end: InMemoryEndpoint) {
        if (end.closed) return
        end.closed = true
        val other = if (end.isHost) clientEnd else hostEnd
        val faults = if (end.isHost) faultsToClient else faultsToHost
        scheduler.schedule(faults.latencyMs) {
            if (!blackholed && !other.closed) other.listener?.onClosed()
        }
    }
}

class InMemoryEndpoint internal constructor(private val link: InMemoryLink, internal val isHost: Boolean) : Transport {
    internal var listener: TransportListener? = null
    internal var closed = false
    internal var inFlight = 0
    internal var wantsWritable = false

    /** The listener its owner installed; tests replay stale callbacks through it. */
    val installedListener: TransportListener? get() = listener

    override fun open(listener: TransportListener) {
        check(this.listener == null) { "transport already opened" }
        this.listener = listener
    }

    override fun trySend(chunk: ByteArray): Boolean = link.send(this, chunk)

    override fun close() = link.closeFrom(this)
}
