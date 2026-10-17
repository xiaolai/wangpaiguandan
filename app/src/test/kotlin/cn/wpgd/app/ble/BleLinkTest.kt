package cn.wpgd.app.ble

import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.transport.VirtualScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * WI-10: [BleLink] is a session [Transport] over one GATT connection: records (session chunks) are
 * split at the link's ATT payload, one packet in flight, reassembled on the other side; backpressure,
 * graceful close and every listener callback go through the owner's loop, as the LAN adapter does.
 */
class BleLinkTest {
    private class Recorder : TransportListener {
        val received = ArrayList<ByteArray>()
        var writable = 0
        var closed = 0
        override fun onReceive(chunk: ByteArray) { received += chunk }
        override fun onWritable() { writable++ }
        override fun onClosed() { closed++ }
    }

    /** A host and a joiner over the fake air; callbacks are posted to [posted] and run by [drain]. */
    private inner class Pair(faults: BleFaults = BleFaults(), limits: BleLimits = BleLimits(), seed: Long = 1) {
        val scheduler = VirtualScheduler()
        val air = FakeAir(scheduler, seed, faults)
        val posted = ArrayList<() -> Unit>()
        val post: (() -> Unit) -> Unit = { task -> posted += task; scheduler.schedule(0) { drain() } }
        val hostRadio = air.radio()
        val joinRadio = air.radio()
        var hostLink: BleLink? = null
        val host = Recorder()
        val joiner = Recorder()
        val server = hostRadio.openServer { port -> hostLink = BleLink(port, post, scheduler, limits).also { it.open(host) } }
        val joinLink = BleLink(joinRadio.connect(hostRadio.address), post, scheduler, limits).also { it.open(joiner) }

        fun drain() {
            while (posted.isNotEmpty()) posted.removeAt(0)()
        }

        fun run(ms: Long = 5_000) = scheduler.runFor(ms)

        fun until(ms: Long = 60_000, done: () -> Boolean) = assertTrue("condition not reached", scheduler.runUntil(ms, done = done))
    }

    /** Hands [c] to the joiner's link, waiting for room; fails (never hangs) if the link stays full or closes. */
    private fun offer(p: Pair, c: ByteArray) {
        repeat(10_000) {
            if (p.joinLink.trySend(c)) return
            check(!p.joinLink.disposed) { "the link closed" }
            p.run(10)
        }
        throw AssertionError("the link never took the chunk")
    }

    private fun chunk(n: Int, size: Int = BleLimits().maxRecord): ByteArray = Random(n).nextBytes(size).also { it[0] = (n ushr 8).toByte(); it[1] = n.toByte() }

    @Test fun recordsCrossWholeAndInOrderAtTheDefaultAndALargeMtu() {
        for (mtu in listOf(23, 185, 247)) {
            val p = Pair(BleFaults(mtu = mtu))
            val sent = (0 until 40).map { chunk(it, 2 + (it * 37) % (BleLimits().maxRecord - 1)) }
            sent.forEach { assertTrue(p.joinLink.trySend(it)) }
            p.until { p.host.received.size == sent.size }
            assertEquals(sent.map { it.toList() }, p.host.received.map { it.toList() })
            // And back.
            sent.forEach { assertTrue(p.hostLink!!.trySend(it)) }
            p.until { p.joiner.received.size == sent.size }
            assertEquals(sent.map { it.toList() }, p.joiner.received.map { it.toList() })
            assertEquals(0L, p.air.overlappingSends)
        }
    }

    @Test fun packetsPerRecordFollowTheMtu() {
        val big = Pair(BleFaults(mtu = 185))
        big.joinLink.trySend(chunk(1))
        big.until { big.host.received.size == 1 }
        assertEquals(1L, big.joinLink.metrics.packetsOut.get())
        val small = Pair(BleFaults(mtu = 23))
        small.joinLink.trySend(chunk(1))
        small.until { small.host.received.size == 1 }
        assertEquals(13L, small.joinLink.metrics.packetsOut.get())
        assertEquals(20, small.joinLink.metrics.attPayload)
        assertEquals(182, big.joinLink.metrics.attPayload)
    }

