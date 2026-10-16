package cn.wpgd.app.lan

import cn.wpgd.app.session.transport.TransportListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The LAN record layer over real loopback sockets: order, bounds, backpressure, close. */
class LanSocketTransportTest {
    private val loop = Executors.newSingleThreadExecutor { r -> Thread(r, "test-loop").apply { isDaemon = true } }
    private val post: (() -> Unit) -> Unit = { task -> loop.execute(task) }
    private val loopback = InetAddress.getLoopbackAddress()

    /** The loopback address as text (a platform type: checked once here instead of at each use). */
    private val loopbackHost: String = checkNotNull(loopback.hostAddress) { "loopback has no address" }
    private val servers = ArrayList<LanServer>()

    @After fun tearDown() {
        servers.forEach { it.stop() }
        loop.shutdownNow()
    }

    private class Recorder : TransportListener {
        val received: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun onReceive(chunk: ByteArray) {
            received += chunk
        }
        override fun onWritable() = Unit
        override fun onClosed() {
            closes.incrementAndGet()
            closed.countDown()
        }
    }

    private fun server(maxConnections: Int = 16, limits: LanLimits = LanLimits(), onPeer: (SocketTransport) -> Unit) =
        LanServer(post, onPeer, limits, maxConnections, loopback).also { servers += it }

