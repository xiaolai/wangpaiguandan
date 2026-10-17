package cn.wpgd.app.ble

import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.transport.VirtualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Races between a Bluetooth room's callbacks and its shutdown. */
class BleListenerRaceTest {
    /** Audit #8: an accept queued on the loop, then the listener closes: the room never gets that link. */
    @Test fun anAcceptQueuedBeforeTheListenerClosedHandsNothingToTheRoom() {
        val scheduler = VirtualScheduler()
        val air = FakeAir(scheduler, 1)
        val hostRadio = air.radio()
        val queued = ArrayList<() -> Unit>()
        val peers = ArrayList<Transport>()
        val listener = BleRoomNetwork(hostRadio, scheduler).listen({ queued += it }) { peers += it }
        val joiner = BleRoomNetwork(air.radio(), scheduler).connect(BleEndpoint(hostRadio.address)) { it() }
        joiner.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = Unit
            override fun onWritable() = Unit
            override fun onClosed() = Unit
        })
        assertTrue(scheduler.runUntil(5_000) { queued.isNotEmpty() })
        listener.close()
        queued.toList().forEach { it() }
        assertEquals("a link closed with its listener is not handed to the room", emptyList<Transport>(), peers)
    }

    private class Scanner : BleScanner {
        var running = false
        val calls = ArrayList<String>()
        override fun start(onSeen: (address: String) -> Unit) { running = true; calls += "start" }
        override fun stop() { running = false; calls += "stop" }
        override fun readAdvert(address: String, onResult: (ByteArray?) -> Unit) = Unit
    }

    /** Audit #9: stopping the browser from its first callback leaves the scanner stopped. */
    @Test fun aStopDuringTheFirstCallbackLeavesTheScannerStopped() {
        val scanner = Scanner()
        val browser = BleRoomBrowser(scanner, VirtualScheduler())
        browser.start { browser.stop() }
        assertEquals(false, scanner.running)
    }
}
