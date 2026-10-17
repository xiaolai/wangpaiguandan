package cn.wpgd.app.session

import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.transport.VirtualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transport.close: chunks already accepted are still delivered. On the in-memory network the peer
 * learns of a graceful close only after the last of them arrived, even when jitter and reordering
 * delay them past the base latency; an explicit disconnect stays abrupt.
 */
class InMemoryCloseOrderTest {
    private class Recorder(private val onClose: () -> Unit) : TransportListener {
        val events = ArrayList<String>()
        override fun onReceive(chunk: ByteArray) { events += "receive ${chunk[0]}" }
        override fun onWritable() = Unit
        override fun onClosed() { events += "closed"; onClose() }
    }

    @Test fun aGracefulCloseArrivesAfterEveryAcceptedChunk() {
        for (seed in 1L..20L) {
            val scheduler = VirtualScheduler()
            val link = InMemoryNetwork(scheduler, seed).link(LinkFaults(latencyMs = 2, jitterMs = 50, reorderRate = 0.5, reorderDelayMs = 40))
            // The receiver closes its own end when told the link closed, as the session does.
            val host = Recorder { link.hostEnd.close() }
            link.hostEnd.open(host)
            link.clientEnd.open(Recorder {})
            repeat(10) { i -> assertTrue(link.clientEnd.trySend(byteArrayOf(i.toByte()))) }
            link.clientEnd.close()
            scheduler.runFor(1_000)
            assertEquals(10, host.events.count { it.startsWith("receive") }, "seed $seed: every accepted chunk arrives: ${host.events}")
            assertEquals("closed", host.events.last(), "seed $seed: the close comes last: ${host.events}")
        }
    }

    @Test fun anExplicitDisconnectStaysAbrupt() {
        val scheduler = VirtualScheduler()
        val link = InMemoryNetwork(scheduler, 3).link(LinkFaults(latencyMs = 20))
        val host = Recorder {}
        link.hostEnd.open(host)
        link.clientEnd.open(Recorder {})
        assertTrue(link.clientEnd.trySend(byteArrayOf(1)))
        link.disconnect()
        scheduler.runFor(1_000)
        assertEquals(listOf("closed"), host.events)
    }

    /** Finding 57: once an end is told its peer closed, it is closed too: it accepts nothing more. */
    @Test fun anEndToldItsPeerClosedRefusesFurtherChunks() {
        val scheduler = VirtualScheduler()
        val link = InMemoryNetwork(scheduler, 4).link(LinkFaults(latencyMs = 5))
        val host = Recorder {}
        link.hostEnd.open(host)
        link.clientEnd.open(Recorder {})
        link.clientEnd.close()
        scheduler.runFor(100)
        assertEquals(listOf("closed"), host.events)
        kotlin.test.assertFalse(link.hostEnd.trySend(byteArrayOf(9)), "an endpoint whose peer closed must refuse chunks")
    }
}
