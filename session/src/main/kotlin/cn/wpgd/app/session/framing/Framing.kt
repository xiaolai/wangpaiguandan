package cn.wpgd.app.session.framing

/**
 * Framing limits. A frame is one whole protocol unit (a handshake message or a sealed message); a
 * chunk is what a transport carries. Defaults suit BLE (one chunk fits a 185-byte ATT MTU).
 */
data class FramingLimits(
    /** Largest chunk payload this end sends; the receiver accepts up to [maxChunkPayload] too. */
    val maxChunkPayload: Int = 160,
    /** Largest frame either end accepts. A header announcing more disconnects the peer (GDN-027). */
    val maxFrameSize: Int = 64 * 1024,
    /** Partial frames buffered at once; the oldest is evicted beyond this. */
    val maxPartialFrames: Int = 4,
    /** Completed or evicted frame ids remembered, so late duplicates are dropped. */
    val rememberedFrameIds: Int = 256,
) {
    init {
        require(maxChunkPayload in 1..(0xFFFF - ChunkCodec.HEADER_SIZE)) { "chunk payload $maxChunkPayload" }
        require(maxFrameSize >= 1 && maxPartialFrames >= 1 && rememberedFrameIds >= 1)
        // The largest frame must be splittable at this end's own chunk size: the wire count is a u16.
        val ownChunks = ChunkCodec.chunkCount(maxFrameSize, maxChunkPayload)
        require(ownChunks <= MAX_WIRE_CHUNKS) { "a $maxFrameSize-byte frame needs $ownChunks chunks of $maxChunkPayload bytes; the wire carries at most $MAX_WIRE_CHUNKS" }
    }

    /**
     * Most chunks a received frame may have. A peer may send smaller chunks than this end does, so
     * the bound does not depend on [maxChunkPayload]: every chunk carries at least one byte (so a
     * frame has at most [maxFrameSize] chunks) and the wire count is a u16. Memory stays bounded by
     * [maxFrameSize] and [maxPartialFrames].
     */
    val maxChunks: Int get() = minOf(maxFrameSize, MAX_WIRE_CHUNKS)

    companion object {
        /** The chunk count field is a u16. */
        const val MAX_WIRE_CHUNKS = 0xFFFF
    }
}

/** One decoded chunk. [payload] is this chunk's slice of the frame. */
class Chunk(val frameId: Long, val index: Int, val count: Int, val frameLength: Int, val payload: ByteArray)

/**
 * Chunk wire format (big-endian), length-prefixed so it also works on a byte stream:
 *
 * ```
 * u16 length       bytes after this field (= 14 + payload size)
 * u8  magic        0xC7
 * u8  version      1
 * u32 frameId      per-sender counter
 * u16 index        0-based
 * u16 count        chunks in the frame, >= 1
 * u32 frameLength  total frame bytes
 * ..  payload
 * ```
 */
object ChunkCodec {
    const val MAGIC = 0xC7
    const val VERSION = 1
    const val HEADER_SIZE = 14

    fun encode(frameId: Long, index: Int, count: Int, frameLength: Int, payload: ByteArray): ByteArray {
        val bodyLen = HEADER_SIZE + payload.size
        require(bodyLen <= 0xFFFF)
        val out = ByteArray(2 + bodyLen)
        var p = 0
        fun u8(v: Int) { out[p++] = v.toByte() }
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Long) { u16((v ushr 16).toInt() and 0xFFFF); u16((v and 0xFFFF).toInt()) }
        u16(bodyLen); u8(MAGIC); u8(VERSION); u32(frameId); u16(index); u16(count); u32(frameLength.toLong())
        payload.copyInto(out, p)
        return out
    }

    /** Chunks of at most [maxChunkPayload] bytes needed for [frameSize] bytes. Long: no Int overflow near Int.MAX_VALUE. */
    fun chunkCount(frameSize: Int, maxChunkPayload: Int): Long {
        require(frameSize >= 0 && maxChunkPayload >= 1)
        return (frameSize.toLong() + maxChunkPayload - 1) / maxChunkPayload
    }

    /** Splits [frame] into chunks of at most [maxChunkPayload] bytes. */
    fun split(frameId: Long, frame: ByteArray, maxChunkPayload: Int): List<ByteArray> {
        require(frame.isNotEmpty()) { "empty frame" }
        val count = chunkCount(frame.size, maxChunkPayload)
        require(count <= FramingLimits.MAX_WIRE_CHUNKS) { "frame too large to chunk" }
        return (0 until count.toInt()).map { i ->
            // from < frame.size, so it fits an Int; the end is clamped without forming from + maxChunkPayload.
            val from = i * maxChunkPayload
            val to = from + minOf(maxChunkPayload, frame.size - from)
            encode(frameId, i, count.toInt(), frame.size, frame.copyOfRange(from, to))
        }
    }

    /** Parses one chunk. Returns a [Decoded.Bad] for anything malformed; never throws on input. */
    fun decode(bytes: ByteArray, limits: FramingLimits): Decoded {
        if (bytes.size < 2 + HEADER_SIZE) return Decoded.Bad(Violation.TRUNCATED)
        var p = 0
        fun u8(): Int = bytes[p++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        val bodyLen = u16()
        if (bodyLen != bytes.size - 2) return Decoded.Bad(if (bodyLen > bytes.size - 2) Violation.TRUNCATED else Violation.MALFORMED)
        if (u8() != MAGIC || u8() != VERSION) return Decoded.Bad(Violation.MALFORMED)
        val frameId = u32()
        val index = u16()
        val count = u16()
        val frameLength = u32()
        val payloadSize = bodyLen - HEADER_SIZE
        return when {
            frameLength > limits.maxFrameSize -> Decoded.Bad(Violation.OVERSIZED)
            frameLength < 1 || count < 1 || index >= count -> Decoded.Bad(Violation.MALFORMED)
            count > limits.maxChunks || count > frameLength -> Decoded.Bad(Violation.MALFORMED)
            payloadSize < 1 || payloadSize > limits.maxChunkPayload || payloadSize > frameLength -> Decoded.Bad(Violation.MALFORMED)
            else -> Decoded.Ok(Chunk(frameId, index, count, frameLength.toInt(), bytes.copyOfRange(p, bytes.size)))
        }
    }

    sealed interface Decoded {
        class Ok(val chunk: Chunk) : Decoded
        class Bad(val violation: Violation) : Decoded
    }
}

