package cn.wpgd.app.ble

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.session.transport.VirtualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WI-10 (C1–C3 over Bluetooth): what the joiner's room list shows, from scans and advert reads. */
class BleRoomBrowserTest {
    private class Hosts : BleScanner {
        val adverts = LinkedHashMap<String, ByteArray?>()
        var onSeen: ((String) -> Unit)? = null
        var reads = 0
        var concurrentReads = 0
        var inRead = 0
        val pending = ArrayList<Pair<String, (ByteArray?) -> Unit>>()
        var stopped = false

        override fun start(onSeen: (address: String) -> Unit) { this.onSeen = onSeen }
        override fun stop() { stopped = true }
        override fun readAdvert(address: String, onResult: (ByteArray?) -> Unit) {
            reads++
            inRead++
            if (inRead > 1) concurrentReads++
            pending += address to onResult
        }

        /** Completes the oldest read with the host's current advert. */
        fun answer() {
            val (address, cb) = pending.removeAt(0)
            inRead--
            cb(adverts[address])
        }

        fun seeAll() = adverts.keys.toList().forEach { onSeen!!(it) }
    }

    private fun advert(id: String, name: String = "周末掼蛋") =
        RoomAdvert(id, name, "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, "a".repeat(64), "b".repeat(64), "K".repeat(43), setOf(Seat.EAST), BleEndpoint.LOCAL)

    private fun address(n: Int) = "4C:1D:2E:3F:40:%02X".format(n)

    private val scheduler = VirtualScheduler()
    private val hosts = Hosts()
    private var shown: List<RoomAdvert> = emptyList()
    private var reports = 0
    private val browser = BleRoomBrowser(hosts, scheduler, BleBrowserConfig(refreshMs = 10_000, lostMs = 15_000, maxHosts = 4))

    private fun start() = browser.start { shown = it; reports++ }

    @Test fun hostsAreReadOneAtATimeAndShownWithTheirScannedAddress() {
        hosts.adverts[address(1)] = BleAdvertCodec.encode(advert("t1"))
        hosts.adverts[address(2)] = BleAdvertCodec.encode(advert("t2", "另一桌"))
        start()
        assertEquals(emptyList<RoomAdvert>(), shown)
        hosts.seeAll()
        assertEquals(1, hosts.reads)
        hosts.answer()
        assertEquals(listOf("t1"), shown.map { it.roomId })
        assertEquals(BleEndpoint(address(1)), shown.single().endpoint)
        assertEquals(2, hosts.reads)
        hosts.answer()
        assertEquals(listOf("t1", "t2"), shown.map { it.roomId })
        assertEquals(0, hosts.concurrentReads)
    }

    @Test fun seeingAHostAgainDoesNotReReadUntilTheRefreshTime() {
        hosts.adverts[address(1)] = BleAdvertCodec.encode(advert("t1"))
        start()
        hosts.seeAll(); hosts.answer()
        repeat(5) { hosts.seeAll() }
        assertEquals(1, hosts.reads)
        scheduler.runFor(10_000)
        hosts.adverts[address(1)] = BleAdvertCodec.encode(advert("t1", "改名了"))
        hosts.seeAll()
        assertEquals(2, hosts.reads)
        hosts.answer()
        assertEquals("改名了", shown.single().tableName)
    }

    @Test fun aHostNoLongerSeenIsDropped() {
        hosts.adverts[address(1)] = BleAdvertCodec.encode(advert("t1"))
        start()
        hosts.seeAll(); hosts.answer()
        assertEquals(1, shown.size)
        scheduler.runFor(14_000)
        assertEquals(1, shown.size)
        scheduler.runFor(2_000)
        assertEquals(0, shown.size)
    }

    @Test fun malformedOrMissingAdvertsAreNeverShown() {
        hosts.adverts[address(1)] = byteArrayOf(1, 1, 1)
        hosts.adverts[address(2)] = null
        hosts.adverts[address(3)] = BleAdvertCodec.encode(advert("t3"))
        start()
        hosts.seeAll()
        repeat(3) { hosts.answer() }
        assertEquals(listOf("t3"), shown.map { it.roomId })
        // A host that starts serving garbage disappears from the list.
        scheduler.runFor(10_000)
        hosts.adverts[address(3)] = byteArrayOf(9)
        hosts.seeAll()
        while (hosts.pending.isNotEmpty()) hosts.answer()
        assertEquals(emptyList<String>(), shown.map { it.roomId })
    }

    @Test fun aCrowdOfHostsCostsBoundedReads() {
        for (n in 1..10) hosts.adverts[address(n)] = BleAdvertCodec.encode(advert("t$n"))
        start()
        hosts.seeAll()
        while (hosts.pending.isNotEmpty()) hosts.answer()
        assertEquals(4, hosts.reads)
        assertEquals(4, shown.size)
    }

    @Test fun oneRoomAtANewAddressReplacesItsOldAddress() {
        hosts.adverts[address(1)] = BleAdvertCodec.encode(advert("t1"))
        start()
        hosts.seeAll(); hosts.answer()
        // The host's random address rotated.
        hosts.adverts.remove(address(1))
        hosts.adverts[address(2)] = BleAdvertCodec.encode(advert("t1"))
        hosts.seeAll(); hosts.answer()
        assertEquals(listOf(BleEndpoint(address(2))), shown.map { it.endpoint })
    }

    @Test fun stoppingStopsTheScanAndIgnoresLateResults() {
        hosts.adverts[address(1)] = BleAdvertCodec.encode(advert("t1"))
        start()
        hosts.seeAll()
        browser.stop()
        assertTrue(hosts.stopped)
        val before = reports
        hosts.answer()
        hosts.seeAll()
        scheduler.runFor(60_000)
        assertEquals(before, reports)
    }

    @Test fun badAddressesFromTheScannerAreIgnored() {
        start()
        hosts.onSeen!!("not an address")
        assertEquals(0, hosts.reads)
    }

    @Test fun aHostWithoutAnAdvertIsReadAgainOnlyAfterTheRetryTime() {
        hosts.adverts[address(1)] = null
        hosts.adverts[address(2)] = byteArrayOf(1, 1, 1)
        start()
        repeat(20) {
            hosts.seeAll()
            while (hosts.pending.isNotEmpty()) hosts.answer()
        }
        assertEquals("one read per host, not one per scan", 2, hosts.reads)
        scheduler.runFor(BleBrowserConfig().retryMs)
        hosts.seeAll()
        while (hosts.pending.isNotEmpty()) hosts.answer()
        assertEquals(4, hosts.reads)
    }
    @Test fun anOlderReadSnapshotNeverOverwritesANewerSweepDeliveredMeanwhile() {
        // Round 3 #2: a read (radio thread) and a sweep (scheduler thread) each take their snapshot
        // under the lock and deliver it outside; the older one must not arrive last.
        hosts.adverts[address(1)] = BleAdvertCodec.encode(advert("t1"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val inCallback = java.util.concurrent.atomic.AtomicInteger()
        val overlaps = java.util.concurrent.atomic.AtomicInteger()
        val last = java.util.concurrent.atomic.AtomicReference<List<RoomAdvert>?>(null)
        browser.start { rooms ->
            if (inCallback.incrementAndGet() > 1) overlaps.incrementAndGet()
            if (rooms.isNotEmpty()) { entered.countDown(); release.await() }
            last.set(rooms)
            inCallback.decrementAndGet()
        }
        hosts.seeAll()
        val reader = Thread { hosts.answer() }.apply { start() } // delivers [t1], held in the callback
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val sweeper = Thread { scheduler.runFor(16_000) }.apply { start() } // the host is lost: delivers []
        sweeper.join(500)
        release.countDown()
        reader.join(5_000)
        sweeper.join(5_000)
        assertEquals(0, overlaps.get())
        assertEquals("the newest snapshot is the one shown", emptyList<RoomAdvert>(), last.get())
    }
}
