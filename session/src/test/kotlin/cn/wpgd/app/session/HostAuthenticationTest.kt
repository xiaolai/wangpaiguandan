package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.client.SessionClient
import cn.wpgd.app.session.crypto.CryptoSuite
import cn.wpgd.app.session.crypto.EphemeralKeyPair
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.crypto.JcaHmacSha256
import cn.wpgd.app.session.crypto.JcaP256KeyAgreement
import cn.wpgd.app.session.crypto.KeyAgreement
import cn.wpgd.app.session.crypto.StoredKeyPair
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.VirtualScheduler
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.B64
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Hello
import cn.wpgd.app.session.wire.Join
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.WireCodec
import cn.wpgd.app.session.wire.WireMessage
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gate G2 (owner-delegated default): a returning member proves its capability without sending it,
 * bound to the host's fresh nonce, the host key it pinned and this connection; a host showing another
 * key gets nothing, a host showing the pinned key without holding it can open nothing, and an old
 * proof never verifies again.
 */
class HostAuthenticationTest {
    private class Captured(val client: SessionClient, val sent: MutableList<WireMessage>)

    /** A client of [config] on a new link to [host], recording every message it sends. */
    private fun dial(scheduler: Scheduler, network: InMemoryNetwork, host: SessionHost, config: cn.wpgd.app.session.client.ClientConfig, seed: Long, rewrite: ((Join) -> Join)? = null): Captured {
        val client = SessionClient(config, testCrypto(seed), scheduler)
        val sent = ArrayList<WireMessage>()
        client.sentTap = { sent += it }
        client.rewriteJoin = rewrite
        val link = network.link()
        host.accept(link.hostEnd)
        client.connect(link.clientEnd)
        return Captured(client, sent)
    }

    private fun Captured.joins() = sent.filterIsInstance<Join>()