/** Why a chunk was refused. [fatal] ones disconnect the peer; the others are dropped and counted. */
enum class Violation(val fatal: Boolean) {
    /** Shorter than its length prefix (a lossy link can do this): dropped. */
    TRUNCATED(false),

    /** Same frame id and index, different bytes or header: the partial frame is discarded. */
    CONFLICT(false),

    /** Header announces a frame above the limit (GDN-027). */
    OVERSIZED(true),

    /** Header fields impossible for an honest sender. */
    MALFORMED(true),
}

/**
 * Bounded reassembly of chunks into frames. A frame is returned only when every chunk is present,
 * all chunks agree on count and length, and the pieces add up to exactly that length; a partial
 * frame is never returned. Duplicates and reordering are absorbed; late chunks of a completed or
 * evicted frame are dropped.
 */
class Reassembler(private val limits: FramingLimits) {
    private class Partial(val count: Int, val length: Int) {
        val pieces = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private val partials = LinkedHashMap<Long, Partial>()
    private val finished = LinkedHashSet<Long>()

    var evicted: Int = 0
        private set
    var duplicates: Int = 0
        private set

    val partialCount: Int get() = partials.size

    sealed interface Result {
        data object Incomplete : Result
        data object Duplicate : Result
        class Complete(val frameId: Long, val frame: ByteArray) : Result
        class Rejected(val violation: Violation) : Result
    }

    fun accept(raw: ByteArray): Result {
        val chunk = when (val d = ChunkCodec.decode(raw, limits)) {
            is ChunkCodec.Decoded.Bad -> return Result.Rejected(d.violation)
            is ChunkCodec.Decoded.Ok -> d.chunk
        }
        if (chunk.frameId in finished) { duplicates++; return Result.Duplicate }
        val partial = partials[chunk.frameId] ?: run {
            if (partials.size >= limits.maxPartialFrames) {
                val oldest = partials.keys.first()
                partials.remove(oldest)
                remember(oldest)
                evicted++
            }
            Partial(chunk.count, chunk.frameLength).also { partials[chunk.frameId] = it }
        }
        if (partial.count != chunk.count || partial.length != chunk.frameLength) return conflict(chunk.frameId)
        val existing = partial.pieces[chunk.index]
        if (existing != null) {
            if (!existing.contentEquals(chunk.payload)) return conflict(chunk.frameId)
            duplicates++
            return Result.Duplicate
        }
        if (partial.bytes + chunk.payload.size > partial.length) return conflict(chunk.frameId)
        partial.pieces[chunk.index] = chunk.payload
        partial.received++
        partial.bytes += chunk.payload.size
        if (partial.received < partial.count) return Result.Incomplete
        partials.remove(chunk.frameId)
        remember(chunk.frameId)
        if (partial.bytes != partial.length) return Result.Rejected(Violation.CONFLICT)
        val frame = ByteArray(partial.length)
        var at = 0
        for (piece in partial.pieces) { checkNotNull(piece).copyInto(frame, at); at += piece.size }
        return Result.Complete(chunk.frameId, frame)
    }

    private fun conflict(frameId: Long): Result {
        partials.remove(frameId)
        remember(frameId)
        return Result.Rejected(Violation.CONFLICT)
    }

    private fun remember(frameId: Long) {
        finished += frameId
        while (finished.size > limits.rememberedFrameIds) finished.remove(finished.first())
    }
}
