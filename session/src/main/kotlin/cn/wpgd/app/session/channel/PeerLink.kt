package cn.wpgd.app.session.channel

import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.framing.Reassembler
import cn.wpgd.app.session.framing.Violation
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.wire.HandshakeMessage
import cn.wpgd.app.session.wire.WireCodec
import cn.wpgd.app.session.wire.WireMessage
import cn.wpgd.app.session.wire.WireValidation

/** What an outbound message is, for queue policy. */
enum class OutKind {
    /** Handshake, admission, status, closing: never coalesced. */
    CONTROL,

    /** Liveness only: a newer heartbeat replaces a queued one, so a slow link never accumulates them. */
    HEARTBEAT,

    /** Receipts and rejections: never dropped while the peer is connected. */
    RECEIPT,

    /** A member's own snapshot: a newer one replaces a queued one. */
    SNAPSHOT,

    /** A follow snapshot: dropped on revocation; a newer one of the same grant replaces a queued one. */
    FOLLOW,
}

/** What arrived, after reassembly, opening and strict decoding. */
sealed interface Inbound {
    class Plain(val message: HandshakeMessage) : Inbound
    class Sealed(val message: WireMessage) : Inbound

    /** Dropped: a chunk or frame problem. [Violation.fatal] ones should close the peer. */
    class Bad(val violation: Violation) : Inbound
}

/**
 * One peer's framing, sealing and ordered bounded outbound queue. Passive: the owner feeds chunks
 * in with [receive] and calls [pump] to write. One queue per peer, so a slow peer only ever fills
 * its own queue (GDN-071).
 */
class PeerLink(
    private val transport: Transport,
    private val limits: FramingLimits,
    private val queueLimit: Int,
) {
    private class Out(val kind: OutKind, val grantId: String?, val message: WireMessage, val plain: Boolean)

    private val reassembler = Reassembler(limits)
    private val queue = ArrayDeque<Out>()
    private var current: ArrayDeque<ByteArray>? = null
    private var currentKind: OutKind? = null
    private var currentGrant: String? = null
    private var nextFrameId = 1L
    private var sealer: Sealer? = null
    private var opener: Opener? = null

    /** Messages fully handed to the transport, by type (observability). */
    var framesSent: Long = 0
        private set
    var superseded: Long = 0
        private set

    val queued: Int get() = queue.size
    val sealed: Boolean get() = sealer != null

    fun installKeys(sealer: Sealer, opener: Opener) {
        this.sealer = sealer
        this.opener = opener
    }

    /** True when the queue holds any message of [kind] (including one being written). */
    fun has(kind: OutKind): Boolean = currentKind == kind || queue.any { it.kind == kind }

    /**
     * Queues [message]. State frames coalesce: a new SNAPSHOT replaces any queued SNAPSHOT, and a new
     * FOLLOW replaces any queued FOLLOW of the same grant (a frame already being written is never cut).
     * So a slow peer holds at most one pending view per kind and catches up with the newest state.
     * Receipts and control messages are never dropped. Returns false when the queue is full (close the peer).
     */
    fun enqueue(kind: OutKind, message: WireMessage, grantId: String? = null): Boolean {
        val plain = message is HandshakeMessage
        check(plain || sealer != null) { "sealed message ${message::class.simpleName} before keys" }
        // A retry of a message that is still waiting in the queue adds nothing: keep the queued copy.
        if (queue.any { it.message == message }) return true
        if (kind == OutKind.HEARTBEAT || kind == OutKind.SNAPSHOT || kind == OutKind.FOLLOW) {
            val before = queue.size
            queue.removeAll { it.kind == kind && it.grantId == grantId }
            superseded += before - queue.size
        }
        if (queue.size >= queueLimit) return false
        queue.addLast(Out(kind, grantId, message, plain))
        return true
    }

    /**
     * Revocation boundary (P4): drops every queued FOLLOW frame of [grantId], including the unsent
     * chunks of one being written. Returns how many messages were dropped.
     */
    fun dropFollow(grantId: String): Int {
        val before = queue.size
        queue.removeAll { it.kind == OutKind.FOLLOW && it.grantId == grantId }
        var dropped = before - queue.size
        if (currentKind == OutKind.FOLLOW && currentGrant == grantId) {
            current = null; currentKind = null; currentGrant = null
            dropped++
        }
        return dropped
    }

    /** Writes chunks until the transport refuses or the queue is empty. */
    fun pump() {
        while (true) {
            val chunks = current ?: run {
                val next = queue.removeFirstOrNull() ?: return
                val bytes = WireCodec.encode(next.message)
                val frame = if (next.plain) plainFrame(bytes) else checkNotNull(sealer).seal(bytes)
                check(frame.size <= limits.maxFrameSize) { "frame of ${frame.size} bytes exceeds the limit" }
                currentKind = next.kind
                currentGrant = next.grantId
                ArrayDeque(ChunkCodec.split(nextFrameId++ and 0xFFFF_FFFFL, frame, limits.maxChunkPayload)).also { current = it }
            }
            while (chunks.isNotEmpty()) {
                if (!transport.trySend(chunks.first())) return
                chunks.removeFirst()
            }
            current = null; currentKind = null; currentGrant = null
            framesSent++
        }
    }

    fun receive(chunk: ByteArray): Inbound? = when (val r = reassembler.accept(chunk)) {
        Reassembler.Result.Incomplete, Reassembler.Result.Duplicate -> null
        is Reassembler.Result.Rejected -> Inbound.Bad(r.violation)
        is Reassembler.Result.Complete -> frame(r.frame)
    }

    private fun frame(frame: ByteArray): Inbound? = when (frame[0]) {
        FrameKind.PLAIN -> {
            val m = WireCodec.decode(frame.copyOfRange(1, frame.size))
            if (m is HandshakeMessage && WireValidation.problem(m) == null) Inbound.Plain(m) else Inbound.Bad(Violation.MALFORMED)
        }
        FrameKind.SEALED -> {
            val o = opener
            // Before keys exist a sealed frame cannot be ours; after, a failed open is a replay or forgery: drop it.
            val plain = o?.open(frame)
            if (plain == null) null
            else {
                val m = WireCodec.decode(plain)
                if (m != null && m !is HandshakeMessage && WireValidation.problem(m) == null) Inbound.Sealed(m) else Inbound.Bad(Violation.MALFORMED)
            }
        }
        else -> Inbound.Bad(Violation.MALFORMED)
    }

    fun close() = transport.close()
}
