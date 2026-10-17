package cn.wpgd.app.session

import cn.wpgd.app.session.channel.Direction
import cn.wpgd.app.session.channel.Inbound
import cn.wpgd.app.session.channel.Opener
import cn.wpgd.app.session.channel.OutKind
import cn.wpgd.app.session.channel.PeerLink
import cn.wpgd.app.session.channel.Sealer
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.framing.Reassembler
import cn.wpgd.app.session.framing.Violation
import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.transport.VirtualScheduler
import cn.wpgd.app.session.wire.Snapshot
import cn.wpgd.app.session.wire.WireMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GDN-022: truncated, duplicated, reordered and oversized chunks never yield a partial accepted frame. */
class FramingFuzzTest {
    private val limits = FramingLimits(maxChunkPayload = 200, maxFrameSize = 8_000, maxPartialFrames = 4, rememberedFrameIds = 64)

    @Test fun seededChunkFaultsNeverYieldAPartialOrForeignFrame() {
        var completed = 0
        var complete = 0
        repeat(CASES) { case ->
            val r = Random(case.toLong())
            val frames = (1..r.nextInt(1, 6)).associate { id -> id.toLong() to r.nextBytes(r.nextInt(1, 3_000)) }
            val chunkSize = r.nextInt(1, limits.maxChunkPayload + 1)
            val clean = frames.flatMap { (id, f) -> ChunkCodec.split(id, f, chunkSize) }
            val mangled = ArrayList<ByteArray>()
            val damaged = HashSet<Long>()
            for (c in clean) {
                val id = frameIdOf(c)
                when (r.nextInt(20)) {
                    0 -> damaged += id // dropped
                    1 -> { mangled += c.copyOf(r.nextInt(c.size)); damaged += id } // truncated, original lost
                    2 -> { mangled += c; mangled += c } // duplicated
                    else -> mangled += c
                }
            }
            if (r.nextInt(4) == 0) mangled += oversizedChunk(r)
            // Reorder: a bounded shuffle (each chunk moves a few places).
            for (i in mangled.indices) {
                val j = (i + r.nextInt(0, 4)).coerceAtMost(mangled.lastIndex)
                val t = mangled[i]; mangled[i] = mangled[j]; mangled[j] = t
            }
            val reassembler = Reassembler(limits)
            val got = HashMap<Long, Int>()
            for (chunk in mangled) {
                when (val result = reassembler.accept(chunk)) {
                    is Reassembler.Result.Complete -> {
                        val original = frames[result.frameId]
                        assertTrue(original != null, "case $case: frame ${result.frameId} was never sent")
                        assertContentEquals(original, result.frame, "case $case: frame ${result.frameId} is not byte-identical")
                        got[result.frameId] = (got[result.frameId] ?: 0) + 1
                        completed++
                    }
                    is Reassembler.Result.Rejected -> assertTrue(result.violation in setOf(Violation.TRUNCATED, Violation.OVERSIZED, Violation.MALFORMED, Violation.CONFLICT))
                    else -> Unit
                }
                assertTrue(reassembler.partialCount <= limits.maxPartialFrames, "case $case: partial frames unbounded")
            }
            assertTrue(got.values.all { it == 1 }, "case $case: a frame completed twice: $got")
            assertTrue(got.keys.none { it in damaged }, "case $case: a frame with a lost chunk completed: ${got.keys intersect damaged}")
            if (damaged.isEmpty() && frames.size <= limits.maxPartialFrames) {
                assertEquals(frames.keys, got.keys, "case $case: undamaged frames must all complete")
                complete++
            }
        }
        assertTrue(completed > CASES / 2, "the fuzz must exercise completion ($completed)")
        assertTrue(complete > CASES / 20, "enough undamaged cases ($complete)")
        println("FramingFuzz: $CASES cases, $completed frames completed, $complete fully clean cases")
    }

    @Test fun oversizedFrameHeaderIsFatal() {
        val r = Reassembler(limits)
        val result = r.accept(oversizedChunk(Random(1)))
        assertIs<Reassembler.Result.Rejected>(result)
        assertEquals(Violation.OVERSIZED, result.violation)
        assertTrue(result.violation.fatal)
    }

    @Test fun conflictingChunkDiscardsThePartialFrame() {
        val r = Reassembler(limits)
        val frame = ByteArray(500) { it.toByte() }
        val chunks = ChunkCodec.split(9, frame, 100)
        r.accept(chunks[0])
        val forged = chunks[1].copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        r.accept(chunks[1])
        assertIs<Reassembler.Result.Rejected>(r.accept(forged))
        for (c in chunks.drop(2)) assertTrue(r.accept(c) !is Reassembler.Result.Complete, "a conflicted frame must never complete")
    }

    @Test fun headerWithImpossibleFieldsIsMalformed() {
        val ok = ChunkCodec.encode(1, 0, 1, 10, ByteArray(10))
        val badMagic = ok.copyOf().also { it[2] = 0 }
        val indexPastCount = ChunkCodec.encode(1, 3, 2, 10, ByteArray(5))
        val payloadTooBig = ChunkCodec.encode(1, 0, 1, 300, ByteArray(300))
        for (bad in listOf(badMagic, indexPastCount, payloadTooBig)) {
            val d = ChunkCodec.decode(bad, limits)
            assertIs<ChunkCodec.Decoded.Bad>(d)
            assertEquals(Violation.MALFORMED, d.violation)
        }
        assertIs<ChunkCodec.Decoded.Ok>(ChunkCodec.decode(ok, limits))
    }

