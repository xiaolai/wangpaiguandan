package cn.wpgd.app.session.channel

import cn.wpgd.app.session.crypto.Aead

/**
 * Frame payload layer, above chunking. Byte 0 is the kind:
 *
 * - `0x01` PLAIN: UTF-8 JSON of a handshake message (hello, welcome, hello_rejected). Nothing
 *   private is ever sent plain.
 * - `0x02` SEALED: `u64 seq` then AES-GCM ciphertext and tag. Nonce = 4-byte direction tag + seq.
 *   AAD = the 9 header bytes. Each direction has its own key, so a nonce never repeats under a key.
 *
 * G2 (owner-delegated default, no external cryptography review): the sealed format depends on the G2 decision.
 */
object FrameKind {
    const val PLAIN: Byte = 0x01
    const val SEALED: Byte = 0x02
}

enum class Direction(val tag: ByteArray) {
    CLIENT_TO_HOST(byteArrayOf(0x43, 0x32, 0x48, 0x00)),
    HOST_TO_CLIENT(byteArrayOf(0x48, 0x32, 0x43, 0x00)),
}

fun plainFrame(json: ByteArray): ByteArray = byteArrayOf(FrameKind.PLAIN) + json

/** Seals outgoing frames for one direction with a strictly increasing sequence number. */
class Sealer(private val aead: Aead, private val key: ByteArray, private val direction: Direction) {
    private var seq = 0L

    fun seal(plaintext: ByteArray): ByteArray {
        seq++
        val header = header(seq)
        return header + aead.seal(key, nonce(direction, seq), header, plaintext)
    }
}

/**
 * Opens incoming sealed frames. Rejects forgeries (authentication) and replays: a sliding window of
 * [WINDOW] sequence numbers accepts reordered frames once each, and anything older than the window.
 * The window moves only after authentication succeeds, so a forged seq cannot shift it.
 */
class Opener(private val aead: Aead, private val key: ByteArray, private val direction: Direction) {
    private var highest = 0L
    private var seen = 0L // bit i: highest - i was accepted

    var replays: Int = 0
        private set
    var forgeries: Int = 0
        private set

    fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 9 + 16 || frame[0] != FrameKind.SEALED) { forgeries++; return null }
        var seq = 0L
        for (i in 1..8) seq = (seq shl 8) or (frame[i].toLong() and 0xFF)
        if (seq <= 0) { forgeries++; return null }
        if (!fresh(seq)) { replays++; return null }
        val plain = aead.open(key, nonce(direction, seq), frame.copyOfRange(0, 9), frame.copyOfRange(9, frame.size))
            ?: run { forgeries++; return null }
        mark(seq)
        return plain
    }

    private fun fresh(seq: Long): Boolean = when {
        seq > highest -> true
        highest - seq >= WINDOW -> false
        else -> (seen ushr (highest - seq).toInt()) and 1L == 0L
    }

    private fun mark(seq: Long) {
        if (seq > highest) {
            val shift = seq - highest
            seen = if (shift >= WINDOW) 0L else seen shl shift.toInt()
            highest = seq
            seen = seen or 1L
        } else {
            seen = seen or (1L shl (highest - seq).toInt())
        }
    }

    private companion object {
        const val WINDOW = 64L
    }
}

private fun header(seq: Long): ByteArray = ByteArray(9).also {
    it[0] = FrameKind.SEALED
    for (i in 0 until 8) it[1 + i] = (seq ushr (56 - 8 * i)).toByte()
}

private fun nonce(direction: Direction, seq: Long): ByteArray = ByteArray(12).also {
    direction.tag.copyInto(it, 0)
    for (i in 0 until 8) it[4 + i] = (seq ushr (56 - 8 * i)).toByte()
}
