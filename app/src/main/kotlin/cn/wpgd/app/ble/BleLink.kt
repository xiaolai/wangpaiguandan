package cn.wpgd.app.ble

import cn.wpgd.app.diagnostics.LatencySamples
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.TransportListener
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What one Bluetooth link measured (WI-10 device spike, `dev-docs/ble-device-spike.md`). Counts and
 * durations only, never payload bytes. Times are milliseconds of the link's [Scheduler].
 */
class BleLinkMetrics {
    /** Current ATT payload (negotiated MTU - 3, capped at 512); 0 until ready. */
    @Volatile var attPayload: Int = 0
        internal set

    /** Time from [BleLink.open] to ready (connection, MTU exchange, discovery, subscription); -1 until then. */
    val readyMs = AtomicLong(-1)
    val attChanges = AtomicLong()
    val packetsOut = AtomicLong()
    val packetsIn = AtomicLong()
    val packetBytesOut = AtomicLong()
    val packetBytesIn = AtomicLong()
    val recordsOut = AtomicLong()
    val recordsIn = AtomicLong()
    val recordBytesOut = AtomicLong()
    val recordBytesIn = AtomicLong()
    val sendFailures = AtomicLong()
    val duplicatePackets = AtomicLong()
    val droppedRecords = AtomicLong()

    /** Highest number of records accepted and not yet sent (bounded by [BleLimits.window]). */
    val queueHigh = AtomicInteger()

    /** Refused packets by [BleViolation] ordinal. */
    val violations = Array(BleViolation.entries.size) { AtomicLong() }

    /** One packet: send started → the stack reported it done. */
    val packetMs = LatencySamples()

    /** One record: queued → its last packet done (includes waiting behind earlier records). */
    val recordMs = LatencySamples()

    fun summary(): String = buildString {
        append("att=").append(attPayload)
        append(" ready_ms=").append(readyMs.get())
        append(" att_changes=").append(attChanges.get())
        append(" packets_out=").append(packetsOut.get()).append(" packets_in=").append(packetsIn.get())
        append(" bytes_out=").append(packetBytesOut.get()).append(" bytes_in=").append(packetBytesIn.get())
        append(" records_out=").append(recordsOut.get()).append(" records_in=").append(recordsIn.get())
        append(" send_failures=").append(sendFailures.get())
        append(" dup_packets=").append(duplicatePackets.get())
        append(" dropped_records=").append(droppedRecords.get())
        append(" queue_high=").append(queueHigh.get())
        for (v in BleViolation.entries) append(" v_").append(v.name.lowercase()).append('=').append(violations[v.ordinal].get())
        append(' ').append(packetMs.summary("packet_ms"))
        append(' ').append(recordMs.summary("record_ms"))
    }
}

/**
 * A session [Transport] over one GATT connection ([BlePort]); the Bluetooth counterpart of the LAN
 * `SocketTransport`. Each chunk the session hands over is a record: it is split at the link's current
 * ATT payload ([BlePacketCodec]), sent one packet at a time (the next only after the stack reports the
 * last one done), and reassembled on the other side ([BleReassembler]), which never hands up a
 * partial record. Nothing here reads the chunk contents; the session's framing, sealing and limits
 * apply above.
 *
 * - Backpressure: at most [BleLimits.window] records wait; [trySend] then refuses and
 *   [TransportListener.onWritable] follows once half the window has drained.
 * - Chunks accepted before the connection is ready wait for it.
 * - A failed send drops the rest of that record (the receiver discards the partial one); the
 *   session above recovers from loss.
 * - A fatal packet from the peer ([BleViolation.fatal]) closes the link.
 * - Graceful [close]: nothing more is accepted, accepted records are still sent for at most
 *   [BleLimits.lingerMs], then the connection closes.
 *
 * Threads: radio callbacks may arrive on any thread; state is guarded by one lock. Every listener
 * callback is handed to [post] (the owner's session loop), never run on a radio thread.
 */
