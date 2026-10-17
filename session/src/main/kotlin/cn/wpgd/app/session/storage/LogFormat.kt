package cn.wpgd.app.session.storage

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Byte layouts of the table files. CRC-32 detects torn and corrupted bytes; it is not a defence
 * against someone editing the files (they are host-private and excluded from backup, audit A29).
 *
 * Log frame (records and commit markers, appended to `log.<segment>`):
 * `kind u8 | revision u64 | length u32 | payload | crc32 u32` — the CRC covers every byte before it.
 * A record's payload is UTF-8 JSON ([RecordPayload]). A marker's payload is the 4-byte CRC of the
 * record frame it commits, so a marker can only commit the exact record that precedes it.
 *
 * Small file (meta, high-water mark, members, checkpoint, quarantine, deletion tombstone):
 * `magic "WPGD" | kind u8 | length u32 | payload | crc32 u32`.
 */
internal object LogFormat {
    const val RECORD: Byte = 0x52 // 'R'
    const val MARKER: Byte = 0x4D // 'M'
    private const val HEADER = 1 + 8 + 4
    private const val TRAILER = 4

    /** No record is near this; a larger length field means the frame is corrupt. */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    class Frame(val kind: Byte, val revision: Long, val payload: ByteArray, val crc: Int, val offset: Int, val size: Int)

    enum class Stop {
        /** The segment ends inside a frame: an append that never became durable in full. */
        TORN,

        /** A complete frame fails its checksum or has an impossible header. */
        CORRUPT,
    }

    class Parsed(val frames: List<Frame>, val stop: Stop?)

    fun frame(kind: Byte, revision: Long, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(HEADER + payload.size + TRAILER)
        b.put(kind).putLong(revision).putInt(payload.size).put(payload)
        b.putInt(crc(b.array(), 0, HEADER + payload.size))
        return b.array()
    }

    fun record(revision: Long, payload: ByteArray) = frame(RECORD, revision, payload)

    fun marker(revision: Long, recordCrc: Int) = frame(MARKER, revision, ByteBuffer.allocate(4).putInt(recordCrc).array())

    fun markerTarget(f: Frame): Int? = if (f.kind == MARKER && f.payload.size == 4) ByteBuffer.wrap(f.payload).int else null

    /** Every valid frame from the start of [bytes] up to the first one that is torn or corrupt. */
    fun parse(bytes: ByteArray): Parsed {
        val frames = ArrayList<Frame>()
        var at = 0
        while (at < bytes.size) {
            if (bytes.size - at < HEADER) return Parsed(frames, Stop.TORN)
            val b = ByteBuffer.wrap(bytes, at, HEADER)
            val kind = b.get()
            val revision = b.long
            val length = b.int
            if ((kind != RECORD && kind != MARKER) || length < 0 || length > MAX_PAYLOAD || revision < 1) return Parsed(frames, Stop.CORRUPT)
            val total = HEADER + length + TRAILER
            if (bytes.size - at < total) return Parsed(frames, Stop.TORN)
            val stored = ByteBuffer.wrap(bytes, at + HEADER + length, TRAILER).int
            if (stored != crc(bytes, at, HEADER + length)) return Parsed(frames, Stop.CORRUPT)
            frames += Frame(kind, revision, bytes.copyOfRange(at + HEADER, at + HEADER + length), stored, at, total)
            at += total
        }
        return Parsed(frames, null)
    }

    // ---- Small checked files -----------------------------------------------------------------------

    private val MAGIC = byteArrayOf('W'.code.toByte(), 'P'.code.toByte(), 'G'.code.toByte(), 'D'.code.toByte())

    enum class FileKind(val code: Byte) { META('T'.code.toByte()), HWM('H'.code.toByte()), MEMBERS('P'.code.toByte()), CHECKPOINT('C'.code.toByte()), QUARANTINE('Q'.code.toByte()), TOMBSTONE('D'.code.toByte()), HOST_KEY('K'.code.toByte()) }

    fun wrap(kind: FileKind, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(4 + 1 + 4 + payload.size + 4)
        b.put(MAGIC).put(kind.code).putInt(payload.size).put(payload)
        b.putInt(crc(b.array(), 0, 9 + payload.size))
        return b.array()
    }

    /** The payload, or null when the bytes are not a complete, correctly checksummed file of [kind]. */
    fun unwrap(kind: FileKind, bytes: ByteArray): ByteArray? {
        if (bytes.size < 13 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4] != kind.code) return null
        val length = ByteBuffer.wrap(bytes, 5, 4).int
        if (length < 0 || bytes.size != 13 + length) return null
        val stored = ByteBuffer.wrap(bytes, 9 + length, 4).int
        if (stored != crc(bytes, 0, 9 + length)) return null
        return bytes.copyOfRange(9, 9 + length)
    }

    fun hwm(revision: Long): ByteArray = wrap(FileKind.HWM, ByteBuffer.allocate(8).putLong(revision).array())

    fun readHwm(bytes: ByteArray): Long? = unwrap(FileKind.HWM, bytes)?.takeIf { it.size == 8 }?.let { ByteBuffer.wrap(it).long }?.takeIf { it >= 0 }

    fun crc(bytes: ByteArray, offset: Int, length: Int): Int {
        val c = CRC32()
        c.update(bytes, offset, length)
        return c.value.toInt()
    }
}
