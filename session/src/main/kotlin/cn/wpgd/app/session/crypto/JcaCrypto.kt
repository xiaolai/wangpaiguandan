package cn.wpgd.app.session.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Gate G2 (owner-delegated default, no external cryptography review): JCA implementations of the crypto seam. This is the only file in
 * :session that imports java.security or javax.crypto, so a platform without JCA swaps this file.
 */

/** The JCA suite: [SecureRandom], ECDH on secp256r1, AES-256-GCM and HMAC-SHA256. */
object JcaCrypto {
    fun suite(random: SecureRandom = SecureRandom()): CryptoSuite =
        CryptoSuite(JcaSecureBytes(random), JcaP256KeyAgreement(random), JcaAesGcm, JcaHmacSha256)
}

class JcaSecureBytes(private val random: SecureRandom) : SecureBytes {
    override fun next(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

class JcaP256KeyAgreement(private val random: SecureRandom) : KeyAgreement {
    private class Pair(val keyPair: KeyPair) : EphemeralKeyPair {
        override val publicKey: ByteArray = keyPair.public.encoded
    }

    override fun generate(): EphemeralKeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec(CURVE), random)
        return Pair(g.generateKeyPair())
    }

    override fun export(pair: EphemeralKeyPair): StoredKeyPair {
        val kp = (pair as? Pair)?.keyPair ?: throw CryptoFailure("key pair from another implementation")
        return StoredKeyPair(kp.public.encoded, kp.private.encoded)
    }

    override fun import(stored: StoredKeyPair): EphemeralKeyPair {
        val pair = try {
            val factory = KeyFactory.getInstance("EC")
            val public = factory.generatePublic(X509EncodedKeySpec(stored.publicKey)) as? ECPublicKey ?: throw CryptoFailure("stored public key is not an EC key")
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(stored.privateKey))
            Pair(KeyPair(public, private))
        } catch (e: GeneralSecurityException) {
            throw CryptoFailure("stored key pair is malformed", e)
        } catch (e: IllegalArgumentException) {
            throw CryptoFailure("stored key pair is malformed", e)
        } catch (e: ClassCastException) {
            throw CryptoFailure("stored key pair is malformed", e)
        }
        // The halves belong together exactly when a fresh key agrees with both the same way.
        val probe = generate()
        if (!MessageDigest.isEqual(agree(pair, probe.publicKey), agree(probe, stored.publicKey))) {
            throw CryptoFailure("stored public and private keys do not belong together")
        }
        return pair
    }

    override fun agree(own: EphemeralKeyPair, peerPublicKey: ByteArray): ByteArray {
        val mine = (own as? Pair)?.keyPair ?: throw CryptoFailure("key pair from another implementation")
        try {
            val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey)) as? ECPublicKey
                ?: throw CryptoFailure("peer key is not an EC key")
            val ownParams = (mine.public as ECPublicKey).params
            if (peer.params.curve != ownParams.curve || peer.params.generator != ownParams.generator) {
                throw CryptoFailure("peer key is not on $CURVE")
            }
            val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
            ka.init(mine.private)
            // The JCA provider validates that the peer point lies on the curve.
            ka.doPhase(peer, true)
            return ka.generateSecret()
        } catch (e: GeneralSecurityException) {
            throw CryptoFailure("key agreement failed", e)
        } catch (e: IllegalArgumentException) {
            throw CryptoFailure("malformed peer key", e)
        } catch (e: ClassCastException) {
            throw CryptoFailure("malformed peer key", e)
        }
    }

    private companion object {
        const val CURVE = "secp256r1"
    }
}

object JcaAesGcm : Aead {
    override val keySize = 32
    override val nonceSize = 12
    private const val TAG_BITS = 128

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == keySize && nonce.size == nonceSize) { "AES-GCM needs a 32-byte key and a 12-byte nonce" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (key.size != keySize || nonce.size != nonceSize || ciphertext.size < TAG_BITS / 8) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(aad)
            c.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            null
        }
    }
}

object JcaHmacSha256 : Hmac {
    override fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        // An empty key is legal for HMAC but SecretKeySpec refuses it; RFC 2104 pads keys with zeros anyway.
        m.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return m.doFinal(data)
    }

    override fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