    private fun waitFor(ms: Long = 5_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test fun chunksArriveWholeAndInOrderBothWays() {
        val atHost = Recorder()
        val s = server { peer ->
            peer.open(object : TransportListener by atHost {
                override fun onReceive(chunk: ByteArray) {
                    atHost.onReceive(chunk)
                    check(peer.trySend(chunk.reversedArray()))
                }
            })
        }
        val atJoiner = Recorder()
        val client = SocketTransport.connect(LanEndpoint(loopbackHost, s.port), post)
        client.open(atJoiner)
        val sent = (1..200).map { i -> ByteArray(1 + i % LanLimits().maxRecord.coerceAtMost(170)) { (i + it).toByte() } }
        for (chunk in sent) waitFor { client.trySend(chunk) }
        waitFor { atJoiner.received.size == sent.size }
        assertEquals(sent.map { it.toList() }, atHost.received.map { it.toList() })
        assertEquals(sent.map { it.reversedArray().toList() }, atJoiner.received.map { it.toList() })
        client.close()
        assertTrue(atJoiner.closed.await(5, TimeUnit.SECONDS))
        assertTrue(atHost.closed.await(5, TimeUnit.SECONDS))
        assertFalse(client.trySend(byteArrayOf(1)))
        assertEquals(1, atJoiner.closes.get())
    }

    /** The session sends its last word (leave, suspended, ended) and closes at once: it must arrive. */
    @Test fun closingStillDeliversWhatWasAccepted() {
        val atHost = Recorder()
        val s = server { it.open(atHost) }
        val client = SocketTransport.connect(LanEndpoint(loopbackHost, s.port), post)
        val r = Recorder()
        client.open(r)
        val last = (1..50).map { i -> ByteArray(100) { i.toByte() } }
        last.forEach { assertTrue(client.trySend(it)) }
        client.close()
        assertFalse("nothing is accepted after close", client.trySend(byteArrayOf(1)))
        assertTrue(atHost.closed.await(10, TimeUnit.SECONDS))
        assertEquals(last.map { it.toList() }, atHost.received.map { it.toList() })
        assertTrue(r.closed.await(5, TimeUnit.SECONDS))
        assertEquals(0L, client.metrics.failures.get())
    }

    @Test fun anOversizedOrEmptyRecordClosesTheConnection() {
        for (length in listOf(0, LanLimits().maxRecord + 1, 0xFFFF)) {
            val atHost = Recorder()
            val s = server { it.open(atHost) }
            Socket(loopback, s.port).use { raw ->
                val out = DataOutputStream(raw.getOutputStream())
                out.writeShort(length)
                out.write(ByteArray(minOf(length, 64)))
                out.flush()
                assertTrue("record of $length bytes accepted", atHost.closed.await(5, TimeUnit.SECONDS))
            }
            assertTrue(atHost.received.isEmpty())
            waitFor { s.openConnections == 0 }
        }
    }

    @Test fun aChunkLargerThanARecordIsRefusedBeforeSending() {
        val s = server { it.open(Recorder()) }
        val client = SocketTransport.connect(LanEndpoint(loopbackHost, s.port), post)
        client.open(Recorder())
        val error = runCatching { client.trySend(ByteArray(LanLimits().maxRecord + 1)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        client.close()
    }

    @Test fun connectionsBeyondTheCapAreClosedAtAccept() {
        val peers = Collections.synchronizedList(ArrayList<SocketTransport>())
        val s = server(maxConnections = 2) { peers += it; it.open(Recorder()) }
        val sockets = (1..4).map { Socket(loopback, s.port) }
        waitFor { peers.size == 2 && s.refused == 2L }
        assertEquals(2, s.openConnections)
        sockets.forEach { it.close() }
        waitFor { s.openConnections == 0 }
        s.stop()
        s.stop()
    }

    @Test fun aFullWindowRefusesUntilWritable() {
        val limits = LanLimits(window = 4)
        // Sends outrun the writer thread; the host starts reading only later.
        val accepted = Collections.synchronizedList(ArrayList<SocketTransport>())
        val s = server(limits = limits) { accepted += it }
        val writable = AtomicInteger()
        val client = SocketTransport.connect(LanEndpoint(loopbackHost, s.port), post, limits)
        client.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = Unit
            override fun onWritable() {
                writable.incrementAndGet()
            }
            override fun onClosed() = Unit
        })
        waitFor { accepted.size == 1 }
        // The writer drains into the socket buffers until they are full (the host is not reading),
        // so one refusal only means "behind for a moment". Keep offering until refusals have lasted
        // 500 ms: then the writer is blocked in write and the window is really full.
        var sent = 0
        var refusedSince = -1L
        val end = System.currentTimeMillis() + 30_000
        while (true) {
            val now = System.currentTimeMillis()
            check(now < end) { "the window never stayed full after $sent chunks" }
            if (client.trySend(ByteArray(160))) {
                sent++
                refusedSince = -1
            } else if (refusedSince < 0) {
                refusedSince = now
            } else if (now - refusedSince >= 500) {
                break
            } else {
                Thread.sleep(1)
            }
        }
        assertFalse("refused while full", client.trySend(ByteArray(160)))
        val before = writable.get()
        val atHost = Recorder()
        accepted.single().open(atHost)
        waitFor(10_000) { writable.get() > before }
        waitFor(10_000) { atHost.received.size == sent }
        client.close()
    }

    @Test fun connectingToNobodyCloses() {
        val port = java.net.ServerSocket(0, 1, loopback).use { it.localPort }
        val r = Recorder()
        SocketTransport.connect(LanEndpoint(loopbackHost, port), post).open(r)
        assertTrue(r.closed.await(10, TimeUnit.SECONDS))
        assertArrayEquals(arrayOf<ByteArray>(), r.received.toTypedArray())
    }

    /** A server shutting down can close an accepted connection before the session ever opened it. */
    @Test fun closingAnUnopenedAcceptedConnectionClosesItsSocket() {
        java.net.ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { remote ->
                val accepted = listener.accept()
                val t = SocketTransport.accepted(accepted, post, LanLimits(lingerMs = 0)) { }
                t.close()
                waitFor { accepted.isClosed }
                remote.soTimeout = 5_000
                assertEquals("the peer sees end of stream", -1, remote.getInputStream().read())
            }
        }
    }
    /** Round 3 #7: a peer sending faster than the session loop works is held back, not queued without bound. */
    @Test fun receivedChunksWaitingForTheLoopAreBounded() {
        val held = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        val holdingPost: (() -> Unit) -> Unit = { held += it }
        val limits = LanLimits(receiveWindow = 8)
        val total = 2_000
        java.net.ServerSocket(0, 1, loopback).use { listener ->
            val r = Recorder()
            val client = SocketTransport.connect(LanEndpoint(loopbackHost, listener.localPort), holdingPost, limits)
            client.open(r)
            listener.accept().use { peer ->
                val writer = Thread {
                    runCatching {
                        val out = DataOutputStream(peer.getOutputStream())
                        repeat(total) { out.writeShort(4); out.writeInt(it) }
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
                waitFor { held.size >= limits.receiveWindow }
                Thread.sleep(300) // a reader that ignored the bound would keep posting meanwhile
                assertEquals("the loop is handed at most the receive window", limits.receiveWindow, held.size)
                // The loop catches up: everything arrives, in order.
                waitFor(10_000) {
                    while (true) (held.poll() ?: break)()
                    r.received.size == total
                }
                assertEquals((0 until total).toList(), r.received.map { java.nio.ByteBuffer.wrap(it).int })
                writer.join(5_000)
            }
            client.close()
        }
    }
}