    /** A peer may chunk more finely than this end does; small chunks are not a protocol violation. */
    @Test fun frameSplitIntoOneBytePayloadsReassembles() {
        val defaults = FramingLimits()
        val frame = ByteArray(1_000) { it.toByte() }
        val r = Reassembler(defaults)
        val results = ChunkCodec.split(3, frame, 1).map { r.accept(it) }
        assertTrue(results.none { it is Reassembler.Result.Rejected }, "an honest one-byte chunking was rejected")
        val done = results.last()
        assertIs<Reassembler.Result.Complete>(done)
        assertContentEquals(frame, done.frame)
    }

    /** The largest accepted frame must be splittable at this end's chunk size (u16 count), without Int overflow. */
    @Test fun limitsRejectAFrameSizeTheWireCannotCount() {
        assertFailsWith<IllegalArgumentException> { FramingLimits(maxChunkPayload = 1) }
        assertFailsWith<IllegalArgumentException> { FramingLimits(maxChunkPayload = 2, maxFrameSize = Int.MAX_VALUE) }
        val edge = FramingLimits(maxChunkPayload = 1, maxFrameSize = 0xFFFF)
        assertEquals(0xFFFF, ChunkCodec.split(1, ByteArray(edge.maxFrameSize), edge.maxChunkPayload).size)
    }

    /** A frame size near Int.MAX_VALUE is accepted by the limits; its chunk count must not overflow Int. */
    @Test fun chunkCountOfTheLargestAcceptedFrameDoesNotOverflow() {
        val payload = 0xFFFF - ChunkCodec.HEADER_SIZE
        val huge = FramingLimits(maxChunkPayload = payload, maxFrameSize = Int.MAX_VALUE)
        val expected = (Int.MAX_VALUE.toLong() + payload - 1) / payload
        assertEquals(expected, ChunkCodec.chunkCount(huge.maxFrameSize, huge.maxChunkPayload))
        assertEquals(1L, ChunkCodec.chunkCount(1, payload))
        assertEquals(2L, ChunkCodec.chunkCount(payload + 1, payload))
    }

    /** End to end over the faulty in-memory transport with sealing: every decoded message is one that was sent. */
    @Test fun sealedLinkOverFaultyTransportDeliversOnlyWholeAuthenticMessages() {
        repeat(60) { case ->
            val scheduler = VirtualScheduler()
            val net = InMemoryNetwork(scheduler, case.toLong())
            val link = net.link(LinkFaults(latencyMs = 3, jitterMs = 10, dropRate = 0.02, duplicateRate = 0.1, reorderRate = 0.3, truncateRate = 0.05))
            val key = ByteArray(32) { (it + case).toByte() }
            val sender = PeerLink(link.hostEnd, limits, 1_000).also {
                it.installKeys(Sealer(JcaAesGcm, key, Direction.HOST_TO_CLIENT), Opener(JcaAesGcm, ByteArray(32), Direction.CLIENT_TO_HOST))
            }
            val receiver = PeerLink(link.clientEnd, limits, 1_000).also {
                it.installKeys(Sealer(JcaAesGcm, ByteArray(32), Direction.CLIENT_TO_HOST), Opener(JcaAesGcm, key, Direction.HOST_TO_CLIENT))
            }
            val sent = HashSet<WireMessage>()
            val received = ArrayList<WireMessage>()
            link.hostEnd.open(object : TransportListener {
                override fun onReceive(chunk: ByteArray) = Unit
                override fun onWritable() = sender.pump()
                override fun onClosed() = Unit
            })
            link.clientEnd.open(object : TransportListener {
                override fun onReceive(chunk: ByteArray) {
                    when (val i = receiver.receive(chunk)) {
                        is Inbound.Sealed -> received += i.message
                        is Inbound.Bad -> assertTrue(!i.violation.fatal, "an honest sender never causes ${i.violation}")
                        else -> Unit
                    }
                }
                override fun onWritable() = Unit
                override fun onClosed() = Unit
            })
            val r = Random(case.toLong())
            repeat(40) { n ->
                val m = Snapshot(1, n.toLong(), "x".repeat(r.nextInt(1, 2_000)))
                sent += m
                sender.enqueue(OutKind.SNAPSHOT, m)
                sender.pump()
                scheduler.runFor(r.nextLong(0, 5))
            }
            scheduler.runFor(2_000)
            assertTrue(received.all { it in sent }, "case $case: a received message was never sent")
            assertEquals(received.size, received.toSet().size, "case $case: a message was accepted twice (replay)")
            assertTrue(received.isNotEmpty())
        }
    }

    private fun frameIdOf(chunk: ByteArray): Long =
        ((chunk[4].toLong() and 0xFF) shl 24) or ((chunk[5].toLong() and 0xFF) shl 16) or ((chunk[6].toLong() and 0xFF) shl 8) or (chunk[7].toLong() and 0xFF)

    private fun oversizedChunk(r: Random): ByteArray = ChunkCodec.encode(1_000 + r.nextLong(1_000), 0, 2, limits.maxFrameSize + 1 + r.nextInt(100_000), ByteArray(10))

    private companion object {
        const val CASES = 600
    }
}
