package cn.wpgd.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * WI-10 (GDN-022 on the device): one session chunk (a "record", at most [BleLimits.maxRecord] bytes)
 * travels as one or more GATT packets no larger than the link's ATT payload (MTU - 3). Whatever the
 * radio does — lose, duplicate, reorder, truncate or forge packets — the receiver hands up only whole
 * records that were sent, never a partial or spliced one, and its memory stays bounded.
 */
class BleChunkingTest {
    private val limits = BleLimits()

    private fun record(size: Int, seed: Int = size): ByteArray = Random(seed).nextBytes(size)

    private fun Reassembly.record(): ByteArray = (this as Reassembly.Record).bytes

    // ---- Splitting at the MTU -------------------------------------------------------------------

    @Test fun everyPacketFitsTheAttPayloadForEachCommonMtu() {
        for (mtu in listOf(23, 27, 185, 247, 517)) {
            // An attribute value is at most 512 bytes whatever the MTU.
            val att = minOf(mtu - 3, limits.maxPacket)
            for (size in listOf(1, 2, att - BlePacketCodec.HEADER_SIZE, att - BlePacketCodec.HEADER_SIZE + 1, limits.maxRecord)) {
                if (size < 1 || size > limits.maxRecord) continue
                val packets = BlePacketCodec.split(7, record(size), att)
                assertTrue("mtu $mtu size $size", packets.all { it.size <= att })
                val payload = att - BlePacketCodec.HEADER_SIZE
                assertEquals("mtu $mtu size $size", (size + payload - 1) / payload, packets.size)
                val r = BleReassembler(limits)
                val out = packets.map { r.accept(it) }
                assertTrue(out.dropLast(1).all { it == Reassembly.Incomplete })
                assertArrayEquals(record(size), out.last().record())
            }
        }
    }

    @Test fun theLargestSessionChunkFitsOnePacketAtTheCommonIphoneMtu() {
        // 176-byte chunk + 6-byte header = 182 = 185 - 3: one write per chunk at MTU 185.
        assertEquals(1, BlePacketCodec.split(0, record(limits.maxRecord), 185 - 3).size)
        assertEquals(2, BlePacketCodec.split(0, record(limits.maxRecord), 184 - 3).size)
    }

    @Test fun theMinimumMtuStillCarriesTheLargestRecord() {
        val packets = BlePacketCodec.split(1, record(limits.maxRecord), BleLimits.MIN_ATT_PAYLOAD)
        assertEquals(13, packets.size) // ceil(176 / 14)
        val r = BleReassembler(limits)
        assertArrayEquals(record(limits.maxRecord), packets.map { r.accept(it) }.last().record())
    }

    @Test fun splittingRefusesWhatCannotBeSent() {
        assertThrows { BlePacketCodec.split(0, ByteArray(0), 20) }
        assertThrows { BlePacketCodec.split(0, record(10), BlePacketCodec.HEADER_SIZE) }
        assertThrows { BlePacketCodec.split(BlePacketCodec.SEQ_SPACE, record(10), 20) }
        assertThrows { BlePacketCodec.split(-1, record(10), 20) }
        // More than 255 packets cannot be numbered.
        assertThrows { BlePacketCodec.split(0, record(256), BlePacketCodec.HEADER_SIZE + 1) }
        assertThrows { BlePacketCodec.split(0, record(0x10000), 512) }
        assertThrows { BlePacketCodec.split(0, record(BlePacketCodec.MAX_LENGTH + 1), 512) }
    }

    @Test fun headerLayoutIsFixed() {
        val p = BlePacketCodec.split(0xAB, byteArrayOf(9, 8, 7), 20).single()
        assertArrayEquals(byteArrayOf(0xB7.toByte(), 0xAB.toByte(), 0, 1, 0, 3, 9, 8, 7), p)
    }

    // ---- Loss, duplicates, reordering ------------------------------------------------------------

    @Test fun aLostPacketNeverYieldsAPartialRecordAndLaterRecordsStillArrive() {
        val r = BleReassembler(limits)
        val a = BlePacketCodec.split(1, record(50, 1), 20)
        val b = BlePacketCodec.split(2, record(50, 2), 20)
        val out = (a.filterIndexed { i, _ -> i != 2 } + b).map { r.accept(it) }
        val records = out.filterIsInstance<Reassembly.Record>()
        assertEquals(1, records.size)
        assertArrayEquals(record(50, 2), records.single().bytes)
        assertEquals(1, r.partialCount) // record 1 waits, bounded, for a packet that never comes
    }

    @Test fun duplicatePacketsAndDuplicateRecordsAreDeliveredOnce() {
        val r = BleReassembler(limits)
        val a = BlePacketCodec.split(3, record(40), 20)
        val out = (a + a + listOf(a[0])).map { r.accept(it) }
        assertEquals(1, out.count { it is Reassembly.Record })
        assertTrue(out.count { it == Reassembly.Duplicate } >= a.size)
        assertEquals(0, r.partialCount)
        assertEquals(a.size + 1L, r.stats.duplicates)
    }

    @Test fun aDuplicatePacketInsideAnIncompleteRecordIsAbsorbed() {
        val r = BleReassembler(limits)
        val a = BlePacketCodec.split(3, record(40), 20)
        assertEquals(Reassembly.Incomplete, r.accept(a[0]))
        assertEquals(Reassembly.Duplicate, r.accept(a[0]))
        val last = a.drop(1).map { r.accept(it) }.last()
        assertArrayEquals(record(40), last.record())
    }

    @Test fun reorderedPacketsAndInterleavedRecordsReassemble() {
        val r = BleReassembler(limits)
        val a = BlePacketCodec.split(10, record(60, 10), 20)
        val b = BlePacketCodec.split(11, record(60, 11), 20)
        val mixed = listOf(a[3], b[2], a[0], b[0], a[2], b[4], b[1], a[1], a[4], b[3])
        val out = mixed.map { r.accept(it) }.filterIsInstance<Reassembly.Record>().map { it.bytes.toList() }
        assertEquals(setOf(record(60, 10).toList(), record(60, 11).toList()), out.toSet())
        assertEquals(2, out.size)
    }

    @Test fun partialRecordsAreBoundedOldestEvictedFirst() {
        val r = BleReassembler(limits)
        for (seq in 0 until limits.maxPartialRecords + 3) r.accept(BlePacketCodec.split(seq, record(40, seq), 20)[0])
        assertEquals(limits.maxPartialRecords, r.partialCount)
        assertEquals(3L, r.stats.evicted)
        // An evicted record's late packets are dropped, never delivered.
        val late = BlePacketCodec.split(0, record(40, 0), 20).drop(1).map { r.accept(it) }
        assertTrue(late.none { it is Reassembly.Record })
        // The newest partial still completes.
        val newest = limits.maxPartialRecords + 2
        assertArrayEquals(record(40, newest), BlePacketCodec.split(newest, record(40, newest), 20).drop(1).map { r.accept(it) }.last().record())
    }

    @Test fun sequenceNumbersWrapAround() {
        val r = BleReassembler(limits)
        for (n in 0 until 1_000) {
            val seq = n % 256
            val got = BlePacketCodec.split(seq, record(30, n), 20).map { r.accept(it) }.last()
            assertArrayEquals("record $n", record(30, n), got.record())
        }
        assertTrue(r.rememberedCount <= limits.rememberedRecords)
    }

    @Test fun aStalePartialNeverMergesWithANewRecordReusingItsNumber() {
        val r = BleReassembler(limits)
        val old = record(42, 1)
        val new = old.copyOf().also { for (i in 14 until it.size) it[i] = (it[i] + 1).toByte() } // same first piece
        val a = BlePacketCodec.split(5, old, 20)
        val b = BlePacketCodec.split(5, new, 20)
        assertArrayEquals(a[0], b[0])
        assertEquals(Reassembly.Incomplete, r.accept(a[0]))
        assertEquals(Reassembly.Incomplete, r.accept(a[2])) // a[1] is lost
        // 255 more records go by, all complete; the sequence number 5 comes round again.
        for (n in 1..255) assertTrue(r.accept(BlePacketCodec.split((5 + n) % 256, record(10, n), 20).single()) is Reassembly.Record)
        val got = b.map { r.accept(it) }.filterIsInstance<Reassembly.Record>()
        assertEquals(1, got.size)
        assertArrayEquals("the new record, not old and new spliced", new, got.single().bytes)
    }

    @Test fun aStalePartialSurvivesEntirelyLostRecordsWithoutSplicingIntoTheNextRecordWithItsLowByte() {
        // Round 3 #1: with an 8-bit record number, 255 records lost without a trace brought number 5
        // round again while record 5's partial was still held, and one packet of the new record
        // completed a hybrid of the two. The 12-bit number tells them apart.
        val r = BleReassembler(limits)
        val old = record(42, 1)
        val new = record(42, 2)
        val a = BlePacketCodec.split(5, old, 20)
        val b = BlePacketCodec.split(5 + 256, new, 20)
        assertEquals(Reassembly.Incomplete, r.accept(a[0]))
        assertEquals(Reassembly.Incomplete, r.accept(a[2])) // a[1] is lost; the next 255 records entirely
        assertEquals(Reassembly.Incomplete, r.accept(b[1]))
        assertEquals(Reassembly.Incomplete, r.accept(b[0]))
        assertArrayEquals("the new record, whole", new, r.accept(b[2]).record())
    }

    @Test fun theRecordNumberUsesTwelveBitsAcrossTheHeader() {
        val p = BlePacketCodec.split(0xAB5, byteArrayOf(9, 8, 7), 20).single()
        assertArrayEquals(byteArrayOf(0xB7.toByte(), 0xB5.toByte(), 0, 1, 0xA0.toByte(), 3, 9, 8, 7), p)
        assertArrayEquals(byteArrayOf(9, 8, 7), BleReassembler(limits).accept(p).record())
    }

    // ---- Truncation, conflicts, oversize, forgery ------------------------------------------------

    @Test fun aTruncatedPacketDropsItsRecordButNotTheLink() {
        val r = BleReassembler(limits)
        val a = BlePacketCodec.split(5, record(50), 20)
        val cut = a[1].copyOf(a[1].size - 3)
        val out = listOf(a[0], cut, a[2], a[3]).map { r.accept(it) }
        assertTrue(out.none { it is Reassembly.Record })
        assertEquals(Reassembly.Dropped(BleViolation.LENGTH_MISMATCH), out.last())
        assertEquals(0, r.partialCount)
        // A packet shorter than its header is dropped, not fatal (a lossy link may do it).
        assertEquals(Reassembly.Dropped(BleViolation.TRUNCATED), r.accept(a[0].copyOf(4)))
        // A header with its payload cut off entirely is truncation too.
        assertEquals(Reassembly.Dropped(BleViolation.TRUNCATED), r.accept(a[0].copyOf(BlePacketCodec.HEADER_SIZE)))
        // The next record still arrives.
        assertArrayEquals(record(9), BlePacketCodec.split(6, record(9), 20).map { r.accept(it) }.last().record())
    }

    @Test fun conflictingPacketsDiscardThePartialRecord() {
        val r = BleReassembler(limits)
        val a = BlePacketCodec.split(5, record(50, 1), 20)
        val forged = BlePacketCodec.split(5, record(50, 2), 20)
        assertEquals(Reassembly.Incomplete, r.accept(a[0]))
        assertEquals(Reassembly.Dropped(BleViolation.CONFLICT), r.accept(forged[0]))
        assertEquals(0, r.partialCount)
        // Same seq, other count or length.
        val r2 = BleReassembler(limits)
        assertEquals(Reassembly.Incomplete, r2.accept(a[0]))
        assertEquals(Reassembly.Dropped(BleViolation.CONFLICT), r2.accept(BlePacketCodec.split(5, record(60, 1), 20)[1]))
        // The discarded record's remaining packets are not delivered later.
        assertTrue(a.drop(1).map { r2.accept(it) }.none { it is Reassembly.Record })
    }

    @Test fun piecesThatOverrunTheAnnouncedLengthAreRefused() {
        val r = BleReassembler(limits)
        // Three packets announced for a 20-byte record; the first two already carry 28 bytes. The
        // record is discarded at once, so a partial never holds more than its announced length.
        val first = packet(seq = 1, index = 0, count = 3, length = 20, payload = ByteArray(14) { 1 })
        val second = packet(seq = 1, index = 1, count = 3, length = 20, payload = ByteArray(14) { 2 })
        assertEquals(Reassembly.Incomplete, r.accept(first))
        assertEquals(Reassembly.Dropped(BleViolation.LENGTH_MISMATCH), r.accept(second))
        assertEquals(0, r.partialCount)
        assertEquals(0, r.bufferedBytes)
    }

    @Test fun oversizedRecordsAndPacketsAreFatal() {
        val r = BleReassembler(limits)
        assertEquals(Reassembly.Fatal(BleViolation.OVERSIZED), r.accept(packet(length = limits.maxRecord + 1, count = 13, payload = ByteArray(14))))
        assertEquals(Reassembly.Fatal(BleViolation.OVERSIZED), r.accept(packet(length = 0xFFFF, count = 255, payload = ByteArray(14))))
        assertEquals(Reassembly.Fatal(BleViolation.OVERSIZED), r.accept(ByteArray(limits.maxPacket + 1).also { packet(length = 10, payload = ByteArray(10)).copyInto(it) }))
        assertEquals(0, r.partialCount)
        // Exactly the limits are fine.
        assertTrue(BleReassembler(limits).accept(packet(length = limits.maxRecord, count = 1, payload = ByteArray(limits.maxRecord))) is Reassembly.Record)
    }

    @Test fun malformedHeadersAreFatal() {
        val r = BleReassembler(limits)
        val good = packet(length = 3, payload = byteArrayOf(1, 2, 3))
        assertEquals(Reassembly.Fatal(BleViolation.MALFORMED), r.accept(good.copyOf().also { it[0] = 0x00 }))
        assertEquals(Reassembly.Fatal(BleViolation.MALFORMED), r.accept(packet(count = 0, length = 3, payload = byteArrayOf(1, 2, 3))))
        assertEquals(Reassembly.Fatal(BleViolation.MALFORMED), r.accept(packet(index = 1, count = 1, length = 3, payload = byteArrayOf(1, 2, 3))))
        assertEquals(Reassembly.Fatal(BleViolation.MALFORMED), r.accept(packet(length = 0, payload = byteArrayOf(1))))
        assertEquals(Reassembly.Fatal(BleViolation.MALFORMED), r.accept(packet(count = 4, length = 3, payload = byteArrayOf(1))))
        assertEquals(Reassembly.Fatal(BleViolation.MALFORMED), r.accept(packet(count = 1, length = 3, payload = byteArrayOf(1, 2, 3, 4))))
        assertEquals(0, r.partialCount)
    }

    @Test fun aSinglePacketRecordShorterThanItsLengthIsDropped() {
        assertEquals(Reassembly.Dropped(BleViolation.LENGTH_MISMATCH), BleReassembler(limits).accept(packet(count = 1, length = 5, payload = byteArrayOf(1, 2, 3))))
    }

    @Test fun decodingNeverThrowsOnRandomBytes() {
        val rnd = Random(22)
        val r = BleReassembler(limits)
        repeat(50_000) {
            val bytes = rnd.nextBytes(rnd.nextInt(0, 40))
            if (bytes.isNotEmpty() && rnd.nextBoolean()) bytes[0] = 0xB7.toByte()
            r.accept(bytes) // must not throw
        }
        assertTrue(r.partialCount <= limits.maxPartialRecords)
        assertTrue(r.rememberedCount <= limits.rememberedRecords)
        assertTrue("memory bounded by announced lengths", r.bufferedBytes <= limits.maxPartialRecords * limits.maxRecord)
    }

    // ---- Property: a faulty radio never yields a record that was not sent -------------------------

    @Test fun underRandomFaultsEveryDeliveredRecordWasSentWholeAndNoneTwice() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val att = listOf(20, 23, 61, 182, 244, 509)[seed % 6]
            // Each record starts with its own number, so equal bytes mean the same record.
            val sent = (0 until 300).map { n -> rnd.nextBytes(rnd.nextInt(2, limits.maxRecord + 1)).also { it[0] = (n ushr 8).toByte(); it[1] = n.toByte() } }
            val air = ArrayList<ByteArray>()
            sent.forEachIndexed { i, rec -> air += BlePacketCodec.split(i % 256, rec, att) }
            // Faults: drop 5%, duplicate 5%, truncate 2%, swap neighbours 5%.
            val faulty = ArrayList<ByteArray>()
            for (p in air) {
                val x = rnd.nextDouble()
                when {
                    x < 0.05 -> Unit
                    x < 0.10 -> { faulty += p; faulty += p }
                    x < 0.12 -> faulty += p.copyOf(rnd.nextInt(0, p.size))
                    else -> faulty += p
                }
            }
            for (i in 0 until faulty.size - 1) if (rnd.nextDouble() < 0.05) faulty[i] = faulty[i + 1].also { faulty[i + 1] = faulty[i] }
            val r = BleReassembler(limits)
            val got = faulty.map { r.accept(it) }
            assertTrue("seed $seed: honest bytes are never fatal", got.none { it is Reassembly.Fatal })
            val delivered = got.filterIsInstance<Reassembly.Record>().map { it.bytes.toList() }
            val sentSet = sent.map { it.toList() }
            assertTrue("seed $seed: a delivered record was never sent", delivered.all { it in sentSet })
            assertEquals("seed $seed: a record delivered twice", delivered.size, delivered.toSet().size)
            assertTrue("seed $seed: too much was lost", delivered.size > sent.size / 4)
            assertTrue(r.partialCount <= limits.maxPartialRecords)
        }
    }

    @Test fun withoutFaultsEveryRecordArrivesInOrder() {
        val rnd = Random(7)
        val r = BleReassembler(limits)
        val sent = (0 until 600).map { rnd.nextBytes(rnd.nextInt(1, limits.maxRecord + 1)) }
        val delivered = sent.flatMapIndexed { i, rec -> BlePacketCodec.split(i % 256, rec, 20) }.map { r.accept(it) }
            .filterIsInstance<Reassembly.Record>().map { it.bytes.toList() }
        assertEquals(sent.map { it.toList() }, delivered)
        assertEquals(sent.size.toLong(), r.stats.records)
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private fun packet(seq: Int = 1, index: Int = 0, count: Int = 1, length: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xB7.toByte(), seq.toByte(), index.toByte(), count.toByte(), (length ushr 8).toByte(), length.toByte()) + payload

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
