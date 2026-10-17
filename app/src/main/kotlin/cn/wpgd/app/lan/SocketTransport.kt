package cn.wpgd.app.lan

import cn.wpgd.app.room.RoomEndpoint
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.TransportListener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where a room listens on the local network. */
data class LanEndpoint(val host: String, val port: Int) : RoomEndpoint {
    override val medium: RoomMedium get() = RoomMedium.LAN

    init {
        require(host.isNotBlank() && host.length <= 255) { "bad host" }
        require(port in 1..65535) { "bad port $port" }
    }
}

/** Bounds of one LAN connection (GDN-027): a peer can cost at most this much memory and time. */
data class LanLimits(
    /** Largest record accepted or sent: one session chunk (u16 length, header, payload). */
    val maxRecord: Int = 2 + ChunkCodec.HEADER_SIZE + FramingLimits().maxChunkPayload,
    /** Chunks queued for writing before [Transport.trySend] refuses (backpressure). */
    val window: Int = 256,
    val connectTimeoutMs: Int = 5_000,
    /** How long a gracefully closed connection may keep writing what it had accepted. */
    val lingerMs: Long = 2_000,
    /**
     * Received chunks handed to the session loop and not yet taken by it; reading pauses at this many,
     * so a peer that sends faster than the loop works is held back by TCP instead of filling memory.
     */
    val receiveWindow: Int = 256,
) {
    init {
        require(maxRecord in 1..0xFFFF && window >= 2 && connectTimeoutMs > 0 && lingerMs >= 0 && receiveWindow >= 1)
    }
}

/** Why a joiner's connection never opened (join by address explains it to the user). */
enum class ConnectFailure {
    /** The host answered that nothing listens on that port. */
    REFUSED,

    /** No answer within [LanLimits.connectTimeoutMs]. */
    TIMED_OUT,

    /** No route, network down, or the name does not resolve. */
    UNREACHABLE,
}

/**
 * What a joiner's connection learned about the other end before closing, so join by address can say
 * what went wrong: connecting failed and why, or the peer sent something that is not this app's records.
 */
interface ConnectReport {
    val connectFailure: ConnectFailure?

    /** Records refused as malformed (zero or oversized length): the peer does not speak this protocol. */
    val malformedRecords: Long
}

/** Counters for diagnostics (no payload bytes). */
class LanMetrics {
    val recordsIn = AtomicLong()
    val recordsOut = AtomicLong()
    val oversized = AtomicLong()
    val failures = AtomicLong()
}

/**
 * A session [Transport] over one TCP connection on the local network (WI-09 LAN adapter, GDN-021).
 *
 * The stream carries records: a u16 big-endian length, then that many bytes (one session chunk).
 * A record of length 0 or above [LanLimits.maxRecord] closes the connection; the session's own
 * framing, sealing and limits apply above this. Nothing here reads the chunk contents.
 *
 * Threads: one reader and one writer thread per connection. Every listener callback is handed to
 * [post] (the owner's session loop), never run on a socket thread, so the single-threaded session
 * stays single-threaded. Callbacks may still arrive after [close] (the contract allows it; the session
 * binds them to its connection identity).
 */