    @Test fun aRejoinSendsAProofAndNeverTheCapability() {
        val t = Table(seed = 401)
        val south = t.player(Seat.SOUTH)
        val cap = checkNotNull(south.client.capability)
        val memberId = checkNotNull(south.client.memberId)
        val back = dial(t.scheduler, t.network, t.host, t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = cap), 4011)
        t.until(10_000) { back.client.phase == ClientPhase.ADMITTED }
        assertEquals(memberId, back.client.memberId, "the proof re-admitted the same member, without approval")
        val joins = back.joins()
        assertTrue(joins.isNotEmpty())
        for (j in joins) {
            val proof = assertNotNull(j.rejoinProof)
            assertNotEquals(cap, proof)
            assertEquals(32, B64.decode(proof)?.size)
        }
        // Nothing the client sent before it was admitted holds the capability, in any field.
        val beforeAdmission = back.sent.takeWhile { it is Hello || it is Join }
        for (m in beforeAdmission) assertTrue(cap !in WireCodec.encodeToString(m), "capability sent in ${m::class.simpleName}")
        assertEquals(t.host.hostKeyFingerprint, back.client.hostKeyFingerprint)
    }

    @Test fun aWelcomeWithAnotherHostKeyIsRefusedBeforeAJoinIsSent() {
        val scheduler = VirtualScheduler()
        val network = InMemoryNetwork(scheduler, 402)
        val real = Table(seed = 402, scheduler = scheduler, network = network)
        val impostor = Table(seed = 403, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY), scheduler = scheduler, network = network)
        assertNotEquals(real.host.hostKeyFingerprint, impostor.host.hostKeyFingerprint)
        val south = real.player(Seat.SOUTH)
        val cap = checkNotNull(south.client.capability)

        // A stored ticket, pinned to the real host's key, reaches a host with the same room id and another key.
        val lost = dial(scheduler, network, impostor.host, real.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = cap), 4021)
        real.until(5_000) { lost.client.phase == ClientPhase.CLOSED }
        assertEquals(CloseReason.HOST_KEY_MISMATCH, lost.client.closeReason)
        assertEquals(1, lost.client.metrics.hostKeyMismatches)
        assertEquals(emptyList(), lost.joins(), "nothing after the hello: no proof, no capability")
        assertTrue(impostor.host.members().isEmpty() && impostor.host.pendingAdmissions().isEmpty())

        // A client pins the first key it meets: the same client, reconnected to the impostor, is refused too.
        val firstKey = south.client.hostKeyFingerprint
        assertEquals(real.host.hostKeyFingerprint, firstKey)
        val link = network.link()
        impostor.host.accept(link.hostEnd)
        south.client.connect(link.clientEnd)
        real.until(5_000) { south.client.phase == ClientPhase.CLOSED }
        assertEquals(CloseReason.HOST_KEY_MISMATCH, south.client.closeReason)
        assertTrue(impostor.host.members().isEmpty())
    }

    /** A key agreement that shows [shown] as a pair's public key while agreeing with its real private key. */
    private class Impersonating(private val inner: KeyAgreement, private val shown: ByteArray) : KeyAgreement {
        private class Shown(val real: EphemeralKeyPair, override val publicKey: ByteArray) : EphemeralKeyPair

        fun impostor(): EphemeralKeyPair = Shown(inner.generate(), shown)
        override fun generate(): EphemeralKeyPair = inner.generate()
        override fun agree(own: EphemeralKeyPair, peerPublicKey: ByteArray): ByteArray = inner.agree((own as? Shown)?.real ?: own, peerPublicKey)
        override fun export(pair: EphemeralKeyPair): StoredKeyPair = inner.export(pair)
        override fun import(stored: StoredKeyPair): EphemeralKeyPair = inner.import(stored)
    }

    @Test fun aHostShowingThePinnedKeyWithoutItsPrivateHalfOpensNothing() {
        val scheduler = VirtualScheduler()
        val network = InMemoryNetwork(scheduler, 404)
        val real = Table(seed = 404, scheduler = scheduler, network = network)
        val south = real.player(Seat.SOUTH)
        val cap = checkNotNull(south.client.capability)
        // The spoofer copies the real room key's public half (from an advert or a welcome) into its own welcome.
        val realPublic = checkNotNull(B64.decode(checkNotNull(south.received.filterIsInstance<cn.wpgd.app.session.wire.Welcome>().first().hostKey)))
        val lying = Impersonating(JcaP256KeyAgreement(SecureRandom()), realPublic)
        val spoofCrypto = CryptoSuite(SeededBytes(4041), lying, JcaAesGcm, JcaHmacSha256)
        val spoof = SessionHost(HostConfig(roomId = ROOM, recovery = TEST_RECOVERY), TestProfiles.base, TestProfiles.match, spoofCrypto, scheduler, ::SplitMix64, null, lying.impostor())
        assertEquals(real.host.hostKeyFingerprint, spoof.hostKeyFingerprint, "the spoofer shows the pinned fingerprint")

        val lost = dial(scheduler, network, spoof, real.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = cap), 4042)
        real.run(10_000)
        assertTrue(lost.client.phase != ClientPhase.ADMITTED, "never admitted by a host that cannot derive the keys")
        assertTrue(spoof.members().isEmpty() && spoof.pendingAdmissions().isEmpty(), "the spoofer could open no join")
        // The joiner's sealed join never opened there: no join was judged (no denial, no admission, nothing pending),
        // and the spoofer dropped the connection only because no frame it could open ever arrived.
        assertEquals(0L, spoof.metrics.denied + spoof.metrics.admitted + spoof.metrics.rejoined)
        val timeouts = (spoof.metrics.closed[CloseReason.HEARTBEAT_TIMEOUT] ?: 0) + (spoof.metrics.closed[CloseReason.HELLO_TIMEOUT] ?: 0)
        assertTrue(timeouts >= 1, "spoofer closes: ${spoof.metrics.closed}")
        assertTrue(lost.joins().isNotEmpty(), "the joiner did send its (sealed) join toward the spoofer")
        // What was sealed toward it holds a proof bound to its nonce, never the capability; it opened none of it anyway.
        for (j in lost.joins()) assertNotEquals(cap, j.rejoinProof)
    }

    @Test fun anOldProofDoesNotVerifyOnANewConnection() {
        val t = Table(seed = 405)
        val south = t.player(Seat.SOUTH)
        val cap = checkNotNull(south.client.capability)
        val first = dial(t.scheduler, t.network, t.host, t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = cap), 4051)
        t.until(10_000) { first.client.phase == ClientPhase.ADMITTED }
        val recorded = first.joins().first()

        // An eavesdropper (or the member's own old connection) replays that join on a new connection: a new nonce, so it fails.
        val replay = dial(t.scheduler, t.network, t.host, t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = cap), 4052) { recorded.copy(epoch = it.epoch) }
        t.until(10_000) { replay.client.phase == ClientPhase.REJECTED }
        assertEquals(AdmissionDenial.CAPABILITY_INVALID, replay.client.denial)
        assertTrue(first.client.phase == ClientPhase.ADMITTED, "the member's live connection is untouched")

        // A proof made with the right capability for another nonce or host key fails the same way.
        val forged = dial(t.scheduler, t.network, t.host, t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = cap), 4053) { j ->
            val other = cn.wpgd.app.session.crypto.RejoinProof.transcript(ROOM, j.epoch, ByteArray(32), ByteArray(32), ByteArray(65), ByteArray(65))
            j.copy(rejoinProof = B64.encode(cn.wpgd.app.session.crypto.RejoinProof.of(JcaHmacSha256, checkNotNull(B64.decode(cap)), other)))
        }
        t.until(10_000) { forged.client.phase == ClientPhase.REJECTED }
        assertEquals(AdmissionDenial.CAPABILITY_INVALID, forged.client.denial)
    }

    /** A first join has no ticket: it sends no proof and is admitted only with the host user's code check. */
    @Test fun aFirstJoinStillNeedsTheConfirmationCode() {
        val t = Table(seed = 406)
        val joiner = dial(t.scheduler, t.network, t.host, t.config(Role.PLAYER, "east", Seat.EAST), 4061)
        t.until(10_000) { joiner.client.phase == ClientPhase.PENDING }
        assertTrue(joiner.joins().all { it.rejoinProof == null })
        val pending = t.host.pendingAdmissions().single()
        val wrong = if (joiner.client.confirmationCode == "000000") "000001" else "000000"
        assertEquals(SessionHost.ApprovalResult.CodeMismatch, t.host.approve(pending.connectionId, wrong))
        assertTrue(t.host.approve(pending.connectionId, checkNotNull(joiner.client.confirmationCode)) is SessionHost.ApprovalResult.Admitted)
    }
}
