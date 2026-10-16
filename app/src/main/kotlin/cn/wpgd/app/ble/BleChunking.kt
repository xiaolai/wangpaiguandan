package cn.wpgd.app.ble

import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.framing.FramingLimits

/**
 * Bounds of one Bluetooth link (GDN-027 for BLE). Plain Kotlin: no Android type here, so the
 * chunking and the link logic run in JVM tests ([BleChunkingTest], `BleLinkTest`).
 *
 * A "record" is one session chunk (`ChunkCodec` output: u16 length, 14-byte header, payload); the
 * session's own framing, sealing and limits apply above it. A "packet" is one GATT write or
 * notification, at most the link's ATT payload (negotiated MTU - 3).
 */
data class BleLimits(
    /** Largest record sent or accepted: one session chunk at the session's default framing limits. */
    val maxRecord: Int = 2 + ChunkCodec.HEADER_SIZE + FramingLimits().maxChunkPayload,
    /** Largest packet accepted: the ATT maximum attribute value length (Bluetooth Core, Vol 3 Part F 3.2.9). */
    val maxPacket: Int = 512,
    /** Records queued for sending before [cn.wpgd.app.session.transport.Transport.trySend] refuses (backpressure). */
    val window: Int = 256,
    /** Records being reassembled at once; the oldest is dropped beyond this. */
    val maxPartialRecords: Int = 4,
    /**
     * Completed or dropped record numbers remembered, so late duplicates are dropped. Below half the
     * record-number space, so a remembered number can never be a new record's after wrap-around.
     */
    val rememberedRecords: Int = 64,
    /** How long a gracefully closed link may keep sending what it had accepted. */
    val lingerMs: Long = 2_000,
) {
    init {
        require(maxRecord in 1..BlePacketCodec.MAX_LENGTH) { "maxRecord $maxRecord" }
        require(maxPacket in MIN_ATT_PAYLOAD..512) { "maxPacket $maxPacket" }
        require(window >= 2 && maxPartialRecords >= 1 && lingerMs >= 0)
        require(rememberedRecords in 1 until BlePacketCodec.SEQ_SPACE / 2) { "rememberedRecords $rememberedRecords" }
        // The default MTU must still carry the largest record in at most 255 packets.
        require((maxRecord + MIN_ATT_PAYLOAD - BlePacketCodec.HEADER_SIZE - 1) / (MIN_ATT_PAYLOAD - BlePacketCodec.HEADER_SIZE) <= 255)
    }

    companion object {
        /** ATT payload at the default ATT MTU of 23 (before any MTU exchange). */
        const val MIN_ATT_PAYLOAD = 20
    }
}

/**
 * Packet layout (big-endian), 6-byte header:
 *
 * ```
 * u8  magic    0xB7 (BLE packet format version 1)
 * u8  seq      low 8 bits of the record number
 * u8  index    0-based packet index within the record
 * u8  count    packets in the record, 1..255
 * u16 high 4 bits: high 4 bits of the record number; low 12 bits: total record bytes, 1..maxRecord
 * ..  payload  this packet's slice of the record, at least 1 byte
 * ```
 *
 * The record number is 12 bits, per sender and direction, modulo [SEQ_SPACE] (4096). A record that
 * was lost entirely leaves no trace at the receiver, so only the width of the number keeps a stale
 * partial record from meeting a new record that reuses its number: that takes 4096 records sent with
 * not one packet of them arriving, against the 256 that an 8-bit number allowed.
 *
 * With the session's largest chunk (176 bytes) a record is one packet from MTU 185 up (176 + 6 = 182
 * = 185 - 3), and 13 packets at the default MTU 23.
 */
object BlePacketCodec {
    const val MAGIC = 0xB7
    const val HEADER_SIZE = 6
    const val SEQ_SPACE = 4096

    /** Largest record length the 12-bit length field carries. */
    const val MAX_LENGTH = 0x0FFF

    /** Splits [record] into packets of at most [attPayload] bytes, numbered [seq]. */
    fun split(seq: Int, record: ByteArray, attPayload: Int): List<ByteArray> {
        require(seq in 0 until SEQ_SPACE) { "seq $seq" }
        require(record.isNotEmpty() && record.size <= MAX_LENGTH) { "record of ${record.size} bytes" }
        require(attPayload > HEADER_SIZE) { "ATT payload $attPayload leaves no room for data" }
        val per = attPayload - HEADER_SIZE
        val count = (record.size + per - 1) / per
        require(count <= 255) { "record of ${record.size} bytes needs $count packets at ATT payload $attPayload" }
        return (0 until count).map { i ->
            val from = i * per
            val to = minOf(record.size, from + per)
            val out = ByteArray(HEADER_SIZE + to - from)
            out[0] = MAGIC.toByte()
            out[1] = seq.toByte()
            out[2] = i.toByte()
            out[3] = count.toByte()
            out[4] = (((seq ushr 8) shl 4) or (record.size ushr 8)).toByte()
            out[5] = record.size.toByte()
            record.copyInto(out, HEADER_SIZE, from, to)
            out
        }
    }
}

/** Why a packet or record was refused. [fatal] ones close the link; the others are dropped and counted. */
enum class BleViolation(val fatal: Boolean) {
    /** Shorter than its header, or a header with no payload (a lossy link can do this): dropped. */
    TRUNCATED(false),

    /** Same record number and index with other bytes, or another count or length: the partial record is discarded. */
    CONFLICT(false),

    /** The pieces do not add up to the announced length (a truncated piece): the record is discarded. */
    LENGTH_MISMATCH(false),

    /** A packet above [BleLimits.maxPacket], or a record announced above [BleLimits.maxRecord] (GDN-027). */
    OVERSIZED(true),

    /** Header fields impossible for an honest sender. */
    MALFORMED(true),
}

/** What one packet did to the reassembler. */
sealed interface Reassembly {
    data object Incomplete : Reassembly

    /** A packet of a record already delivered or discarded, or a repeat of one already held. */
    data object Duplicate : Reassembly

    /** A whole record, exactly as sent. */
    class Record(val bytes: ByteArray) : Reassembly

    data class Dropped(val violation: BleViolation) : Reassembly

    data class Fatal(val violation: BleViolation) : Reassembly
}

/** Counters (no payload bytes). */
class ReassemblyStats {
    var records = 0L
    var duplicates = 0L
    var evicted = 0L
    var dropped = 0L
    var fatal = 0L
}

/**
 * Bounded reassembly of packets into records. A record is returned only when every packet is
 * present, all agree on count and length, and the pieces add up to exactly that length; a partial
 * record is never returned. Duplicates and reordering within [BleLimits.maxPartialRecords] records are
 * absorbed; late packets of a delivered, discarded or evicted record are dropped. Never throws on input.
 *
 * Not thread-safe: the owning link serializes calls.
 */
class BleReassembler(private val limits: BleLimits) {
    private class Partial(val count: Int, val length: Int, val startedAt: Long) {
        val pieces = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private val partials = LinkedHashMap<Int, Partial>()
    private val finished = LinkedHashSet<Int>()

    /** Records started so far; a partial older than [BleLimits.rememberedRecords] of them is dropped. */
    private var started = 0L

    val stats = ReassemblyStats()

    val partialCount: Int get() = partials.size

    val rememberedCount: Int get() = finished.size

    /** Payload bytes held in partial records: at most [BleLimits.maxPartialRecords] x [BleLimits.maxRecord]. */
    val bufferedBytes: Int get() = partials.values.sumOf { it.bytes }

    fun accept(packet: ByteArray): Reassembly {
        if (packet.size > limits.maxPacket) return refuse(BleViolation.OVERSIZED)
        if (packet.size < BlePacketCodec.HEADER_SIZE) return refuse(BleViolation.TRUNCATED)
        val magic = packet[0].toInt() and 0xFF
        val seq = ((packet[4].toInt() and 0xF0) shl 4) or (packet[1].toInt() and 0xFF)
        val index = packet[2].toInt() and 0xFF
        val count = packet[3].toInt() and 0xFF
        val length = ((packet[4].toInt() and 0x0F) shl 8) or (packet[5].toInt() and 0xFF)
        val size = packet.size - BlePacketCodec.HEADER_SIZE
        when {
            magic != BlePacketCodec.MAGIC -> return refuse(BleViolation.MALFORMED)
            length > limits.maxRecord -> return refuse(BleViolation.OVERSIZED)
            length < 1 || count < 1 || index >= count || count > length -> return refuse(BleViolation.MALFORMED)
            size < 1 -> return refuse(BleViolation.TRUNCATED)
            size > length -> return refuse(BleViolation.MALFORMED)
        }
        if (seq in finished) return duplicate()
        val partial = partials[seq] ?: run {
            started++
            // A partial that waited for more than rememberedRecords newer records will never complete
            // honestly, and must be gone before its number comes round again (4096 records later), or a
            // new record could fill its gaps and be delivered spliced.
            val stale = partials.filterValues { started - it.startedAt > limits.rememberedRecords }.keys
            stale.forEach(::evict)
            if (partials.size >= limits.maxPartialRecords) evict(partials.keys.first())
            Partial(count, length, started).also { partials[seq] = it }
        }
        if (partial.count != count || partial.length != length) return discard(seq, BleViolation.CONFLICT)
        val payload = packet.copyOfRange(BlePacketCodec.HEADER_SIZE, packet.size)
        val existing = partial.pieces[index]
        if (existing != null) {
            return if (existing.contentEquals(payload)) duplicate() else discard(seq, BleViolation.CONFLICT)
        }
        if (partial.bytes + payload.size > partial.length) return discard(seq, BleViolation.LENGTH_MISMATCH)
        partial.pieces[index] = payload
        partial.received++
        partial.bytes += payload.size
        if (partial.received < partial.count) return Reassembly.Incomplete
        if (partial.bytes != partial.length) return discard(seq, BleViolation.LENGTH_MISMATCH)
        partials.remove(seq)
        remember(seq)
        val record = ByteArray(partial.length)
        var at = 0
        for (piece in partial.pieces) {
            checkNotNull(piece).copyInto(record, at)
            at += piece.size
        }
        stats.records++
        return Reassembly.Record(record)
    }

    private fun evict(seq: Int) {
        partials.remove(seq)
        remember(seq)
        stats.evicted++
    }

    private fun duplicate(): Reassembly {
        stats.duplicates++
        return Reassembly.Duplicate
    }

    /** The one place a violation is classified: [BleViolation.fatal] decides close or drop. */
    private fun refuse(v: BleViolation): Reassembly = if (v.fatal) {
        stats.fatal++
        Reassembly.Fatal(v)
    } else {
        stats.dropped++
        Reassembly.Dropped(v)
    }

    private fun discard(seq: Int, v: BleViolation): Reassembly {
        partials.remove(seq)
        remember(seq)
        return refuse(v)
    }

    private fun remember(seq: Int) {
        finished.remove(seq)
        finished += seq
        while (finished.size > limits.rememberedRecords) finished.remove(finished.first())
    }
}