class BleLink(
    private val port: BlePort,
    private val post: (() -> Unit) -> Unit,
    private val scheduler: Scheduler,
    private val limits: BleLimits = BleLimits(),
) : Transport {
    private val lock = Any()
    private var listener: TransportListener? = null
    private val reassembler = BleReassembler(limits)

    private class Outgoing(val record: ByteArray, val queuedAt: Long)

    private val queue = ArrayDeque<Outgoing>()
    private var current: List<ByteArray>? = null
    private var currentRecord: Outgoing? = null
    private var nextPacket = 0
    private var inFlight = false
    private var sentAt = 0L
    private var seq = 0
    private var ready = false
    private var attPayload = 0
    private var openedAt = 0L
    private var closing = false
    private var wantsWritable = false
    private var lingerTimer: Cancellable? = null
    private var signalled = false

    /** True once the connection is released (closed, failed or lingered out). */
    @Volatile var disposed: Boolean = false
        private set

    val metrics = BleLinkMetrics()

    val address: String get() = port.address

    /** Test seams: the raw port and its event sink. */
    internal val portForTest: BlePort get() = port
    internal val portEventsForTest: BlePortEvents get() = events

    private val events = object : BlePortEvents {
        override fun onReady(attPayload: Int) {
            synchronized(lock) {
                if (disposed || ready) return
                ready = true
                setAtt(attPayload)
                metrics.readyMs.set(scheduler.now - openedAt)
                pump()
            }
        }

        override fun onAttPayload(attPayload: Int) {
            synchronized(lock) {
                if (disposed) return
                setAtt(attPayload)
                metrics.attChanges.incrementAndGet()
            }
        }

        override fun onPacket(packet: ByteArray) {
            val record = synchronized(lock) {
                if (disposed) return
                metrics.packetsIn.incrementAndGet()
                metrics.packetBytesIn.addAndGet(packet.size.toLong())
                when (val r = reassembler.accept(packet)) {
                    is Reassembly.Record -> {
                        metrics.recordsIn.incrementAndGet()
                        metrics.recordBytesIn.addAndGet(r.bytes.size.toLong())
                        r.bytes
                    }
                    Reassembly.Incomplete -> return
                    Reassembly.Duplicate -> { metrics.duplicatePackets.incrementAndGet(); return }
                    is Reassembly.Dropped -> { metrics.violations[r.violation.ordinal].incrementAndGet(); return }
                    is Reassembly.Fatal -> {
                        metrics.violations[r.violation.ordinal].incrementAndGet()
                        failLocked()
                        null
                    }
                }
            }
            // Not gated on closing: a peer that sends its last word and closes must still be heard;
            // receives are posted before onClosed, so the loop sees them in order.
            if (record != null) post { listener?.onReceive(record) } else signalClosed()
        }

        override fun onSent(ok: Boolean) {
            val writable = synchronized(lock) {
                if (disposed || !inFlight) return
                inFlight = false
                val now = scheduler.now
                metrics.packetMs.add(now - sentAt)
                val packets = checkNotNull(current)
                val record = checkNotNull(currentRecord)
                if (!ok) {
                    metrics.sendFailures.incrementAndGet()
                    metrics.droppedRecords.incrementAndGet()
                    finishRecord()
                } else if (++nextPacket == packets.size) {
                    metrics.recordsOut.incrementAndGet()
                    metrics.recordBytesOut.addAndGet(record.record.size.toLong())
                    metrics.recordMs.add(now - record.queuedAt)
                    finishRecord()
                }
                pump()
                val w = wantsWritable && !closing && pendingLocked() <= limits.window / 2
                if (w) wantsWritable = false
                w
            }
            if (writable) post { if (!disposed) listener?.onWritable() }
        }

        override fun onDisconnected() {
            synchronized(lock) {
                if (disposed) return
                failLocked()
            }
            signalClosed()
        }
    }

    override fun open(listener: TransportListener) {
        synchronized(lock) {
            check(this.listener == null) { "transport already opened" }
            this.listener = listener
            openedAt = scheduler.now
        }
        port.open(events)
    }

    override fun trySend(chunk: ByteArray): Boolean {
        require(chunk.size in 1..limits.maxRecord) { "chunk of ${chunk.size} bytes does not fit one record" }
        synchronized(lock) {
            if (closing || disposed) return false
            if (pendingLocked() >= limits.window) {
                wantsWritable = true
                return false
            }
            queue.addLast(Outgoing(chunk.copyOf(), scheduler.now))
            val pending = pendingLocked()
            if (pending > metrics.queueHigh.get()) metrics.queueHigh.set(pending)
            pump()
            return true
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closing || disposed) return
            closing = true
            if (idleLocked()) {
                disposeLocked()
            } else {
                lingerTimer = scheduler.schedule(limits.lingerMs) {
                    synchronized(lock) { if (!disposed) disposeLocked() }
                }
            }
        }
        signalClosed()
    }

    // ---- Under the lock -------------------------------------------------------------------------

    private fun setAtt(att: Int) {
        val capped = minOf(att, limits.maxPacket)
        require(capped > BlePacketCodec.HEADER_SIZE) { "ATT payload $att too small" }
        attPayload = capped
        metrics.attPayload = capped
    }

    /** Records accepted and not yet completely sent (the one in progress included). */
    private fun pendingLocked(): Int = queue.size + if (current != null) 1 else 0

    private fun idleLocked(): Boolean = !inFlight && current == null && queue.isEmpty()

    /** Sends the next packet if the connection is ready and nothing is in flight. */
    private fun pump() {
        if (disposed || !ready || inFlight) return
        if (current == null) {
            val next = queue.pollFirst()
            if (next == null) {
                if (closing) disposeLocked()
                return
            }
            currentRecord = next
            current = BlePacketCodec.split(seq, next.record, attPayload)
            seq = (seq + 1) % BlePacketCodec.SEQ_SPACE
            nextPacket = 0
        }
        val packet = checkNotNull(current)[nextPacket]
        inFlight = true
        sentAt = scheduler.now
        if (!port.send(packet)) {
            // The port refuses only when its connection is gone.
            inFlight = false
            failLocked()
            signalClosed()
            return
        }
        metrics.packetsOut.incrementAndGet()
        metrics.packetBytesOut.addAndGet(packet.size.toLong())
    }

    private fun finishRecord() {
        current = null
        currentRecord = null
        nextPacket = 0
    }

    private fun failLocked() {
        closing = true
        disposeLocked()
    }

    private fun disposeLocked() {
        if (disposed) return
        disposed = true
        lingerTimer?.cancel()
        queue.clear()
        current = null
        currentRecord = null
        port.close()
    }

    private fun signalClosed() {
        val first = synchronized(lock) {
            if (signalled) false else { signalled = true; true }
        }
        if (first) post { listener?.onClosed() }
    }
}
