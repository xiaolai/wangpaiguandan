package cn.wpgd.app.session

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.crypto.Aead
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.GrantStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GDN-026: an observer of the radio traffic captures no plaintext hand. The positive control runs
 * the same deal with encryption replaced by the identity, and the same scan must find the hands.
 */
class TransportConfidentialityTest {
    private class Capture(val frames: List<ByteArray>, val hands: Map<Seat, List<String>>, val capabilities: List<String>)

    private fun play(aead: Aead): Capture {
        val t = Table(seed = 151, hostCrypto = testCrypto(151, aead), clientAead = aead)
        val players = t.fourPlayers()
        val o = t.observer("watcher")
        t.command(o, FollowRequestBody(Seat.SOUTH))
        val south = players.getValue(Seat.SOUTH)
        t.until(5_000) { south.client.grants.any { it.status == GrantStatus.PENDING } }
        t.command(south, ConsentBody(south.client.grants.single().grantId, true))
        t.startDeal()
        val hands = players.mapValues { (_, p) -> p.client.memberView!!.deal!!.hand.map { it.id } }
        val bots = players.values.map { Bot(t, it) }
        bots.forEach { it.poke() }
        t.until(30 * 60_000L) { t.host.state.phase == MatchPhase.DEAL_ENDED }
        return Capture(capturedFrames(t.network), hands, players.values.map { it.client.capability!! } + o.client.capability!!)
    }

    private fun leaks(c: Capture): List<String> {
        // Projections travel as a JSON string inside the message, so quotes appear escaped: scan both forms.
        val plain = c.hands.values.flatten().map { "\"id\":\"$it\"" } + listOf("\"hand\":", "\"suit\":\"")
        val needles = plain + plain.map { it.replace("\"", "\\\"") } + c.capabilities
        return needles.filter { n -> c.frames.any { it.containsSequence(n.encodeToByteArray()) } }
    }

    @Test fun capturedFramesContainNoPlaintextHandOrCapability() {
        val sealed = play(JcaAesGcm)
        assertTrue(sealed.frames.size > 500, "the capture must cover a whole deal (${sealed.frames.size} frames)")
        assertEquals(emptyList(), leaks(sealed))
    }

    @Test fun positiveControlTheScannerFindsHandsWhenNothingIsEncrypted() {
        val open = play(IdentityAead)
        val found = leaks(open)
        assertTrue(found.contains("\\\"hand\\\":") && found.size > 50, "the scanner must see plaintext hands without encryption: $found")
    }
}
