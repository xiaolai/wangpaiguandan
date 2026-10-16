package cn.wpgd.app.session

import cn.wpgd.app.session.channel.Direction
import cn.wpgd.app.session.channel.Opener
import cn.wpgd.app.session.channel.Sealer
import cn.wpgd.app.session.crypto.CryptoFailure
import cn.wpgd.app.session.crypto.Hkdf
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.crypto.JcaCrypto
import cn.wpgd.app.session.crypto.JcaHmacSha256
import cn.wpgd.app.session.crypto.SessionKeys
import cn.wpgd.app.session.crypto.StoredKeyPair
import cn.wpgd.app.session.wire.Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The crypto seam (G2 owner-delegated default, no external review): platform primitives, checked against published vectors. */
class CryptoTest {
    @Test fun hkdfMatchesRfc5869TestCase1() {
        val ikm = Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = Hex.decode("000102030405060708090a0b0c")
        val info = Hex.decode("f0f1f2f3f4f5f6f7f8f9")
        val hkdf = Hkdf(JcaHmacSha256)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Hex.encode(hkdf.extract(salt, ikm)))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(hkdf.derive(salt, ikm, info, 42)),
        )
    }

    @Test fun hmacSha256MatchesRfc4231TestCase2() {
        val mac = JcaHmacSha256.mac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", Hex.encode(mac))
    }

    @Test fun bothEndsDeriveTheSameKeysAndCode() {
        val suite = JcaCrypto.suite()
        val a = suite.keyAgreement.generate()
        val b = suite.keyAgreement.generate()
        val room = suite.keyAgreement.generate()
        val nonce = ByteArray(32) { it.toByte() }
        fun client(epoch: Long = 1, roomKey: ByteArray = room.publicKey) = SessionKeys.derive(
            suite.hkdf, suite.keyAgreement.agree(a, b.publicKey), suite.keyAgreement.agree(a, roomKey), "room-a", epoch, a.publicKey, b.publicKey, roomKey, nonce,
        )
        val ka = client()
        val kb = SessionKeys.derive(suite.hkdf, suite.keyAgreement.agree(b, a.publicKey), suite.keyAgreement.agree(room, a.publicKey), "room-a", 1, a.publicKey, b.publicKey, room.publicKey, nonce)
        assertContentEquals(ka.clientToHost, kb.clientToHost)
        assertContentEquals(ka.hostToClient, kb.hostToClient)
        assertEquals(ka.confirmationCode, kb.confirmationCode)
        assertEquals(6, ka.confirmationCode.length)
        assertNotEquals(Hex.encode(ka.hostToClient), Hex.encode(client(epoch = 2).hostToClient), "epoch is bound into the keys")
        // G2: a host that shows the room key without holding it derives other keys, so it opens nothing.
        val impostor = suite.keyAgreement.generate()
        val forged = SessionKeys.derive(suite.hkdf, suite.keyAgreement.agree(b, a.publicKey), suite.keyAgreement.agree(impostor, a.publicKey), "room-a", 1, a.publicKey, b.publicKey, room.publicKey, nonce)
        assertNotEquals(Hex.encode(ka.clientToHost), Hex.encode(forged.clientToHost), "the room key's private half is needed")
    }

    /** G2: the room key survives export and import; halves that do not belong together are refused. */
    @Test fun aStoredRoomKeyImportsAndAMismatchedOneFailsLoud() {
        val suite = JcaCrypto.suite()
        val room = suite.keyAgreement.generate()
        val peer = suite.keyAgreement.generate()
        val back = suite.keyAgreement.import(suite.keyAgreement.export(room))
        assertContentEquals(room.publicKey, back.publicKey)
        assertContentEquals(suite.keyAgreement.agree(room, peer.publicKey), suite.keyAgreement.agree(back, peer.publicKey))
        val other = suite.keyAgreement.export(suite.keyAgreement.generate())
        assertFailsWith<CryptoFailure> { suite.keyAgreement.import(StoredKeyPair(room.publicKey, other.privateKey)) }
        assertFailsWith<CryptoFailure> { suite.keyAgreement.import(StoredKeyPair(ByteArray(10), other.privateKey)) }
        assertFailsWith<CryptoFailure> { suite.keyAgreement.import(StoredKeyPair(room.publicKey, ByteArray(10))) }
    }

    @Test fun malformedPeerKeyFailsLoud() {
        val suite = JcaCrypto.suite()
        val a = suite.keyAgreement.generate()
        assertFailsWith<CryptoFailure> { suite.keyAgreement.agree(a, ByteArray(91) { 1 }) }
        assertFailsWith<CryptoFailure> { suite.keyAgreement.agree(a, ByteArray(0)) }
    }

    @Test fun sealedFramesRejectTamperingReplayAndTheWrongDirection() {
        val key = ByteArray(32) { 9 }
        val sealer = Sealer(JcaAesGcm, key, Direction.HOST_TO_CLIENT)
        val opener = Opener(JcaAesGcm, key, Direction.HOST_TO_CLIENT)
        val f1 = sealer.seal("one".encodeToByteArray())
        val f2 = sealer.seal("two".encodeToByteArray())
        assertContentEquals("two".encodeToByteArray(), opener.open(f2))
        assertContentEquals("one".encodeToByteArray(), opener.open(f1), "reordered within the window is accepted once")
        assertNull(opener.open(f1), "replay")
        val tampered = f2.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertNull(Opener(JcaAesGcm, key, Direction.HOST_TO_CLIENT).open(tampered))
        assertNull(Opener(JcaAesGcm, key, Direction.CLIENT_TO_HOST).open(f1), "a frame from the other direction never opens")
        assertEquals(1, opener.replays)
    }
}
