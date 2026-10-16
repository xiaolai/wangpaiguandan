package cn.wpgd.app.session

import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.match.MatchProjector
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.crypto.JcaHmacSha256
import cn.wpgd.app.session.wire.B64
import cn.wpgd.app.session.wire.Hex
import cn.wpgd.app.session.wire.Receipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The canonical projection bytes and their keyed digest are deterministic and match the shared fixtures. */
class ProjectionDigestTest {
    private val fixture = Json.parseToJsonElement(File("../core/src/test/resources/fixtures/wire/digest.json").readText()).jsonObject

    @Test fun digestFixturesHold() {
        val key = Hex.decode(fixture.getValue("digestKeyHex").jsonPrimitive.content)
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(3, cases.size)
        for (c in cases) {
            val projection = c.getValue("projection").jsonPrimitive.content.encodeToByteArray()
            assertEquals(c.getValue("digest").jsonPrimitive.content, B64.encode(JcaHmacSha256.mac(key, projection)))
        }
    }

    @Test fun projectionsAreReproducedByteForByteFromTheSeededDeal() {
        val projector = MatchProjector(TestProfiles.base, TestProfiles.match)
        repeat(2) { // two independent runs, same bytes
            val s = WireSamples.dealState()
            for (c in fixture.getValue("cases").jsonArray.map { it.jsonObject }) {
                val seat = c.getValue("seat").let { if (it is JsonNull) null else Seat.valueOf(it.jsonPrimitive.content) }
                val bytes = if (seat == null) MatchProjectionCodec.encode(projector.spectatorView(s)) else MatchProjectionCodec.encode(projector.memberView(s, seat))
                assertEquals(c.getValue("projection").jsonPrimitive.content, bytes.decodeToString(), "projection for $seat changed")
            }
        }
        assertEquals(TestProfiles.base.hash, fixture.getValue("baseProfileHash").jsonPrimitive.content)
        assertEquals(TestProfiles.match.hash, fixture.getValue("matchProfileHash").jsonPrimitive.content)
    }

    @Test fun receiptDigestIsTheHmacOfTheIssuersOwnProjection() {
        val t = Table(seed = 171)
        val players = t.fourPlayers()
        t.startDeal()
        val actor = players.getValue(t.turn())
        val receipt = assertIs<Receipt>(t.command(actor, simpleBody(actor.client.memberView!!)))
        // The issuer's projection at the committed revision, keyed with the issuer's own digest key.
        val key = B64.decode(actor.received.filterIsInstance<cn.wpgd.app.session.wire.Admitted>().single().digestKey)!!
        val expected = B64.encode(JcaHmacSha256.mac(key, t.memberBytes(actor.client.seat!!).encodeToByteArray()))
        assertEquals(expected, receipt.digest)
        assertTrue(actor.client.metrics.digestsVerified >= 1)
        assertEquals(0, actor.client.metrics.digestMismatches)
    }

    @Test fun aDigestMismatchIsCountedAndForcesResync() {
        val t = Table(seed = 172)
        val players = t.fourPlayers()
        t.startDeal()
        val south = players.getValue(Seat.SOUTH).client
        val resyncs = t.host.metrics.resyncs
        val forged = Receipt(south.epoch!!, "forged", south.revision, B64.encode(ByteArray(32)))
        // A receipt for a command the client sent: register the id as pending by sending a real command first.
        south.submit(cn.wpgd.app.session.wire.FollowRequestBody(Seat.EAST)).let { id ->
            south.onMessage(forged.copy(commandId = id!!))
        }
        assertEquals(1, south.metrics.digestMismatches)
        t.run(500)
        assertTrue(t.host.metrics.resyncs > resyncs, "a mismatch must trigger a resync")
    }
}
