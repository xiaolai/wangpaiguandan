package cn.wpgd.app.session.crypto

/*
 * Gate G2: owner-delegated default implemented 2026-10-16 (dev-docs/decisions/G2-host-authentication.md);
 * no external cryptography review. Per-connection ephemeral key agreement mixed with the host's
 * static room key, platform crypto only, a short confirmation code for first joins, and a keyed
 * proof (never the raw capability) for rejoins. Nothing here is a home-made primitive:
 * implementations wrap the platform (JCA on the JVM and Android, CryptoKit on iPhone). HKDF below is
 * the RFC 5869 construction over the platform HMAC, not a new primitive. Changing any of this is a
 * G2 decision.
 */

/** Cryptographically secure random bytes (the platform CSPRNG in production). */
interface SecureBytes {
    fun next(size: Int): ByteArray
}

/**
 * A P-256 key pair: ephemeral (one per connection), or the host's static room key (G2). The private
 * half leaves the implementation only through [KeyAgreement.export], for the host's own storage.
 */
interface EphemeralKeyPair {
    /** Encoded public key (X.509 SubjectPublicKeyInfo DER for P-256). */
    val publicKey: ByteArray
}

/** Both halves of a key pair as the host stores its room key: host-private storage only, never sent. */
class StoredKeyPair(
    /** X.509 SubjectPublicKeyInfo DER. */
    val publicKey: ByteArray,
    /** PKCS#8 DER. */
    val privateKey: ByteArray,
)

/** Diffie–Hellman on one curve (P-256 by default). */
interface KeyAgreement {
    fun generate(): EphemeralKeyPair

    /** The raw shared secret, or throws [CryptoFailure] when [peerPublicKey] is malformed or off the curve. */
    fun agree(own: EphemeralKeyPair, peerPublicKey: ByteArray): ByteArray

    /** Both halves of [pair], for the host's own storage of its room key (G2). */
    fun export(pair: EphemeralKeyPair): StoredKeyPair

    /**
     * Rebuilds an exported pair. Throws [CryptoFailure] when either half is malformed or the two
     * halves do not belong together (checked by agreeing with a fresh key both ways).
     */
    fun import(stored: StoredKeyPair): EphemeralKeyPair
}

/** Authenticated encryption with associated data (AES-256-GCM by default, 12-byte nonce, 16-byte tag). */
interface Aead {
    val keySize: Int
    val nonceSize: Int

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** The plaintext, or null when authentication fails. Never returns unauthenticated bytes. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?
}

/** HMAC-SHA256. */
interface Hmac {
    fun mac(key: ByteArray, data: ByteArray): ByteArray

    /** Constant-time comparison of two byte strings. */
    fun equal(a: ByteArray, b: ByteArray): Boolean
}

/** Everything the session needs from the platform, behind one seam so a later platform can swap it. */
class CryptoSuite(
    val random: SecureBytes,
    val keyAgreement: KeyAgreement,
    val aead: Aead,
    val hmac: Hmac,
) {
    val hkdf: Hkdf = Hkdf(hmac)
}

class CryptoFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** HKDF-SHA256 (RFC 5869) over the injected [Hmac]. Checked against the RFC test vectors in tests. */
class Hkdf(private val hmac: Hmac) {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac.mac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "HKDF output length $length out of range" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            t = hmac.mac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - written)
            t.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray = expand(extract(salt, ikm), info, length)

    private companion object {
        const val HASH_LEN = 32
    }
}