    @Test fun anAttPayloadAboveTheAttributeLimitIsCappedAt512() {
        val p = Pair(BleFaults(mtu = 517))
        p.until { p.hostLink != null }
        p.run(200)
        assertEquals(512, p.joinLink.metrics.attPayload)
    }

    @Test fun chunksSentBeforeTheConnectionIsReadyWaitAndThenGo() {
        val p = Pair(BleFaults(connectMs = 500))
        assertTrue(p.joinLink.trySend(chunk(1)))
        p.run(400)
        assertTrue(p.host.received.isEmpty())
        p.until { p.host.received.size == 1 }
        assertTrue(p.joinLink.metrics.readyMs.get() >= 500)
    }

    @Test fun aFullWindowRefusesAndOnWritableFollows() {
        val limits = BleLimits(window = 8)
        val p = Pair(limits = limits)
        p.until { p.hostLink != null }
        var accepted = 0
        while (p.joinLink.trySend(chunk(accepted))) accepted++
        assertEquals(8, accepted)
        assertEquals(0, p.joiner.writable)
        p.until { p.joiner.writable == 1 }
        assertTrue(p.joinLink.trySend(chunk(accepted)))
        p.until { p.host.received.size == accepted + 1 }
        assertEquals((0..accepted).map { chunk(it).toList() }, p.host.received.map { it.toList() })
        assertEquals(8, p.joinLink.metrics.queueHigh.get())
    }

    @Test fun listenerCallbacksRunOnlyFromThePostedTasks() {
        val p = Pair()
        p.until { p.hostLink != null }
        val direct = object : TransportListener {
            var inside = false
            var received = 0
            var closed = 0
            override fun onReceive(chunk: ByteArray) { check(inside) { "callback ran outside post" }; received++ }
            override fun onWritable() = check(inside)
            override fun onClosed() { check(inside); closed++ }
        }
        // A second joiner whose posted tasks are run by hand, flagged.
        val queue = ArrayList<() -> Unit>()
        val first = p.hostLink
        val link = BleLink(p.air.radio().connect(p.hostRadio.address), { queue += it }, p.scheduler, BleLimits())
        link.open(direct)
        p.until { p.hostLink !== first }
        assertTrue(p.hostLink!!.trySend(chunk(1)))
        p.run(1_000)
        link.close()
        p.run(3_000)
        assertEquals(0, direct.received)
        direct.inside = true
        queue.forEach { it() }
        assertEquals(1, direct.received)
        assertEquals(1, direct.closed)
    }

    @Test fun gracefulCloseStillDeliversWhatWasAcceptedThenDisconnects() {
        val p = Pair(BleFaults(mtu = 23))
        p.until { p.hostLink != null }
        val sent = (0 until 10).map { chunk(it) }
        sent.forEach { assertTrue(p.joinLink.trySend(it)) }
        p.joinLink.close()
        assertFalse("nothing is accepted after close", p.joinLink.trySend(chunk(99)))
        p.until { p.host.received.size == sent.size }
        assertEquals(sent.map { it.toList() }, p.host.received.map { it.toList() })
        p.until { p.host.closed == 1 }
        p.run(5_000)
        assertEquals("closed is signalled once", 1, p.joiner.closed)
        assertEquals(1, p.host.closed)
    }

    @Test fun aStalledRadioIsDisconnectedAfterTheLinger() {
        val limits = BleLimits(lingerMs = 1_000)
        val p = Pair(BleFaults(stall = true), limits)
        p.until { p.hostLink != null }
        p.joinLink.trySend(chunk(1))
        p.joinLink.trySend(chunk(2))
        p.run(100)
        p.joinLink.close()
        p.run(900)
        assertFalse(p.joinLink.disposed)
        p.run(200)
        assertTrue(p.joinLink.disposed)
        p.until { p.host.closed == 1 }
    }

