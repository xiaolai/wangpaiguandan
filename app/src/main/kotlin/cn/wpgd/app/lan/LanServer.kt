package cn.wpgd.app.lan

import cn.wpgd.app.room.HostingFailure
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The host's listening socket for one room (WI-09). Every accepted connection becomes a
 * [SocketTransport] handed to [onPeer] on the session loop ([post]). At most [maxConnections] are open
 * at once; more are closed at accept, before a byte is read, so a crowd of nearby peers costs bounded
 * memory (the session then admits only who the host approves).
 *
 * [bindAddress] null listens on every local interface (the LAN); tests bind to loopback.
 */
class LanServer(
    private val post: (() -> Unit) -> Unit,
    private val onPeer: (SocketTransport) -> Unit,
    private val limits: LanLimits = LanLimits(),
    private val maxConnections: Int = 16,
    bindAddress: InetAddress? = null,
    /** The listening socket (tests pass one that fails); bound to an ephemeral port on [bindAddress]. */
    private val server: ServerSocket = ServerSocket(0, BACKLOG, bindAddress),
) {
    private val live = Collections.synchronizedSet(LinkedHashSet<SocketTransport>())
    private val stopped = AtomicBoolean(false)
    private val failure = HostingFailure()

    /**
     * Registers [failed], called at most once, on the accept thread, if accepting stops working while
     * the server was not stopped (newcomers can no longer connect); at once if that already happened.
     * Connections already open stay open.
     */
    fun onFailure(failed: () -> Unit) = failure.register(failed)

    /** Connections refused because [maxConnections] were open. */
    @Volatile var refused: Long = 0
        private set

    val port: Int get() = server.localPort

    val openConnections: Int get() = live.size

    init {
        Thread({ acceptLoop() }, "wpgd-lan-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        var failures = 0
        while (!stopped.get()) {
            val s = try {
                server.accept().also { failures = 0 }
            } catch (_: IOException) {
                // stop() closing the socket ends the loop quietly. Anything else is retried a few
                // times (out of file descriptors, an aborted handshake), then reported: a room still
                // announced but no longer accepting must not look healthy.
                if (stopped.get()) return
                failures++
                if (server.isClosed || failures >= MAX_ACCEPT_FAILURES) return acceptFailed()
                try {
                    Thread.sleep(ACCEPT_RETRY_MS * failures)
                } catch (_: InterruptedException) {
                    return acceptFailed()
                }
                continue
            }
            if (stopped.get() || live.size >= maxConnections) {
                refused++
                try { s.close() } catch (_: IOException) { }
                continue
            }
            val t = SocketTransport.accepted(s, post, limits) { live.remove(it) }
            // Atomic with stop()'s snapshot: either stop sees this connection, or this sees stopped.
            val admitted = synchronized(live) { if (stopped.get()) false else live.add(t) }
            if (!admitted) {
                t.close()
                continue
            }
            post { if (!stopped.get()) onPeer(t) else t.close() }
        }
    }

    private fun acceptFailed() {
        try { server.close() } catch (_: IOException) { }
        if (!stopped.get()) failure.fail()
    }

    /** Stops listening and closes every connection. Idempotent. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { server.close() } catch (_: IOException) { }
        synchronized(live) { live.toList() }.forEach { it.close() }
    }

    private companion object {
        const val BACKLOG = 8

        /** Consecutive accept failures (not caused by [stop]) after which the server reports itself failed. */
        const val MAX_ACCEPT_FAILURES = 5
        const val ACCEPT_RETRY_MS = 50L
    }
}
