package cn.wpgd.app.lan

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
) {
    private val server = ServerSocket(0, BACKLOG, bindAddress)
    private val live = Collections.synchronizedSet(LinkedHashSet<SocketTransport>())
    private val stopped = AtomicBoolean(false)

    /** Connections refused because [maxConnections] were open. */
    @Volatile var refused: Long = 0
        private set

    val port: Int get() = server.localPort

    val openConnections: Int get() = live.size

    init {
        Thread({ acceptLoop() }, "wpgd-lan-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!stopped.get()) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                return
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

    /** Stops listening and closes every connection. Idempotent. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { server.close() } catch (_: IOException) { }
        synchronized(live) { live.toList() }.forEach { it.close() }
    }

    private companion object {
        const val BACKLOG = 8
    }
}