class SocketTransport private constructor(
    private val post: (() -> Unit) -> Unit,
    private val limits: LanLimits,
    private val name: String,
    private val opener: () -> Socket,
    private val onDisposed: (SocketTransport) -> Unit,
    /** An accepted connection's socket, owned from construction so closing before [open] closes it. */
    acceptedSocket: Socket? = null,
) : Transport, ConnectReport {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queued = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val signalled = AtomicBoolean(false)
    private val wantsWritable = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    /** The pending end of a graceful close's linger ([close]); cancelled once the socket has closed. */
    @Volatile private var lingerTimeout: ScheduledFuture<*>? = null

    /** Set when a graceful close's linger has ended: a socket that arrives later is closed at once. */
    @Volatile private var lingerEnded = false

    /** Test seam: a graceful close's linger timer is still scheduled. */
    internal val lingerPending: Boolean get() = lingerTimeout?.isDone == false

    /** Test seam: the socket has closed and the disposal was reported. */
    internal val isDisposed: Boolean get() = disposed.get()

    /** Test seam: the writer thread is running. */
    @Volatile internal var writerStarted = false
        private set

    /** One permit per received chunk the loop may still be handed ([LanLimits.receiveWindow]). */
    private val receiving = Semaphore(limits.receiveWindow)

    @Volatile private var socket: Socket? = acceptedSocket

    @Volatile private var listener: TransportListener? = null

    val metrics = LanMetrics()

    val isClosed: Boolean get() = closed.get()

    /** Set before the transport reports closed when connecting failed; null once connected (or for an accepted socket). */
    @Volatile override var connectFailure: ConnectFailure? = null
        private set

    override val malformedRecords: Long get() = metrics.oversized.get()

    override fun open(listener: TransportListener) {
        check(this.listener == null) { "transport already opened" }
        this.listener = listener
        Thread({ readLoop() }, "$name-read").apply { isDaemon = true }.start()
    }

    override fun trySend(chunk: ByteArray): Boolean {
        require(chunk.size in 1..limits.maxRecord) { "chunk of ${chunk.size} bytes does not fit one record" }
        if (closed.get()) return false
        if (queued.get() >= limits.window) {
            // Arm before rechecking: a writer that drained the queue between the first check and
            // arming would otherwise never see the flag, and no writable callback would follow.
            wantsWritable.set(true)
            if (queued.get() >= limits.window) return false
        }
        queued.incrementAndGet()
        queue.put(chunk.copyOf())
        return true
    }

    /**
     * Graceful close: nothing more is accepted, but chunks already accepted by [trySend] are still
     * written (the session sends its last word — leave, suspended, ended — and closes at once), for at
     * most [LanLimits.lingerMs]; then the socket closes. Errors close abruptly ([fail]). An accepted
     * connection keeps its server slot until its socket has closed.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // One shared timer for every connection's linger, cancelled when the socket closes first. It is
        // published before the writer is told to finish, and rechecked after: a disposal that ran first
        // (an error on either thread) saw no timer to cancel, so this side cancels it. closeSocket sets
        // `disposed` before reading `lingerTimeout`, so one of the two always sees the other.
        val timeout = LINGER.schedule({
            lingerEnded = true
            closeSocket()
        }, limits.lingerMs, TimeUnit.MILLISECONDS)
        lingerTimeout = timeout
        if (disposed.get()) timeout.cancel(false)
        queue.put(POISON)
        signalClosed()
    }

    private fun readLoop() {
        try {
            val s = try {
                opener()
            } catch (e: IOException) {
                connectFailure = classify(e)
                throw e
            }
            socket = s
            // The linger ended while connecting: its closeSocket saw no socket. Published before this
            // read and set before that one, so one side always closes the socket ([lingerEnded]).
            if (lingerEnded) {
                closeSocket()
                return
            }
            s.tcpNoDelay = true
            s.keepAlive = true
            // The writer always runs: after a graceful close it still writes what was accepted, then closes.
            Thread({ writeLoop(s) }, "$name-write").apply { isDaemon = true }.start()
            if (closed.get()) return
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            while (!closed.get()) {
                val n = input.readUnsignedShort()
                if (n == 0 || n > limits.maxRecord) {
                    metrics.oversized.incrementAndGet()
                    fail()
                    return
                }
                val bytes = ByteArray(n)
                input.readFully(bytes)
                metrics.recordsIn.incrementAndGet()
                // Backpressure: wait for the loop to take earlier chunks before reading further.
                while (!receiving.tryAcquire(RECEIVE_POLL_MS, TimeUnit.MILLISECONDS)) {
                    if (closed.get()) return
                }
                // Not gated on [closed]: a peer that sends its last word and closes at once must still
                // be heard. Receives are posted before onClosed, so the loop sees them in order.
                post {
                    try {
                        listener?.onReceive(bytes)
                    } finally {
                        receiving.release()
                    }
                }
            }
        } catch (_: IOException) {
            fail()
        } catch (_: SecurityException) {
            fail()
        } catch (_: InterruptedException) {
            fail()
        }
    }

    private fun writeLoop(s: Socket) {
        writerStarted = true
        try {
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            while (true) {
                val chunk = queue.take()
                if (chunk === POISON) {
                    out.flush()
                    closeSocket()
                    return
                }
                out.writeShort(chunk.size)
                out.write(chunk)
                metrics.recordsOut.incrementAndGet()
                val left = queued.decrementAndGet()
                if (queue.isEmpty()) out.flush()
                if (left <= limits.window / 2 && wantsWritable.compareAndSet(true, false)) {
                    post { if (!closed.get()) listener?.onWritable() }
                }
            }
        } catch (_: IOException) {
            fail()
        } catch (_: InterruptedException) {
            fail()
        }
    }

    private fun fail() {
        // After a graceful close the socket closing under the reader is expected, not a failure.
        if (!closed.get()) metrics.failures.incrementAndGet()
        shutDown()
    }

    /** Abrupt close (an error): queued chunks are dropped. */
    private fun shutDown() {
        closed.set(true)
        queue.clear()
        queue.put(POISON)
        closeSocket()
        signalClosed()
    }

    /**
     * Closes the socket and, the first time, reports the connection disposed ([onDisposed]): an
     * accepting server frees its slot only now, when the socket and its threads are really going away.
     */
    private fun closeSocket() {
        val s = socket ?: return
        try {
            s.close()
        } catch (_: IOException) {
            // Already gone.
        }
        if (disposed.compareAndSet(false, true)) {
            lingerTimeout?.cancel(false)
            onDisposed(this)
        }
    }

    private fun signalClosed() {
        if (signalled.compareAndSet(false, true)) post { listener?.onClosed() }
    }

    companion object {
        /** Refused (RST) means the address was reached; anything else means it was not. */
        internal fun classify(e: IOException): ConnectFailure = when {
            e is java.net.SocketTimeoutException -> ConnectFailure.TIMED_OUT
            e is java.net.ConnectException && (e.message.orEmpty().contains("refused", ignoreCase = true) || e.message.orEmpty().contains("ECONNREFUSED")) -> ConnectFailure.REFUSED
            else -> ConnectFailure.UNREACHABLE
        }

        private val POISON = ByteArray(0)

        /** Ends every graceful close's linger: one daemon thread, whatever the number of closes. */
        private val LINGER: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "wpgd-lan-linger").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
        }
        private const val RECEIVE_POLL_MS = 200L

        /** A joiner's connection to [endpoint]; connecting happens on the reader thread. */
        fun connect(endpoint: LanEndpoint, post: (() -> Unit) -> Unit, limits: LanLimits = LanLimits()): SocketTransport =
            SocketTransport(post, limits, "wpgd-lan", {
                Socket().apply { connect(InetSocketAddress(endpoint.host, endpoint.port), limits.connectTimeoutMs) }
            }, { })

        /** Test seam: a joiner's connection whose socket comes from [opener], run on the reader thread. */
        internal fun connecting(opener: () -> Socket, post: (() -> Unit) -> Unit, limits: LanLimits): SocketTransport =
            SocketTransport(post, limits, "wpgd-lan", opener, { })

        /** The host's end of an accepted connection. */
        internal fun accepted(socket: Socket, post: (() -> Unit) -> Unit, limits: LanLimits, onDisposed: (SocketTransport) -> Unit): SocketTransport =
            SocketTransport(post, limits, "wpgd-lan-peer", { socket }, onDisposed, socket)
    }
}
