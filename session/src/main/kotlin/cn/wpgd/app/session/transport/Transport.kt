package cn.wpgd.app.session.transport

/**
 * One end of a peer connection. Transport-independent: BLE, LAN sockets and the in-memory test
 * network all implement this. A transport moves opaque chunks (already framed by
 * [cn.wpgd.app.session.framing.ChunkCodec]); it may lose, duplicate, reorder or truncate them, and
 * the session above must stay correct when it does.
 *
 * Callbacks may arrive late, even after [close] (platform callbacks race, audit A16). The owner
 * binds each listener to its own connection identity and ignores callbacks that no longer match.
 */
interface Transport {
    /** Installs the listener. Called once, before any send. */
    fun open(listener: TransportListener)

    /**
     * Hands one chunk to the transport. Returns false, and keeps nothing, when the transport has no
     * room (backpressure) or is closed; the caller keeps the chunk and retries on [TransportListener.onWritable].
     */
    fun trySend(chunk: ByteArray): Boolean

    /**
     * Stops accepting chunks. Chunks already accepted by [trySend] should still be delivered where
     * the medium allows (bounded in time): the session sends its last message (leave, suspended,
     * ended) and closes at once. A transport that cannot do so loses that message, and the peer
     * sees an ordinary disconnect.
     */
    fun close()
}

interface TransportListener {
    fun onReceive(chunk: ByteArray)

    /** Room to send again after a refused [Transport.trySend]. */
    fun onWritable()

    /** The connection is gone. May be called more than once or after [Transport.close]. */
    fun onClosed()
}

/** Cancels a scheduled task. Idempotent. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Time and deferred work. The session reads time only from here, so tests run in deterministic
 * virtual time and a platform can use its own looper or coroutine dispatcher.
 */
interface Scheduler {
    /** Milliseconds, monotonic. */
    val now: Long

    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}
