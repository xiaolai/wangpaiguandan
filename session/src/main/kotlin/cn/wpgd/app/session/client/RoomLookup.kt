package cn.wpgd.app.session.client

import cn.wpgd.app.session.channel.Inbound
import cn.wpgd.app.session.channel.OutKind
import cn.wpgd.app.session.channel.PeerLink
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.HelloRejected
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.session.wire.RoomListing
import cn.wpgd.app.session.wire.RoomQuery

/** What a [RoomLookup] found at an address. */
sealed interface LookupResult {
    /** A room listens there; [listing] is its public listing (unauthenticated, like a discovery advert). */
    data class Found(val listing: RoomListing) : LookupResult

    /** This app's host answered, but lists no room (not hosting, or the table is closing). */
    data object NotListed : LookupResult

    /** This app's host answered that it is full: a room may listen there, but takes no new connection now. */
    data object Full : LookupResult

    /** Something answered that does not speak this protocol (a wrong port, another service). */
    data object NotARoom : LookupResult

    /** The connection ended before any answer (refused, unreachable: the transport may say which). */
    data object Closed : LookupResult

    /** Connected, or still connecting, and nothing answered within the time limit. */
    data object TimedOut : LookupResult
}

/**
 * Join by address, step one: asks the peer at the other end of [transport] what room it hosts with
 * one plain `room_query`, and reports one [LookupResult] to [onResult] (exactly once), then closes.
 * Nothing secret is sent or received; joining is a separate connection through [SessionClient], so
 * the hello, the six-digit code comparison and host-key pinning (G2) all apply unchanged.
 *
 * Single-threaded like the rest of the session: callbacks arrive on the [scheduler]'s thread.
 */
class RoomLookup(
    private val transport: Transport,
    private val scheduler: Scheduler,
    limits: FramingLimits = FramingLimits(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onResult: (LookupResult) -> Unit,
) {
    init {
        require(timeoutMs > 0) { "timeout must be positive" }
    }

    // A lookup never installs keys: a sealed frame cannot answer it, so it is malformed, not silently dropped.
    private val link = PeerLink(transport, limits, queueLimit = 4, plainOnly = true)
    private var done = false
    private var timer: Cancellable? = null

    fun start() {
        check(timer == null && !done) { "a lookup runs once" }
        transport.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = onChunk(chunk)
            override fun onWritable() = if (!done) link.pump() else Unit
            override fun onClosed() = finish(LookupResult.Closed)
        })
        timer = scheduler.schedule(timeoutMs) { finish(LookupResult.TimedOut) }
        link.enqueue(OutKind.CONTROL, RoomQuery(PROTOCOL_VERSION, PROTOCOL_VERSION))
        link.pump()
    }

    /** Stops waiting; no result is reported after this. */
    fun cancel() {
        if (done) return
        done = true
        timer?.cancel()
        transport.close()
    }

    private fun onChunk(chunk: ByteArray) {
        if (done) return
        when (val inbound = link.receive(chunk) ?: return) {
            is Inbound.Plain -> when (val m = inbound.message) {
                is RoomListing -> finish(LookupResult.Found(m))
                is HelloRejected -> finish(rejected(m.reason))
                else -> finish(LookupResult.NotARoom)
            }
            is Inbound.Bad, is Inbound.Sealed -> finish(LookupResult.NotARoom)
        }
    }

    /**
     * The host answers a query with ROOM_MISMATCH when it lists no room and with ROOM_FULL when it is
     * at its connection bound. Any other reason is not an answer this host gives to a query.
     */
    private fun rejected(reason: HelloRejectReason): LookupResult = when (reason) {
        HelloRejectReason.ROOM_MISMATCH -> LookupResult.NotListed
        HelloRejectReason.ROOM_FULL -> LookupResult.Full
        HelloRejectReason.VERSION_UNSUPPORTED, HelloRejectReason.PROFILE_MISMATCH, HelloRejectReason.MALFORMED -> LookupResult.NotARoom
    }

    private fun finish(result: LookupResult) {
        if (done) return
        done = true
        timer?.cancel()
        transport.close()
        onResult(result)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}