    @Test fun aDisconnectClosesOnceAndRefusesLaterSends() {
        val p = Pair()
        p.until { p.hostLink != null }
        p.air.cut(p.hostRadio.address)
        p.until { p.joiner.closed == 1 && p.host.closed == 1 }
        assertFalse(p.joinLink.trySend(chunk(1)))
        p.joinLink.close()
        p.run(3_000)
        assertEquals(1, p.joiner.closed)
    }

    @Test fun aConnectionToNoHostCloses() {
        val scheduler = VirtualScheduler()
        val air = FakeAir(scheduler, 1)
        val r = Recorder()
        val link = BleLink(air.radio().connect("02:00:00:00:99:99"), { it() }, scheduler, BleLimits())
        link.open(r)
        assertTrue(link.trySend(chunk(1)))
        assertTrue(scheduler.runUntil(5_000) { r.closed == 1 })
        assertFalse(link.trySend(chunk(2)))
    }

    @Test fun aFatalPacketFromThePeerClosesTheLink() {
        val p = Pair()
        p.until { p.hostLink != null }
        p.run(100)
        // A hostile joiner writes a header announcing a record above the limit.
        val evil = byteArrayOf(0xB7.toByte(), 0, 0, 1, 0x0F, 0xFF.toByte()) + ByteArray(10)
        val raw = p.joinLink.portForTest
        assertTrue(raw.send(evil))
        p.until { p.host.closed == 1 }
        assertEquals(1L, p.hostLink!!.metrics.violations[BleViolation.OVERSIZED.ordinal].get())
    }

