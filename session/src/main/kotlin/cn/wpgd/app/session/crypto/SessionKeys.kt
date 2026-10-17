package cn.wpgd.app.session.crypto

/**
 * Gate G2 (owner-delegated default, no external cryptography review): the keys of one connection.
 *
 * `ikm` = ECDH(client ephemeral, host ephemeral) ‖ ECDH(client ephemeral, host room key), each
 * length-prefixed; `salt` =
 * empty; `info` = label, room id, epoch, client public key, host ephemeral public key, host room
 * public key and the host's [nonce] for this connection, each length-prefixed. HKDF-SHA256 yields
 * 68 bytes: the client-to-host key (32), the host-to-client key (32) and 4 bytes for the six-digit
 * confirmation code. Binding the room id and epoch into `info` means keys from another room or an
 * older epoch never open these frames.
 *
 * The second agreement uses the host's static room key ([HostKey]): only the holder of its private
 * half derives these keys, so a peer that merely repeats the room key's public half in its welcome
 * cannot open the joiner's sealed frames (it never sees a join, let alone a rejoin proof). A first
 * join still relies on the confirmation code against a man-in-the-middle: the joiner shows it, and
 * the host approves only when the host user confirms that the code on both screens matches.
 */
class SessionKeys private constructor(
    val clientToHost: ByteArray,
    val hostToClient: ByteArray,
    /** Six decimal digits, zero-padded. */
    val confirmationCode: String,
) {
    companion object {
        const val LABEL = "wpgd-session-v2"
        const val NONCE_BYTES = 32

        fun derive(
            hkdf: Hkdf,
            ephemeralSecret: ByteArray,
            roomKeySecret: ByteArray,
            roomId: String,
            epoch: Long,
            clientPublicKey: ByteArray,
            hostPublicKey: ByteArray,
            hostRoomKey: ByteArray,
            nonce: ByteArray,
        ): SessionKeys {
            require(nonce.size == NONCE_BYTES) { "nonce of ${nonce.size} bytes" }
            val info = lengthPrefixed(LABEL.encodeToByteArray()) +
                lengthPrefixed(roomId.encodeToByteArray()) +
                lengthPrefixed(longBytes(epoch)) +
                lengthPrefixed(clientPublicKey) +
                lengthPrefixed(hostPublicKey) +
                lengthPrefixed(hostRoomKey) +
                lengthPrefixed(nonce)
            val okm = hkdf.derive(ByteArray(0), lengthPrefixed(ephemeralSecret) + lengthPrefixed(roomKeySecret), info, 68)
            val codeValue = ((okm[64].toLong() and 0xff) shl 24) or ((okm[65].toLong() and 0xff) shl 16) or
                ((okm[66].toLong() and 0xff) shl 8) or (okm[67].toLong() and 0xff)
            val code = (codeValue % 1_000_000).toString().padStart(6, '0')
            return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), code)
        }
    }
}

/**
 * The host's static room key (G2): one P-256 key per room, kept in the host's own storage for the
 * life of the table (`tables/<id>/hostkey`), so a restored table has the same key. Joiners pin its
 * [fingerprint] with their rejoin ticket and refuse a welcome carrying another key.
 *
 * Fingerprint = HMAC-SHA256(key = ASCII [FINGERPRINT_LABEL], data = the SPKI DER public key): a
 * fixed-key HMAC is used as the hash so the platform seam needs no further primitive. It is public
 * (adverts carry it) and identifies the key, nothing more.
 */
class HostKey(val pair: EphemeralKeyPair, hmac: Hmac) {
    val publicKey: ByteArray get() = pair.publicKey
    val fingerprint: ByteArray = fingerprintOf(hmac, pair.publicKey)

    companion object {
        const val FINGERPRINT_LABEL = "wpgd-host-key-v1"
        const val FINGERPRINT_BYTES = 32

        fun fingerprintOf(hmac: Hmac, publicKey: ByteArray): ByteArray = hmac.mac(FINGERPRINT_LABEL.encodeToByteArray(), publicKey)
    }
}

/**
 * The rejoin proof (G2): a returning member proves it holds its capability without sending it.
 *
 * proof = HMAC-SHA256(capability, transcript), transcript = label ‖ room id ‖ epoch ‖ the host's
 * nonce for this connection ‖ the host key fingerprint ‖ the client's ephemeral public key ‖ the
 * host's ephemeral public key, each length-prefixed. The nonce is fresh per connection, so an old
 * proof never verifies again; the fingerprint and both ephemeral keys bind it to this host key and
 * this connection, so a proof sent to anyone else (another key, another connection) is useless to
 * them and to the real host.
 */
object RejoinProof {
    const val LABEL = "wpgd-rejoin-v1"
    const val BYTES = 32

    fun transcript(
        roomId: String,
        epoch: Long,
        nonce: ByteArray,
        hostKeyFingerprint: ByteArray,
        clientPublicKey: ByteArray,
        hostPublicKey: ByteArray,
    ): ByteArray = lengthPrefixed(LABEL.encodeToByteArray()) +
        lengthPrefixed(roomId.encodeToByteArray()) +
        lengthPrefixed(longBytes(epoch)) +
        lengthPrefixed(nonce) +
        lengthPrefixed(hostKeyFingerprint) +
        lengthPrefixed(clientPublicKey) +
        lengthPrefixed(hostPublicKey)

    fun of(hmac: Hmac, capability: ByteArray, transcript: ByteArray): ByteArray = hmac.mac(capability, transcript)
}

internal fun lengthPrefixed(b: ByteArray): ByteArray {
    require(b.size <= 0xFFFF) { "field of ${b.size} bytes" }
    return byteArrayOf((b.size ushr 8).toByte(), b.size.toByte()) + b
}

internal fun longBytes(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }
