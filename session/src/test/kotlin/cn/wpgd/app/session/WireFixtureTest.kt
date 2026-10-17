package cn.wpgd.app.session

import cn.wpgd.app.session.channel.Direction
import cn.wpgd.app.session.channel.Opener
import cn.wpgd.app.session.channel.Sealer
import cn.wpgd.app.session.crypto.Hkdf
import cn.wpgd.app.session.crypto.HostKey
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.crypto.JcaHmacSha256
import cn.wpgd.app.session.crypto.RejoinProof
import cn.wpgd.app.session.crypto.SessionKeys
import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.framing.Reassembler
import cn.wpgd.app.session.wire.Hex
import cn.wpgd.app.session.wire.WireCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GDN-105: the wire fixtures shared with the iPhone client. Each checked-in file must equal what the
 * code produces ([WireSamples]); a mismatch writes the expected file to `build/wire-fixtures/`.
 * Decoding is strict: an unknown field or a missing field anywhere rejects the message.
 */
class WireFixtureTest {
    private val dir = File("../core/src/test/resources/fixtures/wire")
    private val pretty = Json { prettyPrint = true }

    @Serializable
    private data class Case(val name: String, val wire: JsonObject)

    @Serializable
    private data class MessageFile(val schema: Int, val protocolVersion: Int, val description: String, val cases: List<Case>)

    private val strictFile = Json { ignoreUnknownKeys = false }

    private fun load(name: String): String {
        val f = File(dir, name)
        check(f.isFile) { "fixture missing: ${f.absolutePath}" }
        return f.readText()
    }

    /** Fixture hex is digits only: a sign that String.toInt(16) would take ("-1" as 0xff) is malformed. */
    @Test fun hexDecodingRejectsSignsAndNonDigits() {
        assertContentEquals(byteArrayOf(0x00, 0x7f, -1, 0x0a), Hex.decode("007fFF0a"))
        for (bad in listOf("-1", "+f", "0g", " 1", "1-")) assertFailsWith<IllegalArgumentException>(bad) { Hex.decode(bad) }
    }

    @Test fun checkedInFixturesEqualWhatTheCodeProduces() {
        val out = File("build/wire-fixtures").apply { mkdirs() }
        val stale = ArrayList<String>()
        for ((name, produce) in WireSamples.files) {
            val expected = produce()
            File(out, name).writeText(pretty.encodeToString(JsonElement.serializer(), expected) + "\n")
            val file = File(dir, name)
            if (!file.isFile || Json.parseToJsonElement(file.readText()) != expected) stale += name
        }
        assertEquals(emptyList(), stale, "fixtures differ from the code; expected versions are in ${out.absolutePath}")
    }

    @Test fun everyMessageFixtureDecodesStrictlyAndReencodesToTheSameBytes() {
        var cases = 0
        for (name in listOf("handshake.json", "command.json", "receipt.json", "rejection.json", "recovery.json", "lobby.json")) {
            val file = strictFile.decodeFromString(MessageFile.serializer(), load(name))
            for (c in file.cases) {
                val text = c.wire.toString()
                val decoded = assertNotNull(WireCodec.decode(text), "$name/${c.name} does not decode")
                assertEquals(text, WireCodec.encodeToString(decoded), "$name/${c.name} does not re-encode identically")
                cases++
            }
        }
        assertTrue(cases >= 30, "fixtures cover every message ($cases cases)")
    }

    /** A literal U+FFFD is valid UTF-8 and round-trips; malformed UTF-8 bytes are refused. */
    @Test fun replacementCharacterRoundTripsAndMalformedUtf8IsRefused() {
        val join = cn.wpgd.app.session.wire.Join(3, "a\uFFFDb", null, false, null)
        assertEquals(join, WireCodec.decode(WireCodec.encode(join)))
        val bytes = WireCodec.encode(join)
        val at = (0..bytes.size - 3).first { bytes[it] == 0xEF.toByte() && bytes[it + 1] == 0xBF.toByte() && bytes[it + 2] == 0xBD.toByte() }
        val malformed = bytes.copyOfRange(0, at) + byteArrayOf(0xC3.toByte(), 0x28) + bytes.copyOfRange(at + 3, bytes.size)
        assertNull(WireCodec.decode(malformed))
    }

    /**
     * Finding 58: JSON (RFC 8259) forbids unescaped U+0000..U+001F inside strings; the strict decoder
     * refuses them, while the same characters escaped are ordinary text.
     */
    @Test fun unescapedControlCharactersInStringsAreRefused() {
        val rejected = cn.wpgd.app.session.wire.HelloRejected(cn.wpgd.app.session.wire.HelloRejectReason.ROOM_FULL, 2, 2, "a\tb")
        val escaped = WireCodec.encodeToString(rejected)
        assertTrue(escaped.contains("a\\tb"), "the encoder escapes a tab: $escaped")
        assertEquals(rejected, WireCodec.decode(escaped))
        for (c in listOf('\u0000', '\t', '\n', '\r', '\u001F')) {
            val literal = escaped.replace("a\\tb", "a${c}b")
            assertNull(WireCodec.decode(literal), "an unescaped U+%04X inside a string was accepted".format(c.code))
            assertNull(WireCodec.decode(literal.encodeToByteArray()))
        }
        // Whitespace between tokens is still allowed: only string contents are restricted.
        assertEquals(rejected, WireCodec.decode(escaped.replace(",\"protocolMin\"", ",\n\t\"protocolMin\"")))
    }

    @Test fun unknownOrMissingFieldsRejectAtEveryLevel() {
        for (name in listOf("handshake.json", "command.json", "receipt.json", "rejection.json", "recovery.json", "lobby.json")) {
            for (c in strictFile.decodeFromString(MessageFile.serializer(), load(name)).cases) {
                val withUnknown = JsonObject(c.wire + ("unexpected" to JsonPrimitive(1)))
                assertNull(WireCodec.decode(withUnknown.toString()), "$name/${c.name}: unknown field accepted")
                for (key in c.wire.keys.filter { it != "type" }) {
                    assertNull(WireCodec.decode(JsonObject(c.wire - key).toString()), "$name/${c.name}: missing '$key' accepted")
                }
                val body = c.wire["body"] as? JsonObject ?: continue
                val nested = JsonObject(c.wire + ("body" to JsonObject(body + ("unexpected" to JsonPrimitive(true)))))
                assertNull(WireCodec.decode(nested.toString()), "$name/${c.name}: unknown field in body accepted")
            }
        }
        assertNull(WireCodec.decode("{\"type\":\"no_such_message\"}"))
        assertNull(WireCodec.decode("{\"type\":\"heartbeat\",\"epoch\":\"3\",\"revision\":null,\"statusRevision\":null}"), "wrong type accepted")
    }

    @Test fun fixtureFileItselfIsStrict() {
        val withExtra = load("rejection.json").replaceFirst("\"schema\"", "\"extra\": 1, \"schema\"")
        assertFailsWith<SerializationException> { strictFile.decodeFromString(MessageFile.serializer(), withExtra) }
    }

    @Test fun frameEnvelopeVectorsHold() {
        val f = Json.parseToJsonElement(load("frame-envelope.json")).jsonObject

        val chunking = f.getValue("chunking").jsonObject
        val frame = Hex.decode(chunking.getValue("frameHex").jsonPrimitive.content)
        val chunks = chunking.getValue("chunksHex").jsonArray.map { Hex.decode(it.jsonPrimitive.content) }
        val produced = ChunkCodec.split(chunking.getValue("frameId").jsonPrimitive.long, frame, chunking.getValue("maxChunkPayload").jsonPrimitive.int)
        assertEquals(chunks.map(Hex::encode), produced.map(Hex::encode))
        val r = Reassembler(FramingLimits(maxChunkPayload = 16))
        val results = chunks.reversed().map { r.accept(it) }
        val complete = results.filterIsInstance<Reassembler.Result.Complete>().single()
        assertContentEquals(frame, complete.frame)

        val sealed = f.getValue("sealed").jsonObject
        val key = Hex.decode(sealed.getValue("keyHex").jsonPrimitive.content)
        val plaintext = sealed.getValue("plaintext").jsonPrimitive.content.encodeToByteArray()
        val sealer = Sealer(JcaAesGcm, key, Direction.HOST_TO_CLIENT)
        val opener = Opener(JcaAesGcm, key, Direction.HOST_TO_CLIENT)
        for (v in sealed.getValue("frames").jsonArray) {
            val expected = Hex.decode(v.jsonObject.getValue("frameHex").jsonPrimitive.content)
            assertContentEquals(expected, sealer.seal(plaintext))
            assertContentEquals(plaintext, opener.open(expected))
        }

        val kd = f.getValue("keyDerivation").jsonObject
        fun hex(k: String) = Hex.decode(kd.getValue(k).jsonPrimitive.content)
        val keys = SessionKeys.derive(
            Hkdf(JcaHmacSha256), hex("sharedSecretHex"), hex("roomKeySharedSecretHex"), kd.getValue("roomId").jsonPrimitive.content,
            kd.getValue("epoch").jsonPrimitive.long, hex("clientPublicKeyHex"), hex("hostPublicKeyHex"), hex("hostRoomKeyHex"), hex("nonceHex"),
        )
        assertEquals(kd.getValue("clientToHostHex").jsonPrimitive.content, Hex.encode(keys.clientToHost))
        assertEquals(kd.getValue("hostToClientHex").jsonPrimitive.content, Hex.encode(keys.hostToClient))
        assertEquals(kd.getValue("confirmationCode").jsonPrimitive.content, keys.confirmationCode)
        // G2 vectors: the host key fingerprint and a rejoin proof, recomputed from their stated inputs.
        val hk = f.getValue("hostKey").jsonObject
        val fp = HostKey.fingerprintOf(JcaHmacSha256, Hex.decode(hk.getValue("roomKeyHex").jsonPrimitive.content))
        assertEquals(hk.getValue("fingerprintHex").jsonPrimitive.content, Hex.encode(fp))
        val rp = f.getValue("rejoinProof").jsonObject
        val transcript = RejoinProof.transcript(
            kd.getValue("roomId").jsonPrimitive.content, kd.getValue("epoch").jsonPrimitive.long, hex("nonceHex"), fp,
            hex("clientPublicKeyHex"), hex("hostPublicKeyHex"),
        )
        assertEquals(rp.getValue("transcriptHex").jsonPrimitive.content, Hex.encode(transcript))
        assertEquals(rp.getValue("proofHex").jsonPrimitive.content, Hex.encode(RejoinProof.of(JcaHmacSha256, Hex.decode(rp.getValue("capabilityHex").jsonPrimitive.content), transcript)))
    }
}