    @Test fun aFailedSendDropsThatRecordOnlyAndCountsIt() {
        val p = Pair(BleFaults(mtu = 23, sendFailure = 0.03), seed = 4)
        p.until { p.hostLink != null }
        val sent = (0 until 100).map { chunk(it) }
        for (c in sent) {
            offer(p, c)
        }
        p.run(60_000)
        val got = p.host.received.map { it.toList() }
        assertTrue(p.joinLink.metrics.sendFailures.get() > 0)
        assertTrue("some records lost", got.size < sent.size)
        assertTrue("most records arrive", got.size > sent.size / 2)
        // What arrives is whole, sent, in order.
        val order = got.map { r -> sent.indexOfFirst { it.toList() == r } }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test fun aBiggerMtuAfterConnectingIsUsedForTheNextRecords() {
        val p = Pair(BleFaults(mtu = 23))
        p.until { p.hostLink != null }
        p.run(100)
        p.joinLink.trySend(chunk(1))
        p.until { p.host.received.size == 1 }
        assertEquals(13L, p.joinLink.metrics.packetsOut.get())
        // The MTU exchange completes late: the air now carries 182 bytes and the link is told so.
        p.air.faults = p.air.faults.copy(mtu = 185)
        p.joinLink.portEventsForTest.onAttPayload(182)
        p.joinLink.trySend(chunk(2))
        p.until { p.host.received.size == 2 }
        assertEquals(14L, p.joinLink.metrics.packetsOut.get())
        assertEquals(182, p.joinLink.metrics.attPayload)
        assertArrayEquals(chunk(2), p.host.received[1])
    }

    @Test fun underRadioFaultsEveryChunkDeliveredWasSentAndNoneTwiceOrOutOfOrder() {
        for (seed in 1L..6L) {
            val p = Pair(BleFaults(mtu = listOf(23, 185)[(seed % 2).toInt()], drop = 0.02, duplicate = 0.05, truncate = 0.01, reorder = 0.05), seed = seed)
            p.until { p.hostLink != null }
            val sent = (0 until 150).map { chunk(it, 2 + (it * 53) % (BleLimits().maxRecord - 1)) }
            for (c in sent) offer(p, c)
            p.run(120_000)
            val got = p.host.received.map { r -> sent.indexOfFirst { it.toList() == r.toList() } }
            assertTrue("seed $seed: unsent chunk delivered", got.all { it >= 0 })
            assertEquals("seed $seed: duplicate delivered", got.size, got.toSet().size)
            assertTrue("seed $seed: most chunks arrive", got.size > sent.size / 2)
            assertEquals("seed $seed: honest faults never close the link", 0, p.host.closed)
        }
    }

    @Test fun metricsExposeWhatTheDeviceSpikeMeasures() {
        val p = Pair(BleFaults(mtu = 185, latencyMs = 12, connectMs = 150))
        p.until { p.hostLink != null }
        repeat(20) { p.joinLink.trySend(chunk(it)) }
        p.until { p.host.received.size == 20 }
        val m = p.joinLink.metrics
        assertEquals(182, m.attPayload)
        assertTrue(m.readyMs.get() >= 150)
        assertEquals(20L, m.recordsOut.get())
        assertEquals(20L * BleLimits().maxRecord, m.recordBytesOut.get())
        assertEquals(12L, m.packetMs.percentile(95.0))
        assertTrue(m.recordMs.percentile(95.0) >= 12)
        assertEquals(20L, p.hostLink!!.metrics.recordsIn.get())
        val text = m.summary()
        for (key in listOf("att=182", "ready_ms=", "packets_out=20", "packet_ms_p95=12", "record_ms_p95=", "send_failures=0")) {
            assertTrue("$key in $text", key in text)
        }
    }

    @Test fun anOversizedChunkIsAProgrammingError() {
        val p = Pair()
        try {
            p.joinLink.trySend(ByteArray(BleLimits().maxRecord + 1))
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    /**
     * Audit #7: the last record and a disconnect arrive on two radio threads at once. The loop must see
     * the record before the close (the session drops what arrives after its connection closed).
     */
    @Test fun aRecordCompletedBeforeADisconnectIsPostedBeforeTheClose() {
        val port = object : BlePort {
            override val address = "AA:BB:CC:DD:EE:FF"
            override fun open(events: BlePortEvents) = Unit
            override fun send(packet: ByteArray) = true
            override fun close() = Unit
        }
        val packetThread = Thread.currentThread()
        val inPost = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val posted = java.util.Collections.synchronizedList(ArrayList<() -> Unit>())
        val post: (() -> Unit) -> Unit = { task ->
            // The packet's thread is held where it hands the record to the loop.
            if (Thread.currentThread() === packetThread && inPost.count > 0) {
                inPost.countDown()
                release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            posted += task
        }
        val link = BleLink(port, post, VirtualScheduler())
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        link.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) { order += "receive" }
            override fun onWritable() = Unit
            override fun onClosed() { order += "closed" }
        })
        val packet = BlePacketCodec.split(0, byteArrayOf(1, 2, 3), 185).single()
        val disconnect = Thread {
            inPost.await(2, java.util.concurrent.TimeUnit.SECONDS)
            link.portEventsForTest.onDisconnected()
        }.apply { start() }
        // Lets the disconnect run while the record is being handed over, then lets the packet finish.
        val releaser = Thread {
            inPost.await(2, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(300)
            release.countDown()
        }.apply { start() }
        link.portEventsForTest.onPacket(packet)
        disconnect.join(5_000)
        releaser.join(5_000)
        synchronized(posted) { posted.toList() }.forEach { it() }
        assertEquals(listOf("receive", "closed"), order)
    }

    @Test fun theHostSideServerCountsConnections() {
        val p = Pair()
        p.until { p.hostLink != null }
        val second = BleLink(p.air.radio().connect(p.hostRadio.address), p.post, p.scheduler, BleLimits())
        second.open(Recorder())
        p.run(500)
        assertEquals(2, p.server.stats.connected)
        assertEquals(2, p.server.stats.maxConnected)
        second.close()
        p.run(5_000)
        assertEquals(1, p.server.stats.connected)
        assertEquals(2, p.server.stats.maxConnected)
    }
}
